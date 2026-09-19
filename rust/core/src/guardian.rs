//! Guardian loop: background thread periodically checks network, auto-re-authenticates on
//! disconnect with exponential backoff.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use std::thread::JoinHandle;

use crate::auth::{self, AuthOutcome};
use crate::config::Config;
use crate::netcheck::{self, NetStatus};
use crate::{log_error, log_info, log_warn};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GuardianState {
    Stopped,
    Monitoring,
    Authenticating,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GuardianEvent {
    State(GuardianState),
    NetStatus(NetStatus),
    AuthResult(AuthOutcome),
    ConfigReloaded,
}

struct Inner {
    running: AtomicBool,
    manual_kick: AtomicBool,
    probe_now: AtomicBool,
    state: Mutex<GuardianState>,
    cfg: Mutex<Config>,
}

pub struct Guardian {
    inner: Arc<Inner>,
    pub events: crossbeam_channel::Receiver<GuardianEvent>,
    events_tx: crossbeam_channel::Sender<GuardianEvent>,
    handle: Mutex<Option<JoinHandle<()>>>,
}

impl Guardian {
    pub fn start(cfg: Config) -> Self {
        let (tx, rx) = crossbeam_channel::unbounded();
        let inner = Arc::new(Inner {
            running: AtomicBool::new(cfg.guardian_enabled),
            manual_kick: AtomicBool::new(false),
            probe_now: AtomicBool::new(true),  // probe immediately on start
            state: Mutex::new(if cfg.guardian_enabled {
                GuardianState::Monitoring
            } else {
                GuardianState::Stopped
            }),
            cfg: Mutex::new(cfg),
        });
        let g = Self {
            inner,
            events: rx,
            events_tx: tx,
            handle: Mutex::new(None),
        };
        g.spawn_thread();
        g
    }

    fn spawn_thread(&self) {
        let inner = Arc::clone(&self.inner);
        let tx = self.events_tx.clone();
        let handle = std::thread::Builder::new()
            .name("guardian-loop".into())
            .spawn(move || run_loop(inner, tx))
            .expect("spawn guardian thread");
        *self.handle.lock().unwrap_or_else(|e| e.into_inner()) = Some(handle);
    }

    pub fn enable(&self) {
        self.inner.running.store(true, Ordering::SeqCst);
        self.set_state(GuardianState::Monitoring);
        log_info!("Guardian enabled");
    }

    pub fn disable(&self) {
        self.inner.running.store(false, Ordering::SeqCst);
        self.set_state(GuardianState::Stopped);
        log_info!("Guardian disabled");
    }

    pub fn is_running(&self) -> bool {
        self.inner.running.load(Ordering::SeqCst)
    }

    pub fn state(&self) -> GuardianState {
        *self.inner.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    #[allow(dead_code)]
    pub fn kick(&self) {
        self.inner.manual_kick.store(true, Ordering::SeqCst);
    }

    /// Trigger an immediate network probe (without re-authentication).
    pub fn probe_now(&self) {
        self.inner.probe_now.store(true, Ordering::SeqCst);
    }

    pub fn update_config(&self, cfg: Config) {
        *self.inner.cfg.lock().unwrap_or_else(|e| e.into_inner()) = cfg;
        self.events_tx.send(GuardianEvent::ConfigReloaded).ok();
        log_info!("Config updated");
    }

    pub fn config(&self) -> Config {
        self.inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn auth_once(&self) -> AuthOutcome {
        let cfg = self.inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone();
        self.set_state(GuardianState::Authenticating);
        let r = auth::authenticate(&cfg);
        self.events_tx.send(GuardianEvent::AuthResult(r.clone())).ok();
        self.set_state(if self.is_running() {
            GuardianState::Monitoring
        } else {
            GuardianState::Stopped
        });
        r
    }

    pub fn stop(&self) {
        self.inner.running.store(false, Ordering::SeqCst);
        if let Some(h) = self.handle.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = h.join();
        }
    }

    fn set_state(&self, s: GuardianState) {
        *self.inner.state.lock().unwrap_or_else(|e| e.into_inner()) = s;
        self.events_tx.send(GuardianEvent::State(s)).ok();
    }
}

impl Drop for Guardian {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Interval when connected: network is stable, check infrequently.
const CONNECTED_INTERVAL: Duration = Duration::from_secs(3);
/// Interval when disconnected/captive: check frequently to catch reconnection.
const DISCONNECTED_INTERVAL: Duration = Duration::from_secs(3);

fn run_loop(inner: Arc<Inner>, tx: crossbeam_channel::Sender<GuardianEvent>) {
    let mut next_check = std::time::Instant::now();
    let mut consecutive_failures: u32 = 0;
    let mut last_status: Option<NetStatus> = None;

    loop {
        if inner.manual_kick.swap(false, Ordering::SeqCst) {
            do_auth(&inner, &tx, None);
            consecutive_failures = 0;
            next_check = std::time::Instant::now() + CONNECTED_INTERVAL;
        }

        // Immediate network probe (triggered after auth, on start, etc.)
        if inner.probe_now.swap(false, Ordering::SeqCst) {
            let cfg = inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let status = probe_with_portal(&cfg);
            let interval = match &status {
                NetStatus::Connected | NetStatus::NonCampus => CONNECTED_INTERVAL,
                _ => DISCONNECTED_INTERVAL,
            };
            last_status = Some(status.clone());
            tx.send(GuardianEvent::NetStatus(status)).ok();
            next_check = std::time::Instant::now() + interval;
        }

        if !inner.running.load(Ordering::SeqCst) {
            // Guardian off: still periodically check network to drive UI status card
            let now = std::time::Instant::now();
            if now >= next_check {
                let cfg = inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone();
                let status = probe_with_portal(&cfg);
                let interval = match &status {
                    NetStatus::Connected | NetStatus::NonCampus => CONNECTED_INTERVAL,
                    _ => DISCONNECTED_INTERVAL,
                };
                last_status = Some(status.clone());
                tx.send(GuardianEvent::NetStatus(status)).ok();
                next_check = now + interval;
            }
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }

        let now = std::time::Instant::now();
        if now >= next_check {
            let cfg = inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let status = probe_with_portal(&cfg);

            match &status {
                NetStatus::Connected | NetStatus::NonCampus => {
                    consecutive_failures = consecutive_failures.saturating_sub(1);
                }
                NetStatus::DnsPending => {
                    tx.send(GuardianEvent::NetStatus(NetStatus::DnsPending)).ok();
                    log_info!("DNS pending, recheck in 5s");
                    next_check = std::time::Instant::now() + DISCONNECTED_INTERVAL;
                    std::thread::sleep(Duration::from_millis(500));
                    continue;
                }
                _ => {}
            }

            // Only emit event when status actually changes
            let changed = last_status.as_ref() != Some(&status);
            if changed {
                tx.send(GuardianEvent::NetStatus(status.clone())).ok();
            }
            last_status = Some(status.clone());

            match status {
                NetStatus::Connected | NetStatus::NonCampus => {
                    consecutive_failures = 0;
                    next_check = now + CONNECTED_INTERVAL;
                }
                NetStatus::CaptivePortal { redirect } => {
                    log_warn!("Captive portal detected: {redirect}");
                    let (ac_ip, ac_name, portal_user_ip) = crate::netcheck::extract_ac_params(&redirect);
                    log_info!("AC params: ip={ac_ip} name={ac_name} user_ip={portal_user_ip}");
                    consecutive_failures = run_retry_burst(&inner, &tx, Some((ac_ip.as_str(), ac_name.as_str(), portal_user_ip.as_str())));
                    let backoff = backoff_delay(&cfg, consecutive_failures);
                    next_check = std::time::Instant::now() + backoff;
                }
                NetStatus::DnsPending => unreachable!(),
                NetStatus::Disconnected { reason } => {
                    log_warn!("Network unreachable: {reason}");
                    consecutive_failures += 1;
                    let backoff = backoff_delay(&cfg, consecutive_failures);
                    next_check = std::time::Instant::now() + backoff;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

fn backoff_delay(cfg: &Config, failures: u32) -> Duration {
    if failures == 0 {
        return DISCONNECTED_INTERVAL;
    }
    let exp = failures.saturating_sub(1).min(6);
    let secs = cfg.retry_interval.as_secs().saturating_mul(1u64 << exp).min(300);
    Duration::from_secs(secs)
}

fn run_retry_burst(
    inner: &Arc<Inner>,
    tx: &crossbeam_channel::Sender<GuardianEvent>,
    ac: Option<(&str, &str, &str)>,
) -> u32 {
    let cfg = inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone();
    for attempt in 1..=cfg.max_retries {
        if !inner.running.load(Ordering::SeqCst) {
            return 0;
        }
        let outcome = do_auth(inner, tx, ac);
        if outcome.is_ok() {
            return 0;
        }
        log_warn!("Auth attempt {attempt}/{} failed", cfg.max_retries);
        if attempt < cfg.max_retries {
            std::thread::sleep(cfg.retry_interval);
        }
    }
    log_error!("{} consecutive auth failures, entering backoff", cfg.max_retries);
    cfg.max_retries
}

fn do_auth(
    inner: &Arc<Inner>,
    tx: &crossbeam_channel::Sender<GuardianEvent>,
    ac: Option<(&str, &str, &str)>,
) -> AuthOutcome {
    let cfg = inner.cfg.lock().unwrap_or_else(|e| e.into_inner()).clone();
    inner_state(inner, GuardianState::Authenticating, tx);
    let outcome = auth::authenticate_with_ac(&cfg, ac);
    tx.send(GuardianEvent::AuthResult(outcome.clone())).ok();
    inner_state(inner, GuardianState::Monitoring, tx);
    match &outcome {
        AuthOutcome::Success => log_info!("Auth success"),
        AuthOutcome::AlreadyOnline => log_info!("Already online"),
        AuthOutcome::Failed { msg } => log_error!("Auth failed: {msg}"),
        AuthOutcome::NetworkError { msg } => log_error!("Auth net error: {msg}"),
    }
    outcome
}

fn inner_state(inner: &Arc<Inner>, s: GuardianState, tx: &crossbeam_channel::Sender<GuardianEvent>) {
    *inner.state.lock().unwrap_or_else(|e| e.into_inner()) = s;
    tx.send(GuardianEvent::State(s)).ok();
}

/// Check network, and when connected, also probe the portal to distinguish campus vs non-campus.
fn probe_with_portal(cfg: &Config) -> NetStatus {
    let status = netcheck::check(&cfg.check_url, Duration::from_secs(8));
    if matches!(status, NetStatus::Connected) {
        // Connected to internet — check if we're actually on the campus network
        if !netcheck::portal_reachable(&cfg.auth_url, Duration::from_secs(3)) {
            return NetStatus::NonCampus;
        }
    }
    status
}
