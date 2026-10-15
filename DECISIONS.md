# MailGuard architecture decisions

These are architecture records, not claims of implementation. **Accepted** means agreed direction; **proposed** needs approval; **provisional** means a repository-audit or implementation detail still needs verification. Future changes should add a new numbered decision or explicitly supersede an earlier ID, with evidence. See [PLAN.md](PLAN.md) and [PROGRESS.md](PROGRESS.md).

## D-001 — Java 21 and Spring Boot backend
Status: **Accepted direction; Java 21 API/OpenAPI verified with test-scope H2 and disposable Docker/MySQL**, production data unverified.
Decision: Align compiler, runtime, CI, and containers on Java 21; retain Spring Boot with a compatible dependency set.
Rationale: One supported baseline limits environment drift while retaining the existing Java ecosystem.
Alternatives: Keep Java 17/23 as currently documented or replace Spring Boot.
Trade-offs/consequences: Pin Spring Boot 4.1.0 with springdoc 3.1.1; test-scope H2 isolates HTTP/startup tests but does not prove MySQL compatibility or container startup.
Audit evidence: `pom.xml:8,29-30` selects Spring Boot 4.1.0 and Java 17; `Dockerfile:1` and README select Java 23. The sole context test fails at datasource setup, so it does not establish API or springdoc runtime compatibility.
Implementation evidence (2026-10-15): `pom.xml` compiles for Java 21; `Dockerfile` selects Temurin 21. `mvn -B test` passed two offline HTTP tests. The app started on Java 21 with test-scope H2; live `/api/email/validate` (malformed input), `/v3/api-docs`, and Swagger UI returned HTTP 200. Docker and production database were not tested.
Subsequent runtime evidence (2026-10-15): The Java 21 Docker image started against a disposable MySQL 8.4.11 container; two synthetic requests persisted two history rows. Production data and restart persistence remain unverified; see PROGRESS.md.

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
Status: **Interim single-address semantics approved and implemented in D-018; richer evidence taxonomy remains provisional**.
Decision: Record local syntax, then DNS/MX where meaningful, then authorized historical evidence as separate observations with provenance/time and explicit absent/unknown/transient states. Optional SMTP evidence requires separate policy and does not prove delivery.
Rationale: A boolean obscures missing data and network uncertainty.
Alternatives: Single valid/invalid result or SMTP as final authority.
Trade-offs/consequences: Richer API and persistence model; client must display uncertainty; existing response/history compatibility requires deliberate migration.
Audit evidence (before D-018): `EmailValidationResponse.java` exposes nullable Boolean checks plus status/message; `EmailValidationHistoryService.java:58-95` marked no MX `INVALID`, SMTP success `VALID`, SMTP failure `UNVERIFIED`, and overwrote its final message. `DnsLookupService.java` queried MX twice and collapsed transient failures. D-018 records the approved in-place safety cutover.

## D-009 — SMTP is optional, conservative, and disabled by default
Status: **Automatic SMTP path removed in D-018; future SMTP policy provisional** pending authorization and egress threat review.
Decision: No automatic sending and no unrestricted probing. SMTP verification requires explicit opt-in policy, bounded egress/concurrency, and local fake-server tests; it is not part of the baseline milestone.
Rationale: Recipient enumeration, injection, unreliable responses, and network abuse are unacceptable defaults.
Alternatives: Always probe or remove SMTP forever.
Trade-offs/consequences: Less mailbox-level evidence by default; if enabled later, document consent/authorization, timeouts, allowable targets, and unknown outcomes before deployment.
Audit evidence (before D-018): `SmtpVerificationService.java:20-101` connected on port 25 on the request path and logged full addresses; `EmailFormatValidator.java` permitted control characters that reached `RCPT TO`. No live probes were run. D-018 removed the automatic path and obsolete service.

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
Status: **Single-address cutover approved and implemented in D-018; asynchronous bulk routes still proposed and unimplemented**.
Decision: Keep the existing single-address route while adding separate asynchronous import/progress/results/export routes; do not turn `/api/email/validate` into a job-creation endpoint without versioned or approved contract migration. Remove automatic SMTP from its normal path as the accepted safety direction in D-009, and explicitly document/test the resulting response/status changes.
Rationale: Bulk requests need durable progress and retries; established single-address callers must not silently receive a job ID instead of their existing response.
Alternatives: Issue 15,000 synchronous validation requests, replace the existing route in place, or keep unsafe SMTP for wire compatibility.
Trade-offs/consequences: Two entry paths temporarily share classification rules; the safety fix changes legacy semantics. Inventory consumers before cutover and test both contracts. Do not add compatibility shims that preserve unsafe probing.
Audit evidence (before D-018): `EmailValidationHistoryService.validate` synchronously performed DNS, SMTP and persistence; `EmailValidationResponse` returned nullable booleans plus status/message. D-018 replaced probing with a passive response in place.

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

