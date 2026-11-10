//! `CliOutputDecoderTest`, `CliModelListTest` and `CliAgentAdapterTest`, ported.

use std::path::PathBuf;
use std::sync::Arc;
use tg_agent::cli::{CliAgentAdapter, CliAgentPreset, CliFailure, CliModelList, CliOutputFormat};
use tg_agent::{
    ActivityState, AgentAdapter, AgentError, AgentEvent, AgentMessage, AgentModel, AgentRequest,
    Role, with_model,
};

fn lines(parts: &[&str]) -> String {
    parts.join("\n")
}

/// Fed in awkward chunks: pipe reads split lines anywhere.
fn chunks(output: &str) -> Vec<String> {
    let chars: Vec<char> = output.chars().collect();
    chars.chunks(7).map(|c| c.iter().collect()).collect()
}

fn events(
    format: CliOutputFormat,
    output: &str,
    exit: i32,
) -> (Vec<AgentEvent>, Result<String, CliFailure>) {
    let mut decoder = format.decoder("Agent");
    let events: Vec<AgentEvent> = chunks(output)
        .iter()
        .flat_map(|c| decoder.consume(c))
        .collect();
    let answer = decoder.finish(exit, "");
    (events, answer)
}

fn decode(
    format: CliOutputFormat,
    output: &str,
    exit: i32,
) -> (String, Result<String, CliFailure>) {
    let (events, answer) = events(format, output, exit);
    (deltas(&events), answer)
}

fn deltas(events: &[AgentEvent]) -> String {
    events
        .iter()
        .filter_map(|e| {
            if let AgentEvent::TextDelta(t) = e {
                Some(t.as_str())
            } else {
                None
            }
        })
        .collect()
}

fn reasoning(events: &[AgentEvent]) -> String {
    events
        .iter()
        .filter_map(|e| {
            if let AgentEvent::ReasoningDelta(t) = e {
                Some(t.as_str())
            } else {
                None
            }
        })
        .collect()
}

fn activities(events: &[AgentEvent]) -> Vec<String> {
    events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::Activity { id, label, state } => Some(format!("{id} {label} {state:?}")),
            _ => None,
        })
        .collect()
}

fn ok(answer: &str) -> Result<String, CliFailure> {
    Ok(answer.to_owned())
}

fn failure(message: &str) -> Result<String, CliFailure> {
    Err(CliFailure::Agent(AgentError(message.to_owned())))
}

#[test]
fn cursor_streams_its_timestamped_deltas_and_skips_the_repeated_whole_message() {
    let output = lines(&[
        r#"{"type":"system","subtype":"init","apiKeySource":"login","model":"x"}"#,
        r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Count"}]}}"#,
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"1\n2\n3\n4"}]},"timestamp_ms":1793237066348}"#,
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"\n5"}]},"timestamp_ms":1793237066376}"#,
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"1\n2\n3\n4\n5"}]}}"#,
        r#"{"type":"result","subtype":"success","is_error":false,"result":"1\n2\n3\n4\n5"}"#,
    ]);
    assert_eq!(
        decode(CliOutputFormat::ClaudeStreamJson, &output, 0),
        ("1\n2\n3\n4\n5".into(), ok("1\n2\n3\n4\n5"))
    );
}

#[test]
fn claude_streams_text_deltas() {
    let output = lines(&[
        r#"{"type":"system","subtype":"init","tools":[]}"#,
        r#"{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}"#,
        r#"{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}"#,
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hello"}]}}"#,
        r#"{"type":"result","subtype":"success","is_error":false,"result":"Hello"}"#,
    ]);
    assert_eq!(
        decode(CliOutputFormat::ClaudeStreamJson, &output, 0),
        ("Hello".into(), ok("Hello"))
    );
}

#[test]
fn claudes_error_result_is_a_failure_not_an_answer() {
    let output = r#"{"type":"result","subtype":"success","is_error":true,"result":"Failed to authenticate: OAuth session expired"}"#;
    assert_eq!(
        decode(CliOutputFormat::ClaudeStreamJson, output, 1).1,
        failure("Failed to authenticate: OAuth session expired")
    );
}

