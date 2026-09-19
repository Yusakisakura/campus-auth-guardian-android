//! ePortal JSONP authentication protocol — identical to Windows guardian-core.
//!
//! Protocol summary (from live capture):
//! - Request: GET `{base}/eportal/portal/login?callback=dr1005&login_method=1&...`
//! - `user_account` is URL-encoded: `,0,{student_id}@{operator}` → `%2C0%2C...%40unicom`
//! - Response: JSONP `dr1005({"result":1,"msg":"...","ret_code":0})`
//! - `result=1` success; `result=0` + `ret_code=2` already online (treat as success)

use serde_json::Value;

use crate::config::Config;
use crate::netcheck::NetStatus;
use crate::{log_info, log_warn};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthOutcome {
    Success,
    AlreadyOnline,
    Failed { msg: String },
    NetworkError { msg: String },
}

impl AuthOutcome {
    pub fn is_ok(&self) -> bool {
        matches!(self, AuthOutcome::Success | AuthOutcome::AlreadyOnline)
    }
}

/// RFC 3986 percent-encoding (everything except unreserved characters).
pub fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for &b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// Build the login URL. `ac` = (wlan_ac_ip, wlan_ac_name) extracted from captive portal redirect.
pub fn build_login_url(cfg: &Config, ip: &str, callback: &str, ac: Option<(&str, &str)>) -> String {
    let account = match cfg.operator {
        crate::config::Operator::Campus => format!(",0,{}", cfg.student_id),
        _ => format!(",0,{}@{}", cfg.student_id, cfg.operator.as_str()),
    };
    let (ac_ip, ac_name) = ac.unwrap_or(("", ""));
    format!(
        "{}?callback={cb}&login_method=1&user_account={acc}&user_password={pw}\
         &wlan_user_ip={ip}&wlan_user_ipv6=&wlan_user_mac=000000000000\
         &wlan_ac_ip={acip}&wlan_ac_name={acname}&jsVersion=4.1.3&terminal_type=1&lang=zh-cn&v=3015&lang=zh",
        cfg.auth_url,
        cb = urlencode(callback),
        acc = urlencode(&account),
        pw = urlencode(&cfg.password),
        ip = urlencode(ip),
        acip = urlencode(ac_ip),
        acname = urlencode(ac_name),
    )
}

/// Parse JSONP `dr1005({...})` to extract result/ret_code/msg.
fn parse_jsonp(text: &str) -> Option<(i64, Option<i64>, String)> {
    let start = text.find('(')?;
    let end = text.rfind(')')?;
    if start >= end {
        return None;
    }
    let v: Value = serde_json::from_str(&text[start + 1..end]).ok()?;
    let result = v.get("result")?.as_i64()?;
    let ret_code = v.get("ret_code").and_then(|r| r.as_i64());
    let msg = v
        .get("msg")
        .and_then(|m| m.as_str())
        .unwrap_or("")
        .to_string();
    Some((result, ret_code, msg))
}

/// Execute one authentication attempt: collect candidate IPs (fixed_ip first + local adapters
/// sorted by score), try each in order. Return immediately on success; return last failure if
/// all candidates fail.
///
/// Before trying candidates, probe the check_url to detect a captive portal redirect and
/// extract AC parameters (wlan_ac_ip / wlan_ac_name). These are passed to the actual login
/// request so the ePortal server can identify the AC — many servers return "domain error"
/// when AC parameters are missing.
pub fn authenticate(cfg: &Config) -> AuthOutcome {
    let mut candidates: Vec<String> = Vec::new();

    // 1) Fixed IP (user-specified, highest priority)
    if let Some(f) = cfg.fixed_ip.as_deref() {
        if !f.trim().is_empty() {
            candidates.push(f.trim().to_string());
        }
    }
    // 2) All local IPv4 addresses (sorted by score: 10.x preferred)
    for a in crate::ipdetect::list_adapters() {
        if !candidates.contains(&a.ip) {
            candidates.push(a.ip);
        }
    }
    // Score-sort (except the first fixed entry)
    if candidates.len() > 1 {
        let mut rest = candidates.split_off(1);
        rest.sort_by_key(|ip| std::cmp::Reverse(ip_score(ip)));
        candidates.extend(rest);
    }
    candidates.dedup();

    if candidates.is_empty() {
        return AuthOutcome::NetworkError { msg: "No usable local IP detected".into() };
    }
    log_info!("Auth candidate IPs: {:?}", candidates);

    // Probe network to extract AC parameters from captive portal redirect (if any)
    let ac_params: Option<(String, String, String)> =
        match crate::netcheck::check(&cfg.check_url, std::time::Duration::from_secs(6)) {
            NetStatus::CaptivePortal { redirect } => {
                let (ac_ip, ac_name, user_ip) = crate::netcheck::extract_ac_params(&redirect);
                log_info!("Captive portal AC: ip={ac_ip} name={ac_name} user_ip={user_ip}");
                Some((ac_ip, ac_name, user_ip))
            }
            _ => None,
        };
    let ac_ref = ac_params.as_ref().map(|(a, b, _c)| (a.as_str(), b.as_str()));

    let mut last: Option<AuthOutcome> = None;
    for (i, ip) in candidates.iter().enumerate() {
        let outcome = authenticate_with_ip(cfg, ip, ac_ref);
        let ok = outcome.is_ok();
        log_info!("Candidate {}/{} IP {ip} result: {}", i + 1, candidates.len(),
            match &outcome {
                AuthOutcome::Success => "success".to_string(),
                AuthOutcome::AlreadyOnline => "already_online".to_string(),
                AuthOutcome::Failed { msg } => format!("failed({msg})"),
                AuthOutcome::NetworkError { msg } => format!("net_error({msg})"),
            });
        if ok {
            return outcome;
        }
        last = Some(outcome);
    }
    last.unwrap_or_else(|| AuthOutcome::NetworkError { msg: "All candidate IPs failed".into() })
}

