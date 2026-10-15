# Engineering rules for AI coding sessions

1. Read `PLAN.md`, `DECISIONS.md`, `PROGRESS.md`, and the relevant source/tests before changes. Distinguish verified implementation from planned design. Record new architecture choices with numbered decision IDs.
2. Preserve existing observable behavior unless an approved change expressly alters it. Migrate every affected caller and contract test together; avoid compatibility shims and unrelated refactors.
3. Make small, reviewable, end-to-end changes. Keep API, worker, migrations, frontend, and operations aligned only when the change requires it. Never mark a scaffold or untested wiring as complete.
4. Test the changed behavior with focused JUnit/integration tests and a safe smoke run; use Testcontainers for real PostgreSQL/Valkey integration where applicable. Prefer deterministic DNS fixtures and local fakes. Report commands, results, and unverified paths accurately.
5. Never send email, access personal email accounts, or probe external SMTP hosts in development/tests. Keep SMTP disabled by default unless an explicit approved policy and controls exist. Avoid logging or exposing full email addresses, credentials, or bounce content.
6. Treat database jobs as at-least-once: bounded leases/retries, idempotent writes, and tested crash/reclaim behavior. Bound virtual-thread work, external I/O, and cache use; PostgreSQL remains authoritative.
7. Preserve the original MIT license and `Copyright (c) 2026 Aasheesh Verma` attribution. Do not delete upstream notices. Never commit or push unless asked.
8. Keep `PLAN.md` phases/risks/acceptance evidence, `DECISIONS.md` decisions/statuses/trade-offs, `PROGRESS.md` verified findings/next actions, and user-facing docs synchronized whenever work changes reality. Do not describe proposals as shipped functionality; record commands and unverified paths accurately.
9. Optional local project skills, when installed, live in `.agents/skills/`; OMP reads them without an extra agent-specific skills target. If the local code graph is configured in `.omp/mcp.json`, its index belongs in ignored `.code-review-graph/`; rebuild it after source changes when graph results matter.
10. Use treehouse only for requested parallel-agent isolation. Do not run `gnhf` or initialize the no-mistakes gate without explicit approval: their workflows can commit, reset, push, or open PRs. Firstmate is staged separately but requires a Linux/macOS environment (or configured WSL on Windows).

## Git and Pull Request Workflow

1. Every approved implementation milestone must use a dedicated feature branch created from the latest main branch.
2. Keep each pull request small, focused, and independently reviewable.
3. Before committing, run the relevant tests and inspect git status, git diff --check, and the complete diff.
4. Record changed files, test commands, results, known limitations, and architectural decisions in PROGRESS.md and DECISIONS.md as appropriate.
5. Never commit, push, open a pull request, or merge without explicit user approval for that action.
6. After approval, create a descriptive commit and push the feature branch to origin, never upstream.
7. Open a pull request against main and review the full PR diff, CI results, and potential regressions.
8. Do not merge a PR until the user approves it and required checks pass.
9. After merging, update the local main branch before starting the next feature.
10. Never force-push, rewrite upstream history, or include secrets, private email lists, or generated personal data in commits.
