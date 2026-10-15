# MailGuard architecture decisions

These are architecture records, not claims of implementation. **Accepted** means agreed direction; **proposed** needs approval; **provisional** means a repository-audit or implementation detail still needs verification. Future changes should add a new numbered decision or explicitly supersede an earlier ID, with evidence. See [PLAN.md](PLAN.md) and [PROGRESS.md](PROGRESS.md).

## D-001 — Java 21 and Spring Boot backend
Status: **Accepted direction; provisional dependency compatibility** until a Java 21 API startup and OpenAPI smoke test.
Decision: Align compiler, runtime, CI, and containers on Java 21; retain Spring Boot with a compatible dependency set.
Rationale: One supported baseline limits environment drift while retaining the existing Java ecosystem.
Alternatives: Keep Java 17/23 as currently documented or replace Spring Boot.
Trade-offs/consequences: Upgrade/build and dependency compatibility work; pin and test Boot/OpenAPI choices rather than assuming their pairing runs.
Audit evidence: `pom.xml:8,29-30` selects Spring Boot 4.1.0 and Java 17; `Dockerfile:1` and README select Java 23. The sole context test fails at datasource setup, so it does not establish API or springdoc runtime compatibility.

## D-002 — React, TypeScript, and Vite client
Status: **Accepted direction**; scope of initial UI is provisional.
Decision: Build a separate frontend consuming a documented API.
Rationale: Type safety and a locally runnable client without binding domain rules to server templates.
Alternatives: Server-rendered pages or no UI.
Trade-offs/consequences: Separate toolchain and contract testing; do not duplicate outcome policy in the client.

## D-003 — PostgreSQL plus Flyway as durable store
Status: **Accepted direction; provisional legacy-data migration** because live MySQL history, if any, was not inspected.
Decision: PostgreSQL owns durable history, results, and jobs; Flyway owns forward-only schema changes.
Rationale: Transactional job coordination and reproducible schemas are preferable to implicit updates.
Alternatives: Keep MySQL with ORM schema updates; store durable jobs in Valkey.
Trade-offs/consequences: Data migration planning, explicit indexes and transaction design; startup must refuse unknown schema drift rather than silently mutate it.
Audit evidence: `pom.xml:52-56` includes MySQL only; `EmailValidationHistory.java` and `EmailValidationHistoryRepository.java` define the current table/repository; `application.properties:7` sets `ddl-auto=update`. No Flyway migrations, PostgreSQL driver, or data inventory is present. Decide whether to import existing rows before designing migration.

## D-004 — Valkey for DNS cache and rate limiting
Status: **Accepted direction; provisional TTL/failure policy** until evidence and load requirements are defined.
Decision: Valkey stores ephemeral DNS cache entries and shared rate-limit state, not authoritative jobs or results.
Rationale: Shared limits and caching across API/worker processes with a rebuildable cache.
Alternatives: In-process cache/limiter or PostgreSQL-backed limit state.
Trade-offs/consequences: Extra local service and outage behavior to define; never confuse cached lookup failures with absent MX. Rate-limit failure policy must be explicit.

## D-005 — Modular monolith with independent API and worker processes
Status: **Accepted direction; provisional module layout** until API/worker contracts are designed.
Decision: Keep one codebase and shared domain model, with separate runnable entry points and worker-only job execution.
Rationale: Operational separation of latency-sensitive HTTP and background work without distributed-service overhead.
Alternatives: One process for both roles or microservices.
Trade-offs/consequences: Shared releases and DB schema, but independent resource limits and lifecycle tests; no job processing inside arbitrary API request threads by default.
Audit evidence: Only `MailGuardApplication.java` boots the current app; `EmailValidationHistoryService.validate` performs DNS, synchronous SMTP, and persistence within a request. No separate worker exists.