#[test]
fn codex_warnings_dont_fail_the_turn_but_turn_failed_does() {
    let answered = lines(&[
        r#"{"type":"thread.started","thread_id":"t"}"#,
        r#"{"type":"item.completed","item":{"id":"item_0","type":"error","message":"Model metadata not found."}}"#,
        r#"{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"pong"}}"#,
        r#"{"type":"turn.completed"}"#,
    ]);
    assert_eq!(
        decode(CliOutputFormat::CodexJson, &answered, 0),
        ("pong".into(), ok("pong"))
    );
    let failed = lines(&[
        r#"{"type":"item.completed","item":{"id":"item_0","type":"error","message":"Model metadata not found."}}"#,
        r#"{"type":"turn.failed","error":{"message":"{\"type\":\"error\",\"status\":400,\"error\":{\"type\":\"invalid_request_error\",\"message\":\"The model is not supported.\"}}"}}"#,
    ]);
    assert_eq!(
        decode(CliOutputFormat::CodexJson, &failed, 1).1,
        failure("The model is not supported.")
    );
}

#[test]
fn gemini_streams_assistant_deltas() {
    let output = lines(&[
        r#"{"type":"init","session_id":"s","model":"gemini"}"#,
        r#"{"type":"message","role":"user","content":"hi"}"#,
        r#"{"type":"message","role":"assistant","content":"Hi ","delta":true}"#,
        r#"{"type":"message","role":"assistant","content":"there","delta":true}"#,
        r#"{"type":"result","status":"success"}"#,
    ]);
    assert_eq!(
        decode(CliOutputFormat::GeminiStreamJson, &output, 0),
        ("Hi there".into(), ok("Hi there"))
    );
}

#[test]
fn antigravity_trusts_its_own_status_over_the_exit_code() {
    let output = lines(&[
        r#"{"event":"init"}"#,
        r#"{"event":"step_update","step_update":{"text_delta":"po"}}"#,
        r#"{"event":"step_update","step_update":{"text_delta":"ng"}}"#,
        r#"{"event":"result","result":{"status":"SUCCESS","response":"pong"}}"#,
    ]);
    assert_eq!(
        decode(CliOutputFormat::AntigravityStreamJson, &output, 0),
        ("pong".into(), ok("pong"))
    );
    let refused = r#"{"event":"result","result":{"status":"INVALID","response":"","error":"invalid --effort"}}"#;
    assert_eq!(
        decode(CliOutputFormat::AntigravityStreamJson, refused, 0).1,
        failure("invalid --effort")
    );
}

#[test]
fn claude_streams_its_thinking_apart_from_the_answer() {
    let output = lines(&[
        r#"{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}}"#,
        r#"{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"17*20=340, "}}}"#,
        r#"{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plus 51."}}}"#,
        r#"{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"391"}}}"#,
        r#"{"type":"result","subtype":"success","is_error":false,"result":"391"}"#,
    ]);
    let (events, answer) = events(CliOutputFormat::ClaudeStreamJson, &output, 0);
    assert_eq!(reasoning(&events), "17*20=340, plus 51.");
    assert_eq!(answer, ok("391"));
}

#[test]
fn tool_calls_in_whole_messages_become_activities_finished_by_their_results() {
    let output = lines(&[
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"schema.sql"}}]}}"#,
        r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"..."}]}}"#,
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"ls\nrm"}}]}}"#,
        r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","is_error":true,"content":"denied"}]}}"#,
        r#"{"type":"result","subtype":"success","is_error":false,"result":"Done"}"#,
    ]);
    let (events, _) = events(CliOutputFormat::ClaudeStreamJson, &output, 0);
    assert_eq!(
        activities(&events),
        [
            "t1 Read · schema.sql Running",
            "t1 Read · schema.sql Done",
            "t2 Bash · ls Running",
            "t2 Bash · ls Failed"
        ]
    );
}

#[test]
fn cursors_thinking_and_tool_calls() {
    let output = lines(&[
        r#"{"type":"thinking","subtype":"delta","text":"Looking at the schema","session_id":"s"}"#,
        r#"{"type":"thinking","subtype":"completed","session_id":"s"}"#,
        r#"{"type":"tool_call","subtype":"started","call_id":"c1","tool_call":{"readToolCall":{"args":{"path":"a.sql"}}}}"#,
        r#"{"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"readToolCall":{"args":{"path":"a.sql"},"result":{"success":{"content":"x"}}}}}"#,
        r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]},"timestamp_ms":1}"#,
        r#"{"type":"result","subtype":"success","is_error":false,"result":"ok"}"#,
    ]);
    let (events, answer) = events(CliOutputFormat::ClaudeStreamJson, &output, 0);
    assert_eq!(reasoning(&events), "Looking at the schema");
    assert_eq!(
        activities(&events),
        ["c1 read · a.sql Running", "c1 read · a.sql Done"]
    );
    assert_eq!(answer, ok("ok"));
}

