# Programming Knowledge Base

This file stores durable engineering knowledge learned across projects so future ChatGPT conversations and coding agents can reuse proven patterns instead of rediscovering them.

It is not a project status file. Project-specific state belongs in `PROJECTS.md` / project repositories. This file captures reusable technical lessons, architecture patterns, testing discipline, and implementation decisions that proved useful.

## Core engineering discipline

1. Inspect the existing code, recent commits, branches, PRs, CI, and runtime state before editing.
2. Preserve working architecture and user data unless a redesign is deliberate and justified.
3. Make small changes that can be verified independently.
4. Never report code as working merely because it compiles.
5. Treat verification as separate levels:
   - source/code review,
   - unit tests,
   - lint/static checks,
   - build/package success,
   - signing/package verification,
   - emulator/device runtime verification,
   - end-to-end real-world behavior.
6. When something fails, inspect the actual log/stack trace/job output first; do not debug by guesswork.
7. Protect the product goal rather than a single technical route. A blocked implementation path is not automatically a blocked project.
8. Experimental/debug mechanisms must be isolated from release behavior.

## Android architecture lessons

### Scheduling and alarms

- Use `AlarmManager` for time-based local execution when the product needs device-local scheduling.
- On Android 12+ exact alarms require checking `canScheduleExactAlarms()`; exact scheduling can be unavailable even if the rest of the app works.
- A robust scheduler should attempt exact scheduling when allowed and fall back to inexact scheduling when Android rejects exact alarms.
- The scheduling API should return an explicit result object such as:
  - scheduled / failed,
  - exact / inexact,
  - actual trigger timestamp,
  - error reason.
- Do not tell the UI that scheduling succeeded until Android has actually accepted the alarm registration call.
- PendingIntent identity matters. Stable request codes and intent targets are required for reliable cancellation/replacement.
- After boot/app restart, restore pending WAITING/RETRYING work from persistent state instead of assuming alarms survived.
- Quiet hours, retry delays, next-recipient delays, and recurrence should be policy layers around the scheduler rather than hard-coded into one execution function.

### Task state machines

For automation apps, model task execution explicitly. A useful durable state vocabulary is:

`WAITING -> RETRYING -> SUCCESS / PARTIAL / FAILED / CANCELLED`

Keep per-recipient progress separately from whole-task state. Store:

- current recipient index,
- attempt count,
- max attempts,
- last attempt time,
- completion time,
- last error,
- success/failure counters,
- bounded delivery-event history.

For multiple recipients, one recipient failure should not automatically erase progress for the rest. A task can finish as PARTIAL when some deliveries succeed and some fail.

### Retry/watchdog design

- Retry policy should be a separate component so backoff can change without rewriting task execution.
- Long-running or UI-driven automation should have a watchdog that can detect stalled execution and move the task to a recoverable state.
- Cancellation must cancel the primary alarm plus any watchdog/legacy PendingIntents associated with the same task.
- Restore logic should distinguish a fresh WAITING task, a WAITING task already partway through its recipients, and a RETRYING task.

### Accessibility-driven automation

- Accessibility is an execution adapter, not the scheduler itself.
- Keep scheduling/storage/execution state independent from app-specific UI automation.
- App adapters should isolate WhatsApp-specific selectors/navigation from the general automation engine so other apps can be added later.
- Accessibility success must be device-tested; CI cannot prove that a target app UI opened, matched selectors, or sent a message.
- UI changes in third-party apps are expected. Diagnostics and logs must make selector/navigation failures visible.

### Persistence

- Small local prototypes can persist task state using `SharedPreferences` + JSON, but the data model should remain explicit and migration-aware.
- Read old fields defensively to preserve backward compatibility after schema changes.
- Keep histories bounded to avoid unbounded preference growth.
- If task volume or relational complexity grows substantially, migrate to Room/SQLite rather than indefinitely expanding JSON blobs.

### Attachments and Android URIs

- For user-selected files, prefer the Storage Access Framework (`ACTION_OPEN_DOCUMENT`).
- Request persistable read permission when available so scheduled sending can still access attachments later.
- Persist both URI and MIME type.
- Providers may offer only temporary access, so attachment failures must be reported at execution time rather than silently ignored.

## Debug and QA architecture

### Debug-only control surface

For device QA, put test tooling under `src/debug` so release builds do not include it.

A useful debug controller can expose commands such as:

