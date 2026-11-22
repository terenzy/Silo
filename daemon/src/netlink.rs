use crate::config::CanonicalCidr;
use crate::protocol::{ActiveConnection, ActiveInterfaces, BlockedPacketEvent};
use crate::sys::now_millis;
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

// Netlink constants (verified against Linux 6.6 UAPI headers)
const AF_NETLINK: i32 = libc::AF_NETLINK;
const NETLINK_ROUTE: i32 = 0;
const NETLINK_SOCK_DIAG: i32 = 4;
const NETLINK_NETFILTER: i32 = 12;

const NLM_F_REQUEST: u16 = 0x0001;
const NLM_F_ACK: u16 = 0x0004;
const NLM_F_DUMP: u16 = 0x0300;

const NLMSG_ERROR: u16 = 2;
const NLMSG_DONE: u16 = 3;

const NLA_F_NESTED: u16 = 0x8000;
const NLA_F_NET_BYTEORDER: u16 = 0x4000;
const NLA_TYPE_MASK: u16 = 0x3FFF;

// sock_diag / inet_diag
const SOCK_DIAG_BY_FAMILY: u16 = 20;
const SOCK_DESTROY: u16 = 21;
const INET_DIAG_NOCOOKIE: u32 = u32::MAX;

// nfnetlink subsystems
const NFNL_SUBSYS_ULOG: u16 = 4;
const NFNL_SUBSYS_IPSET: u16 = 6;
const NFNETLINK_V0: u8 = 0;

// NFLOG (nfnetlink_log.h)
const NFULNL_MSG_PACKET: u16 = 0;
const NFULNL_MSG_CONFIG: u16 = 1;
const NFULA_PAYLOAD: u16 = 9;
const NFULA_PREFIX: u16 = 10;
const NFULA_UID: u16 = 11;
const NFULA_CFG_CMD: u16 = 1;
const NFULA_CFG_MODE: u16 = 2;
const NFULNL_CFG_CMD_BIND: u8 = 1;
const NFULNL_CFG_CMD_PF_BIND: u8 = 3;
const NFULNL_CFG_CMD_PF_UNBIND: u8 = 4;
const NFULNL_COPY_PACKET: u8 = 2;

pub const SILO_NFLOG_GROUP: u16 = 42;

// IPSET (ip_set.h)
const IPSET_PROTOCOL_7: u8 = 7;
const IPSET_PROTOCOL_MIN: u8 = 6;
const IPSET_CMD_PROTOCOL: u16 = 1;
const IPSET_CMD_CREATE: u16 = 2;
const IPSET_CMD_DESTROY: u16 = 3;
const IPSET_CMD_SWAP: u16 = 6;
const IPSET_CMD_ADD: u16 = 9;
const IPSET_CMD_TYPE: u16 = 13;

const IPSET_ATTR_PROTOCOL: u16 = 1;
const IPSET_ATTR_SETNAME: u16 = 2;
const IPSET_ATTR_TYPENAME: u16 = 3;
const IPSET_ATTR_SETNAME2: u16 = 3;
const IPSET_ATTR_REVISION: u16 = 4;
const IPSET_ATTR_FAMILY: u16 = 5;
const IPSET_ATTR_FLAGS: u16 = 6;
const IPSET_ATTR_DATA: u16 = 7;

const IPSET_ATTR_IP: u16 = 1;
const IPSET_ATTR_CIDR: u16 = 3;
const IPSET_ATTR_HASHSIZE: u16 = 18;
const IPSET_ATTR_MAXELEM: u16 = 19;
const IPSET_ATTR_IPADDR_IPV4: u16 = 1;
const IPSET_ATTR_IPADDR_IPV6: u16 = 2;
const IPSET_FLAG_EXIST: u32 = 1;

// rtnetlink
const RTM_GETLINK: u16 = 18;
const RTM_NEWLINK: u16 = 16;
const IFLA_IFNAME: u16 = 3;
const IFF_UP: u32 = 0x1;
const IFF_LOOPBACK: u32 = 0x8;
const IFF_RUNNING: u32 = 0x40;

#[inline]
fn nlmsg_align(len: usize) -> usize {
    (len + 3) & !3
}

#[inline]
fn nla_align(len: usize) -> usize {
    (len + 3) & !3
}