#[test]
fn codex_reasoning_and_commands() {
    let output = lines(&[
        r#"{"type":"item.completed","item":{"id":"item_0","type":"reasoning","text":"**Planning**"}}"#,
        r#"{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"ls","status":"in_progress"}}"#,
        r#"{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"ls","exit_code":0,"status":"completed"}}"#,
        r#"{"type":"item.started","item":{"id":"item_2","type":"command_execution","command":"cat x","status":"in_progress"}}"#,
        r#"{"type":"item.completed","item":{"id":"item_2","type":"command_execution","command":"cat x","exit_code":1,"status":"failed"}}"#,
        r#"{"type":"item.completed","item":{"id":"item_3","type":"reasoning","text":"**Answering**"}}"#,
        r#"{"type":"item.completed","item":{"id":"item_4","type":"agent_message","text":"pong"}}"#,
    ]);
    let (events, answer) = events(CliOutputFormat::CodexJson, &output, 0);
    assert_eq!(reasoning(&events), "**Planning**\n\n**Answering**");
    assert_eq!(
        activities(&events),
        [
            "item_1 Ran ls Running",
            "item_1 Ran ls Done",
            "item_2 Ran cat x Running",
            "item_2 Ran cat x Failed"
        ]
    );
    assert_eq!(answer, ok("pong"));
}

#[test]
fn gemini_tool_calls() {
    let output = lines(&[
        r#"{"type":"tool_use","tool_name":"read_file","tool_id":"g1","parameters":{"path":"a.sql"}}"#,
        r#"{"type":"tool_result","tool_id":"g1","status":"success","output":"x"}"#,
        r#"{"type":"message","role":"assistant","content":"ok","delta":true}"#,
        r#"{"type":"result","status":"success"}"#,
    ]);
    let (events, _) = events(CliOutputFormat::GeminiStreamJson, &output, 0);
    assert_eq!(
        activities(&events),
        ["g1 read_file · a.sql Running", "g1 read_file · a.sql Done"]
    );
}

#[test]
fn antigravity_streams_its_response_steps_and_shows_other_steps_as_activities() {
    let output = lines(&[
        r#"{"event":"init","conversation_id":"c","init":{"cwd":"/tmp","tools":["ask_question"]}}"#,
        r#"{"event":"step_update","step_update":{"conversation_id":"c","step_index":0,"state":"DONE","step_type":"user_input"}}"#,
        r#"{"event":"step_update","step_update":{"conversation_id":"c","step_index":1,"state":"ACTIVE","step_type":"view_file"}}"#,
        r#"{"event":"step_update","step_update":{"conversation_id":"c","step_index":1,"state":"DONE","step_type":"view_file"}}"#,
        r#"{"event":"step_update","step_update":{"conversation_id":"c","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"**Answer:**\n1"}}"#,
        r#"{"event":"step_update","step_update":{"conversation_id":"c","step_index":2,"state":"DONE","step_type":"agent_response","text_delta":"7 times 23 is 391.\n","duration_seconds":8.25}}"#,
        r#"{"event":"result","result":{"conversation_id":"c","status":"SUCCESS","response":"**Answer:**\n17 times 23 is 391.\n"}}"#,
    ]);
    let (events, answer) = events(CliOutputFormat::AntigravityStreamJson, &output, 0);
    assert_eq!(deltas(&events), "**Answer:**\n17 times 23 is 391.\n");
    assert_eq!(
        activities(&events),
        ["step-1 View file Running", "step-1 View file Done"]
    );
    assert_eq!(answer, ok("**Answer:**\n17 times 23 is 391."));
}

#[test]
fn opencode_parts_become_text_reasoning_and_activities() {
    let output = lines(&[
        r#"{"type":"step_start","timestamp":1,"sessionID":"s","part":{"type":"step-start"}}"#,
        r#"{"type":"reasoning","timestamp":2,"sessionID":"s","part":{"type":"reasoning","text":"Check the schema."}}"#,
        r#"{"type":"tool_use","timestamp":3,"sessionID":"s","part":{"type":"tool","callID":"call_1","tool":"read","state":{"status":"completed","title":"schema.sql","input":{"filePath":"schema.sql"}}}}"#,
        r#"{"type":"text","timestamp":4,"sessionID":"s","part":{"type":"text","text":"Two tables."}}"#,
        r#"{"type":"step_finish","timestamp":5,"sessionID":"s","part":{"type":"step-finish"}}"#,
    ]);
    let (events, answer) = events(CliOutputFormat::OpenCodeJson, &output, 0);
    assert_eq!(reasoning(&events), "Check the schema.");
    assert_eq!(activities(&events), ["call_1 read · schema.sql Done"]);
    assert_eq!(answer, ok("Two tables."));
}

