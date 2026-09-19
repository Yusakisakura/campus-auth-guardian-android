//! Network connectivity detection and captive portal identification.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use crate::log_warn;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NetStatus {
    Connected,
    CaptivePortal { redirect: String },
    DnsPending,
    NonCampus,
    Disconnected { reason: String },
}

/// Extract AC parameters (wlanacip / wlanacname / wlanuserip) from captive portal redirect URL.
pub fn extract_ac_params(redirect: &str) -> (String, String, String) {
    let get = |key: &str| -> String {
        redirect
            .split('?')
            .nth(1)
            .unwrap_or("")
            .split('&')
            .find_map(|kv| {
                let (k, v) = kv.split_once('=')?;
                (k.eq_ignore_ascii_case(key)).then(|| v.to_string())
            })
            .unwrap_or_default()
    };
    (get("wlanacip"), get("wlanacname"), get("wlanuserip"))
}

/// Extract Location header from HTTP response (only for 301/302/303/307/308).
fn extract_location(head: &str) -> Option<String> {
    let status_first_line = head.lines().next()?;
    let code: u16 = status_first_line.split_whitespace().nth(1)?.parse().ok()?;
    if !matches!(code, 301 | 302 | 303 | 307 | 308) {
        return None;
    }
    head.lines()
        .find_map(|l| {
            let (name, v) = l.split_once(':')?;
            name.trim()
                .eq_ignore_ascii_case("location")
                .then(|| v.trim().trim_end_matches('\r').to_string())
        })
}

/// HTTP GET via raw TcpStream (lightweight, no full HTTP client needed for one probe).
pub fn check(url: &str, timeout: Duration) -> NetStatus {
    check_inner(url, timeout, false)
}

/// Quick TCP reachability probe to the portal server. Returns true if it responds at all.
pub fn portal_reachable(portal_url: &str, timeout: Duration) -> bool {
    let (host, port, _) = match parse_http_url(portal_url) {
        Some(p) => p,
        None => return false,
    };
    let addr = format!("{host}:{port}");
    use std::net::ToSocketAddrs;
    let sockaddrs: Vec<_> = match addr.to_socket_addrs() {
        Ok(it) => it.collect(),
        Err(_) => return false,
    };
    for sa in &sockaddrs {
        if TcpStream::connect_timeout(sa, timeout).is_ok() {
            return true;
        }
    }
    false
}

/// Internal check with recursion guard for captive portal probing.
fn check_inner(url: &str, timeout: Duration, is_probe: bool) -> NetStatus {
    let (host, port, path) = match parse_http_url(url) {
        Some(p) => p,
        None => return NetStatus::Disconnected { reason: format!("Invalid URL: {url}") },
    };

    if port == 443 {
        return check_https(url, timeout);
    }

    let addr = format!("{host}:{port}");

    use std::net::ToSocketAddrs;
    let sockaddrs: Vec<_> = match addr.to_socket_addrs() {
        Ok(it) => it.collect(),
        Err(e) => {
            log_warn!("DNS resolve {addr} failed: {e}");
            return NetStatus::DnsPending;
        }
    };
    let mut last_err: Option<std::io::Error> = None;
    let mut stream = None;
    for sa in &sockaddrs {
        match TcpStream::connect_timeout(sa, timeout) {
            Ok(s) => { stream = Some(s); break; }
            Err(e) => { last_err = Some(e); }
        }
    }
    let mut stream = match stream {
        Some(s) => s,
        None => {
            let reason = last_err.map(|e| e.to_string()).unwrap_or_else(|| "No available address".into());
            return NetStatus::Disconnected { reason: format!("Connect {addr} failed: {reason}") };
        }
    };
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));

    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: CampusAuthAndroid/1.0\r\nConnection: close\r\n\r\n"
    );
    if let Err(e) = stream.write_all(req.as_bytes()) {
        return NetStatus::Disconnected { reason: format!("Send failed: {e}") };
    }

    let mut buf = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    let mut total = 0;
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                total += n;
                if total > 64 * 1024 {
                    break;
                }
                if let Ok(text) = std::str::from_utf8(&buf) {
                    if text.contains("\r\n\r\n") {
                        break;
                    }
                }
            }
            Err(e) => return NetStatus::Disconnected { reason: format!("Read failed: {e}") },
        }
    }

    let head = String::from_utf8_lossy(&buf).into_owned();
    if let Some(loc) = extract_location(&head) {
        return NetStatus::CaptivePortal { redirect: loc };
    }
    if head.starts_with("HTTP/1.") && head.contains(" 200 ") {
        // ePortal often returns 200 with login form body instead of 302 redirect.
        // When check_url IS the ePortal login endpoint, AC params are not in the URL.
        // Probe a generic URL to trigger the real redirect with AC parameters.
        if !is_probe && (head.contains("eportal") || head.contains("portal") || head.contains("wlanacip")
            || head.contains("wlanuserip") || head.contains("login")) {
            return probe_captive_portal(timeout);
        }
        NetStatus::Connected
    } else {
        NetStatus::Disconnected { reason: "No valid HTTP response".into() }
    }
}

