//! TableGrip's themes, resolved into the colours, fonts and metrics the UI kit draws with.
//!
//! The built-in theme files are read straight from the shared resources (`resources/themes`), the
//! user's from their themes folder, and `themeOverrides` and `accentColor` from settings are laid over them. Every
//! resolved value is checked against what the Kotlin app resolves, dumped to `conformance/golden/themes`.
//!
//! ```
//! use tg_theme::{AppearanceSettings, Registry};
//!
//! let theme = AppearanceSettings::default().theme(&Registry::new(), true);
//! assert_eq!(theme.colors.islands.island.to_hex(), "#1E1F22");
//! ```

pub mod color;
mod lenient;
pub mod registry;
pub mod resolve;
pub mod settings;
pub mod spec;
pub mod ui;
pub mod zoom;

pub use color::Rgba;
pub use registry::{DEFAULT_DARK_THEME, DEFAULT_LIGHT_THEME, Registry, ThemeEntry};
pub use resolve::ColorTheme;
pub use settings::{AppearanceSettings, ThemeSelection};
pub use spec::{ThemeFamily, ThemeOverride, ThemeSpec};
pub use ui::{Fonts, Theme, UiColors};