#[test]
fn opencodes_error_event_fails_the_turn() {
    let output = r#"{"type":"error","timestamp":1793428366854,"sessionID":"ses_e6f5","error":{"type":"provider.invalid-request","message":"Upstream request failed: This Go model trains on request data.","status":400}}"#;
    assert_eq!(
        decode(CliOutputFormat::OpenCodeJson, output, 0).1,
        failure("Upstream request failed: This Go model trains on request data.")
    );
}

// Model lists

#[test]
fn model_lists_as_each_cli_prints_them() {
    let cursor = "Available models\n\nauto - Auto (default)\ngpt-5.3-codex - Codex 5.3\n\nTip: use --model <id>\n";
    assert_eq!(
        CliModelList::Cursor.parse(cursor),
        [
            AgentModel::new("auto", Some("Auto")),
            AgentModel::new("gpt-5.3-codex", Some("Codex 5.3"))
        ]
    );
    let grok = "You are not authenticated.\n\nDefault model: grok-4.6\n\nAvailable models:\n  * grok-4.6 (default)\n  - grok-4.5\n";
    assert_eq!(
        CliModelList::Grok
            .parse(grok)
            .into_iter()
            .map(|m| m.id)
            .collect::<Vec<_>>(),
        ["grok-4.6", "grok-4.5"]
    );
    let opencode =
        "\u{1b}[2mgitlab/duo-chat-opus-5\u{1b}[0m\nkimi-code-plan-cn/k3\nsome warning line\n";
    assert_eq!(
        CliModelList::OpenCode
            .parse(opencode)
            .into_iter()
            .map(|m| m.id)
            .collect::<Vec<_>>(),
        ["gitlab/duo-chat-opus-5", "kimi-code-plan-cn/k3"]
    );
    let agy = "Fetching available models...\ngemini-4-argon-high\tGemini 4 Argon (High)\nclaude-opus-5-5-low\tClaude Opus 5.5 (Low)\n";
    assert_eq!(
        CliModelList::Antigravity.parse(agy),
        [
            AgentModel::new("gemini-4-argon-high", Some("Gemini 4 Argon (High)")),
            AgentModel::new("claude-opus-5-5-low", Some("Claude Opus 5.5 (Low)"))
        ]
    );
    let codex = r#"{"models":[{"slug":"gpt-reserve","display_name":"GPT-Reserve","visibility":"hide"},{"slug":"gpt-5.6-terra","display_name":"GPT-5.6-Terra","visibility":"list"},{"slug":"gpt-6"}]}"#;
    assert_eq!(
        CliModelList::Codex.parse(codex),
        [
            AgentModel::new("gpt-5.6-terra", Some("GPT-5.6-Terra")),
            AgentModel::new("gpt-6", None)
        ]
    );
    assert!(CliModelList::Codex.parse("error: not logged in").is_empty());
    assert!(
        CliModelList::Antigravity
            .parse("Not signed in. Run agy login.")
            .is_empty()
    );
}

#[test]
fn a_picked_model_sits_before_the_prompt_and_a_blank_one_adds_nothing() {
    let plain = CliAgentPreset::Codex.arguments("hi", None);
    assert!(!plain.contains(&"--model".to_owned()));
    assert_eq!(CliAgentPreset::Codex.arguments("hi", Some("  ")), plain);
    let chosen = CliAgentPreset::Codex.arguments("hi", Some("gpt-6"));
    assert_eq!(chosen[chosen.len() - 3..], ["--model", "gpt-6", "hi"]);
    assert_eq!(chosen.len(), plain.len() + 2);
    assert!(!CliAgentPreset::Hermes.chooses_model());
    assert_eq!(
        CliAgentPreset::Hermes.arguments("hi", None),
        CliAgentPreset::Hermes.arguments("hi", Some("x"))
    );
    assert!(CliAgentPreset::Gemini.chooses_model());
}

// The adapter, against small shell scripts standing in for a CLI agent.

#[cfg(unix)]
fn script(dir: &std::path::Path, body: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    static COUNTER: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let path = dir.join(format!(
        "agent-{}",
        COUNTER.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
    ));
    std::fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    path
}

fn request(dir: &std::path::Path, text: &str) -> AgentRequest {
    AgentRequest {
        messages: vec![AgentMessage::new(Role::User, text)],
        working_directory: Some(dir.join("work")),
        ..AgentRequest::default()
    }
}