fn open_netlink_socket(protocol: i32, rcvbuf: i32) -> io::Result<OwnedFd> {
    let fd = unsafe {
        libc::socket(
            AF_NETLINK,
            libc::SOCK_RAW | libc::SOCK_CLOEXEC,
            protocol,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };

    if rcvbuf > 0 {
        unsafe {
            libc::setsockopt(
                owned.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_RCVBUF,
                (&rcvbuf as *const i32).cast(),
                std::mem::size_of::<i32>() as libc::socklen_t,
            );
        }
    }

    // 2s receive timeout so blocking Netlink operations can never hang daemon threads
    let tv = libc::timeval {
        tv_sec: 2,
        tv_usec: 0,
    };
    unsafe {
        libc::setsockopt(
            owned.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            (&tv as *const libc::timeval).cast(),
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        );
    }

    let mut addr: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
    addr.nl_family = AF_NETLINK as u16;
    addr.nl_pid = 0; // Let kernel assign unique port id
    addr.nl_groups = 0;

    let rc = unsafe {
        libc::bind(
            owned.as_raw_fd(),
            (&addr as *const libc::sockaddr_nl).cast(),
            std::mem::size_of::<libc::sockaddr_nl>() as libc::socklen_t,
        )
    };
    if rc < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(owned)
}

fn nl_send(fd: &OwnedFd, msg: &[u8]) -> io::Result<()> {
    let mut dst: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
    dst.nl_family = AF_NETLINK as u16;
    let sent = unsafe {
        libc::sendto(
            fd.as_raw_fd(),
            msg.as_ptr().cast(),
            msg.len(),
            0,
            (&dst as *const libc::sockaddr_nl).cast(),
            std::mem::size_of::<libc::sockaddr_nl>() as libc::socklen_t,
        )
    };
    if sent < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn nl_recv(fd: &OwnedFd, buf: &mut [u8]) -> io::Result<usize> {
    let n = unsafe {
        libc::recv(
            fd.as_raw_fd(),
            buf.as_mut_ptr().cast(),
            buf.len(),
            0,
        )
    };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

struct NlBuf {
    data: Vec<u8>,
}

impl NlBuf {
    fn new(msg_type: u16, flags: u16, seq: u32) -> Self {
        let mut data = Vec::with_capacity(128);
        data.extend_from_slice(&0u32.to_ne_bytes()); // nlmsg_len placeholder
        data.extend_from_slice(&msg_type.to_ne_bytes());
        data.extend_from_slice(&flags.to_ne_bytes());
        data.extend_from_slice(&seq.to_ne_bytes());
        data.extend_from_slice(&0u32.to_ne_bytes()); // nlmsg_pid
        Self { data }
    }

    fn put_bytes(&mut self, b: &[u8]) {
        self.data.extend_from_slice(b);
    }

    fn put_nla(&mut self, nla_type: u16, payload: &[u8]) {
        let len = (4 + payload.len()) as u16;
        self.data.extend_from_slice(&len.to_ne_bytes());
        self.data.extend_from_slice(&nla_type.to_ne_bytes());
        self.data.extend_from_slice(payload);
        let pad = nla_align(len as usize) - (len as usize);
        for _ in 0..pad {
            self.data.push(0);
        }
    }

    fn start_nested(&mut self, nla_type: u16) -> usize {
        let pos = self.data.len();
        self.data.extend_from_slice(&0u16.to_ne_bytes());
        self.data
            .extend_from_slice(&(nla_type | NLA_F_NESTED).to_ne_bytes());
        pos
    }

    fn end_nested(&mut self, start_pos: usize) {
        let len = (self.data.len() - start_pos) as u16;
        self.data[start_pos..start_pos + 2].copy_from_slice(&len.to_ne_bytes());
        let pad = nla_align(len as usize) - (len as usize);
        for _ in 0..pad {
            self.data.push(0);
        }
    }

    fn finish(mut self) -> Vec<u8> {
        let total = self.data.len() as u32;
        self.data[0..4].copy_from_slice(&total.to_ne_bytes());
        self.data
    }
}

fn parse_nl_ack(buf: &[u8]) -> Result<(), i32> {
    if buf.len() < 20 {
        return Err(libc::EPROTO);
    }
    let nlmsg_type = u16::from_ne_bytes([buf[4], buf[5]]);
    if nlmsg_type == NLMSG_ERROR {
        let err = i32::from_ne_bytes([buf[16], buf[17], buf[18], buf[19]]);
        if err == 0 {
            return Ok(());
        }
        return Err(-err);
    }
    Ok(())
}

// ============================================================================
// 1. Live Interface Enumeration via NETLINK_ROUTE (MediaTek ccmni+, wlan+, tun+)
// ============================================================================

pub fn classify_active_interfaces() -> ActiveInterfaces {
    let mut result = ActiveInterfaces::default();
    let Ok(fd) = open_netlink_socket(NETLINK_ROUTE, 65536) else {
        return classify_interfaces_sysfs_fallback();
    };

    // struct ifinfomsg (16 bytes): family=AF_UNSPEC(0), rest zero
    let mut msg = NlBuf::new(RTM_GETLINK, NLM_F_REQUEST | NLM_F_DUMP, 1);
    msg.put_bytes(&[0u8; 16]);
    if nl_send(&fd, &msg.finish()).is_err() {
        return classify_interfaces_sysfs_fallback();
    }

    let mut buf = vec![0u8; 32768];
    while let Ok(n) = nl_recv(&fd, &mut buf) {
        if n == 0 {
            break;
        }
        let mut offset = 0usize;
        let mut done = false;
        while offset + 16 <= n {
            let nl_len = u32::from_ne_bytes([
                buf[offset],
                buf[offset + 1],
                buf[offset + 2],
                buf[offset + 3],
            ]) as usize;
            let nl_type = u16::from_ne_bytes([buf[offset + 4], buf[offset + 5]]);
            if nl_len < 16 || offset + nl_len > n {
                break;
            }
            if nl_type == NLMSG_DONE {
                done = true;
                break;
            }
            if nl_type == RTM_NEWLINK && nl_len >= 32 {
                let ifi_flags = u32::from_ne_bytes([
                    buf[offset + 24],
                    buf[offset + 25],
                    buf[offset + 26],
                    buf[offset + 27],
                ]);
                let is_up = (ifi_flags & IFF_UP) != 0;
                let is_running = (ifi_flags & IFF_RUNNING) != 0;
                let is_lo = (ifi_flags & IFF_LOOPBACK) != 0;
                if is_up && !is_lo {
                    if let Some(name) = extract_ifname(&buf[offset + 32..offset + nl_len]) {
                        push_classified_iface(&mut result, name, is_running);
                    }
                }
            }
            offset += nlmsg_align(nl_len);
        }
        if done {
            break;
        }
    }

    result
}

fn extract_ifname(attrs: &[u8]) -> Option<String> {
    let mut pos = 0usize;
    while pos + 4 <= attrs.len() {
        let len = u16::from_ne_bytes([attrs[pos], attrs[pos + 1]]) as usize;
        let typ = u16::from_ne_bytes([attrs[pos + 2], attrs[pos + 3]]) & NLA_TYPE_MASK;
        if len < 4 || pos + len > attrs.len() {
            break;
        }
        if typ == IFLA_IFNAME {
            let raw = &attrs[pos + 4..pos + len];
            let end = raw.iter().position(|&b| b == 0).unwrap_or(raw.len());
            return Some(String::from_utf8_lossy(&raw[..end]).into_owned());
        }
        pos += nla_align(len);
    }
    None
}

fn classify_interfaces_sysfs_fallback() -> ActiveInterfaces {
    let mut result = ActiveInterfaces::default();
    if let Ok(entries) = std::fs::read_dir("/sys/class/net") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name == "lo" {
                continue;
            }
            let oper = std::fs::read_to_string(entry.path().join("operstate"))
                .unwrap_or_default();
            let up = oper.trim() == "up" || oper.trim() == "unknown";
            if up {
                push_classified_iface(&mut result, name, true);
            }
        }
    }
    result
}

pub fn push_classified_iface(out: &mut ActiveInterfaces, name: String, is_running: bool) {
    if name.starts_with("tun") || name.starts_with("wg") || name.starts_with("ppp") || name.starts_with("ipsec") {
        if !out.vpn.contains(&name) {
            out.vpn.push(name);
        }
    } else if name.starts_with("wlan") || name.starts_with("swlan") || name.starts_with("ap") {
        if is_running && !out.wifi.contains(&name) {
            out.wifi.push(name);
        }
    } else if name.starts_with("ccmni")
        || name.starts_with("rmnet")
        || name.starts_with("pdp")
        || name.starts_with("clat")
        || name.starts_with("v4-ccmni")
        || name.starts_with("v4-rmnet")
    {
        if is_running && !out.cellular.contains(&name) {
            out.cellular.push(name);
        }
    } else if is_running && !name.starts_with("dummy") && !name.starts_with("sit") && !out.other.contains(&name) {
        out.other.push(name);
    }
}

// ============================================================================
// 2. Active & Listening Sockets via NETLINK_SOCK_DIAG + SOCK_DESTROY
// ============================================================================

#[derive(Debug, Clone)]
pub struct RawInetSocket {
    pub family: u8,
    pub protocol: u8,
    pub state: u8,
    pub sport: [u8; 2],
    pub dport: [u8; 2],
    pub src: [u8; 16],
    pub dst: [u8; 16],
    pub iface: u32,
    pub cookie: [u32; 2],
    pub uid: u32,
}

impl RawInetSocket {
    pub fn local_ip(&self) -> IpAddr {
        bytes_to_ip(self.family, &self.src)
    }
    pub fn remote_ip(&self) -> IpAddr {
        bytes_to_ip(self.family, &self.dst)
    }
    pub fn local_port(&self) -> u16 {
        u16::from_be_bytes(self.sport)
    }
    pub fn remote_port(&self) -> u16 {
        u16::from_be_bytes(self.dport)
    }
}

fn bytes_to_ip(family: u8, b: &[u8; 16]) -> IpAddr {
    if family == libc::AF_INET as u8 {
        IpAddr::V4(Ipv4Addr::new(b[0], b[1], b[2], b[3]))
    } else {
        let v6 = Ipv6Addr::from(*b);
        // Unmap IPv4-mapped IPv6 addresses (::ffff:a.b.c.d) for clearer display
        if let Some(v4) = v6.to_ipv4_mapped() {
            IpAddr::V4(v4)
        } else {
            IpAddr::V6(v6)
        }
    }
}

pub fn tcp_state_name(state: u8) -> &'static str {
    match state {
        1 => "ESTABLISHED",
        2 => "SYN_SENT",
        3 => "SYN_RECV",
        4 => "FIN_WAIT1",
        5 => "FIN_WAIT2",
        6 => "TIME_WAIT",
        7 => "CLOSE",
        8 => "CLOSE_WAIT",
        9 => "LAST_ACK",
        10 => "LISTEN",
        11 => "CLOSING",
        _ => "UNKNOWN",
    }
}

