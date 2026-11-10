//! State for each screen, without GPUI (`app/src/main/kotlin`'s view models): what the shell shows, the connections
//! and their dialog, the explorer tree, the workspace tabs and the session that brings them back. Everything is
//! testable headless; work that needs I/O comes back as [`app::Effect`]s, or is an `async fn` the app runs on tokio.
//!
//! The architecture of the app around these models is in the S6 log of docs/plans/rust-migration.md.

pub mod app;
pub mod appearance;
pub mod connections;
pub mod explorer;
pub mod imports;
pub mod keymap;
pub mod registry;
pub mod session;
pub mod workspace;

pub use app::{AppModel, Dialog, Effect, LeftTool};
pub use registry::{Live, Registry, Status};
