//! UniFFI-exported API for Kotlin.
//!
//! The UDL file (guardian.udl) defines the FFI surface. build.rs generates scaffolding
//! from UDL at compile time; uniffi-bindgen generates Kotlin bindings from UDL.
//! Both sides stay in sync because they share the same UDL source.
//!
//! IMPORTANT: Every function listed in guardian.udl must have a matching Rust function here.
//! Function signatures must be compatible with UniFFI's type mapping:
//!   UDL `string`       → Rust `String`
//!   UDL `string?`      → Rust `Option<String>`
//!   UDL `boolean`      → Rust `bool`
//!   UDL `i32`          → Rust `i32`
//!   UDL `void`         → Rust `()` (or omitted)
//!   [Throws=X]         → Rust `Result<_, X>`

use std::sync::OnceLock;

use crate::auth::AuthOutcome;
use crate::config::{Config, Operator};
use crate::guardian::{Guardian, GuardianEvent, GuardianState};
use crate::netcheck::NetStatus;

static GUARDIAN: OnceLock<Guardian> = OnceLock::new();

/// Try to get the guardian instance. Returns Err if not initialized.
fn guardian() -> Result<&'static Guardian, GuardianError> {
    GUARDIAN.get().ok_or(GuardianError::NotInitialized)
}

// ── Error type for UniFFI ───────────────────────────────────────────────────
// Variants must be simple (no data) to match the UDL enum definition.
// Implement Display manually for meaningful error messages.

#[derive(Debug)]
pub enum GuardianError {
    NotInitialized,
    ConfigError,
    IoError,
}

impl std::fmt::Display for GuardianError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            GuardianError::NotInitialized => write!(f, "Not initialized: call guardian_init first"),
            GuardianError::ConfigError => write!(f, "Configuration error"),
            GuardianError::IoError => write!(f, "IO error"),
        }
    }
}

impl std::error::Error for GuardianError {}

// ── Initialization ──────────────────────────────────────────────────────────

/// Initialize the guardian with a configuration INI string.
pub fn guardian_init(config_ini: String) -> Result<(), GuardianError> {
    crate::log_info!("guardian_init starting");
    let cfg = Config::parse(&config_ini);
    crate::log_info!("Config loaded: auth_url={}, check_url={}, student_id={}, operator={}",
        cfg.auth_url, cfg.check_url, cfg.student_id, cfg.operator);
    let g = Guardian::start(cfg);
    GUARDIAN.set(g).ok();
    crate::log_info!("Guardian initialized successfully");
    Ok(())
}

// ── Configuration ───────────────────────────────────────────────────────────

/// Get current config as INI string.
pub fn guardian_config_ini() -> Result<String, GuardianError> {
    Ok(guardian()?.config().to_ini())
}

/// Apply config from INI string. Returns Ok(()) on success.
pub fn guardian_config_apply(config_ini: String) -> Result<(), GuardianError> {
    let cfg = Config::parse(&config_ini);
    guardian()?.update_config(cfg);
    Ok(())
}

// ── Authentication ──────────────────────────────────────────────────────────

/// Execute one authentication attempt (blocking). Returns JSON result string.
pub fn guardian_auth_now() -> Result<String, GuardianError> {
    let outcome = guardian()?.auth_once();
    Ok(match outcome {
        AuthOutcome::Success => r#"{"ok":true,"status":"success"}"#.to_string(),
        AuthOutcome::AlreadyOnline => r#"{"ok":true,"status":"already_online"}"#.to_string(),
        AuthOutcome::Failed { msg } => {
            serde_json::json!({"ok": false, "status": "failed", "msg": msg}).to_string()
        }
        AuthOutcome::NetworkError { msg } => {
            serde_json::json!({"ok": false, "status": "network_error", "msg": msg}).to_string()
        }
    })
}

// ── Guardian control ────────────────────────────────────────────────────────

/// Enable/disable guardian mode.
pub fn guardian_set_enabled(on: bool) -> Result<(), GuardianError> {
    let g = guardian()?;
    if on {
        if !g.is_running() {
            g.enable();
        }
    } else {
        g.disable();
    }
    Ok(())
}

/// Current state: 0=stopped, 1=monitoring, 2=authenticating, -1=not init.
pub fn guardian_state() -> i32 {
    match GUARDIAN.get() {
        None => -1,
        Some(g) => match g.state() {
            GuardianState::Stopped => 0,
            GuardianState::Monitoring => 1,
            GuardianState::Authenticating => 2,
        },
    }
}