pub fn dump_inet_sockets(family: u8, protocol: u8) -> io::Result<Vec<RawInetSocket>> {
    let fd = open_netlink_socket(NETLINK_SOCK_DIAG, 131072)?;
    let mut msg = NlBuf::new(SOCK_DIAG_BY_FAMILY, NLM_F_REQUEST | NLM_F_DUMP, 1);

    // struct inet_diag_req_v2 (56 bytes)
    let mut req = [0u8; 56];
    req[0] = family;
    req[1] = protocol;
    req[2] = 0;
    req[3] = 0;
    req[4..8].copy_from_slice(&0xFFFF_FFFFu32.to_ne_bytes()); // all states
    // cookie = INET_DIAG_NOCOOKIE (offset 48..56)
    req[48..52].copy_from_slice(&INET_DIAG_NOCOOKIE.to_ne_bytes());
    req[52..56].copy_from_slice(&INET_DIAG_NOCOOKIE.to_ne_bytes());
    msg.put_bytes(&req);
    nl_send(&fd, &msg.finish())?;

    let mut sockets = Vec::new();
    let mut buf = vec![0u8; 65536];
    loop {
        let n = match nl_recv(&fd, &mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => break,
            Err(e) => return Err(e),
        };
        let mut offset = 0usize;
        let mut done = false;
        while offset + 16 <= n {
            let nl_len = u32::from_ne_bytes([
                buf[offset],
                buf[offset + 1],
                buf[offset + 2],
                buf[offset + 3],
            ]) as usize;
            let nl_type = u16::from_ne_bytes([buf[offset + 4], buf[offset + 5]]);
            if nl_len < 16 || offset + nl_len > n {
                break;
            }
            if nl_type == NLMSG_DONE || nl_type == NLMSG_ERROR {
                done = true;
                break;
            }
            // struct inet_diag_msg is 72 bytes right after 16-byte nlmsghdr
            if nl_type == SOCK_DIAG_BY_FAMILY && nl_len >= 16 + 72 {
                let p = &buf[offset + 16..offset + 16 + 72];
                let mut src = [0u8; 16];
                let mut dst = [0u8; 16];
                src.copy_from_slice(&p[8..24]);
                dst.copy_from_slice(&p[24..40]);
                let iface = u32::from_ne_bytes([p[40], p[41], p[42], p[43]]);
                let c0 = u32::from_ne_bytes([p[44], p[45], p[46], p[47]]);
                let c1 = u32::from_ne_bytes([p[48], p[49], p[50], p[51]]);
                let uid = u32::from_ne_bytes([p[64], p[65], p[66], p[67]]);
                sockets.push(RawInetSocket {
                    family: p[0],
                    protocol,
                    state: p[1],
                    sport: [p[4], p[5]],
                    dport: [p[6], p[7]],
                    src,
                    dst,
                    iface,
                    cookie: [c0, c1],
                    uid,
                });
            }
            offset += nlmsg_align(nl_len);
        }
        if done {
            break;
        }
    }
    Ok(sockets)
}

