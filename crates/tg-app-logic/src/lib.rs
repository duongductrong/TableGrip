//! State for each screen, without GPUI (`app/src/main/kotlin`'s view models): what the shell shows, the connections
//! and their dialog, the explorer tree, the workspace tabs and the session that brings them back. Everything is
//! testable headless; work that needs I/O comes back as effects for the app to run, or is an `async fn` the app runs
//! on tokio.

pub mod connections;
pub mod imports;
pub mod registry;

pub use registry::{Live, Registry, Status};