## D-006 — PostgreSQL-backed durable jobs
Status: **Accepted direction; claim algorithm agreed in D-015; provisional lease/retry constants and schema** pending workload definition.
Decision: Transactionally claim PostgreSQL jobs with fenced leases; use bounded retries/backoff, reclaim expired leases, and idempotent evidence/result persistence. Assume at-least-once execution, not exactly-once side effects.
Rationale: Survive worker crashes without losing work or treating duplicate execution as a new result.
Alternatives: In-memory queue, Valkey queue, or broker.
Trade-offs/consequences: Claim/index/locking complexity and careful lease renewal; prove concurrent claims, restart recovery, and duplicate-write safety with PostgreSQL Testcontainers.

## D-007 — Bounded virtual-thread concurrency
Status: **Accepted direction; provisional limits** until measured.
Decision: Use Java virtual threads for blocking I/O only under admission limits and explicit DNS/SMTP/DB timeouts.
Rationale: Simple blocking code without permitting unlimited external or database work.
Alternatives: Unbounded virtual threads, fixed platform-thread pool, or reactive rewrite.
Trade-offs/consequences: Virtual threads reduce thread overhead, not downstream saturation; define per-stage limits, queue backpressure, and metrics before increasing throughput.

## D-008 — Evidence-based verification pipeline
Status: **Accepted stage sequence; provisional result taxonomy and API compatibility** until a consumer contract is agreed.
Decision: Record local syntax, then DNS/MX where meaningful, then authorized historical evidence as separate observations with provenance/time and explicit absent/unknown/transient states. Optional SMTP evidence requires separate policy and does not prove delivery.
Rationale: A boolean obscures missing data and network uncertainty.
Alternatives: Single valid/invalid result or SMTP as final authority.
Trade-offs/consequences: Richer API and persistence model; client must display uncertainty; existing response/history compatibility requires deliberate migration.
Audit evidence: `EmailValidationResponse.java` exposes nullable Boolean checks plus status/message; `EmailValidationHistoryService.java:58-95` marks no MX `INVALID`, SMTP success `VALID`, SMTP failure `UNVERIFIED`, and overwrites its final message. `DnsLookupService.java` queries MX twice and does not distinguish all transient failures. Decide whether `/api/email/validate` changes in place or receives a versioned replacement.

## D-009 — SMTP is optional, conservative, and disabled by default
Status: **Accepted direction; provisional operational policy** pending authorization and egress threat review.
Decision: No automatic sending and no unrestricted probing. SMTP verification requires explicit opt-in policy, bounded egress/concurrency, and local fake-server tests; it is not part of the baseline milestone.
Rationale: Recipient enumeration, injection, unreliable responses, and network abuse are unacceptable defaults.
Alternatives: Always probe or remove SMTP forever.
Trade-offs/consequences: Less mailbox-level evidence by default; if enabled later, document consent/authorization, timeouts, allowable targets, and unknown outcomes before deployment.
Audit evidence: Current `SmtpVerificationService.java:20-101` connects on port 25 on the normal request path and logs full email addresses; `EmailFormatValidator.java` permits control characters that reach `RCPT TO`. No live probes were run. Remove the automatic path before enabling any optional future protocol check.

## D-010 — Docker Compose local-first runtime
Status: **Accepted direction; provisional service topology** until the local deployment is designed.
Decision: Compose is the primary development/integration path for API, worker, PostgreSQL, Valkey, UI and appropriate observability; no cloud hosting required.
Rationale: Reproducible local operation.
Alternatives: Mandatory managed cloud services or undocumented host-installed dependencies.
Trade-offs/consequences: Container health, resource limits, secrets handling, and documentation need maintenance; tests must also run independently of a permanently running Compose stack.
Audit evidence: The repository has a single `Dockerfile` with Java 23 and a skipped-test build; no Compose file or UI/worker containers exist.

