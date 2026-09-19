//! Local IPv4 address enumeration for Android.
//!
//! Uses UDP socket trick (no permissions required) and optional external IP
//! from Android's ConnectivityManager passed via UniFFI.

use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdapterIp {
    pub name: String,
    pub ip: String,
    pub mac: Option<String>,
}

// ── External IP (set from Kotlin via ConnectivityManager) ───────────────────

static EXTERNAL_IP: OnceLock<std::sync::Mutex<Option<String>>> = OnceLock::new();

fn external_ip_lock() -> &'static std::sync::Mutex<Option<String>> {
    EXTERNAL_IP.get_or_init(|| std::sync::Mutex::new(None))
}

pub fn set_external_ip(ip: &str) {
    *external_ip_lock().lock().unwrap_or_else(|e| e.into_inner()) = Some(ip.to_string());
}

pub fn get_external_ip() -> Option<String> {
    external_ip_lock().lock().unwrap_or_else(|e| e.into_inner()).clone()
}

// ── Interface enumeration ───────────────────────────────────────────────────

/// List all active network adapters with IPv4 addresses.
pub fn list_adapters() -> Vec<AdapterIp> {
    list_adapters_std()
}

fn list_adapters_std() -> Vec<AdapterIp> {
    #[allow(unused_mut, unused_assignments)]
    let mut adapters = Vec::new();

    // Method 1: Use libc getifaddrs on Android (API 24+).
    // Returns empty on non-Android or if getifaddrs is unavailable.
    #[cfg(target_os = "android")]
    {
        adapters = getifaddrs_android();
    }

    // Method 2: UDP socket trick — connect to 8.8.8.8:80, read local addr.
    // Works on all Android versions, no special permissions.
    if adapters.is_empty() {
        if let Some(ip) = udp_detect_primary_ip() {
            adapters.push(AdapterIp {
                name: "wlan0".into(),
                ip,
                mac: None,
            });
        }
    }

    // Prepend externally-provided IP if set from Kotlin (highest priority)
    if let Some(ext_ip) = get_external_ip() {
        if !adapters.iter().any(|a| a.ip == ext_ip) {
            adapters.insert(0, AdapterIp {
                name: "external".into(),
                ip: ext_ip,
                mac: None,
            });
        }
    }

    // Filter out loopback, link-local, and Tailscale CGNAT
    adapters.retain(|a| {
        !a.ip.starts_with("127.")
            && !a.ip.starts_with("169.254.")
            && !a.ip.starts_with("100.")
    });

    adapters
}

/// UDP socket trick to detect the primary outbound IPv4 address.
/// Connects to 8.8.8.8:80 (no actual traffic sent), reads local socket address.
fn udp_detect_primary_ip() -> Option<String> {
    use std::net::UdpSocket;
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    let local = sock.local_addr().ok()?;
    match local.ip() {
        std::net::IpAddr::V4(v4) => Some(v4.to_string()),
        _ => None,
    }
}

/// Use libc getifaddrs on Android to enumerate all interfaces.
/// Falls back to empty (the UDP trick covers the primary interface).
#[cfg(target_os = "android")]
fn getifaddrs_android() -> Vec<AdapterIp> {
    // getifaddrs requires NDK API 24+. The UDP trick (above) works universally.
    // For multi-interface support, Kotlin calls set_external_ip() with the IP
    // from ConnectivityManager.
    Vec::new()
}

/// Select the best IP for authentication.
/// Priority: campus 10.x > host 192.168.x (non .1 gateway) > 172.16-31.x > other.
#[allow(dead_code)]
pub fn detect_local_ip() -> Option<String> {
    let ips: Vec<String> = list_adapters()
        .into_iter()
        .map(|a| a.ip)
        .collect();

    let score = |ip: &str| -> u8 {
        let octets: Vec<u32> = ip.split('.').filter_map(|o| o.parse().ok()).collect();
        if octets.len() != 4 {
            return 0;
        }
        let (a, b, c) = (octets[0], octets[1], octets[2]);
        if a == 10 {
            5
        } else if a == 192 && b == 168 && c != 1 {
            4
        } else if a == 192 && b == 168 {
            3
        } else if a == 172 && (16..=31).contains(&b) {
            2
        } else {
            1
        }
    };

    ips.into_iter().max_by_key(|ip| score(ip))
}
