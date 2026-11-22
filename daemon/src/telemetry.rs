use crate::protocol::{BlockedPacketEvent, TelemetrySnapshot};
use std::collections::{BTreeMap, HashMap, VecDeque};

pub const TELEMETRY_RING_CAPACITY: usize = 2048;

/// Zero-Disk RAM-only bounded ring buffer.
/// Never writes visited/blocked IPs to storage; vanishes completely on reboot/BFU.
#[derive(Debug, Default)]
pub struct TelemetryRing {
    total_blocked: u64,
    per_uid_blocked: BTreeMap<u32, u64>,
    events: VecDeque<BlockedPacketEvent>,
}

impl TelemetryRing {
    pub fn new() -> Self {
        Self {
            total_blocked: 0,
            per_uid_blocked: BTreeMap::new(),
            events: VecDeque::with_capacity(TELEMETRY_RING_CAPACITY),
        }
    }

    pub fn push(&mut self, mut event: BlockedPacketEvent, uid_map: &HashMap<u32, String>) {
        self.total_blocked = self.total_blocked.saturating_add(1);
        if let Some(uid) = event.uid {
            *self.per_uid_blocked.entry(uid).or_insert(0) += 1;
            if event.package_name.is_none() {
                event.package_name = uid_map.get(&uid).cloned();
            }
        }
        if self.events.len() >= TELEMETRY_RING_CAPACITY {
            self.events.pop_front();
        }
        self.events.push_back(event);
    }

    pub fn total_blocked(&self) -> u64 {
        self.total_blocked
    }

    pub fn snapshot(&self, limit: usize) -> TelemetrySnapshot {
        let cap = limit.clamp(1, TELEMETRY_RING_CAPACITY);
        let events = self.events.iter().rev().take(cap).cloned().collect();
        TelemetrySnapshot {
            total_blocked: self.total_blocked,
            per_uid_blocked: self.per_uid_blocked.clone(),
            events,
        }
    }

    pub fn clear(&mut self) {
        self.total_blocked = 0;
        self.per_uid_blocked.clear();
        self.events.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_buffer_stays_strictly_bounded() {
        let mut ring = TelemetryRing::new();
        let mut map = HashMap::new();
        map.insert(10200, "com.tracker.app".to_string());

        for i in 0..(TELEMETRY_RING_CAPACITY + 250) {
            ring.push(
                BlockedPacketEvent {
                    timestamp_ms: i as u64,
                    uid: Some(10200),
                    package_name: None,
                    family: 4,
                    protocol: "TCP".into(),
                    src_ip: "10.0.0.2".into(),
                    src_port: 40000,
                    dst_ip: "198.51.100.1".into(),
                    dst_port: 443,
                    reason: "SILO_UID".into(),
                },
                &map,
            );
        }

        let snap = ring.snapshot(5000);
        assert_eq!(snap.events.len(), TELEMETRY_RING_CAPACITY);
        assert_eq!(snap.total_blocked, (TELEMETRY_RING_CAPACITY + 250) as u64);
        assert_eq!(snap.events[0].package_name.as_deref(), Some("com.tracker.app"));
        // Most recent event is first
        assert_eq!(
            snap.events[0].timestamp_ms,
            (TELEMETRY_RING_CAPACITY + 249) as u64
        );
    }
}