/// Auth with captive portal AC parameters.
pub fn authenticate_with_ac(cfg: &Config, ac: Option<(&str, &str, &str)>) -> AuthOutcome {
    if let Some((_, _, portal_ip)) = ac {
        if !portal_ip.is_empty() {
            let mut cfg2 = cfg.clone();
            cfg2.fixed_ip = Some(portal_ip.to_string());
            return authenticate(&cfg2);
        }
    }
    authenticate(cfg)
}

/// IP candidate scoring: 10.x campus net highest, then 192.168 non-gateway, lowest 172.16-31.
pub fn ip_score(ip: &str) -> u8 {
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
}

fn authenticate_with_ip(cfg: &Config, ip: &str, ac: Option<(&str, &str)>) -> AuthOutcome {
    let agent = match ureq::AgentBuilder::new()
        .timeout_connect(std::time::Duration::from_secs(5))
        .timeout(std::time::Duration::from_secs(10))
        .build()
    {
        a => a,
    };
    // Try fetching login page first (grab PHPSESSID if available)
    let _ = agent.get(&cfg.login_page_url()).call();

    let url = build_login_url(cfg, ip, "dr1005", ac);
    log_info!("Auth URL: {url}");

    match agent.get(&url).call() {
        Ok(resp) => {
            let status = resp.status();
            log_info!("HTTP status: {status}");
            if status != 200 {
                return AuthOutcome::NetworkError { msg: format!("HTTP {status}") };
            }
            match resp.into_string() {
                Ok(body) => {
                    log_info!("HTTP Response: {}", truncate(&body, 300));
                    interpret(&body)
                }
                Err(e) => AuthOutcome::NetworkError { msg: format!("Read response failed: {e}") },
            }
        }
        Err(ureq::Error::Status(code, resp)) => {
            let body = resp.into_string().unwrap_or_default();
            log_warn!("HTTP {code} body: {}", truncate(&body, 300));
            interpret(&body)
        }
        Err(e) => AuthOutcome::NetworkError { msg: e.to_string() },
    }
}

/// Interpret JSONP response.
pub fn interpret(jsonp: &str) -> AuthOutcome {
    match parse_jsonp(jsonp) {
        Some((1, _msg, _)) => AuthOutcome::Success,
        Some((0, Some(2), _msg)) => AuthOutcome::AlreadyOnline,
        Some((0, _, msg)) => AuthOutcome::Failed { msg },
        Some((code, _, _)) => AuthOutcome::Failed { msg: format!("Unknown result={code}") },
        None => AuthOutcome::Failed { msg: "Response is not JSONP".into() },
    }
}

fn truncate(s: &str, n: usize) -> &str {
    match s.char_indices().nth(n) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> Config {
        let mut c = Config::default();
        c.auth_url = "http://10.10.102.50:801/eportal/portal/login".into();
        c.student_id = "12345678".into();
        c.operator = crate::config::Operator::Unicom;
        c.password = "TestPass123".into();
        c
    }

    #[test]
    fn url_encode_account() {
        assert_eq!(urlencode(",0,12345678@unicom"), "%2C0%2C12345678%40unicom");
        assert_eq!(urlencode("pw/1+2"), "pw%2F1%2B2");
    }

    #[test]
    fn login_url_shape() {
        let url = build_login_url(&cfg(), "10.20.30.40", "dr1005", None);
        assert!(url.starts_with("http://10.10.102.50:801/eportal/portal/login?callback=dr1005&"));
        assert!(url.contains("user_account=%2C0%2C12345678%40unicom"));
        assert!(url.contains("wlan_user_ip=10.20.30.40"));
    }

    #[test]
    fn interpret_success() {
        let o = interpret(r#"dr1005({"result":1,"msg":"认证成功！"});"#);
        assert_eq!(o, AuthOutcome::Success);
    }

    #[test]
    fn interpret_already_online() {
        let o = interpret(r#"dr1005({"result":0,"msg":"IP: 10.20.30.41 已经在线！","ret_code":2});"#);
        assert_eq!(o, AuthOutcome::AlreadyOnline);
    }

    #[test]
    fn interpret_fail() {
        let o = interpret(r#"dr1005({"result":0,"msg":"AC认证失败","ret_code":1});"#);
        assert_eq!(o, AuthOutcome::Failed { msg: "AC认证失败".into() });
    }

    #[test]
    fn interpret_garbage() {
        assert!(matches!(interpret("not jsonp"), AuthOutcome::Failed { .. }));
    }

    #[test]
    fn ip_score_priority() {
        assert!(ip_score("10.20.30.41") > ip_score("192.168.100.100"));
        assert!(ip_score("192.168.1.100") > ip_score("172.29.144.1"));
        assert!(ip_score("172.29.144.1") > ip_score("100.123.202.1"));
        assert_eq!(ip_score("not-an-ip"), 0);
    }
}