pub fn list_active_connections(uid_map: &HashMap<u32, String>) -> Vec<ActiveConnection> {
    let mut conns = Vec::new();
    for (family, proto, proto_name) in [
        (libc::AF_INET as u8, libc::IPPROTO_TCP as u8, "TCP"),
        (libc::AF_INET6 as u8, libc::IPPROTO_TCP as u8, "TCP6"),
        (libc::AF_INET as u8, libc::IPPROTO_UDP as u8, "UDP"),
        (libc::AF_INET6 as u8, libc::IPPROTO_UDP as u8, "UDP6"),
    ] {
        if let Ok(list) = dump_inet_sockets(family, proto) {
            for sk in list {
                let is_listening = (proto == libc::IPPROTO_TCP as u8 && sk.state == 10)
                    || (proto == libc::IPPROTO_UDP as u8 && sk.remote_port() == 0);
                conns.push(ActiveConnection {
                    uid: sk.uid,
                    package_name: uid_map.get(&sk.uid).cloned(),
                    family: sk.family,
                    protocol: proto_name.to_string(),
                    state: tcp_state_name(sk.state).to_string(),
                    local_ip: sk.local_ip().to_string(),
                    local_port: sk.local_port(),
                    remote_ip: sk.remote_ip().to_string(),
                    remote_port: sk.remote_port(),
                    is_listening,
                });
            }
        }
    }
    conns
}

/// Immediately tears down an active TCP socket in kernel space using `SOCK_DESTROY`
/// (`CONFIG_INET_DIAG_DESTROY=y`, verified active in SM-A146P kernel 6.6.127).
pub fn destroy_socket(sk: &RawInetSocket) -> io::Result<()> {
    let fd = open_netlink_socket(NETLINK_SOCK_DIAG, 16384)?;
    let mut msg = NlBuf::new(SOCK_DESTROY, NLM_F_REQUEST | NLM_F_ACK, 2);

    let mut req = [0u8; 56];
    req[0] = sk.family;
    req[1] = sk.protocol;
    req[4..8].copy_from_slice(&(1u32 << sk.state).to_ne_bytes());
    req[8..10].copy_from_slice(&sk.sport);
    req[10..12].copy_from_slice(&sk.dport);
    req[12..28].copy_from_slice(&sk.src);
    req[28..44].copy_from_slice(&sk.dst);
    req[44..48].copy_from_slice(&sk.iface.to_ne_bytes());
    req[48..52].copy_from_slice(&sk.cookie[0].to_ne_bytes());
    req[52..56].copy_from_slice(&sk.cookie[1].to_ne_bytes());

    msg.put_bytes(&req);
    nl_send(&fd, &msg.finish())?;

    let mut buf = [0u8; 4096];
    let n = nl_recv(&fd, &mut buf)?;
    parse_nl_ack(&buf[..n]).map_err(io::Error::from_raw_os_error)
}

/// Kills all active non-listening TCP (v4 + v6) sockets owned by `target_uid`.
/// Returns number of sockets successfully terminated.
pub fn destroy_uid_sockets(target_uid: u32) -> usize {
    let mut killed = 0usize;
    for family in [libc::AF_INET as u8, libc::AF_INET6 as u8] {
        if let Ok(list) = dump_inet_sockets(family, libc::IPPROTO_TCP as u8) {
            for sk in list {
                // Never kill LISTEN sockets on loopback unintentionally; focus on established/active outbound/inbound flows
                if sk.uid == target_uid
                    && sk.state != 10
                    && sk.state != 6
                    && destroy_socket(&sk).is_ok()
                {
                    killed += 1;
                }
            }
        }
    }
    killed
}

pub fn destroy_matching_connection(
    family: u8,
    local_ip: &str,
    local_port: u16,
    remote_ip: &str,
    remote_port: u16,
) -> Result<usize, String> {
    let mut killed = 0usize;
    let fam_candidates: &[u8] = match family {
        4 => &[libc::AF_INET as u8, libc::AF_INET6 as u8],
        6 => &[libc::AF_INET6 as u8],
        f if f == libc::AF_INET as u8 => &[libc::AF_INET as u8, libc::AF_INET6 as u8],
        f if f == libc::AF_INET6 as u8 => &[libc::AF_INET6 as u8],
        _ => &[libc::AF_INET as u8, libc::AF_INET6 as u8],
    };
    for &fam in fam_candidates {
        if let Ok(list) = dump_inet_sockets(fam, libc::IPPROTO_TCP as u8) {
            for sk in list {
                if sk.local_port() == local_port
                    && sk.remote_port() == remote_port
                    && sk.local_ip().to_string() == local_ip
                    && sk.remote_ip().to_string() == remote_ip
                    && destroy_socket(&sk).is_ok()
                {
                    killed += 1;
                }
            }
        }
    }
    Ok(killed)
}

