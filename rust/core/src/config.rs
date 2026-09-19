//! Configuration model and INI read/write.
//!
//! Compatible with the Windows version's `user_account = ,0,id@operator` legacy format.

use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Operator {
    #[default]
    Campus,
    Cmcc,
    Unicom,
    Telecom,
}

impl Operator {
    pub fn as_str(self) -> &'static str {
        match self {
            Operator::Campus => "campus",
            Operator::Cmcc => "cmcc",
            Operator::Unicom => "unicom",
            Operator::Telecom => "telecom",
        }
    }

    pub const ALL: [Operator; 4] = [
        Operator::Campus,
        Operator::Cmcc,
        Operator::Unicom,
        Operator::Telecom,
    ];

    pub fn display(self) -> &'static str {
        match self {
            Operator::Campus => "校园网",
            Operator::Cmcc => "中国移动",
            Operator::Unicom => "中国联通",
            Operator::Telecom => "中国电信",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "campus" => Some(Operator::Campus),
            "cmcc" => Some(Operator::Cmcc),
            "unicom" => Some(Operator::Unicom),
            "telecom" => Some(Operator::Telecom),
            _ => None,
        }
    }
}

impl std::fmt::Display for Operator {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    pub auth_url: String,
    pub check_url: String,
    pub check_interval: Duration,
    pub student_id: String,
    pub operator: Operator,
    pub password: String,
    pub fixed_ip: Option<String>,
    pub guardian_enabled: bool,
    pub retry_interval: Duration,
    pub max_retries: u32,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            auth_url: "http://10.10.102.50:801/eportal/portal/login".into(),
            check_url: "http://www.baidu.com".into(),
            check_interval: Duration::from_secs(30),
            student_id: String::new(),
            operator: Operator::default(),
            password: String::new(),
            fixed_ip: None,
            guardian_enabled: false,
            retry_interval: Duration::from_secs(10),
            max_retries: 3,
        }
    }
}

impl Config {
    /// Normalize auth URL: allow bare server address, auto-complete login endpoint.
    pub fn normalize_auth_url(raw: &str) -> String {
        let s = raw.trim();
        if s.is_empty() || s.contains("/eportal") {
            return s.into();
        }
        let mut s = s.to_string();
        if !s.contains("://") {
            s = format!("http://{s}");
        }
        let no_query = s.split(['#', '?']).next().unwrap_or(&s);
        let base = no_query.trim_end_matches('/');
        let after_scheme = base.split("://").nth(1).unwrap_or(base);
        let authority = after_scheme.split('/').next().unwrap_or(after_scheme);
        let host_part = authority.rsplit(']').next().unwrap_or(authority);
        let with_port = if host_part.contains(':') {
            base.to_string()
        } else {
            format!("{base}:801")
        };
        format!("{with_port}/eportal/portal/login")
    }

    pub fn portal_base(&self) -> &str {
        self.auth_url
            .split("/eportal")
            .next()
            .unwrap_or(&self.auth_url)
            .trim_end_matches('/')
    }

    pub fn login_page_url(&self) -> String {
        format!("{}/srun_portal_pc.php?ac_id=1&", self.portal_base())
    }
}

// ── INI parsing ─────────────────────────────────────────────────────────────

#[derive(Debug, Default)]
struct Ini {
    network: Vec<(String, String)>,
    account: Vec<(String, String)>,
    guardian: Vec<(String, String)>,
}

fn parse_ini(text: &str) -> Ini {
    let mut ini = Ini::default();
    let mut section = "";
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with(['#', ';']) {
            continue;
        }
        if let Some(name) = line.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
            section = name.trim();
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        let (k, v) = (k.trim().to_string(), v.trim().to_string());
        match section {
            "network" => ini.network.push((k, v)),
            "account" => ini.account.push((k, v)),
            "guardian" => ini.guardian.push((k, v)),
            _ => {}
        }
    }
    ini
}

fn get<'a>(pairs: &'a [(String, String)], key: &str) -> Option<&'a str> {
    pairs
        .iter()
        .rev()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v.as_str())
}

fn get_u64(pairs: &[(String, String)], key: &str) -> Option<u64> {
    get(pairs, key)?.trim().parse().ok()
}

fn get_bool(pairs: &[(String, String)], key: &str) -> Option<bool> {
    match get(pairs, key)?.trim() {
        "1" | "true" | "yes" | "on" => Some(true),
        "0" | "false" | "no" | "off" => Some(false),
        _ => None,
    }
}

fn split_legacy_account(v: &str) -> Option<(String, Operator)> {
    let last = v.rsplit(',').next()?.trim();
    let (id, op) = last.split_once('@')?;
    let id = id.trim();
    if id.is_empty() {
        return None;
    }
    Some((id.to_string(), Operator::parse(op).unwrap_or_default()))
}