/// Probe a generic URL to trigger captive portal redirect and extract AC parameters.
/// Used when the original check_url IS the ePortal login endpoint (returns 200 instead of 302).
fn probe_captive_portal(timeout: Duration) -> NetStatus {
    let probe_urls = [
        "http://connect.rom.miui.com/generate_204",
        "http://captive.apple.com/hotspot-detect.html",
        "http://www.msftconnecttest.com/connecttest.txt",
        "http://www.gstatic.com/generate_204",
    ];
    for probe_url in &probe_urls {
        match check_inner(probe_url, timeout, true) {
            NetStatus::CaptivePortal { redirect } => {
                log_warn!("Captive portal detected via probe {probe_url}: {redirect}");
                return NetStatus::CaptivePortal { redirect };
            }
            NetStatus::Connected => return NetStatus::Connected,
            _ => continue,
        }
    }
    // All probes failed — treat as disconnected
    NetStatus::Disconnected { reason: "All captive portal probes failed".into() }
}

fn check_https(url: &str, timeout: Duration) -> NetStatus {
    match ureq::AgentBuilder::new().timeout(timeout).timeout_connect(timeout).build().get(url).call() {
        Ok(_) => NetStatus::Connected,
        Err(ureq::Error::Status(code, resp)) => {
            let loc = resp.header("location").map(|s| s.to_string());
            match loc {
                Some(l) if (300..400).contains(&code) => NetStatus::CaptivePortal { redirect: l },
                _ => NetStatus::Connected,
            }
        }
        Err(e) => NetStatus::Disconnected { reason: e.to_string() },
    }
}

/// Parse http://host[:port]/path → (host, port, path).
pub fn parse_http_url(url: &str) -> Option<(String, u16, String)> {
    let rest = url.strip_prefix("http://")?;
    let (hostport, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match hostport.rsplit_once(':') {
        Some((h, p)) => {
            let port: u16 = p.parse().ok()?;
            if port == 0 {
                return None;
            }
            (h, port)
        }
        None => (hostport, 80),
    };
    if host.is_empty() {
        return None;
    }
    Some((host.to_string(), port, path.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_parse() {
        assert_eq!(
            parse_http_url("http://www.baidu.com"),
            Some(("www.baidu.com".into(), 80, "/".into()))
        );
        assert_eq!(
            parse_http_url("http://10.10.102.50:801/a79.htm?x=1"),
            Some(("10.10.102.50".into(), 801, "/a79.htm?x=1".into()))
        );
        assert_eq!(parse_http_url("ftp://x"), None);
    }

    #[test]
    fn location_extraction() {
        let head = "HTTP/1.1 302 Found\r\nLocation: http://10.10.102.50/a79.htm?wlanuserip=10.20.30.41\r\n\r\n";
        assert_eq!(
            extract_location(head),
            Some("http://10.10.102.50/a79.htm?wlanuserip=10.20.30.41".into())
        );
    }

    #[test]
    fn extract_ac_params_full() {
        let (ip, name, user) = extract_ac_params(
            "http://10.10.102.50/a79.htm?wlanuserip=10.20.30.42&wlanacname=&wlanacip=10.10.102.49",
        );
        assert_eq!(ip, "10.10.102.49");
        assert_eq!(name, "");
        assert_eq!(user, "10.20.30.42");
    }
}
