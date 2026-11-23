use crate::protocol::{
    parse_ipc_request, IpcRequest, IpcResponse, MAX_IPC_LINE_BYTES, MAX_IPC_RESPONSE_BYTES,
};
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const MAX_CONCURRENT_CLIENTS: usize = 16;
const SERVER_READ_TIMEOUT: Duration = Duration::from_secs(30);
const SERVER_WRITE_TIMEOUT: Duration = Duration::from_secs(15);
const BRIDGE_READ_TIMEOUT: Duration = Duration::from_secs(45);
const BRIDGE_WRITE_TIMEOUT: Duration = Duration::from_secs(15);

type BridgeConn = (BufReader<UnixStream>, UnixStream);

/// Process-wide mutex serializing the tiny `umask(0o077)` -> `bind()` -> restore critical section
/// so parallel Rust tests never race on the process-global umask.
static BIND_UMASK_LOCK: Mutex<()> = Mutex::new(());

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

/// Derives the singleton lockfile path alongside `socket_path`:
/// - `/data/adb/silo/silod.sock` -> `/data/adb/silo/silod.lock`
/// - `/tmp/foo/test.sock` -> `/tmp/foo/test.lock`
/// - `/tmp/foo/sock` -> `/tmp/foo/sock.lock`
pub fn derive_lock_path(socket_path: &Path) -> PathBuf {
    if socket_path.extension().is_some() {
        socket_path.with_extension("lock")
    } else {
        let mut os = socket_path.as_os_str().to_os_string();
        os.push(".lock");
        PathBuf::from(os)
    }
}

/// Exclusive non-blocking `flock(LOCK_EX | LOCK_NB)` RAII guard preventing duplicate `silod` instances.
/// Intentionally NEVER unlinks the lockfile on drop (unlinking a flocked path creates an inode race).
pub struct SingletonLockGuard {
    file: File,
}

impl SingletonLockGuard {
    pub fn acquire(lock_path: &Path) -> Result<Self, String> {
        if let Some(parent) = lock_path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).map_err(|e| format!("mkdir lock parent: {}", e))?;
                let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
            }
        }
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .mode(0o600)
            .open(lock_path)
            .map_err(|e| format!("open singleton lock {}: {}", lock_path.display(), e))?;
        let _ = fs::set_permissions(lock_path, fs::Permissions::from_mode(0o600));

        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if rc != 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EWOULDBLOCK)
                || err.kind() == io::ErrorKind::WouldBlock
            {
                return Err(format!(
                    "another silod instance is already running (lock held: {})",
                    lock_path.display()
                ));
            }
            return Err(format!("flock failed on {}: {}", lock_path.display(), err));
        }
        Ok(Self { file })
    }
}

