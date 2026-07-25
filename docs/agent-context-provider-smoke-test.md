# Agent context real-provider smoke test

Use this runbook only after a user-owned test model configuration and a disposable project are available. It deliberately does not contain credentials or send provider requests automatically.

## Goal

Verify the live path that automated tests cannot prove:

- a real provider receives one small Agent request;
- `LAST_ACTUAL_REQUEST` is visible in the context dialog;
- `NEXT_REQUEST_ESTIMATE` is clearly distinct and does not send a request;
- manual compaction persists the expected conversation events;
- a full backend restart does not mix old and new runtime state classes.

## Start from one fresh artifact

1. Stop all old IDE Run/Debug sessions and Java processes for this backend.
2. In PowerShell, run:

```powershell
cd D:\LabexAgentackend
.\scripts\start-release.ps1
```

The script runs `mvn clean package` before starting the generated JAR. This is required after enum or state-machine changes; do not rely on HotSwap for such changes.

## Test with a small, disposable conversation

1. Log in, open a disposable project, and select a low-cost provider configuration.
2. Send one short request without tools, such as a request to summarize one small source file.
3. Open **LLM context** -> **Actual sent context** and confirm:
   - `previewSource` is `LAST_ACTUAL_REQUEST`;
   - sections are present and secrets are redacted;
   - the displayed model matches the selected model.
4. Type, but do not send, the next short request. Open **Next request estimate** and confirm:
   - `previewSource` is `NEXT_REQUEST_ESTIMATE`;
   - the UI says the estimate was not sent to the LLM;
   - the draft is marked as included;
   - no new chat message, Agent task, or provider usage appears before Send is clicked.
5. Trigger manual compaction from the conversation menu. Confirm the timeline contains:
   - `COMPACTION_STARTED`;
   - `COMPACTION_SUMMARY`;
   - `COMPACTION_COMPLETED`.
   If the selected compaction model is unavailable, confirm the completed strategy reports deterministic fallback instead.

## Optional automatic-compaction check

Only perform this against a disposable conversation and a model configuration with a conservative context window. Configure a lower valid compaction threshold, produce enough harmless context to cross it, then verify that the context status and timeline describe the compaction reason and preserve the latest turns.

## Record evidence

Capture, without copying secrets:

- backend startup log showing the Agent run-state linkage check;
- one `LAST_ACTUAL_REQUEST` screenshot or payload summary;
- one `NEXT_REQUEST_ESTIMATE` screenshot or payload summary;
- compaction timeline event names and strategy;
- the model/configuration name, not its API key.
