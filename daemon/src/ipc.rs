use crate::protocol::{parse_ipc_request, IpcRequest, IpcResponse, MAX_IPC_LINE_BYTES};
use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

const MAX_CONCURRENT_CLIENTS: usize = 16;

/// Verifies peer credentials via Linux `SO_PEERCRED`.
/// Only allows `uid == 0` (root) or the daemon's own effective UID (for non-root host tests).
fn verify_peer_cred(stream: &UnixStream) -> io::Result<()> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: u32::MAX,
        gid: u32::MAX,
    };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut cred as *mut libc::ucred).cast(),
            &mut len,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    let self_euid = unsafe { libc::geteuid() };
    if cred.uid == 0 || cred.uid == self_euid {
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!("unauthorized peer uid={}", cred.uid),
        ))
    }
}

/// Reads a single `\n`-terminated line strictly bounded by `max_bytes` so a hostile or
/// corrupted stream cannot trigger unbounded RAM allocation.
pub fn read_line_bounded<R: BufRead>(reader: &mut R, max_bytes: usize) -> io::Result<Option<String>> {
    let mut buf = Vec::with_capacity(256);
    loop {
        let available = match reader.fill_buf() {
            Ok(slice) => slice,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        if available.is_empty() {
            if buf.is_empty() {
                return Ok(None);
            }
            break;
        }
        if let Some(nl_idx) = available.iter().position(|&b| b == b'\n') {
            if buf.len() + nl_idx > max_bytes {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "IPC frame exceeds maximum allowed size",
                ));
            }
            buf.extend_from_slice(&available[..nl_idx]);
            reader.consume(nl_idx + 1);
            break;
        } else {
            let n = available.len();
            if buf.len() + n > max_bytes {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "IPC frame exceeds maximum allowed size",
                ));
            }
            buf.extend_from_slice(available);
            reader.consume(n);
        }
    }
    String::from_utf8(buf)
        .map(Some)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid UTF-8 in IPC frame"))
}

pub fn run_ipc_server<H>(
    socket_path: &Path,
    stop: Arc<AtomicBool>,
    handler: Arc<H>,
) -> Result<(), String>
where
    H: Fn(IpcRequest) -> IpcResponse + Send + Sync + 'static,
{
    if let Some(parent) = socket_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("mkdir socket parent: {}", e))?;
        let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
    }
    if socket_path.exists() {
        let _ = fs::remove_file(socket_path);
    }

    let listener = UnixListener::bind(socket_path)
        .map_err(|e| format!("bind unix socket {}: {}", socket_path.display(), e))?;
    let _ = fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600));
    listener
        .set_nonblocking(true)
        .map_err(|e| format!("set_nonblocking listener: {}", e))?;

    let active_clients = Arc::new(AtomicUsize::new(0));

    while !stop.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _)) => {
                if active_clients.load(Ordering::SeqCst) >= MAX_CONCURRENT_CLIENTS {
                    drop(stream);
                    continue;
                }
                if verify_peer_cred(&stream).is_err() {
                    drop(stream);
                    continue;
                }
                let _ = stream.set_nonblocking(false);
                let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
                let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));

                let h = Arc::clone(&handler);
                let stop_c = Arc::clone(&stop);
                let counter = Arc::clone(&active_clients);
                counter.fetch_add(1, Ordering::SeqCst);

                thread::spawn(move || {
                    handle_client(stream, stop_c, h);
                    counter.fetch_sub(1, Ordering::SeqCst);
                });
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(40));
            }
            Err(_) => {
                thread::sleep(Duration::from_millis(100));
            }
        }
    }

    let _ = fs::remove_file(socket_path);
    Ok(())
}

fn handle_client<H>(stream: UnixStream, stop: Arc<AtomicBool>, handler: Arc<H>)
where
    H: Fn(IpcRequest) -> IpcResponse,
{
    let Ok(mut writer) = stream.try_clone() else {
        return;
    };
    let mut reader = BufReader::new(stream);

    while !stop.load(Ordering::Relaxed) {
        match read_line_bounded(&mut reader, MAX_IPC_LINE_BYTES) {
            Ok(Some(line)) => {
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    continue;
                }
                let resp = match parse_ipc_request(trimmed) {
                    Ok(req) => handler(req),
                    Err((req_id, err_msg)) => {
                        IpcResponse::err(req_id, format!("malformed request: {}", err_msg))
                    }
                };
                if let Ok(mut out) = serde_json::to_string(&resp) {
                    out.push('\n');
                    if writer.write_all(out.as_bytes()).is_err() || writer.flush().is_err() {
                        break;
                    }
                }
            }
            Ok(None) => break,
            Err(e)
                if e.kind() == io::ErrorKind::WouldBlock
                    || e.kind() == io::ErrorKind::TimedOut =>
            {
                // Keep persistent --bridge connections alive while UI is open
                continue;
            }
            Err(_) => break,
        }
    }
}