impl Drop for SingletonLockGuard {
    fn drop(&mut self) {
        unsafe {
            let _ = libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

struct UmaskGuard {
    prev: libc::mode_t,
}

impl UmaskGuard {
    fn restrict_077() -> Self {
        let prev = unsafe { libc::umask(0o077) };
        Self { prev }
    }
}

impl Drop for UmaskGuard {
    fn drop(&mut self) {
        unsafe {
            let _ = libc::umask(self.prev);
        }
    }
}

struct SocketUnlinkGuard<'a> {
    socket_path: &'a Path,
    bound_dev_ino: Option<(u64, u64)>,
}

impl Drop for SocketUnlinkGuard<'_> {
    fn drop(&mut self) {
        unlink_socket_if_matches(self.socket_path, self.bound_dev_ino);
    }
}

fn unlink_socket_if_matches(socket_path: &Path, expected_dev_ino: Option<(u64, u64)>) {
    let Some((exp_dev, exp_ino)) = expected_dev_ino else {
        return;
    };
    if let Ok(meta) = fs::symlink_metadata(socket_path) {
        if meta.dev() == exp_dev && meta.ino() == exp_ino {
            let _ = fs::remove_file(socket_path);
        }
    }
}

/// Starts the root-only Unix domain IPC server, acquiring the singleton `flock` next to `socket_path`.
#[cfg(test)]
pub fn run_ipc_server<H>(
    socket_path: &Path,
    stop: Arc<AtomicBool>,
    handler: Arc<H>,
) -> Result<(), String>
where
    H: Fn(IpcRequest) -> IpcResponse + Send + Sync + 'static,
{
    let lock = SingletonLockGuard::acquire(&derive_lock_path(socket_path))?;
    run_ipc_server_with_lock(socket_path, &lock, stop, handler)
}

/// Starts the IPC server when the caller (`run_daemon`) already holds `SingletonLockGuard`
/// so the lock remains held across startup subsystem sync AND shutdown cleanup (`thaw_all`).
pub fn run_ipc_server_with_lock<H>(
    socket_path: &Path,
    _lock: &SingletonLockGuard,
    stop: Arc<AtomicBool>,
    handler: Arc<H>,
) -> Result<(), String>
where
    H: Fn(IpcRequest) -> IpcResponse + Send + Sync + 'static,
{

    if let Some(parent) = socket_path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).map_err(|e| format!("mkdir socket parent: {}", e))?;
            let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
        }
    }

    let (listener, bound_dev_ino) = {
        let _umask_mtx = BIND_UMASK_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let _umask = UmaskGuard::restrict_077();
        if fs::symlink_metadata(socket_path).is_ok() {
            let _ = fs::remove_file(socket_path);
        }
        let listener = UnixListener::bind(socket_path)
            .map_err(|e| format!("bind unix socket {}: {}", socket_path.display(), e))?;
        let _ = fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600));
        let dev_ino = fs::symlink_metadata(socket_path)
            .ok()
            .map(|m| (m.dev(), m.ino()));
        (listener, dev_ino)
    };

    let _cleanup_guard = SocketUnlinkGuard {
        socket_path,
        bound_dev_ino,
    };

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
                let _ = stream.set_read_timeout(Some(SERVER_READ_TIMEOUT));
                let _ = stream.set_write_timeout(Some(SERVER_WRITE_TIMEOUT));

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
            Err(e) if e.kind() == io::ErrorKind::InvalidData => {
                let resp = IpcResponse::err(0, format!("IPC framing error: {}", e));
                if let Ok(mut out) = serde_json::to_string(&resp) {
                    out.push('\n');
                    let _ = writer.write_all(out.as_bytes());
                    let _ = writer.flush();
                }
                break;
            }
            Err(_) => break,
        }
    }
}

fn exchange_on_conn(conn: &mut BridgeConn, payload: &[u8]) -> io::Result<String> {
    let (sock_r, sock_w) = conn;
    sock_w.write_all(payload)?;
    sock_w.flush()?;
    match read_line_bounded(sock_r, MAX_IPC_RESPONSE_BYTES)? {
        Some(resp) => Ok(resp),
        None => Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "silod closed connection unexpectedly",
        )),
    }
}

fn is_reconnectable_err(e: &io::Error) -> bool {
    matches!(
        e.kind(),
        io::ErrorKind::BrokenPipe
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::UnexpectedEof
            | io::ErrorKind::NotConnected
    )
}

/// Persistent stdio-to-Unix-socket bridge invoked by `Silo.apk` via `su -c silod --bridge`.
/// Keeps `/data/adb/silo/silod.sock` strictly `0600` root-only without modifying SELinux policies!
pub fn run_stdio_bridge(socket_path: &Path) -> i32 {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut stdin_reader = BufReader::new(stdin.lock());
    let mut stdout_lock = stdout.lock();
    run_stdio_bridge_io(socket_path, &mut stdin_reader, &mut stdout_lock)
}