#[cfg(unix)]
#[tokio::test]
async fn the_adapter_runs_the_clis_list_command() {
    let dir = tempfile::tempdir().unwrap();
    let grok = script(
        dir.path(),
        "[ \"$1\" = models ] || exit 1\nprintf 'Available models:\\n  * grok-4.6 (default)\\n  - grok-4.5\\n'",
    );
    let ids: Vec<String> = CliAgentAdapter::new(CliAgentPreset::Grok, grok.clone())
        .models()
        .await
        .into_iter()
        .map(|m| m.id)
        .collect();
    assert_eq!(ids, ["grok-4.6", "grok-4.5"]);
    // Hermes takes no model, so it isn't asked for a list.
    assert!(
        CliAgentAdapter::new(CliAgentPreset::Hermes, grok)
            .models()
            .await
            .is_empty()
    );
}

#[test]
fn with_model_leaves_an_agent_that_cant_choose_untouched() {
    let hermes: Arc<dyn AgentAdapter> = Arc::new(CliAgentAdapter::new(
        CliAgentPreset::Hermes,
        PathBuf::from("/nowhere"),
    ));
    assert!(Arc::ptr_eq(&with_model(hermes.clone(), Some("x")), &hermes));
}

#[cfg(unix)]
#[tokio::test]
async fn streams_stdout_and_completes_with_the_whole_answer() {
    let dir = tempfile::tempdir().unwrap();
    // The prompt is the last argument; no shell sits between us and the agent, so it arrives verbatim.
    let agent = CliAgentAdapter::new(
        CliAgentPreset::Hermes,
        script(
            dir.path(),
            "for a; do last=$a; done; printf 'you said: '; printf '%s' \"$last\"",
        ),
    );
    let events = agent
        .send(request(dir.path(), "it's $HOME `x`"))
        .collect()
        .await
        .unwrap();
    assert_eq!(events.first(), Some(&AgentEvent::Thinking));
    assert_eq!(
        events.last(),
        Some(&AgentEvent::Completed("you said: it's $HOME `x`".into()))
    );
    assert_eq!(deltas(&events), "you said: it's $HOME `x`");
    assert!(dir.path().join("work").is_dir());
}

#[cfg(unix)]
#[tokio::test]
async fn a_failed_run_reports_the_line_that_names_the_problem() {
    let dir = tempfile::tempdir().unwrap();
    let agent = CliAgentAdapter::new(
        CliAgentPreset::Hermes,
        script(
            dir.path(),
            "echo '#####' >&2; printf '\\033[91mError: \\033[0mnot logged in\\n' >&2; echo 'bye' >&2; exit 3",
        ),
    );
    let error = agent
        .send(request(dir.path(), "hi"))
        .collect()
        .await
        .unwrap_err();
    assert_eq!(error.0, "Error: not logged in");
}

#[cfg(unix)]
#[tokio::test]
async fn dropping_the_stream_kills_the_agent() {
    let dir = tempfile::tempdir().unwrap();
    let marker = dir.path().join("finished");
    let agent = CliAgentAdapter::new(
        CliAgentPreset::Hermes,
        script(
            dir.path(),
            &format!("printf 'started'; sleep 3; touch '{}'", marker.display()),
        ),
    );
    let mut stream = agent.send(request(dir.path(), "hi"));
    let first = tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            match stream.next().await {
                Some(Ok(AgentEvent::TextDelta(t))) => return t,
                Some(_) => continue,
                None => panic!("ended"),
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(first, "started");
    drop(stream);
    tokio::time::sleep(std::time::Duration::from_secs(4)).await;
    assert!(!marker.exists(), "the agent was killed before it finished");
}

#[test]
fn earlier_turns_travel_in_the_prompt() {
    let request = AgentRequest {
        messages: vec![
            AgentMessage::new(Role::User, "count users"),
            AgentMessage::new(Role::Assistant, "SELECT count(*) FROM users"),
            AgentMessage::new(Role::User, "only active ones"),
        ],
        system: Some("Be brief.".into()),
        reminder: Some("Don't run anything.".into()),
        ..AgentRequest::default()
    };
    assert_eq!(
        request.to_prompt(),
        "Be brief.\n\nConversation so far:\nUser: count users\n\nAssistant: SELECT count(*) FROM users\n\nUser: <system-reminder>\nDon't run anything.\n</system-reminder>\n\nonly active ones"
    );
}

#[test]
fn activity_states_are_named_like_kotlin() {
    assert_eq!(
        serde_json::to_string(&ActivityState::Done).unwrap(),
        "\"Done\""
    );
}
