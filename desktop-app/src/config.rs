//! Persisted app state: this device's own ID, and the set of previously
//! paired phones (so daily use is "open both apps, they reconnect" rather
//! than re-entering a pairing code every launch). See
//! `docs/architecture.md` §2.3 and `protocol-spec.md` §5.
//!
//! Lives at `%LOCALAPPDATA%\AudioRelay\config.toml` on Windows, or
//! `$XDG_CONFIG_HOME/audiorelay/config.toml` (falling back to
//! `~/.config/audiorelay/config.toml`) on Linux — both via
//! `directories::ProjectDirs`. Never commit a real one — see the root
//! `.gitignore`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PairedDevice {
    pub device_name: String,
    /// Hex-encoded 32-byte session key derived at pairing time
    /// (protocol-spec.md §5). Treat this file as a secret.
    pub session_key_hex: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Config {
    /// This laptop's own stable device ID, generated once on first run.
    pub device_id: String,
    /// Keyed by the phone's device_id.
    #[serde(default)]
    pub paired_devices: HashMap<String, PairedDevice>,
    /// Light/dark preference. `#[serde(default)]` so config files written by
    /// earlier versions still load.
    #[serde(default)]
    pub appearance: crate::ui::Appearance,
    /// Whether this laptop's own output should keep playing while streaming
    /// to a phone. `#[serde(default)]` so older config files load with the
    /// behavior they already had — this laptop's audio always played
    /// locally, since loopback capture never touched local playback — as
    /// the default rather than silently muting existing users on upgrade.
    #[serde(default = "default_play_locally_while_relaying")]
    pub play_locally_while_relaying: bool,
}

fn default_play_locally_while_relaying() -> bool {
    true
}

impl Default for Config {
    fn default() -> Self {
        Config {
            device_id: Uuid::new_v4().to_string(),
            paired_devices: HashMap::new(),
            appearance: crate::ui::Appearance::default(),
            play_locally_while_relaying: default_play_locally_while_relaying(),
        }
    }
}

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("could not determine a config directory for this platform")]
    NoConfigDir,
    #[error("I/O error accessing config file: {0}")]
    Io(#[from] std::io::Error),
    #[error("config file is not valid TOML: {0}")]
    Parse(#[from] toml::de::Error),
    #[error("failed to serialize config: {0}")]
    Serialize(#[from] toml::ser::Error),
}

impl Config {
    pub fn default_path() -> Result<PathBuf, ConfigError> {
        let dirs =
            directories::ProjectDirs::from("", "", "AudioRelay").ok_or(ConfigError::NoConfigDir)?;
        Ok(dirs.data_local_dir().join("config.toml"))
    }

    /// Loads from the default platform config path, or returns a fresh
    /// default `Config` (with a newly-generated `device_id`) if no config
    /// file exists yet — that's the expected state on first run, not an
    /// error.
    pub fn load() -> Result<Self, ConfigError> {
        Self::load_from(&Self::default_path()?)
    }

    pub fn load_from(path: &Path) -> Result<Self, ConfigError> {
        match std::fs::read_to_string(path) {
            Ok(contents) => Ok(toml::from_str(&contents)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Config::default()),
            Err(e) => Err(e.into()),
        }
    }

    pub fn save(&self) -> Result<(), ConfigError> {
        self.save_to(&Self::default_path()?)
    }

    pub fn save_to(&self, path: &Path) -> Result<(), ConfigError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let contents = toml::to_string_pretty(self)?;
        std::fs::write(path, contents)?;
        Ok(())
    }

    pub fn remember_device(
        &mut self,
        phone_device_id: &str,
        device_name: &str,
        session_key_hex: &str,
    ) {
        self.paired_devices.insert(
            phone_device_id.to_string(),
            PairedDevice {
                device_name: device_name.to_string(),
                session_key_hex: session_key_hex.to_string(),
            },
        );
    }

    pub fn forget_device(&mut self, phone_device_id: &str) {
        self.paired_devices.remove(phone_device_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_has_a_unique_device_id() {
        let a = Config::default();
        let b = Config::default();
        assert_ne!(a.device_id, b.device_id);
        assert!(Uuid::parse_str(&a.device_id).is_ok());
    }

    #[test]
    fn round_trips_through_save_and_load() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("config.toml");

        let mut cfg = Config::default();
        cfg.remember_device("phone-1", "Pixel 9", "deadbeef");
        cfg.save_to(&path).unwrap();

        let loaded = Config::load_from(&path).unwrap();
        assert_eq!(cfg, loaded);
    }

    #[test]
    fn loading_a_missing_file_returns_a_fresh_default() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("does-not-exist.toml");
        let cfg = Config::load_from(&path).unwrap();
        assert!(cfg.paired_devices.is_empty());
    }

    #[test]
    fn forget_device_removes_it() {
        let mut cfg = Config::default();
        cfg.remember_device("phone-1", "Pixel 9", "deadbeef");
        assert!(cfg.paired_devices.contains_key("phone-1"));
        cfg.forget_device("phone-1");
        assert!(!cfg.paired_devices.contains_key("phone-1"));
    }

    #[test]
    fn save_creates_parent_directories() {
        let dir = tempfile::tempdir().unwrap();
        let nested = dir.path().join("nested").join("dirs").join("config.toml");
        Config::default().save_to(&nested).unwrap();
        assert!(nested.exists());
    }
}