- `status`
- `list`
- `get`
- `create`
- `cancel`
- `delete`
- `cleanup`

The test harness should:

- clearly identify test-created tasks,
- use a dedicated ID range or registry,
- be able to remove test data safely,
- return machine-readable JSON,
- expose exact/inexact alarm result,
- expose task state and delivery history,
- avoid adding test-only APIs to production classes when possible.

If a debug BroadcastReceiver must be exported for ADB/Termux testing, restrict it to debug builds and require a test credential. Never treat that as a production remote-control mechanism.

### QA separation

GitHub Actions can verify compilation and deterministic tests. Real Android QA is still required for:

- alarm firing at the intended time,
- Doze/background behavior,
- exact alarm permission behavior,
- Accessibility interaction,
- WhatsApp/target-app UI traversal,
- multi-recipient real sends,
- attachment delivery,
- device lock/unlock behavior,
- OEM battery optimization behavior.

## GitHub Actions / Android CI lessons

A strong baseline pipeline for RUN-style Android projects is:

1. checkout,
2. Java 17 setup,
3. Android SDK/platform tools setup,
4. Gradle setup,
5. unit tests,
6. Android lint,
7. debug APK build,
8. APK signing-certificate verification,
9. artifact upload.

Other durable lessons:

- Use a persistent debug signing key in CI when repeated device upgrades/install-over-existing-app behavior matters.
- Version code can be tied to the GitHub Actions run number for monotonically increasing debug builds.
- Use workflow concurrency so stale builds on the same branch are cancelled when a newer commit arrives.
- A green build is evidence for build correctness, not proof of end-to-end Android automation correctness.

## Kotlin / Android code style

- Prefer small objects/classes with one responsibility: store, scheduler, policy, executor, adapter, view.
- Return structured results instead of relying only on exceptions or UI strings.
- Keep Android framework interaction at the edges and business/policy logic testable with JVM unit tests where possible.
- Use defensive parsing for persisted JSON and imported CSV/text.
- Keep user-facing Arabic strings separate from internal state constants where practical.
- Avoid duplicate implementations of the same debug/control logic; centralize behavior and let transport layers delegate to it.

## Local AI integration lessons

RUN uses local-AI-oriented components experimentally. Durable principles:

- AI assistance should not block core scheduling functionality.
- Load heavy AI workers lazily, only when first needed.
- Validate model files before import/use.
- Keep generated text as reviewed drafts rather than silently triggering automated outbound communication.
- Separate prompt construction, model storage, inference engine, and UI composer so each can evolve independently.

## Bridge / device-control programming lessons

The Termux Bridge is infrastructure, not an Android app feature.

- Bind local services to `127.0.0.1` by default.
- Public tunnel exposure should be deliberate and minimized.
- FastMCP host-header security must match the actual externally used hostname; a rotated Quick Tunnel hostname can cause HTTP 421 / Invalid Host errors.
- Quick Tunnel URLs are temporary and should not be hard-coded as permanent infrastructure.
- Never confuse a tunnel being alive with the MCP/service endpoint being healthy; test the complete path.
- Keep command execution and screen visibility as separate layers:
  - Bridge = execution/files/commands,
  - Eyes = screen visibility/control.
- Do not store active tokens, passwords, private keys, or tunnel credentials in this knowledge repository.

## Repository and cross-session continuity

- The canonical project registry is `fateh1989/fateh1989/PROJECTS.md`.
- Any project repository should contain an `AGENTS.md` handoff when long-lived continuation matters.
- A future agent must read central context plus the target repository before making changes.
- Repository names may differ from product names; resolve them through the registry rather than guessing.
- When a durable technical lesson emerges, add it here. When only project state changes, update the project/status files instead.

## Data-system / reverse-index architecture lesson

For projects such as heavy-equipment reverse parts lookup, model relationships rather than only pages/screens. A useful normalized graph is:

`part number <-> manufacturer <-> machine/model <-> serial range <-> assembly <-> diagram/reference <-> supersession/cross-reference`

Keep source/provenance with each relationship so conflicting catalog data can be compared instead of overwritten blindly.

## Definition of done

For programming work, "done" should specify the verification level achieved. Examples:

- "Implemented, not yet built"
- "Unit tests pass"
- "Lint + APK build pass"
- "Signed APK artifact produced"
- "Installed on Android"
- "Real-device workflow verified end-to-end"

Do not collapse these into a single vague claim that the program "works".
