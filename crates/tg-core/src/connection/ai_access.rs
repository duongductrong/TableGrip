//! What of a connection may be sent to an AI agent (`connection/AiAccess.kt`). Restrictive by default.

use crate::connection::config::{ConnectionConfig, Environment};
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AiAccess {
    Off,
    SchemaOnly,
    SchemaAndSamples,
}

impl AiAccess {
    /// The app-wide default when settings hold nothing usable.
    pub const DEFAULT: AiAccess = AiAccess::SchemaOnly;

    pub fn label(self) -> &'static str {
        match self {
            Self::Off => "Off",
            Self::SchemaOnly => "Schema only",
            Self::SchemaAndSamples => "Schema and sample data",
        }
    }

    pub fn description(self) -> &'static str {
        match self {
            Self::Off => {
                "Nothing about this connection is sent to an agent; AI actions are hidden."
            }
            Self::SchemaOnly => {
                "Table and column names, types, DDL, your SQL and error messages. No values from the data."
            }
            Self::SchemaAndSamples => {
                "Also sample values and query results, e.g. a column's distinct values."
            }
        }
    }

    pub fn shares_schema(self) -> bool {
        self != Self::Off
    }

    pub fn shares_data(self) -> bool {
        self == Self::SchemaAndSamples
    }

    /// Unknown or blank names fall back to the default: a bad edit must never share more than intended.
    pub fn of(name: &str) -> AiAccess {
        [Self::Off, Self::SchemaOnly, Self::SchemaAndSamples]
            .into_iter()
            .find(|a| a.name() == name)
            .unwrap_or(Self::DEFAULT)
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::Off => "Off",
            Self::SchemaOnly => "SchemaOnly",
            Self::SchemaAndSamples => "SchemaAndSamples",
        }
    }

    /// What `config` may share, given the app-wide `default`. A connection's own choice wins; otherwise production
    /// connections never share data, even when the default would.
    pub fn effective(default: AiAccess, config: Option<&ConnectionConfig>) -> AiAccess {
        if let Some(own) = config.and_then(|c| c.ai_access) {
            return own;
        }
        let production = config.is_some_and(|c| c.environment == Environment::Production);
        if production && default == Self::SchemaAndSamples {
            Self::SchemaOnly
        } else {
            default
        }
    }
}