/// Poll one event (non-blocking). Returns Some(json) if event available, None otherwise.
/// UniFFI maps Option<String> → nullable String in Kotlin.
pub fn guardian_poll_event() -> Option<String> {
    let g = GUARDIAN.get()?;
    match g.events.try_recv() {
        Ok(ev) => {
            let json = match ev {
                GuardianEvent::State(s) => {
                    serde_json::json!({
                        "type": "state",
                        "state": match s {
                            GuardianState::Stopped => 0,
                            GuardianState::Monitoring => 1,
                            GuardianState::Authenticating => 2,
                        }
                    })
                }
                GuardianEvent::NetStatus(ns) => serde_json::json!({
                    "type": "net",
                    "status": match ns {
                        NetStatus::Connected => "connected",
                        NetStatus::CaptivePortal { .. } => "captive",
                        NetStatus::DnsPending => "dns_pending",
                        NetStatus::NonCampus => "non_campus",
                        NetStatus::Disconnected { .. } => "disconnected",
                    },
                    "detail": match ns {
                        NetStatus::CaptivePortal { redirect } => redirect,
                        NetStatus::Disconnected { reason } => reason,
                        _ => String::new(),
                    }
                }),
                GuardianEvent::AuthResult(o) => serde_json::json!({
                    "type": "auth",
                    "ok": o.is_ok(),
                    "detail": match o {
                        AuthOutcome::Success => "success".to_string(),
                        AuthOutcome::AlreadyOnline => "already_online".to_string(),
                        AuthOutcome::Failed { msg } => msg,
                        AuthOutcome::NetworkError { msg } => msg,
                    }
                }),
                GuardianEvent::ConfigReloaded => serde_json::json!({"type": "config"}),
            };
            Some(json.to_string())
        }
        Err(_) => None,
    }
}

/// Switch operator (campus/cmcc/unicom/telecom).
pub fn guardian_set_operator(op: String) -> Result<(), GuardianError> {
    let Some(op) = Operator::parse(&op) else {
        return Err(GuardianError::ConfigError);
    };
    let mut cfg = guardian()?.config();
    cfg.operator = op;
    guardian()?.update_config(cfg);
    Ok(())
}

// ── Network probe ────────────────────────────────────────────────────────────

/// Trigger an immediate network probe (use after auth to quickly update UI status).
pub fn guardian_probe_network() {
    if let Some(g) = GUARDIAN.get() {
        g.probe_now();
    }
}

// ── Logging ─────────────────────────────────────────────────────────────────

/// Read recent logs as JSON array string.
pub fn guardian_recent_logs() -> String {
    let lines = crate::logger::recent();
    let arr: Vec<serde_json::Value> = lines
        .iter()
        .map(|l| {
            serde_json::json!({
                "ts": l.ts,
                "level": l.level.as_str(),
                "text": l.text,
            })
        })
        .collect();
    serde_json::to_string(&arr).unwrap_or_else(|_| "[]".into())
}

// ── Server probe ────────────────────────────────────────────────────────────

/// Probe auth server reachability. Returns JSON result string.
pub fn guardian_probe_server(auth_url: String) -> String {
    let mut issues: Vec<serde_json::Value> = Vec::new();

    if auth_url.is_empty() {
        issues.push(serde_json::json!("auth_url is empty"));
    }
    let base = auth_url.split("/eportal").next().unwrap_or("").trim_end_matches('/');
    let mut reachable = false;
    let mut http_status: Option<u16> = None;
    let mut latency_ms: Option<u64> = None;

    if !base.is_empty() {
        let started = std::time::Instant::now();
        match crate::netcheck::check(base, std::time::Duration::from_secs(6)) {
            NetStatus::Connected => {
                reachable = true;
                latency_ms = Some(started.elapsed().as_millis() as u64);
                http_status = Some(200);
            }
            NetStatus::CaptivePortal { .. } => {
                reachable = true;
                latency_ms = Some(started.elapsed().as_millis() as u64);
                http_status = Some(302);
            }
            NetStatus::DnsPending => {
                issues.push(serde_json::json!("DNS not ready (may need auth first)"));
            }
            NetStatus::NonCampus => {
                reachable = true;
                issues.push(serde_json::json!("Not on campus network"));
            }
            NetStatus::Disconnected { reason } => {
                issues.push(serde_json::json!(format!("Server unreachable: {reason}")));
            }
        }
    }

    serde_json::json!({
        "reachable": reachable,
        "http_status": http_status,
        "latency_ms": latency_ms,
        "issues": issues,
    })
    .to_string()
}

// ── Utility ─────────────────────────────────────────────────────────────────

/// Available operators as JSON array.
pub fn guardian_operators() -> String {
    let ops: Vec<serde_json::Value> = Operator::ALL
        .iter()
        .map(|op| {
            serde_json::json!({
                "key": op.as_str(),
                "name": op.display(),
            })
        })
        .collect();
    serde_json::to_string(&ops).unwrap_or_else(|_| "[]".into())
}

/// Provide local IP from Android's ConnectivityManager.
pub fn guardian_set_local_ip(ip: String) {
    crate::ipdetect::set_external_ip(&ip);
}
