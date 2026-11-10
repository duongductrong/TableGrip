//! The activity monitor (`activity/Activity.kt`): a connection of its own that lists the server's sessions and can
//! cancel or end them. The data types live in `tg_core::activity`; each engine supplies an [`ActivityReader`].

use crate::error::{DriverError, Result};
use crate::sql::SqlConn;
use async_trait::async_trait;
pub use tg_core::activity::{ActivitySnapshot, ActivitySupport, SessionActivity, SessionState};

#[async_trait]
pub trait ActivityMonitor: Send {
    fn read_only(&self) -> bool;

    async fn snapshot(&mut self) -> Result<ActivitySnapshot>;

    /// Privileges the monitor's user lacks to see every session, as the engine names them.
    async fn missing_privileges(&mut self) -> Result<Vec<String>>;

    /// Cancels the session's running query; false if it was gone.
    async fn cancel(&mut self, id: &str) -> Result<bool>;

    /// Ends the session; false if it was gone.
    async fn terminate(&mut self, id: &str) -> Result<bool>;
}

/// How one engine reads and controls sessions, over a plain SQL connection.
#[async_trait]
pub trait ActivityReader: Send + Sync {
    async fn read(&self, conn: &mut dyn SqlConn) -> Result<ActivitySnapshot>;

    async fn missing_privileges(&self, _conn: &mut dyn SqlConn) -> Result<Vec<String>> {
        Ok(Vec::new())
    }

    async fn cancel(&self, conn: &mut dyn SqlConn, id: &str) -> Result<bool>;

    async fn terminate(&self, conn: &mut dyn SqlConn, id: &str) -> Result<bool>;
}

/// An [`ActivityMonitor`] from a dedicated connection and an engine's reader, refusing changes when read-only.
pub struct SqlActivityMonitor {
    conn: Box<dyn SqlConn>,
    reader: Box<dyn ActivityReader>,
    read_only: bool,
}

impl SqlActivityMonitor {
    pub fn new(conn: Box<dyn SqlConn>, reader: Box<dyn ActivityReader>, read_only: bool) -> Self {
        Self {
            conn,
            reader,
            read_only,
        }
    }
}

#[async_trait]
impl ActivityMonitor for SqlActivityMonitor {
    fn read_only(&self) -> bool {
        self.read_only
    }

    async fn snapshot(&mut self) -> Result<ActivitySnapshot> {
        self.reader.read(self.conn.as_mut()).await
    }

    async fn missing_privileges(&mut self) -> Result<Vec<String>> {
        self.reader.missing_privileges(self.conn.as_mut()).await
    }

    async fn cancel(&mut self, id: &str) -> Result<bool> {
        if self.read_only {
            return Err(DriverError::read_only("cancelling other sessions' queries"));
        }
        self.reader.cancel(self.conn.as_mut(), id).await
    }

    async fn terminate(&mut self, id: &str) -> Result<bool> {
        if self.read_only {
            return Err(DriverError::read_only("terminating sessions"));
        }
        self.reader.terminate(self.conn.as_mut(), id).await
    }
}
