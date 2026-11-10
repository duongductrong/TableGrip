//! `ProviderAgentTest`, ported. Everything runs against recorded streams or a server on localhost: no provider is
//! ever called.

use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tg_agent::provider::{
    AgentMode, ProviderAgentAdapter, ProviderConfig, ProviderProtocol, StreamDecoder,
};
use tg_agent::{
    AgentAdapter, AgentError, AgentEvent, AgentMessage, AgentRegistry, AgentRequest,
    AgentTransport, Role,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

type Recorded = Arc<Mutex<Vec<(String, HashMap<String, String>, String)>>>;

/// A provider on localhost answering every request with `status` and `body`, recording what it was sent.
async fn serve(status: u16, body: &'static str, content_type: &'static str) -> (String, Recorded) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let recorded: Recorded = Arc::default();
    let log = recorded.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            let mut data = Vec::new();
            let mut buffer = [0u8; 8192];
            let (head, body_start) = loop {
                let n = stream.read(&mut buffer).await.unwrap_or(0);
                if n == 0 {
                    break (String::new(), 0);
                }
                data.extend_from_slice(&buffer[..n]);
                if let Some(i) = data.windows(4).position(|w| w == b"\r\n\r\n") {
                    break (String::from_utf8_lossy(&data[..i]).into_owned(), i + 4);
                }
            };
            let mut lines = head.split("\r\n");
            let path = lines
                .next()
                .unwrap_or("")
                .split(' ')
                .nth(1)
                .unwrap_or("")
                .to_owned();
            let headers: HashMap<String, String> = lines
                .filter_map(|l| l.split_once(':'))
                .map(|(k, v)| (k.trim().to_lowercase(), v.trim().to_owned()))
                .collect();
            let length: usize = headers
                .get("content-length")
                .and_then(|l| l.parse().ok())
                .unwrap_or(0);
            let mut received = data[body_start..].to_vec();
            while received.len() < length {
                let n = stream.read(&mut buffer).await.unwrap_or(0);
                if n == 0 {
                    break;
                }
                received.extend_from_slice(&buffer[..n]);
            }
            log.lock().unwrap().push((
                path,
                headers,
                String::from_utf8_lossy(&received).into_owned(),
            ));
            let response = format!(
                "HTTP/1.1 {status} X\r\ncontent-type: {content_type}\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = stream.write_all(response.as_bytes()).await;
        }
    });
    (format!("http://127.0.0.1:{port}"), recorded)
}

fn adapter(
    protocol: ProviderProtocol,
    base: &str,
    key: Option<&'static str>,
) -> ProviderAgentAdapter {
    ProviderAgentAdapter::new(
        ProviderConfig::of(protocol, base, ""),
        Arc::new(move |_| key.map(str::to_owned)),
    )
}

fn request() -> AgentRequest {
    AgentRequest {
        messages: vec![
            AgentMessage::new(Role::User, "List tables"),
            AgentMessage::new(Role::Assistant, "users, orders"),
            AgentMessage::new(Role::User, "Count users"),
            AgentMessage::new(Role::User, "Only active ones"),
        ],
        system: Some("You help with SQL.".into()),
        reminder: Some("Suggest, don't run.".into()),
        ..AgentRequest::default()
    }
}

fn decode(protocol: ProviderProtocol, payloads: &[&str]) -> Result<(String, String), AgentError> {
    let mut decoder = StreamDecoder::new(protocol, "Provider");
    let mut text = String::new();
    for p in payloads {
        for e in decoder.data(p)? {
            if let AgentEvent::TextDelta(t) = e {
                text.push_str(&t);
            }
        }
    }
    Ok((text, decoder.finish()?))
}

#[test]
fn anthropic_stream_yields_text_deltas() {
    let result = decode(
        ProviderProtocol::Anthropic,
        &[
            r#"{"type":"message_start","message":{"id":"msg_1"}}"#,
            r#"{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}"#,
            r#"{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}"#,
            r#"{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"#,
            r#"{"type":"message_stop"}"#,
        ],
    );
    assert_eq!(result, Ok(("Hello".into(), "Hello".into())));
}