/// Probes whether the kernel responds to `SOCK_DESTROY` (returns EOPNOTSUPP/ENOENT vs EPERM/ENOTSUP).
pub fn probe_sock_destroy_supported() -> bool {
    let Ok(fd) = open_netlink_socket(NETLINK_SOCK_DIAG, 8192) else {
        return false;
    };
    // Send a benign probe targeting a non-existent TCP socket on port 0;
    // kernel returns ENOENT when CONFIG_INET_DIAG_DESTROY=y, or EOPNOTSUPP when disabled.
    let mut msg = NlBuf::new(SOCK_DESTROY, NLM_F_REQUEST | NLM_F_ACK, 99);
    let mut req = [0u8; 56];
    req[0] = libc::AF_INET as u8;
    req[1] = libc::IPPROTO_TCP as u8;
    req[4..8].copy_from_slice(&(1u32 << 1).to_ne_bytes());
    req[48..52].copy_from_slice(&INET_DIAG_NOCOOKIE.to_ne_bytes());
    req[52..56].copy_from_slice(&INET_DIAG_NOCOOKIE.to_ne_bytes());
    msg.put_bytes(&req);
    if nl_send(&fd, &msg.finish()).is_err() {
        return false;
    }
    let mut buf = [0u8; 4096];
    match nl_recv(&fd, &mut buf) {
        Ok(n) => match parse_nl_ack(&buf[..n]) {
            Ok(()) => true,
            Err(errno) => errno != libc::EOPNOTSUPP && errno != libc::ENOSYS,
        },
        Err(_) => false,
    }
}

// ============================================================================
// 3. Native Pure-Rust Netlink IPSET Client (`NFNL_SUBSYS_IPSET = 6`)
//    Works WITHOUT needing an external `/system/bin/ipset` CLI binary!
// ============================================================================

pub fn probe_ipset_netlink() -> Option<u8> {
    let fd = open_netlink_socket(NETLINK_NETFILTER, 16384).ok()?;
    let msg_type = (NFNL_SUBSYS_IPSET << 8) | IPSET_CMD_PROTOCOL;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST, 10);
    // struct nfgenmsg (4 bytes): family=AF_UNSPEC(0), version=0, res_id=0
    msg.put_bytes(&[0, NFNETLINK_V0, 0, 0]);
    msg.put_nla(IPSET_ATTR_PROTOCOL, &[IPSET_PROTOCOL_7]);
    nl_send(&fd, &msg.finish()).ok()?;

    let mut buf = [0u8; 4096];
    let n = nl_recv(&fd, &mut buf).ok()?;
    if n < 20 {
        return None;
    }
    let nl_type = u16::from_ne_bytes([buf[4], buf[5]]);
    if nl_type == NLMSG_ERROR {
        return None;
    }
    // Parse attrs after 16-byte nlmsghdr + 4-byte nfgenmsg
    let mut pos = 20usize;
    while pos + 4 <= n {
        let len = u16::from_ne_bytes([buf[pos], buf[pos + 1]]) as usize;
        let typ = u16::from_ne_bytes([buf[pos + 2], buf[pos + 3]]) & NLA_TYPE_MASK;
        if len < 4 || pos + len > n {
            break;
        }
        if typ == IPSET_ATTR_PROTOCOL && len >= 5 {
            let proto = buf[pos + 4];
            if proto >= IPSET_PROTOCOL_MIN {
                return Some(proto);
            }
        }
        pos += nla_align(len);
    }
    None
}

fn ipset_query_hash_net_revision(fd: &OwnedFd, proto: u8, family: u8) -> Option<u8> {
    let msg_type = (NFNL_SUBSYS_IPSET << 8) | IPSET_CMD_TYPE;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST, 11);
    msg.put_bytes(&[libc::AF_UNSPEC as u8, NFNETLINK_V0, 0, 0]);
    msg.put_nla(IPSET_ATTR_PROTOCOL, &[proto]);
    msg.put_nla(IPSET_ATTR_TYPENAME, b"hash:net\0");
    msg.put_nla(IPSET_ATTR_FAMILY, &[family]);
    nl_send(fd, &msg.finish()).ok()?;

    let mut buf = [0u8; 4096];
    let n = nl_recv(fd, &mut buf).ok()?;
    if n < 20 || u16::from_ne_bytes([buf[4], buf[5]]) == NLMSG_ERROR {
        return None;
    }
    let mut pos = 20usize;
    while pos + 4 <= n {
        let len = u16::from_ne_bytes([buf[pos], buf[pos + 1]]) as usize;
        let typ = u16::from_ne_bytes([buf[pos + 2], buf[pos + 3]]) & NLA_TYPE_MASK;
        if len < 4 || pos + len > n {
            break;
        }
        if typ == IPSET_ATTR_REVISION && len >= 5 {
            return Some(buf[pos + 4]);
        }
        pos += nla_align(len);
    }
    None
}

fn ipset_create_set(
    fd: &OwnedFd,
    proto: u8,
    set_name: &str,
    family: u8,
    revision: u8,
    maxelem: u32,
) -> Result<(), String> {
    let msg_type = (NFNL_SUBSYS_IPSET << 8) | IPSET_CMD_CREATE;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST | NLM_F_ACK, 12);
    msg.put_bytes(&[libc::AF_UNSPEC as u8, NFNETLINK_V0, 0, 0]);
    msg.put_nla(IPSET_ATTR_PROTOCOL, &[proto]);
    let mut name_nul = set_name.as_bytes().to_vec();
    name_nul.push(0);
    msg.put_nla(IPSET_ATTR_SETNAME, &name_nul);
    msg.put_nla(IPSET_ATTR_TYPENAME, b"hash:net\0");
    msg.put_nla(IPSET_ATTR_REVISION, &[revision]);
    msg.put_nla(IPSET_ATTR_FAMILY, &[family]);
    msg.put_nla(
        IPSET_ATTR_FLAGS | NLA_F_NET_BYTEORDER,
        &IPSET_FLAG_EXIST.to_be_bytes(),
    );

    let data_pos = msg.start_nested(IPSET_ATTR_DATA);
    msg.put_nla(
        IPSET_ATTR_HASHSIZE | NLA_F_NET_BYTEORDER,
        &1024u32.to_be_bytes(),
    );
    msg.put_nla(
        IPSET_ATTR_MAXELEM | NLA_F_NET_BYTEORDER,
        &maxelem.to_be_bytes(),
    );
    msg.end_nested(data_pos);

    nl_send(fd, &msg.finish()).map_err(|e| format!("ipset create send: {}", e))?;
    let mut buf = [0u8; 4096];
    let n = nl_recv(fd, &mut buf).map_err(|e| format!("ipset create recv: {}", e))?;
    parse_nl_ack(&buf[..n]).map_err(|errno| format!("ipset create errno={}", errno))
}