impl Config {
    pub fn parse(text: &str) -> Self {
        let ini = parse_ini(text);
        let mut cfg = Config::default();

        if let Some(v) = get(&ini.network, "auth_url") {
            if !v.is_empty() {
                cfg.auth_url = Config::normalize_auth_url(v);
            }
        }
        if let Some(v) = get(&ini.network, "check_url") {
            if !v.is_empty() {
                cfg.check_url = v.to_string();
            }
        }
        if let Some(v) = get_u64(&ini.network, "check_interval") {
            if (1..=3600).contains(&v) {
                cfg.check_interval = Duration::from_secs(v);
            }
        }

        if let Some(v) = get(&ini.account, "student_id") {
            cfg.student_id = v.trim().to_string();
        }
        if let Some(op) = get(&ini.account, "operator_type").and_then(Operator::parse) {
            cfg.operator = op;
        }
        if let Some(v) = get(&ini.account, "user_password") {
            cfg.password = v.to_string();
        }
        match get(&ini.account, "fixed_ip") {
            Some(v) if !v.trim().is_empty() => cfg.fixed_ip = Some(v.trim().to_string()),
            _ => cfg.fixed_ip = None,
        }
        if let Some((id, op)) = get(&ini.account, "user_account").and_then(split_legacy_account) {
            if cfg.student_id.is_empty() {
                cfg.student_id = id;
            }
            if cfg.operator == Operator::default() {
                cfg.operator = op;
            }
        }

        if let Some(v) = get_bool(&ini.guardian, "enabled") {
            cfg.guardian_enabled = v;
        }
        if let Some(v) = get_u64(&ini.guardian, "retry_interval") {
            if (1..=3600).contains(&v) {
                cfg.retry_interval = Duration::from_secs(v);
            }
        }
        if let Some(v) = get_u64(&ini.guardian, "max_retries") {
            if (1..=100).contains(&v) {
                cfg.max_retries = v as u32;
            }
        }
        cfg
    }

    pub fn to_ini(&self) -> String {
        format!(
            "# Campus Auth Guardian 配置文件 (UTF-8)\n\
             \n\
             [network]\n\
             auth_url = {auth}\n\
             check_url = {check}\n\
             check_interval = {interval}\n\
             \n\
             [account]\n\
             student_id = {id}\n\
             operator_type = {op}\n\
             user_password = {pw}\n\
             fixed_ip = {ip}\n\
             \n\
             [guardian]\n\
             enabled = {en}\n\
             retry_interval = {retry}\n\
             max_retries = {max}\n",
            auth = self.auth_url,
            check = self.check_url,
            interval = self.check_interval.as_secs(),
            id = self.student_id,
            op = self.operator.as_str(),
            pw = self.password,
            ip = self.fixed_ip.as_deref().unwrap_or(""),
            en = if self.guardian_enabled { 1 } else { 0 },
            retry = self.retry_interval.as_secs(),
            max = self.max_retries,
        )
    }

    /// Read config from INI text; on Android the caller provides the text (from app storage).
    #[allow(dead_code)]
    pub fn from_ini(text: &str) -> Self {
        Config::parse(text)
    }

    /// Serialize to INI string (caller saves to app storage on Android).
    #[allow(dead_code)]
    pub fn save_to_string(&self) -> String {
        self.to_ini()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_standard_ini() {
        let cfg = Config::parse(
            "[network]\nauth_url = http://1.2.3.4:801/eportal/portal/login\ncheck_interval = 15\n\
             [account]\nstudent_id = 12345678\noperator_type = unicom\nuser_password = pw\nfixed_ip = 10.0.0.1\n\
             [guardian]\nenabled = 1\nretry_interval = 5\nmax_retries = 7\n",
        );
        assert_eq!(cfg.auth_url, "http://1.2.3.4:801/eportal/portal/login");
        assert_eq!(cfg.check_interval, Duration::from_secs(15));
        assert_eq!(cfg.student_id, "12345678");
        assert_eq!(cfg.operator, Operator::Unicom);
        assert_eq!(cfg.password, "pw");
        assert_eq!(cfg.fixed_ip.as_deref(), Some("10.0.0.1"));
        assert!(cfg.guardian_enabled);
        assert_eq!(cfg.retry_interval, Duration::from_secs(5));
        assert_eq!(cfg.max_retries, 7);
    }

    #[test]
    fn parses_legacy_user_account() {
        let cfg = Config::parse("[account]\nuser_account = ,0,12345678@unicom\nuser_password = pw\n");
        assert_eq!(cfg.student_id, "12345678");
        assert_eq!(cfg.operator, Operator::Unicom);
    }

    #[test]
    fn defaults_when_empty() {
        let cfg = Config::parse("");
        assert_eq!(cfg, Config::default());
    }

    #[test]
    fn normalize_auth_url_cases() {
        assert_eq!(
            Config::normalize_auth_url("http://10.10.102.50/"),
            "http://10.10.102.50:801/eportal/portal/login"
        );
        assert_eq!(
            Config::normalize_auth_url("10.10.102.50"),
            "http://10.10.102.50:801/eportal/portal/login"
        );
        assert_eq!(
            Config::normalize_auth_url("http://10.10.102.50:8080/"),
            "http://10.10.102.50:8080/eportal/portal/login"
        );
        assert_eq!(
            Config::normalize_auth_url("http://10.10.102.50:801/eportal/portal/login"),
            "http://10.10.102.50:801/eportal/portal/login"
        );
    }

    #[test]
    fn roundtrip_ini() {
        let mut cfg = Config::default();
        cfg.student_id = "abc".into();
        cfg.operator = Operator::Telecom;
        cfg.guardian_enabled = true;
        let text = cfg.to_ini();
        assert_eq!(Config::parse(&text), cfg);
    }
}