## D-011 — Deterministic tests and local observability
Status: **Accepted direction; provisional instrumentation details** until implementation.
Decision: Use JUnit 5 and Mockito for units, Testcontainers for persistence/integration, OpenTelemetry for traces/metrics, and Prometheus/Grafana where useful locally. No external SMTP or personal-account tests.
Rationale: Safety and reproducible fault/transition coverage, with actionable diagnostics.
Alternatives: Live integration checks or logs-only operations.
Trade-offs/consequences: Container-dependent integration suite and telemetry overhead; redact addresses and credentials from spans, metrics, and logs.
Audit evidence: `MailguardApplicationTests.java` is the only test. A safe `mvn test -B -q -Dspring.test.context.failure.threshold=1` run compiled, then failed context initialization (1 test, 1 error): datasource URL did not start with `jdbc` because DB environment variables are absent. `Dockerfile:8` skips tests. No Testcontainers or telemetry implementation is present.

## D-012 — MIT attribution and private data boundaries
Status: **Accepted direction; derivative MIT text added, upstream grant provenance unverified; provisional retention/access policy**.
Decision: Preserve upstream MIT attribution and notices in distributions; establish retention, access, deletion, and safe logging policy for email addresses and bounce data before durable ingestion.
Rationale: Legal provenance and personal data are foundational constraints.
Alternatives: Re-license or collect unlimited history.
Trade-offs/consequences: Retention/deletion design and reduced raw-data logging; do not use personal email accounts for testing.
Audit evidence: The upstream README claims MIT and names `Copyright (c) 2026 Aasheesh Verma`, but has no standalone full `LICENSE`; `pom.xml:17-19` has an empty license entry. This derivative adds canonical MIT text with that attribution in `LICENSE`, without altering upstream notices; upstream rights confirmation is still outstanding. `EmailValidationHistory.java` stores addresses, `SmtpVerificationService.java` logs them, and SQL logging is enabled (`application.properties:9`).

## Audit-dependent decisions to close

- Repository audit completed; observations are attached to D-001, D-003, D-005, D-008–D-012. Audit did not establish running API behavior or existing production data. Provisional design clauses remain provisional, not implemented.
- Define result taxonomy and backward-compatibility contract (D-008), inventory any legacy data before an import plan (D-003), job lease/retry/idempotency keys (D-006), cache TTL/rate-limit outage behavior (D-004), and SMTP authorization/egress policy (D-009) in their respective milestones.

## D-013 — Streaming per-import deduplication and row-preserving exports
Status: **Accepted flow; unimplemented**, with CSV limits, normalization, owner/access and retention policy still provisional.
Decision: Stream bounded CSV batches into durable per-import rows and retain each original ordinal. Record rejected/duplicate rows, normalize accepted addresses once, then create one job per unique normalized address within that import; a duplicate links to the first row's result. Invalid email syntax remains a verification outcome, not an invisible import rejection. Export one row per input ordinal with reason codes. Do not deduplicate contacts across owners by default.
Rationale: A 15,000-row import should not require 15,000 concurrent HTTP requests or memory proportional to the whole file, and deduplication must not silently discard user rows.
Alternatives: Load entire CSV into memory; deduplicate globally; drop duplicates or rejected rows from exports.
Trade-offs/consequences: Staging and row/result mapping use storage and require CSV size, encoding, column, formula-injection-safe export, tenant authorization and deletion rules. Test deterministic 15,000-row imports, interruption/restart and ordered exports before claiming capacity.
Audit evidence: Only `POST /api/email/validate` and `/api/email/test` exist in `EmailValidationHistoryController.java`; no import/export or tenant model exists.