/// Testable core stdio bridge loop supporting transparent single reconnect+retry on cached-socket failure.
pub fn run_stdio_bridge_io<R: BufRead, W: Write>(
    socket_path: &Path,
    stdin_reader: &mut R,
    stdout_writer: &mut W,
) -> i32 {
    let mut conn: Option<BridgeConn> = connect_socket(socket_path).ok();

    loop {
        let line = match read_line_bounded(stdin_reader, MAX_IPC_LINE_BYTES) {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(e) => {
                let err_line = make_bridge_err("", &format!("IPC framing error: {}", e));
                let _ = stdout_writer.write_all(err_line.as_bytes());
                let _ = stdout_writer.write_all(b"\n");
                let _ = stdout_writer.flush();
                break;
            }
        };

        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let mut payload = String::with_capacity(trimmed.len() + 1);
        payload.push_str(trimmed);
        payload.push('\n');

        let had_cached_conn = conn.is_some();
        if conn.is_none() {
            conn = connect_socket(socket_path).ok();
        }

        let response_line = match conn.as_mut() {
            Some(active) => match exchange_on_conn(active, payload.as_bytes()) {
                Ok(resp) => resp,
                Err(first_err) if had_cached_conn && is_reconnectable_err(&first_err) => {
                    // Cached socket died (e.g., silod restarted): transparently reconnect once and retry
                    conn = connect_socket(socket_path).ok();
                    match conn.as_mut() {
                        Some(fresh) => match exchange_on_conn(fresh, payload.as_bytes()) {
                            Ok(resp) => resp,
                            Err(retry_err) => {
                                conn = None;
                                make_bridge_err(
                                    trimmed,
                                    &format!("lost connection to silod daemon: {}", retry_err),
                                )
                            }
                        },
                        None => make_bridge_err(
                            trimmed,
                            "silod daemon is not running (ensure Silo KernelSU module is installed & enabled)",
                        ),
                    }
                }
                Err(err) => {
                    conn = None;
                    let msg = if err.kind() == io::ErrorKind::TimedOut
                        || err.kind() == io::ErrorKind::WouldBlock
                    {
                        "silod request timed out".to_string()
                    } else {
                        format!("silod IPC error: {}", err)
                    };
                    make_bridge_err(trimmed, &msg)
                }
            },
            None => make_bridge_err(
                trimmed,
                "silod daemon is not running (ensure Silo KernelSU module is installed & enabled)",
            ),
        };

        if stdout_writer.write_all(response_line.as_bytes()).is_err()
            || stdout_writer.write_all(b"\n").is_err()
            || stdout_writer.flush().is_err()
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
    read_line_bounded(&mut r, MAX_IPC_RESPONSE_BYTES)
        .map_err(|e| format!("read response failed: {}", e))?
        .ok_or_else(|| "empty response from silod".to_string())
}

fn connect_socket(socket_path: &Path) -> io::Result<BridgeConn> {
    let stream = UnixStream::connect(socket_path)?;
    stream.set_read_timeout(Some(BRIDGE_READ_TIMEOUT))?;
    stream.set_write_timeout(Some(BRIDGE_WRITE_TIMEOUT))?;
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::DaemonCommand;
    use std::io::Read;
    use std::sync::mpsc;

    fn wait_for_socket(sock: &Path) {
        for _ in 0..80 {
            if fs::symlink_metadata(sock).is_ok() {
                return;
            }
            thread::sleep(Duration::from_millis(15));
        }
        panic!("timed out waiting for socket {}", sock.display());
    }

    #[test]
    fn bounded_reader_enforces_limit() {
        let data = b"hello\nworld\n";
        let mut cursor = BufReader::new(&data[..]);
        assert_eq!(
            read_line_bounded(&mut cursor, 16).unwrap().as_deref(),
            Some("hello")
        );
        assert_eq!(
            read_line_bounded(&mut cursor, 16).unwrap().as_deref(),
            Some("world")
        );
        assert_eq!(read_line_bounded(&mut cursor, 16).unwrap(), None);

        let oversized = vec![b'A'; 1024];
        let mut cur2 = BufReader::new(&oversized[..]);
        assert!(read_line_bounded(&mut cur2, 128).is_err());
    }

    #[test]
    fn unix_socket_server_handles_authenticated_rpc() {
        let dir = std::env::temp_dir().join(format!("silo_ipc_auth_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let sock = dir.join("silod.sock");
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

        wait_for_socket(&sock);

        let resp_raw = run_single_rpc(&sock, r#"{"id":99,"cmd":"ping"}"#).unwrap();
        let resp: IpcResponse = serde_json::from_str(&resp_raw).unwrap();
        assert_eq!(resp.id, 99);
        assert!(resp.ok);

        stop.store(true, Ordering::SeqCst);
        let _ = t.join();
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn derive_lock_path_handles_sock_and_custom_extensions() {
        assert_eq!(
            derive_lock_path(Path::new("/data/adb/silo/silod.sock")),
            PathBuf::from("/data/adb/silo/silod.lock")
        );
        assert_eq!(
            derive_lock_path(Path::new("/tmp/a/test.sock")),
            PathBuf::from("/tmp/a/test.lock")
        );
        assert_eq!(
            derive_lock_path(Path::new("/tmp/a/custom")),
            PathBuf::from("/tmp/a/custom.lock")
        );
    }

    #[test]
    fn singleton_lock_rejects_second_instance_and_releases_on_drop() {
        let dir = std::env::temp_dir().join(format!("silo_ipc_lock_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let sock = dir.join("silod.sock");
        let lock_path = derive_lock_path(&sock);

        let g1 = SingletonLockGuard::acquire(&lock_path).unwrap();
        let err = SingletonLockGuard::acquire(&lock_path)
            .err()
            .expect("second lock acquisition must fail");
        assert!(err.contains("already running"), "unexpected err: {}", err);

        let stop = Arc::new(AtomicBool::new(false));
        let handler = Arc::new(|req: IpcRequest| IpcResponse::ok_empty(req.id));
        let srv_err = run_ipc_server(&sock, stop, handler)
            .expect_err("duplicate server must fail while lock is held");
        assert!(
            srv_err.contains("already running"),
            "unexpected server err: {}",
            srv_err
        );

        drop(g1);
        let g2 = SingletonLockGuard::acquire(&lock_path).unwrap();
        drop(g2);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn shutdown_preserves_replaced_socket_inode() {
        let dir = std::env::temp_dir().join(format!("silo_ipc_inode_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let sock = dir.join("silod.sock");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_srv = Arc::clone(&stop);
        let sock_srv = sock.clone();

        let handler = Arc::new(|req: IpcRequest| IpcResponse::ok_empty(req.id));
        let t = thread::spawn(move || {
            let _ = run_ipc_server(&sock_srv, stop_srv, handler);
        });
        wait_for_socket(&sock);

        // Replace socket path with a brand new file (different inode)
        let replacement = dir.join("replacement.tmp");
        fs::write(&replacement, b"new-owner").unwrap();
        fs::rename(&replacement, &sock).unwrap();

        stop.store(true, Ordering::SeqCst);
        let _ = t.join();

        assert!(
            sock.exists(),
            "old server shutdown must NOT unlink a replaced socket with a different inode"
        );
        assert_eq!(fs::read(&sock).unwrap(), b"new-owner");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn four_mib_frame_accepted_and_oversized_rejected() {
        // 2 MiB JSON line fits comfortably within 4 MiB MAX_IPC_LINE_BYTES
        let mut two_mib = vec![b'a'; 2 * 1024 * 1024];
        two_mib.push(b'\n');
        let mut cur = BufReader::new(&two_mib[..]);
        let got = read_line_bounded(&mut cur, MAX_IPC_LINE_BYTES)
            .unwrap()
            .unwrap();
        assert_eq!(got.len(), 2 * 1024 * 1024);

        // MAX_IPC_LINE_BYTES + 1 bytes must be rejected with InvalidData
        let mut oversized = vec![b'b'; MAX_IPC_LINE_BYTES + 1];
        oversized.push(b'\n');
        let mut cur_over = BufReader::new(&oversized[..]);
        let err = read_line_bounded(&mut cur_over, MAX_IPC_LINE_BYTES).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);

        // Verify socket bind enforces 0600 permissions
        let dir = std::env::temp_dir().join(format!("silo_ipc_mode_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let sock = dir.join("silod.sock");
        let stop = Arc::new(AtomicBool::new(false));
        let stop_srv = Arc::clone(&stop);
        let sock_srv = sock.clone();
        let handler = Arc::new(|req: IpcRequest| IpcResponse::ok_empty(req.id));
        let t = thread::spawn(move || {
            let _ = run_ipc_server(&sock_srv, stop_srv, handler);
        });
        wait_for_socket(&sock);

        let mode = fs::symlink_metadata(&sock).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "IPC socket must be bound 0600 root-only");

        stop.store(true, Ordering::SeqCst);
        let _ = t.join();
        let _ = fs::remove_dir_all(&dir);
    }

    struct ChannelReader {
        rx: mpsc::Receiver<Vec<u8>>,
        buf: Vec<u8>,
        pos: usize,
    }

    impl Read for ChannelReader {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            let avail = self.fill_buf()?;
            if avail.is_empty() {
                return Ok(0);
            }
            let n = avail.len().min(out.len());
            out[..n].copy_from_slice(&avail[..n]);
            self.consume(n);
            Ok(n)
        }
    }

    impl BufRead for ChannelReader {
        fn fill_buf(&mut self) -> io::Result<&[u8]> {
            if self.pos >= self.buf.len() {
                match self.rx.recv() {
                    Ok(chunk) => {
                        self.buf = chunk;
                        self.pos = 0;
                    }
                    Err(_) => {
                        self.buf.clear();
                        self.pos = 0;
                    }
                }
            }
            Ok(&self.buf[self.pos..])
        }

        fn consume(&mut self, amt: usize) {
            self.pos = (self.pos + amt).min(self.buf.len());
        }
    }

    struct LineChannelWriter {
        tx: mpsc::Sender<String>,
        buf: Vec<u8>,
    }

    impl Write for LineChannelWriter {
        fn write(&mut self, data: &[u8]) -> io::Result<usize> {
            self.buf.extend_from_slice(data);
            while let Some(pos) = self.buf.iter().position(|&b| b == b'\n') {
                let line = String::from_utf8_lossy(&self.buf[..pos]).into_owned();
                self.buf.drain(..=pos);
                let _ = self.tx.send(line);
            }
            Ok(data.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn bridge_reconnects_once_transparently_after_server_restart() {
        let dir = std::env::temp_dir().join(format!("silo_ipc_bridge_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let sock = dir.join("silod.sock");

        let make_handler = || {
            Arc::new(|req: IpcRequest| match req.command {
                DaemonCommand::Ping => IpcResponse::ok(req.id, serde_json::json!({ "pong": true })),
                _ => IpcResponse::err(req.id, "unsupported"),
            })
        };

        // Start server #1
        let stop1 = Arc::new(AtomicBool::new(false));
        let stop1_srv = Arc::clone(&stop1);
        let sock1 = sock.clone();
        let h1 = make_handler();
        let t1 = thread::spawn(move || {
            let _ = run_ipc_server(&sock1, stop1_srv, h1);
        });
        wait_for_socket(&sock);

        // Start persistent --bridge session wired to channels
        let (req_tx, req_rx) = mpsc::channel::<Vec<u8>>();
        let (resp_tx, resp_rx) = mpsc::channel::<String>();
        let sock_bridge = sock.clone();
        let bridge_thread = thread::spawn(move || {
            let mut reader = ChannelReader {
                rx: req_rx,
                buf: Vec::new(),
                pos: 0,
            };
            let mut writer = LineChannelWriter {
                tx: resp_tx,
                buf: Vec::new(),
            };
            run_stdio_bridge_io(&sock_bridge, &mut reader, &mut writer)
        });

        // Request 1 on server #1
        req_tx
            .send(b"{\"id\":1,\"cmd\":\"ping\"}\n".to_vec())
            .unwrap();
        let raw1 = resp_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        let r1: IpcResponse = serde_json::from_str(&raw1).unwrap();
        assert_eq!(r1.id, 1);
        assert!(r1.ok);

        // Restart daemon: stop server #1 cleanly, then bind server #2 at same socket path
        stop1.store(true, Ordering::SeqCst);
        let _ = t1.join();

        let stop2 = Arc::new(AtomicBool::new(false));
        let stop2_srv = Arc::clone(&stop2);
        let sock2 = sock.clone();
        let h2 = make_handler();
        let t2 = thread::spawn(move || {
            let _ = run_ipc_server(&sock2, stop2_srv, h2);
        });
        wait_for_socket(&sock);

        // Request 2 on the SAME bridge session: must transparently reconnect once and succeed!
        req_tx
            .send(b"{\"id\":2,\"cmd\":\"ping\"}\n".to_vec())
            .unwrap();
        let raw2 = resp_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        let r2: IpcResponse = serde_json::from_str(&raw2).unwrap();
        assert_eq!(r2.id, 2);
        assert!(r2.ok, "bridge retry after restart failed: {:?}", r2.error);

        // Clean shutdown
        drop(req_tx);
        assert_eq!(bridge_thread.join().unwrap(), 0);
        stop2.store(true, Ordering::SeqCst);
        let _ = t2.join();
        let _ = fs::remove_dir_all(&dir);
    }
}
