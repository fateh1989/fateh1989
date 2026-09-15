# Working Rules for Future ChatGPT Sessions

These are durable workflow preferences for project work with this account. They are meant to prevent new conversations from restarting work, guessing, or repeating already-solved setup.

## Engineering

1. Inspect the existing repository/device state before changing anything.
2. Do not rebuild a known project from scratch unless explicitly requested.
3. Make small, reviewable changes and verify after each meaningful step.
4. Never claim that code works before it has actually been tested at the appropriate level.
5. Distinguish clearly between:
   - code written only,
   - CI/unit/lint/build verification,
   - real Android device verification.
6. If CI or runtime fails, read the actual logs and fix the cause instead of guessing.
7. Preserve architecture and data unless a redesign is deliberate and explained.
8. Do not silently use temporary hacks or patches as if they were permanent solutions.
9. If the current implementation path is blocked, preserve the goal and consider another architecture/tool/privilege/execution path before declaring the project impossible.
10. For Android app builds, prefer GitHub Actions for compilation and use the phone/Termux primarily for installation and real-device testing.

## Tool usage

- If ChatGPT has an available connector/tool that can inspect or change the repository/device, use it rather than asking the user to manually repeat work.
- Before touching RUN, resolve it to `fateh1989/1122` and read `AGENTS.md` plus the latest commits/CI.
- Before troubleshooting the Android bridge, read `BRIDGE.md` and verify current live state.
- Do not confuse the Bridge with an application repository.

## Communication

- Default language: Arabic unless the user requests otherwise.
- Keep explanations direct and compact; avoid unnecessary lectures or repeated caveats.
- Put commands, links, and text that must be copied into copy-friendly code blocks.
- During longer technical work, report concrete progress: what file/action is being worked on, what succeeded, what failed, and what is next.
- Do not ask for repeated screenshots when text/logs can answer the question.

## Research

- For facts that can change, especially software/services/pricing/current websites, use live web research rather than relying only on stored knowledge.
- For heavy-equipment parts research, the saved goal and sources are in `RESEARCH.md`; current availability and pricing must still be verified live.

## Cost / account preferences

- Prefer free and open-source solutions when practical.
- Avoid workflows that require paid services unless the user explicitly asks for them.
- Avoid unnecessary SMS-based MFA dependencies where another reasonable path exists.

## Project-state discipline

When a durable decision changes — repository mapping, branch strategy, bridge path, project status, architecture, or major workflow rule — update the appropriate canonical file in `fateh1989/fateh1989` so future sessions have the same handoff.