/// Persistent stdio-to-Unix-socket bridge invoked by `Silo.apk` via `su -c silod --bridge`.
/// Keeps `/data/adb/silo/silod.sock` strictly `0600` root-only without modifying SELinux policies!
pub fn run_stdio_bridge(socket_path: &Path) -> i32 {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut stdin_reader = BufReader::new(stdin.lock());
    let mut stdout_lock = stdout.lock();

    let mut conn: Option<(BufReader<UnixStream>, UnixStream)> = connect_socket(socket_path).ok();

    while let Ok(Some(line)) = read_line_bounded(&mut stdin_reader, MAX_IPC_LINE_BYTES) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        // Lazy reconnect if daemon restarted
        if conn.is_none() {
            conn = connect_socket(socket_path).ok();
        }

        let response_line = match conn.as_mut() {
            Some((sock_r, sock_w)) => {
                let mut payload = String::with_capacity(trimmed.len() + 1);
                payload.push_str(trimmed);
                payload.push('\n');
                if sock_w.write_all(payload.as_bytes()).is_err() || sock_w.flush().is_err() {
                    conn = None;
                    make_bridge_err(trimmed, "lost connection to silod daemon")
                } else {
                    match read_line_bounded(sock_r, MAX_IPC_LINE_BYTES * 4) {
                        Ok(Some(resp)) => resp,
                        _ => {
                            conn = None;
                            make_bridge_err(trimmed, "silod closed connection unexpectedly")
                        }
                    }
                }
            }
            None => make_bridge_err(
                trimmed,
                "silod daemon is not running (ensure Silo KernelSU module is installed & enabled)",
            ),
        };

        if stdout_lock.write_all(response_line.as_bytes()).is_err()
            || stdout_lock.write_all(b"\n").is_err()
            || stdout_lock.flush().is_err()
        {
            break;
        }
    }
    0
}

/// Sends a single JSON-RPC request to the running daemon and prints the response line.
pub fn run_single_rpc(socket_path: &Path, json_req: &str) -> Result<String, String> {
    let (mut r, mut w) =
        connect_socket(socket_path).map_err(|e| format!("cannot connect to silod: {}", e))?;
    let mut payload = json_req.trim().to_string();
    payload.push('\n');
    w.write_all(payload.as_bytes())
        .and_then(|_| w.flush())
        .map_err(|e| format!("write request failed: {}", e))?;
    read_line_bounded(&mut r, MAX_IPC_LINE_BYTES * 4)
        .map_err(|e| format!("read response failed: {}", e))?
        .ok_or_else(|| "empty response from silod".to_string())
}

fn connect_socket(socket_path: &Path) -> io::Result<(BufReader<UnixStream>, UnixStream)> {
    let stream = UnixStream::connect(socket_path)?;
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(Duration::from_secs(10)))?;
    let writer = stream.try_clone()?;
    Ok((BufReader::new(stream), writer))
}

fn make_bridge_err(raw_req: &str, msg: &str) -> String {
    let req_id = serde_json::from_str::<serde_json::Value>(raw_req)
        .ok()
        .and_then(|v| v.get("id")?.as_u64())
        .unwrap_or(0);
    serde_json::to_string(&IpcResponse::err(req_id, msg))
        .unwrap_or_else(|_| r#"{"id":0,"ok":false,"error":"bridge error"}"#.to_string())
}

#[allow(dead_code)]
fn _assert_read_trait<R: Read>(_: &R) {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::DaemonCommand;

    #[test]
    fn bounded_reader_enforces_limit() {
        let data = b"hello\nworld\n";
        let mut cursor = BufReader::new(&data[..]);
        assert_eq!(read_line_bounded(&mut cursor, 16).unwrap().as_deref(), Some("hello"));
        assert_eq!(read_line_bounded(&mut cursor, 16).unwrap().as_deref(), Some("world"));
        assert_eq!(read_line_bounded(&mut cursor, 16).unwrap(), None);

        let oversized = vec![b'A'; 1024];
        let mut cur2 = BufReader::new(&oversized[..]);
        assert!(read_line_bounded(&mut cur2, 128).is_err());
    }

    #[test]
    fn unix_socket_server_handles_authenticated_rpc() {
        let sock = std::env::temp_dir().join(format!("silo_ipc_test_{}.sock", std::process::id()));
        let stop = Arc::new(AtomicBool::new(false));
        let stop_srv = Arc::clone(&stop);
        let sock_srv = sock.clone();

        let handler = Arc::new(|req: IpcRequest| match req.command {
            DaemonCommand::Ping => IpcResponse::ok(req.id, serde_json::json!({ "pong": true })),
            _ => IpcResponse::err(req.id, "unsupported"),
        });

        let t = thread::spawn(move || {
            let _ = run_ipc_server(&sock_srv, stop_srv, handler);
        });

        // Wait briefly for listener bind
        for _ in 0..30 {
            if sock.exists() {
                break;
            }
            thread::sleep(Duration::from_millis(20));
        }

        let resp_raw = run_single_rpc(&sock, r#"{"id":99,"cmd":"ping"}"#).unwrap();
        let resp: IpcResponse = serde_json::from_str(&resp_raw).unwrap();
        assert_eq!(resp.id, 99);
        assert!(resp.ok);

        stop.store(true, Ordering::SeqCst);
        let _ = t.join();
        let _ = fs::remove_file(&sock);
    }
}