## D-014 — Single-address compatibility beside asynchronous bulk jobs
Status: **Proposed; unimplemented**, with a safety cutover requiring an explicit API contract decision.
Decision: Keep the existing single-address route while adding separate asynchronous import/progress/results/export routes; do not turn `/api/email/validate` into a job-creation endpoint without versioned or approved contract migration. Remove automatic SMTP from its normal path as the accepted safety direction in D-009, and explicitly document/test the resulting response/status changes.
Rationale: Bulk requests need durable progress and retries; established single-address callers must not silently receive a job ID instead of their existing response.
Alternatives: Issue 15,000 synchronous validation requests, replace the existing route in place, or keep unsafe SMTP for wire compatibility.
Trade-offs/consequences: Two entry paths temporarily share classification rules; the safety fix changes legacy semantics. Inventory consumers before cutover and test both contracts. Do not add compatibility shims that preserve unsafe probing.
Audit evidence: `EmailValidationHistoryService.validate` synchronously performs DNS, SMTP and persistence; `EmailValidationResponse` returns nullable booleans plus status/message; neither route has been live-tested.

## D-015 — Fenced PostgreSQL leases rather than a broker
Status: **Accepted claim and completion flow; unimplemented**, with lease/backoff/failure limits provisional until tested.
Decision: Claim due/expired rows with PostgreSQL `FOR UPDATE SKIP LOCKED` in short transactions; increment a lease token on claim. Execute bounded verification work outside the claim transaction. Only the current, unexpired token may atomically write evidence, reason-coded result and terminal job state, or write a transient failure observation with its retry state. Reject stale writes; bound attempts/backoff and make duplicate result keys unique.
Rationale: At-least-once execution needs crash recovery and prevention of stale workers overwriting newer results. Valkey remains disposable.
Alternatives: Long DB transactions across DNS I/O, an in-memory/Valkey queue, or a separate broker with cross-store consistency.
Trade-offs/consequences: Polling, indexes, clock/lease policy and claim contention load PostgreSQL; a broker may be revisited after measuring queue latency and capacity. Tests must cover concurrent claims, crash/reclaim, expiry during I/O and duplicate writes with real PostgreSQL.
Audit evidence: `EmailValidationHistoryRepository` is a simple `JpaRepository` without a job entity; the source tree has no worker or migration.

## D-016 — Incremental root-layout migration
Status: **Proposed; unimplemented**.
Decision: Keep the existing Maven root and `com.aash.mailguard` entry point while carving feature packages inside the backend; move to `backend/` or a Maven multi-module build only after a runnable split needs it. Add separate API/worker entry points at the durable-core phase; build the frontend against verified contracts.
Rationale: A directory reshuffle before any behavior or isolated tests exist raises review and regression cost without delivering a usable product.
Alternatives: Rebuild everything into the sample repository tree first or split into microservices.
Trade-offs/consequences: Root layout may temporarily differ from the target diagram; two independently runnable entry points and bounded resources still need explicit smoke tests. The modular monolith preserves shared domain rules without broker/distributed transactions.
Audit evidence: One root `pom.xml`, one `MailGuardApplication.java`, no worker, frontend or Compose file in the inspected repository.

## D-017 — Upstream license and publication gate
Status: **User approved public baseline push after missing-license warning; upstream/origin configured, upstream grant still unverified**.
Decision: Preserve upstream Git history and copyright. The original remote is `upstream` and the user's public repository is `origin`. User explicitly requested the baseline push after choosing to wait for rights confirmation; canonical MIT text is included from the upstream README's MIT claim, but that claim is not independent proof of the grant. Keep the publication risk visible and exclude private data.
Rationale: A README claim alone is not a standalone full license grant, and pushing can expose private data; Git history attributes upstream work.
Alternatives: Reset Git history, claim original authorship, or push immediately based on an incomplete notice.
Trade-offs/consequences: `upstream` is `https://github.com/aasheesh06/MailGuard.git`, `origin` is `https://github.com/soumaydutt/mail-intelligence-platform.git`. The upstream README asserts MIT and names the author, while upstream has no `LICENSE` or GitHub license metadata. Obtain confirmation of upstream rights; do not represent the gap as resolved by adding a derivative `LICENSE`.
