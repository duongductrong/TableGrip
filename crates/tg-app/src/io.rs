//! The bridge between GPUI and tokio. GPUI's executor runs the UI; drivers need tokio's reactor, so every database or
//! file job runs on the tokio runtime started in `main`, and the UI awaits its `JoinHandle` (awaitable from any
//! executor) inside a GPUI task, then updates its entity:
//!
//! ```text
//! cx.spawn(async move |this, cx| {
//!     let rows = io(&handle, async move { db.tables(&ns).await }).await;   // runs on tokio
//!     this.update(cx, |shell, cx| { /* back on the UI thread */ cx.notify() })
//! })
//! ```

use std::future::Future;

use gpui::{App, Global};
use tokio::runtime::Handle;

/// The tokio runtime's handle, as a GPUI global.
#[derive(Clone)]
pub struct Io(pub Handle);

impl Global for Io {}

pub fn handle(cx: &App) -> Handle {
    cx.global::<Io>().0.clone()
}

/// Runs `f` on tokio; the returned future resolves on whichever executor awaits it.
pub fn spawn<T: Send + 'static>(
    handle: &Handle,
    f: impl Future<Output = T> + Send + 'static,
) -> impl Future<Output = T> + 'static {
    let task = handle.spawn(f);
    async move { task.await.expect("a background job panicked") }
}

/// Runs blocking `f` (file and keychain access) on tokio's blocking pool.
pub fn blocking<T: Send + 'static>(
    handle: &Handle,
    f: impl FnOnce() -> T + Send + 'static,
) -> impl Future<Output = T> + 'static {
    let task = handle.spawn_blocking(f);
    async move { task.await.expect("a background job panicked") }
}