fn ipset_add_cidr(
    fd: &OwnedFd,
    proto: u8,
    set_name: &str,
    cidr: &CanonicalCidr,
) -> Result<(), String> {
    let msg_type = (NFNL_SUBSYS_IPSET << 8) | IPSET_CMD_ADD;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST | NLM_F_ACK, 13);
    msg.put_bytes(&[libc::AF_UNSPEC as u8, NFNETLINK_V0, 0, 0]);
    msg.put_nla(IPSET_ATTR_PROTOCOL, &[proto]);
    let mut name_nul = set_name.as_bytes().to_vec();
    name_nul.push(0);
    msg.put_nla(IPSET_ATTR_SETNAME, &name_nul);
    msg.put_nla(
        IPSET_ATTR_FLAGS | NLA_F_NET_BYTEORDER,
        &IPSET_FLAG_EXIST.to_be_bytes(),
    );

    let data_pos = msg.start_nested(IPSET_ATTR_DATA);
    let ip_pos = msg.start_nested(IPSET_ATTR_IP);
    match cidr.ip {
        IpAddr::V4(v4) => {
            msg.put_nla(IPSET_ATTR_IPADDR_IPV4 | NLA_F_NET_BYTEORDER, &v4.octets());
        }
        IpAddr::V6(v6) => {
            msg.put_nla(IPSET_ATTR_IPADDR_IPV6 | NLA_F_NET_BYTEORDER, &v6.octets());
        }
    }
    msg.end_nested(ip_pos);

    let full_bits = match cidr.ip {
        IpAddr::V4(_) => 32,
        IpAddr::V6(_) => 128,
    };
    if cidr.prefix < full_bits {
        msg.put_nla(IPSET_ATTR_CIDR, &[cidr.prefix]);
    }
    msg.end_nested(data_pos);

    nl_send(fd, &msg.finish()).map_err(|e| format!("ipset add send: {}", e))?;
    let mut buf = [0u8; 4096];
    let n = nl_recv(fd, &mut buf).map_err(|e| format!("ipset add recv: {}", e))?;
    parse_nl_ack(&buf[..n]).map_err(|errno| format!("ipset add errno={}", errno))
}

fn ipset_swap(fd: &OwnedFd, proto: u8, set_a: &str, set_b: &str) -> Result<(), String> {
    let msg_type = (NFNL_SUBSYS_IPSET << 8) | IPSET_CMD_SWAP;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST | NLM_F_ACK, 14);
    msg.put_bytes(&[libc::AF_UNSPEC as u8, NFNETLINK_V0, 0, 0]);
    msg.put_nla(IPSET_ATTR_PROTOCOL, &[proto]);
    let mut a = set_a.as_bytes().to_vec();
    a.push(0);
    let mut b = set_b.as_bytes().to_vec();
    b.push(0);
    msg.put_nla(IPSET_ATTR_SETNAME, &a);
    msg.put_nla(IPSET_ATTR_SETNAME2, &b);

    nl_send(fd, &msg.finish()).map_err(|e| format!("ipset swap send: {}", e))?;
    let mut buf = [0u8; 4096];
    let n = nl_recv(fd, &mut buf).map_err(|e| format!("ipset swap recv: {}", e))?;
    parse_nl_ack(&buf[..n]).map_err(|errno| format!("ipset swap errno={}", errno))
}

fn ipset_destroy(fd: &OwnedFd, proto: u8, set_name: &str) {
    let msg_type = (NFNL_SUBSYS_IPSET << 8) | IPSET_CMD_DESTROY;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST | NLM_F_ACK, 15);
    msg.put_bytes(&[libc::AF_UNSPEC as u8, NFNETLINK_V0, 0, 0]);
    msg.put_nla(IPSET_ATTR_PROTOCOL, &[proto]);
    let mut n_nul = set_name.as_bytes().to_vec();
    n_nul.push(0);
    msg.put_nla(IPSET_ATTR_SETNAME, &n_nul);
    if nl_send(fd, &msg.finish()).is_ok() {
        let mut buf = [0u8; 4096];
        let _ = nl_recv(fd, &mut buf);
    }
}

/// Atomically syncs an IPv4 or IPv6 `hash:net` ipset using CREATE + POPULATE + SWAP + DESTROY.
/// Zero packet-filtering downtime during updates!
pub fn ipset_atomic_sync(
    set_name: &str,
    family: u8,
    cidrs: &[CanonicalCidr],
) -> Result<usize, String> {
    let proto = probe_ipset_netlink().ok_or_else(|| "kernel ipset netlink unavailable".to_string())?;
    let fd = open_netlink_socket(NETLINK_NETFILTER, 65536)
        .map_err(|e| format!("open ipset socket: {}", e))?;
    let rev = ipset_query_hash_net_revision(&fd, proto, family).unwrap_or(6);
    let maxelem = (cidrs.len().max(1024) as u32).clamp(1024, 65534);
    let tmp_name = format!("{}_tmp", set_name);

    // Ensure live set exists first (no-op with IPSET_FLAG_EXIST)
    ipset_create_set(&fd, proto, set_name, family, rev, maxelem)?;

    // Recreate clean temp set, populate, atomic swap, destroy temp
    ipset_destroy(&fd, proto, &tmp_name);
    ipset_create_set(&fd, proto, &tmp_name, family, rev, maxelem)?;

    let mut loaded = 0usize;
    for cidr in cidrs {
        if ipset_add_cidr(&fd, proto, &tmp_name, cidr).is_ok() {
            loaded += 1;
        }
    }

    ipset_swap(&fd, proto, &tmp_name, set_name)?;
    ipset_destroy(&fd, proto, &tmp_name);
    Ok(loaded)
}

