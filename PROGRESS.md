# MailGuard progress

Status as of 2026-10-15: architecture documentation only. Phase 0 source audit documented; Phases 1–5 in [PLAN.md](PLAN.md) are not implemented. [DECISIONS.md](DECISIONS.md) separates the agreed bulk flow from provisional limits and other proposals. Do not treat design approval as shipped behavior.

## Verified baseline

- Inspected the tracked source, `pom.xml`, `Dockerfile`, `README.md`, and `src/test/`. Root Maven build declares Spring Boot 4.1.0, Java 17, MySQL/JPA, dnsjava 3.6.3 and springdoc 2.8.9. Dockerfile and README claim Java 23; no Java 21 alignment yet.
- `EmailValidationHistoryController` defines `POST /api/email/validate` and `POST /api/email/test`. One `MailGuardApplication` process handles requests; no worker, bulk API, frontend, PostgreSQL/Flyway, Valkey, Compose, CI or Testcontainers is present in inspected files.
- `EmailValidationHistoryService` performs syntax, two MX lookups, an automatic port-25 SMTP probe and a JPA history write synchronously. `EmailFormatValidator` permits control characters; `SmtpVerificationService` includes the email in logs and `RCPT TO`. `GlobalExceptionHandler` returns exception text. These are source observations, not live endpoint results; no SMTP probe or personal mailbox access was performed.
- MySQL configuration requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; `ddl-auto=update` and SQL logging are enabled. The only test is `MailguardApplicationTests.contextLoads`. The earlier audit recorded `mvn test -B -q -Dspring.test.context.failure.threshold=1`: 1 test, 0 failures, 1 error because the datasource URL was not JDBC. It was not rerun in this documentation pass; no runtime API or 15,000-row processing is verified.
- Upstream README names MIT and `Copyright (c) 2026 Aasheesh Verma`, but upstream has no full `LICENSE` and `pom.xml` has an empty license entry. This derivative adds canonical MIT text with that attribution in `LICENSE`; upstream license provenance remains unverified. `upstream` is the original remote, `origin` is `https://github.com/soumaydutt/mail-intelligence-platform.git`, and `gh auth status` confirms `soumaydutt` is signed in locally. GitHub's upstream license endpoint returned 404 and metadata reports `license: null`.

## Design progress (not implementation)

- The user agreed the ordered bulk flow in [PLAN.md](PLAN.md): bounded streaming CSV with original ordinals, normalization and visible duplicates/rejections, one PostgreSQL job per accepted unique import contact, fenced `SKIP LOCKED` claims, syntax then DNS then authorized evidence, atomic evidence/result/completion with stale-write rejection and transient retry, and reason-coded progress/export in input order. This flow is approved as architecture only; no runtime path is implemented.
- D-008, D-013 and D-015 in [DECISIONS.md](DECISIONS.md) record the accepted flow. D-014 and D-016 remain proposed; D-017 records the unresolved upstream licensing risk alongside the user's publication instruction. CSV limits, normalization, lease/backoff constants, access and retention remain provisional.
- Updated [AGENTS.md](AGENTS.md) to require synchronized plan, decisions, progress and user-facing documentation.

## Baseline publication scope and checks

- Candidate files: `AGENTS.md`, `PLAN.md`, `DECISIONS.md`, `PROGRESS.md`, `README.md`, `LICENSE`, and `.gitignore`. The README explains upstream provenance and points to the new origin; `LICENSE` contains canonical MIT text with upstream attribution. Local `.agents/`, `.omp/`, and `skills-lock.json` are not part of this baseline.
- Documentation smoke check (Python assertions): local Markdown links, whitespace, common credential-token/key patterns in the seven candidate files, MIT attribution, and README license reference passed. The complete staged diff and `git diff --cached --check` were reviewed; a damaged plan sentence found during review was repaired before commit. No application source, dependency or database behavior changed. Maven was not rerun: the earlier context test failure due to absent `DB_URL` remains recorded above.

## Next actions and unresolved approvals

1. The user explicitly requested public baseline publication after being warned about missing upstream license metadata. Preserve the unresolved grant risk while retaining original notices and canonical MIT text. Confirm the remote main tip after pushing; obtain confirmation of upstream rights before claiming they are verified. Never share credentials in chat. Inventory any existing MySQL history and its retention/import rights.
2. Approve the single-address API contract change needed to disable automatic SMTP; define evidence reason codes, unknown/confidence limits, and retention/access policy. Decide if SMTP stays disabled indefinitely; enabling it later needs separate egress authorization and local-only tests.
3. Implement Phase 1 safely: align Java 21 and compatible Spring Boot/OpenAPI, make validation passive, add deterministic unit/API and isolated database tests, and retain MIT attribution. Verify API startup and document exact compatibility changes.
4. Then implement and test PostgreSQL/Flyway migrations, independent worker and fenced job claims. Only afterward add bounded CSV ingestion, per-import deduplication, authorized results/export, cache/limiter and UI. Measure real 15,000-row throughput and memory before claiming reliability.
5. Set CSV byte/row/tenant limits, structural rejection and normalization rules, encoding and export safety; job lease/attempt/backoff/concurrency targets; Valkey outage rules and authorized bounce/suppression source before those milestones. No values have been validated yet.

Verification for the architecture documentation pass: read-only source and Git inventory plus internal Markdown link/decision/phase checks; no Maven run or live network checks. The agreed flow is documented but not runtime-verified.
