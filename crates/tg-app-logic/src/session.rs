//! Keeps the workspace across restarts (`workspace/SessionKeeper.kt`): open consoles (with unsaved text) and tables
//! (with their filter) come back as they were, with the layout around them as Settings → Startup & Layout asks. The
//! file is Kotlin's (`tg_core::session`), so either app reopens the other's tabs.

use tg_core::session::{Session, WindowBounds};

use crate::app::{AppModel, LeftTool};

/// A saved sidebar divider position, kept where the sidebar stays usable.
pub fn restored_sidebar_split(saved: f32) -> f32 {
    saved.clamp(0.1, 0.6)
}

/// A saved agent-panel divider position, kept where the agent panel stays usable.
pub fn restored_agent_split(saved: f32) -> f32 {
    saved.clamp(0.3, 0.9)
}

/// The session as the app stands now; `window` is the main window's bounds when they are known.
pub fn snapshot(app: &AppModel, window: Option<WindowBounds>) -> Session {
    let selected_key = app.workspace.selected().map(|t| t.key);
    let saved: Vec<(u64, _)> = app
        .workspace
        .tabs
        .iter()
        .filter_map(|t| t.spec.to_session().map(|s| (t.key, s)))
        .collect();
    Session {
        selected: saved
            .iter()
            .position(|(k, _)| Some(*k) == selected_key)
            .map_or(-1, |i| i as i32),
        tabs: saved.into_iter().map(|(_, s)| s).collect(),
        left_tool: app.left_tool.map(|t| t.name().to_owned()),
        connection_id: app.current_connection_id.clone(),
        agent_open: app.agent_open,
        // The agent chat is S10's; until then the last one stays as it was saved.
        agent_chat: app.agent_chat.clone(),
        window,
        sidebar_split: Some(app.sidebar_split),
        agent_split: Some(app.agent_split),
    }
}

/// What restoring leaves for the window to apply.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Restored {
    /// Where the window goes, when Settings keep its bounds (at least 640×420 dp).
    pub window: Option<WindowBounds>,
    /// The agent chat to reopen.
    pub agent_chat: Option<String>,
}

/// Reopens the session's tabs whose connection still exists, and the layout around them, as the settings ask.
pub fn restore(app: &mut AppModel, session: &Session) -> Restored {
    let settings = app.settings.clone();
    let mut reopened: Vec<Option<u64>> = Vec::new();
    if settings.restore_tabs {
        for saved in &session.tabs {
            let config = app.connections.by_id(saved.connection_id()).cloned();
            reopened.push(config.map(|c| app.workspace.restore(&c, saved)));
        }
    }
    if let Some(Some(key)) = usize::try_from(session.selected)
        .ok()
        .and_then(|i| reopened.get(i))
    {
        app.select_tab(*key);
    } else if let Some(key) = app.workspace.selected().map(|t| t.key) {
        app.select_tab(key);
    }
    let tool = if settings.startup_tool == "LastUsed" {
        session.left_tool.clone()
    } else {
        Some(settings.startup_tool.clone())
    };
    app.left_tool = tool.as_deref().and_then(LeftTool::of);
    app.agent_open = match settings.startup_agent.as_str() {
        "Open" => true,
        "LastUsed" => session.agent_open,
        _ => false,
    };
    if settings.remember_panel_sizes {
        if let Some(s) = session.sidebar_split {
            app.sidebar_split = restored_sidebar_split(s);
        }
        if let Some(s) = session.agent_split {
            app.agent_split = restored_agent_split(s);
        }
    }
    app.agent_chat = session.agent_chat.clone();
    if let Some(id) = &session.connection_id
        && app.connections.by_id(id).is_some()
        && app.workspace.selected().is_none()
    {
        app.current_connection_id = Some(id.clone());
    }
    Restored {
        window: session
            .window
            .clone()
            .filter(|_| settings.remember_window_bounds)
            .map(|w| WindowBounds {
                width: w.width.max(640.0),
                height: w.height.max(420.0),
                ..w
            }),
        agent_chat: session.agent_chat.clone(),
    }
}