## D-018 — Passive single-address API cutover
Status: **Approved in conversation; implemented and verified offline**, pending production MySQL/Docker validation.
Decision: Keep `POST /api/email/validate` synchronous and its existing response fields; remove automatic SMTP probing and return `smtpVerified=null`. Reject ISO control characters locally. Return `INVALID` only on format failure, otherwise `UNVERIFIED`. Set `mxRecordFound=true` for a non-null MX, `false` for absent MX/NXDOMAIN/null MX, and `null` for DNS errors or an unparseable domain. Save the same outcome in history; query MX once.
Rationale: DNS observations do not establish mailbox existence. Eliminating port-25 probes and false `VALID`/`INVALID` classifications is safer than preserving misleading legacy behavior.
Alternatives: Add a second passive route while disabling the old probe, or leave automatic SMTP in place. The user selected the smaller in-place cutover.
Trade-offs/consequences: Existing callers expecting SMTP booleans or `VALID` for an accepted `RCPT TO` must handle `null`/`UNVERIFIED`. The current response still lacks timestamps, provenance, and detailed DNS reason codes; those belong to the durable evidence milestone. MySQL history and retention/access policy remain unverified.
Verification (2026-10-15): A focused HTTP regression failed before the cutover (`absentMxIsUnverifiedRatherThanInvalid`). Offline DNS fixtures and H2-backed HTTP tests pass after it; a live local H2 smoke returned HTTP 200 with `UNVERIFIED`, `mxRecordFound=null`, `smtpVerified=null` for an unparseable DNS name and `INVALID` for CR/LF. No external DNS or SMTP was used.

## D-019 — Step 1 error and build-context privacy boundary
Status: **Implemented; H2 and disposable MySQL/Docker verified**. The oversized-input HTTP 500 rule was superseded by D-020; production data remains unverified.
Decision: Keep the synchronous API's success fields, route names and existing HTTP 500 status for oversized input. Validate the `email` field against the history column's 255-character limit before persistence. Return a fixed message for unexpected errors and preserve the error response fields while replacing untrusted request paths with `/`. Disable default Hibernate SQL printing and resolved-exception warnings that include rejected values or paths. Exclude local `.env*`, `.agents/`, `.omp/` and skill metadata from Docker's build context; ignore `.env*` in Git.
Rationale: Database and framework exceptions exposed SQL details and synthetic address fragments in HTTP responses or logs. Credentials embedded in a JDBC URL can appear in startup logs.
Alternatives: Change oversized inputs to HTTP 400 now, echo the exception details, or log resolved exceptions verbatim. A new 4xx error contract needs separate approval; preserving HTTP 500 avoids another status change during the approved SMTP cutover.
Trade-offs/consequences: Oversized input still receives a generic HTTP 500 with no history row; that status is not ideal but matches the existing observable outcome. The safe-path fallback `/` loses path detail for unknown routes. Suppressing the framework's resolved-exception WARN logs reduces diagnostics; unrelated infrastructure logs, MySQL behavior, image contents and PII retention/access policy remain unverified. Do not put credentials in `DB_URL`.
Verification (2026-10-15): Before the fix, H2 HTTP requests exposed SQL or a synthetic address in errors, and resolver WARN logs included rejected values and unknown paths. Focused offline log-capture tests failed before the logging setting and passed after it, even with `spring.mvc.log-resolved-exception=true`. `mvn -B package` passed 20 tests. A Java 21/H2 smoke checked both routes, OpenAPI, invalid and unknown DNS results, oversized input and sanitized errors; its observed logs contained no synthetic address or SQL statement. Neither MySQL nor Docker was run.

## D-020 — Request error status and safe route reporting
Status: **Approved in the PR #1 review request; implemented and verified with HTTP tests**.
Decision: Keep format-invalid email strings as HTTP 200 with `status=INVALID`. Return HTTP 400 with a fixed message for malformed JSON and `@Size(max=255)` violations, superseding D-019's HTTP 500 rule for oversized inputs. Keep unexpected failures as sanitized HTTP 500; report only exact known API routes (`/api/email/validate`, `/api/email/test`) in error responses and redact unknown paths to `/`. DNS resolver I/O failures remain uncertain MX observations as required by D-018.
Rationale: Invalid request bodies are client errors, not server failures. Explicit route allowlisting retains useful paths without reflecting untrusted URI content; transient DNS errors cannot establish absent MX.
Trade-offs/consequences: Callers relying on HTTP 500 for oversized input must handle HTTP 400. Format-invalid strings still produce HTTP 200 and history records; unsupported methods and unknown routes retain the existing generic HTTP 500 behavior until a separate contract decision. No external DNS or SMTP probes are used for these tests.
Verification (2026-10-15): The original `@Size` HTTP integration test passed while asserting 500. Three new expectations failed before the fix: oversized and malformed JSON returned 500, and `/api/email/test` was redacted. The existing timeout fixture and new `IOException` fixture both returned unknown MX (`null`) before changes to DNS code. Focused HTTP/DNS tests and a local Java 21/H2 API smoke passed after the handler change; see PROGRESS.md for the final package check.
