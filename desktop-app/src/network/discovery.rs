//! mDNS advertisement of `_audiorelay._udp.local.` so the Android app can
//! find this laptop without the user typing an IP. See
//! `protocol-spec.md` §2.
//!
//! `mdns-sd` is a pure-Rust, cross-platform crate (works on Linux/macOS too,
//! not just Windows), which is why this module isn't `cfg(windows)`-gated —
//! it can be exercised in CI on any OS.

use mdns_sd::{ServiceDaemon, ServiceInfo};

use crate::protocol::{PROTOCOL_VERSION, SERVICE_TYPE};

#[derive(Debug, thiserror::Error)]
pub enum DiscoveryError {
    #[error("mDNS daemon error: {0}")]
    Mdns(#[from] mdns_sd::Error),
}

/// Starts advertising this laptop on the LAN. Returns the `ServiceDaemon` —
/// **keep it alive** for as long as advertisement should continue; dropping
/// it unregisters the service.
pub fn advertise(
    device_id: &str,
    device_name: &str,
    control_port: u16,
) -> Result<ServiceDaemon, DiscoveryError> {
    let daemon = ServiceDaemon::new()?;

    let host_ipv4 = local_ipv4().unwrap_or_else(|| "0.0.0.0".to_string());
    let instance_name = device_id;
    let host_name = format!("{device_name}.local.");

    let mut properties = std::collections::HashMap::new();
    properties.insert("id".to_string(), device_id.to_string());
    properties.insert("name".to_string(), device_name.to_string());
    properties.insert("protocol_version".to_string(), PROTOCOL_VERSION.to_string());

    let service = ServiceInfo::new(
        SERVICE_TYPE,
        instance_name,
        &host_name,
        host_ipv4.as_str(),
        control_port,
        Some(properties),
    )?
    .enable_addr_auto();

    daemon.register(service)?;
    Ok(daemon)
}

/// Best-effort local IPv4 lookup (no external I/O — just asks the OS which
/// interface would be used to reach a public address, without actually
/// sending anything, via a connected UDP socket). Falls back to letting
/// `ServiceInfo::enable_addr_auto()` figure it out if this fails.
fn local_ipv4() -> Option<String> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip().to_string())
}