pub fn ipset_cleanup(set_name: &str) {
    if let Some(proto) = probe_ipset_netlink() {
        if let Ok(fd) = open_netlink_socket(NETLINK_NETFILTER, 16384) {
            ipset_destroy(&fd, proto, set_name);
            ipset_destroy(&fd, proto, &format!("{}_tmp", set_name));
        }
    }
}

// ============================================================================
// 4. Live NFLOG Packet Listener (`NETLINK_NETFILTER`, group 42)
// ============================================================================

fn nflog_send_cfg(fd: &OwnedFd, family: u8, group: u16, cmd: u8, copy_range: Option<u32>) -> io::Result<()> {
    let msg_type = (NFNL_SUBSYS_ULOG << 8) | NFULNL_MSG_CONFIG;
    let mut msg = NlBuf::new(msg_type, NLM_F_REQUEST | NLM_F_ACK, 20);
    // nfgenmsg: family, version=0, res_id=group.to_be()
    let g_be = group.to_be_bytes();
    msg.put_bytes(&[family, NFNETLINK_V0, g_be[0], g_be[1]]);
    msg.put_nla(NFULA_CFG_CMD, &[cmd]);
    if let Some(range) = copy_range {
        // struct nfulnl_msg_config_mode { __be32 copy_range; __u8 copy_mode; __u8 _pad; }
        let mut mode_bytes = [0u8; 6];
        mode_bytes[0..4].copy_from_slice(&range.to_be_bytes());
        mode_bytes[4] = NFULNL_COPY_PACKET;
        mode_bytes[5] = 0;
        msg.put_nla(NFULA_CFG_MODE, &mode_bytes);
    }
    nl_send(fd, &msg.finish())?;
    let mut buf = [0u8; 4096];
    let _ = nl_recv(fd, &mut buf);
    Ok(())
}

/// Runs the blocking NFLOG listener loop on a dedicated background thread until `stop` is set.
pub fn run_nflog_loop<F>(group: u16, stop: Arc<AtomicBool>, mut on_event: F)
where
    F: FnMut(BlockedPacketEvent),
{
    let Ok(fd) = open_netlink_socket(NETLINK_NETFILTER, 262144) else {
        return;
    };

    // Unbind/bind PF_INET & PF_INET6 (best-effort; kernel 3.8+ ignores PF_BIND safely)
    for fam in [libc::AF_INET as u8, libc::AF_INET6 as u8] {
        let _ = nflog_send_cfg(&fd, fam, 0, NFULNL_CFG_CMD_PF_UNBIND, None);
        let _ = nflog_send_cfg(&fd, fam, 0, NFULNL_CFG_CMD_PF_BIND, None);
    }
    if nflog_send_cfg(&fd, libc::AF_UNSPEC as u8, group, NFULNL_CFG_CMD_BIND, Some(128)).is_err() {
        return;
    }

    let mut buf = vec![0u8; 32768];
    while !stop.load(Ordering::Relaxed) {
        match nl_recv(&fd, &mut buf) {
            Ok(n) if n >= 20 => {
                parse_nflog_datagram(&buf[..n], &mut on_event);
            }
            Ok(_) => {}
            Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut || e.kind() == io::ErrorKind::Interrupted => {
                continue;
            }
            Err(_) => {
                std::thread::sleep(std::time::Duration::from_millis(250));
            }
        }
    }
}

pub fn parse_nflog_datagram<F>(buf: &[u8], on_event: &mut F)
where
    F: FnMut(BlockedPacketEvent),
{
    let mut offset = 0usize;
    let pkt_msg_type = (NFNL_SUBSYS_ULOG << 8) | NFULNL_MSG_PACKET;
    while offset + 20 <= buf.len() {
        let nl_len = u32::from_ne_bytes([
            buf[offset],
            buf[offset + 1],
            buf[offset + 2],
            buf[offset + 3],
        ]) as usize;
        let nl_type = u16::from_ne_bytes([buf[offset + 4], buf[offset + 5]]);
        if nl_len < 20 || offset + nl_len > buf.len() {
            break;
        }
        if nl_type == pkt_msg_type {
            let attrs = &buf[offset + 20..offset + nl_len];
            if let Some(ev) = parse_nflog_attrs(attrs) {
                on_event(ev);
            }
        }
        offset += nlmsg_align(nl_len);
    }
}

fn parse_nflog_attrs(attrs: &[u8]) -> Option<BlockedPacketEvent> {
    let mut uid: Option<u32> = None;
    let mut prefix = String::from("BLOCKED");
    let mut payload: Option<&[u8]> = None;

    let mut pos = 0usize;
    while pos + 4 <= attrs.len() {
        let len = u16::from_ne_bytes([attrs[pos], attrs[pos + 1]]) as usize;
        let typ = u16::from_ne_bytes([attrs[pos + 2], attrs[pos + 3]]) & NLA_TYPE_MASK;
        if len < 4 || pos + len > attrs.len() {
            break;
        }
        let val = &attrs[pos + 4..pos + len];
        match typ {
            NFULA_UID if val.len() >= 4 => {
                uid = Some(u32::from_be_bytes([val[0], val[1], val[2], val[3]]));
            }
            NFULA_PREFIX => {
                let end = val.iter().position(|&b| b == 0).unwrap_or(val.len());
                let raw = String::from_utf8_lossy(&val[..end]).trim().to_string();
                if !raw.is_empty() {
                    prefix = raw;
                }
            }
            NFULA_PAYLOAD => {
                payload = Some(val);
            }
            _ => {}
        }
        pos += nla_align(len);
    }

    let pkt = payload?;
    parse_ip_packet_headers(pkt, uid, prefix)
}

