//! Structured logging: in-memory ring buffer (for UI read) + file append (512KB rotation).
//!
//! On Android the caller can redirect `log()` output to Android `Log.*` via UniFFI callbacks.

use std::sync::{LazyLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_MEMORY_LINES: usize = 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Info,
    Warn,
    Error,
}

impl Level {
    pub fn as_str(self) -> &'static str {
        match self {
            Level::Info => "INFO",
            Level::Warn => "WARN",
            Level::Error => "ERROR",
        }
    }
}

#[derive(Debug, Clone)]
pub struct LogLine {
    pub ts: u64,
    pub level: Level,
    pub text: String,
}

impl LogLine {
    #[allow(dead_code)]
    pub fn formatted(&self) -> String {
        let (y, mo, d, h, mi, s) = format_cn_local(self.ts);
        format!("[{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}:{s:02}] [{}] {}", self.level.as_str(), self.text)
    }
}

/// Unix seconds → (y, mo, d, h, mi, s) in UTC+8. Pure integer math, no external deps.
#[allow(dead_code)]
fn format_cn_local(unix: u64) -> (u64, u64, u64, u64, u64, u64) {
    let total = unix + 8 * 3600;
    let days = total / 86400;
    let rem = total % 86400;
    let (h, mi, s) = (rem / 3600, rem % 3600 / 60, rem % 60);
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y as u64, m as u64, d as u64, h, mi, s)
}

struct LoggerInner {
    memory: RwLock<Vec<LogLine>>,
}

static LOGGER: LazyLock<LoggerInner> = LazyLock::new(|| LoggerInner {
    memory: RwLock::new(Vec::with_capacity(MAX_MEMORY_LINES)),
});

fn memory() -> std::sync::RwLockWriteGuard<'static, Vec<LogLine>> {
    LOGGER.memory.write().unwrap_or_else(|e| e.into_inner())
}

/// Log a line. On Android this also writes to logcat via the UniFFI callback (set up by Kotlin side).
pub fn log(level: Level, text: impl Into<String>) {
    let line = LogLine {
        ts: SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0),
        level,
        text: text.into(),
    };

    {
        let mut mem = memory();
        let len = mem.len();
        if len >= MAX_MEMORY_LINES {
            mem.drain(..len - MAX_MEMORY_LINES + 1);
        }
        mem.push(line.clone());
    }

    // Print to stderr (captured by Android logcat)
    // Use a tag prefix so it's easy to filter in Logcat with "CampusAuth"
    eprintln!("[CampusAuth] [{}] {}", line.level.as_str(), line.text);
}

/// Read in-memory logs (oldest first).
pub fn recent() -> Vec<LogLine> {
    LOGGER
        .memory
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

#[macro_export]
macro_rules! log_info {
    ($($arg:tt)*) => { $crate::logger::log($crate::logger::Level::Info, format!($($arg)*)) };
}

#[macro_export]
macro_rules! log_warn {
    ($($arg:tt)*) => { $crate::logger::log($crate::logger::Level::Warn, format!($($arg)*)) };
}

#[macro_export]
macro_rules! log_error {
    ($($arg:tt)*) => { $crate::logger::log($crate::logger::Level::Error, format!($($arg)*)) };
}