#[test]
fn anthropic_error_event_and_refusal_fail_the_turn() {
    let error = decode(
        ProviderProtocol::Anthropic,
        &[r#"{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"#],
    );
    assert_eq!(error, Err(AgentError("Overloaded".into())));
    let refusal = decode(
        ProviderProtocol::Anthropic,
        &[r#"{"type":"message_delta","delta":{"stop_reason":"refusal"}}"#],
    );
    assert_eq!(
        refusal,
        Err(AgentError("Provider declined to answer.".into()))
    );
}

#[test]
fn openai_stream_yields_content_deltas_until_done() {
    let result = decode(
        ProviderProtocol::OpenAi,
        &[
            r#"{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}"#,
            r#"{"choices":[{"index":0,"delta":{"content":"SELECT"}}]}"#,
            r#"{"choices":[{"index":0,"delta":{"content":" 1"},"finish_reason":"stop"}]}"#,
            "[DONE]",
        ],
    );
    assert_eq!(result, Ok(("SELECT 1".into(), "SELECT 1".into())));
}

#[test]
fn reasoning_streams_apart_from_the_answer_in_both_protocols() {
    let reasoning = |protocol, payloads: &[&str]| {
        let mut decoder = StreamDecoder::new(protocol, "Provider");
        let mut thought = String::new();
        for p in payloads {
            for e in decoder.data(p).unwrap() {
                if let AgentEvent::ReasoningDelta(t) = e {
                    thought.push_str(&t);
                }
            }
        }
        (thought, decoder.finish().unwrap())
    };
    assert_eq!(
        reasoning(
            ProviderProtocol::Anthropic,
            &[
                r#"{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Count rows."}}"#,
                r#"{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"42"}}"#,
            ]
        ),
        ("Count rows.".into(), "42".into())
    );
    assert_eq!(
        reasoning(
            ProviderProtocol::OpenAi,
            &[
                r#"{"choices":[{"index":0,"delta":{"reasoning_content":"Count "}}]}"#,
                r#"{"choices":[{"index":0,"delta":{"reasoning":"rows."}}]}"#,
                r#"{"choices":[{"index":0,"delta":{"content":"42"}}]}"#,
                "[DONE]",
            ]
        ),
        ("Count rows.".into(), "42".into())
    );
}

#[tokio::test]
async fn anthropic_request_carries_system_alternating_turns_and_the_reminder() {
    let (base, recorded) = serve(
        200,
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"42\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        "text/event-stream",
    )
    .await;
    let events = adapter(ProviderProtocol::Anthropic, &base, Some("test-key"))
        .send(request())
        .collect()
        .await
        .unwrap();
    assert_eq!(events.last(), Some(&AgentEvent::Completed("42".into())));
    let (path, headers, body) = recorded.lock().unwrap()[0].clone();
    assert_eq!(path, "/v1/messages");
    assert_eq!(
        headers.get("x-api-key").map(String::as_str),
        Some("test-key")
    );
    let json: Value = serde_json::from_str(&body).unwrap();
    assert_eq!(json["model"], "claude-opus-5-5");
    assert_eq!(json["system"], "You help with SQL.");
    let messages = json["messages"].as_array().unwrap();
    assert_eq!(
        messages
            .iter()
            .map(|m| m["role"].as_str().unwrap())
            .collect::<Vec<_>>(),
        ["user", "assistant", "user"]
    );
    let last = messages.last().unwrap()["content"].as_str().unwrap();
    assert!(
        last.starts_with("<system-reminder>\nSuggest, don't run.\n</system-reminder>"),
        "{last}"
    );
    assert!(last.ends_with("Count users\n\nOnly active ones"), "{last}");
}

#[tokio::test]
async fn openai_request_puts_the_system_first_and_sends_no_key_when_there_is_none() {
    let (base, recorded) = serve(
        200,
        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n",
        "text/event-stream",
    )
    .await;
    let events = adapter(ProviderProtocol::OpenAi, &base, None)
        .send(request())
        .collect()
        .await
        .unwrap();
    assert_eq!(events.last(), Some(&AgentEvent::Completed("ok".into())));
    let (path, headers, body) = recorded.lock().unwrap()[0].clone();
    assert_eq!(path, "/chat/completions");
    assert!(!headers.contains_key("authorization"));
    let json: Value = serde_json::from_str(&body).unwrap();
    let roles: Vec<&str> = json["messages"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["role"].as_str().unwrap())
        .collect();
    assert_eq!(roles, ["system", "user", "assistant", "user"]);
}

#[tokio::test]
async fn an_api_error_is_reported_with_the_providers_message() {
    let (base, _) = serve(
        401,
        r#"{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"#,
        "application/json",
    )
    .await;
    let error = adapter(ProviderProtocol::Anthropic, &base, Some("k"))
        .send(request())
        .collect()
        .await
        .unwrap_err();
    assert_eq!(error.0, "Anthropic: invalid x-api-key");
}

#[tokio::test]
async fn anthropic_without_a_key_fails_before_sending_anything() {
    let (base, recorded) = serve(200, "", "text/event-stream").await;
    let error = adapter(ProviderProtocol::Anthropic, &base, None)
        .send(request())
        .collect()
        .await
        .unwrap_err();
    assert!(error.0.contains("ANTHROPIC_API_KEY"));
    assert!(recorded.lock().unwrap().is_empty());
}

#[tokio::test]
async fn provider_mode_offers_the_configured_provider_and_discovery_sends_nothing() {
    let (base, recorded) = serve(200, "", "text/event-stream").await;
    let registry = AgentRegistry::standard(
        Arc::new(|| AgentMode::Provider),
        Arc::new(move || ProviderConfig::of(ProviderProtocol::OpenAi, &base, "local-model")),
        Arc::new(|_| None),
    );
    let agents = registry.discover(false).await;
    assert_eq!(
        agents
            .iter()
            .map(|a| a.descriptor().id.clone())
            .collect::<Vec<_>>(),
        ["provider:openai"]
    );
    assert_eq!(agents[0].descriptor().transport, AgentTransport::Http);
    assert_eq!(agents[0].descriptor().name, "local-model");
    assert!(recorded.lock().unwrap().is_empty());
}

#[test]
fn settings_written_before_the_choice_existed_stay_on_the_cli_agents() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("settings.json");
    std::fs::write(&file, r#"{"theme":"Dark","agent":"cli:codex"}"#).unwrap();
    let settings = tg_core::settings::SettingsStore::new(&file).load();
    assert_eq!(settings.agent, "cli:codex");
    assert_eq!(AgentMode::of(&settings.agent_mode), AgentMode::Cli);
    assert_eq!(
        settings.agent_mode,
        tg_core::settings::Settings::default().agent_mode
    );
    assert_eq!(AgentMode::of("Nonsense"), AgentMode::Cli);
}