/// Bounds-checked IPv4/IPv6 + TCP/UDP/ICMP header parser (zero unsafe, zero panic).
pub fn parse_ip_packet_headers(
    pkt: &[u8],
    uid: Option<u32>,
    reason: String,
) -> Option<BlockedPacketEvent> {
    if pkt.is_empty() {
        return None;
    }
    let version = pkt[0] >> 4;
    if version == 4 {
        if pkt.len() < 20 {
            return None;
        }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if ihl < 20 || pkt.len() < ihl {
            return None;
        }
        let proto_num = pkt[9];
        let src_ip = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]).to_string();
        let dst_ip = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]).to_string();
        let (protocol, src_port, dst_port) = parse_l4(proto_num, &pkt[ihl..]);
        Some(BlockedPacketEvent {
            timestamp_ms: now_millis(),
            uid,
            package_name: None,
            family: 4,
            protocol,
            src_ip,
            src_port,
            dst_ip,
            dst_port,
            reason,
        })
    } else if version == 6 {
        if pkt.len() < 40 {
            return None;
        }
        let proto_num = pkt[6];
        let mut s = [0u8; 16];
        let mut d = [0u8; 16];
        s.copy_from_slice(&pkt[8..24]);
        d.copy_from_slice(&pkt[24..40]);
        let src_ip = Ipv6Addr::from(s).to_string();
        let dst_ip = Ipv6Addr::from(d).to_string();
        let (protocol, src_port, dst_port) = parse_l4(proto_num, &pkt[40..]);
        Some(BlockedPacketEvent {
            timestamp_ms: now_millis(),
            uid,
            package_name: None,
            family: 6,
            protocol,
            src_ip,
            src_port,
            dst_ip,
            dst_port,
            reason,
        })
    } else {
        None
    }
}

fn parse_l4(proto_num: u8, l4: &[u8]) -> (String, u16, u16) {
    let (sport, dport) = if (proto_num == 6 || proto_num == 17) && l4.len() >= 4 {
        (
            u16::from_be_bytes([l4[0], l4[1]]),
            u16::from_be_bytes([l4[2], l4[3]]),
        )
    } else {
        (0, 0)
    };
    let proto = match proto_num {
        6 => "TCP".to_string(),
        17 => "UDP".to_string(),
        1 => "ICMP".to_string(),
        58 => "ICMPv6".to_string(),
        other => format!("PROTO_{}", other),
    };
    (proto, sport, dport)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ipv4_tcp_packet_safely() {
        let mut pkt = vec![0u8; 24];
        pkt[0] = 0x45; // IPv4, IHL=20
        pkt[9] = 6; // TCP
        pkt[12..16].copy_from_slice(&[192, 168, 1, 10]);
        pkt[16..20].copy_from_slice(&[93, 184, 216, 34]);
        pkt[20..22].copy_from_slice(&51234u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&443u16.to_be_bytes());

        let ev = parse_ip_packet_headers(&pkt, Some(10234), "SILO_UID".into()).unwrap();
        assert_eq!(ev.family, 4);
        assert_eq!(ev.protocol, "TCP");
        assert_eq!(ev.src_ip, "192.168.1.10");
        assert_eq!(ev.dst_ip, "93.184.216.34");
        assert_eq!(ev.src_port, 51234);
        assert_eq!(ev.dst_port, 443);
        assert_eq!(ev.uid, Some(10234));
    }

    #[test]
    fn parses_ipv6_udp_packet_safely() {
        let mut pkt = vec![0u8; 44];
        pkt[0] = 0x60; // IPv6
        pkt[6] = 17; // UDP
        pkt[8..24].copy_from_slice(&Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1).octets());
        pkt[24..40].copy_from_slice(&Ipv6Addr::new(0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1111).octets());
        pkt[40..42].copy_from_slice(&40000u16.to_be_bytes());
        pkt[42..44].copy_from_slice(&53u16.to_be_bytes());

        let ev = parse_ip_packet_headers(&pkt, Some(10555), "SILO_DNS".into()).unwrap();
        assert_eq!(ev.family, 6);
        assert_eq!(ev.protocol, "UDP");
        assert_eq!(ev.dst_port, 53);
        assert_eq!(ev.dst_ip, "2606:4700:4700::1111");
    }

    #[test]
    fn malformed_packets_never_panic() {
        assert!(parse_ip_packet_headers(&[], None, "X".into()).is_none());
        assert!(parse_ip_packet_headers(&[0x45, 0x00], None, "X".into()).is_none());
        assert!(parse_ip_packet_headers(&[0x40; 20], None, "X".into()).is_none()); // IHL=0
        assert!(parse_ip_packet_headers(&[0x60; 10], None, "X".into()).is_none()); // short IPv6
    }

    #[test]
    fn classifies_mediatek_and_vpn_interfaces_correctly() {
        let mut ifaces = ActiveInterfaces::default();
        push_classified_iface(&mut ifaces, "wlan0".into(), true);
        push_classified_iface(&mut ifaces, "ccmni0".into(), true);
        push_classified_iface(&mut ifaces, "ccmni1".into(), true);
        push_classified_iface(&mut ifaces, "tun0".into(), true);
        push_classified_iface(&mut ifaces, "wg0".into(), true);

        assert_eq!(ifaces.wifi, vec!["wlan0"]);
        assert_eq!(ifaces.cellular, vec!["ccmni0", "ccmni1"]);
        assert_eq!(ifaces.vpn, vec!["tun0", "wg0"]);
    }
}
