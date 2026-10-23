# MailGuard
![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green?style=for-the-badge&logo=springboot)
![Maven](https://img.shields.io/badge/Maven-Build-red?style=for-the-badge&logo=apachemaven)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue?style=for-the-badge&logo=postgresql)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-green?style=for-the-badge&logo=swagger)

MailGuard exposes a synchronous, passive email check: local format validation followed by bounded DNS routing observation. It does not verify a mailbox or send email.

This repository preserves the history and attribution of [the original MailGuard](https://github.com/aasheesh06/MailGuard). The implemented API, worker, storage and browser slices are described below; [PLAN.md](PLAN.md) separates them from the larger unimplemented product.

## Single-host production candidate (Phase 12)

`compose.prod.yaml` builds a TLS proxy, private API and headless worker, PostgreSQL 17 and authenticated Valkey 8. It is separate from development `compose.yaml`. Only loopback proxy ports are published by default. An administrator key is mandatory for validation and all private routes in the production profile; no public multi-user or tenant model exists. See [deployment](docs/DEPLOYMENT.md), [operator runbook](docs/OPERATIONS.md), [recovery drill](docs/BACKUP_RESTORE.md), [privacy](docs/PRIVACY.md) and [release gates](docs/RELEASE_READINESS.md) before use.

For an isolated disposable smoke, install Node.js 24, Docker Compose v2 and Chromium (`cd frontend && npm ci && npx playwright install chromium`), then return to the repository root and run `node scripts/prod/verify.mjs`. This creates a unique, local, self-signed TLS stack, exercises browser import/export and proxy abuse limits, stalls and resumes the worker to check Docker health, and verifies a separate restore with schema checks, eight-table content hashes and a read-only packaged-API import read. Its test-owned PostgreSQL volume and private archive remain for inspection; they are **not** production credentials or data. `node scripts/prod/verify.mjs --load` additionally runs five bounded 100-, 1,000- and 10,000-row synthetic invalid-address imports per profile. The archived volume is never removed by the script. Production startup requires operator-supplied certificates, restricted secret files, DNS egress control and the unresolved approvals listed in the release gates.

---
## ✨ Features
- ✔ Email format validation
- ✔ Domain extraction, MX advertisement, Null MX, and implicit A/AAAA routing observation
- ✔ Explicit unknown DNS outcome; no SMTP connection
- ✔ REST API built with Spring Boot
- ✔ Swagger/OpenAPI documentation
- ✔ PostgreSQL validation history with versioned Flyway schema
- ✔ PostgreSQL-backed single-address jobs with a separately runnable, bounded worker
- ✔ Authorized historical delivery events, audit-preserving suppressions, and optional validation evidence (Phase 9)
- ✔ React/TypeScript/Vite single-principal local operations dashboard (Phase 11)
- ✔ Maven project

## 📦 Installation
Clone the repository:

```bash
git clone https://github.com/soumaydutt/mail-intelligence-platform.git
```

Move into the project directory:

```bash
cd mail-intelligence-platform
```

Local Java startup requires `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`; it has no implicit database fallback. Production Compose supplies credentials from external secret files under `/run/secrets/` instead of putting them in environment values or the image. Keep credentials out of `DB_URL`, which may appear in startup logs. Requires Java 21, Maven, Docker with a running Linux daemon for mandatory PostgreSQL and Valkey integration tests, and a reachable PostgreSQL 17 database. The HTTP regression suite uses isolated H2 and disables Flyway; Testcontainers cases do not skip when Docker is unavailable. A first disconnected run needs Maven dependencies and `postgres:17-alpine` plus `valkey/valkey:8-alpine` locally cached. Tests need no external DNS or SMTP.

On Windows PowerShell, start the **local-only** PostgreSQL and disposable Valkey services with a unique local database password:

```powershell
$env:DB_USERNAME = 'mailguard_local'
$env:DB_PASSWORD = Read-Host 'Local PostgreSQL password'
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/mailguard'
docker compose up -d --wait
mvn -B clean verify
mvn -B spring-boot:run
```

In another terminal, set the same `DB_USERNAME`/`DB_PASSWORD` for Compose and inspect the migration without printing stored addresses:

```powershell
docker compose exec -T postgres psql -U $env:DB_USERNAME -d mailguard -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;'
docker compose exec -T postgres psql -U $env:DB_USERNAME -d mailguard -c 'SELECT count(*) FROM email_validation_history;'
```

Stop the application with Ctrl+C, then run `docker compose stop` from a shell with the same Compose credentials. The named PostgreSQL volume and history survive restarts; do **not** run `docker compose down -v` against data you intend to keep. Valkey has no volume and loses cache/quotas on restart. `DB_PORT` and `VALKEY_PORT` change localhost host ports (defaults `5432` and `6379`); update `DB_URL` and `VALKEY_PORT` accordingly. `DB_NAME` changes the initial database name (default `mailguard`). Compose is for local development, not production credential storage or authenticated Valkey. On other shells, set equivalent environment variables before running the same commands.

## 🚀 Quick Start
Start Spring Boot only after PostgreSQL is healthy. Flyway applies V1–V5 on an empty database; a compatible existing V1–V4 database upgrades forward without rewriting previous migrations or deleting rows. An untracked populated database is refused. Hibernate validates instead of repairing schema. The pool defaults to five connections and one minimum idle; change `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`, `DB_POOL_CONNECTION_TIMEOUT_MS`, `DB_POOL_IDLE_TIMEOUT_MS` and `DB_POOL_MAX_LIFETIME_MS` only after measuring workload.

Once the application starts successfully, open your browser:

```
http://localhost:8080/swagger-ui/index.html
```

You can test the local-development email validation APIs directly from Swagger UI. The production profile disables Swagger and OpenAPI and does not publish the API port.

## PostgreSQL cutover and recovery

Flyway V1 initializes a **new empty PostgreSQL 17 database**; Hibernate validates the schema rather than updating it. The owner reports that the previous MySQL database has two irrelevant records and explicitly does not want them imported. The old MySQL database is **not required** for the new installation. Do not delete its database, containers or volumes as part of this cutover.

On first startup, check Flyway V1–V5, `email_validation_history`, `verification_job`, `import_batch`, `delivery_event`, and `delivery_suppression`. A compatible V4 installation receives V5's additive delivery tables and nullable completed-job evidence JSONB; existing history, jobs, imports, and terminal snapshots stay intact. Reuse the same database on subsequent starts. No old MySQL records are imported.

**Never reset a populated PostgreSQL database automatically.** Flyway baseline and clean are disabled; Hibernate uses `ddl-auto=validate`. V1–V4 are immutable. A missing Flyway history entry, incompatible checksum, or incompatible schema must stop startup; investigate and back up instead of dropping tables, baselining, repairing, or replaying applied migrations. PostgreSQL backup and restore, not the unused MySQL database, protect new writes.

History retains full addresses without approved retention or access policy; there is no public history-read endpoint. The in-memory `EvidenceClassifier` still does not persist its DNS/SMTP observations. Phase 9 persists separate, authorized delivery events and suppression audit rows under V5; it does not enable SMTP. Phase 10 only caches disposable DNS observations; it never caches history or suppressions. Phase 11 adds the browser client and Phase 12 provides a loopback-only TLS deployment candidate, not approval for public access. See D-032 through D-039.

## 📖 Usage
MailGuard checks local email format, then observes DNS routing for format-valid input. It does not connect to recipient mail servers. Requests within the 255-character storage limit retain history writes. Longer values and malformed JSON return HTTP 400 before DNS or persistence; format-invalid email strings retain HTTP 200 with `status=INVALID`.

## Durable single-address jobs (Phase 7)

Set `JOBS_API_KEY` to a private, nonempty shared bearer key **before** starting the API. Without it, `/api/jobs` returns HTTP 401. Keep `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` identical in both processes. Package once, then run the API and a separate headless worker in different PowerShell terminals:

```powershell
$env:JOBS_API_KEY = Read-Host 'Private job API key'
java -jar target/mailguard-0.0.1-SNAPSHOT.jar
```

```powershell
# Set the same DB_URL, DB_USERNAME and DB_PASSWORD as the API.
$env:JOBS_WORKER_ENABLED = 'true'
java -jar target/mailguard-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none
```

The API defaults to `JOBS_WORKER_ENABLED=false`; the headless process serves no HTTP. `mvn -B package` builds the jar. `POST /api/jobs` accepts `{"email":"bad-address"}` with `X-Job-API-Key` and an optional printable ASCII `Idempotency-Key` (1–128 characters). It returns HTTP 202 with `{ "id": "<uuid>", "reused": false }` and `Location: /api/jobs/<uuid>`. `GET /api/jobs/<uuid>` returns state, attempts, times, a safe failure code, and a completed `result` with the seven established validation fields. Phase 9 can add `historicalEvidence` only when a valid address has an observation or the evidence store is unavailable. Invalid syntax completes with `INVALID`, not a failed job. The synchronous validation route remains synchronous.

`DELETE /api/jobs/<uuid>` is **cancellation, not data erasure**. PENDING, RUNNING and RETRY become CANCELLED with HTTP 200 and a job-status body; repeating DELETE on CANCELLED returns the same terminal status with HTTP 200. A COMPLETED or FAILED job stays unchanged and returns HTTP 409 with a sanitized `Conflict` error; an unknown ID returns HTTP 404. Cancellation and completion compete under PostgreSQL's row lock: only one terminal outcome wins. The states are `PENDING`, `RUNNING`, `RETRY`, `COMPLETED`, `FAILED` and `CANCELLED`.

```powershell
$headers = @{ 'X-Job-API-Key' = $env:JOBS_API_KEY; 'Idempotency-Key' = 'local-example-1' }
$job = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/jobs -Headers $headers -ContentType 'application/json' -Body '{"email":"bad-address"}'
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/jobs/$($job.id)" -Headers @{ 'X-Job-API-Key' = $env:JOBS_API_KEY }
```

A repeated key with the same email returns the original ID with `reused=true`, even after restart; another email returns HTTP 409. An absent or wrong key returns 401; malformed bodies, IDs and addresses over 255 characters return 400; an unknown ID returns 404. The request body is limited to 8 KiB. `JOBS_MAX_OUTSTANDING` defaults to 20,000 persisted outstanding jobs; admission returns 429 at the cap while matching idempotent replays still succeed. `JOBS_MAX_ATTEMPTS` defaults to three (allowed 1–20). `JOBS_WORKER_CONCURRENCY` defaults to 20 (allowed 1–200), `JOBS_WORKER_LEASE_SECONDS` to 30 (allowed 6–300), `JOBS_WORKER_POLL_MILLIS` to 250 (allowed 50–60,000), and `JOBS_WORKER_SHUTDOWN_SECONDS` to 10 (allowed 1–300). Workers renew leases every one-third of the lease period, reclaim expired work, and retry temporary DNS outcomes with bounded backoff. PostgreSQL fences stale writes and atomically commits one linked history row with each completion; the worker may recompute after a crash. Worker logs report only aggregate counters. No public SMTP path or broker is enabled.

Idempotency uses **byte-exact identity of the decoded email string encoded as UTF-8**, not a normalized address or the raw JSON document. The same globally unique key and exact email reuse the original job; changing domain letter case, local-part case, Unicode bytes or whitespace conflicts even when DNS treats two domains alike. The local part is not universally safe to lowercase. A different key creates a separate job. The SHA-256 fingerprint is not anonymization; the original email and key remain stored.

**Single-principal boundary:** Phase 7 supports one trusted administrative principal. A nonempty shared `JOBS_API_KEY` is required for POST, GET and DELETE; missing configuration, a missing header or a wrong key fails closed with HTTP 401. Every key holder can read and cancel every job, and idempotency keys are global rather than tenant-scoped. Worker logs use aggregate counters without job IDs, addresses or idempotency keys. The legacy synchronous routes remain unauthenticated. This is **not safe as a multi-tenant public API**. Before public deployment, add owner-scoped authentication/authorization, tenant-scoped idempotency, TLS termination, abuse/rate controls, and an approved retention policy. Do not present a shared key as tenant isolation.

**Retention status:** There is no automated expiration or deletion. `verification_job.email`, completed `result_email`, linked validation-history rows and plaintext idempotency keys persist; backups can retain the same personal data. A cancelled job is still stored, and `DELETE /api/jobs/<uuid>` does not delete it. The nullable unique `email_validation_history.job_id` is a foreign key without cascading deletion: removing a job while linked history exists would violate the constraint. A later operator-approved policy must set retention periods and legal-hold rules, restrict deletion to eligible terminal jobs, remove linked history **before** its job in one transaction, handle synchronous history rows with null `job_id` separately, and coordinate backup expiry plus idempotency-key reuse after removal. Use bounded, auditable batches without logging addresses; do not enable a scheduled purge before policy approval. The opt-in synthetic benchmark is `mvn -B "-Djobs.benchmark=true" "-Dtest=JobBenchmarkTest" test`; it does not measure external DNS or 15,000-row CSV throughput.

## Streaming CSV imports (Phase 8)

Run the same API and headless worker against the same PostgreSQL database. Every import route requires a nonempty `X-Job-API-Key`; this is one trusted principal, **not** tenant isolation. Use TLS and private network access. Phase 9 does not change CSV upload, progress, paging, cancellation, or export columns; a completed unique job may carry optional historical evidence through authenticated `GET /api/jobs/{id}`.

```powershell
$headers = @{ 'X-Job-API-Key' = $env:JOBS_API_KEY }
$import = curl.exe -sS -H "X-Job-API-Key: $env:JOBS_API_KEY" -F "file=@contacts.csv;type=text/csv" http://127.0.0.1:8080/api/imports | ConvertFrom-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/imports/$($import.id)" -Headers $headers
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/imports/$($import.id)/results?after=0&limit=100" -Headers $headers
curl.exe -sS -H "X-Job-API-Key: $env:JOBS_API_KEY" "http://127.0.0.1:8080/api/imports/$($import.id)/export" -o results.csv
```

`POST /api/imports` accepts one multipart part named `file`, with a `.csv` filename and `text/csv` UTF-8 content type. The CSV needs one `email` header, case-insensitive; other columns are ignored. UTF-8 BOM, LF/CRLF, quoted commas, escaped quotes and multiline fields are supported. Successful staging returns HTTP 202, `{ "id": "<uuid>" }` and `Location: /api/imports/<uuid>`. Empty files with only a header complete with zero rows. Oversized, missing, blank, or malformed cells retain their input ordinal and a safe rejection reason where possible. Email syntax-invalid values within the 255-character storage bound still get jobs and `INVALID` results. Addresses deduplicate **only within each import**, by exact decoded text; duplicates link to the first row's job. Repeating an upload creates a new import and new jobs; no import-level `Idempotency-Key` is supported.

`GET /api/imports/<uuid>` reports durable state, `inputRows`, accepted/duplicate/rejected counts, pending/running/retry/completed/failed/cancelled unique-job counts, timestamps and a safe failure code. Active imports derive progress from rows and jobs; terminal imports read counters captured in the same database transaction as their terminal state. `INGESTING` is not eligible for promotion. Only `READY` and `PROCESSING` imports feed jobs; `COMPLETED` includes exhausted failures but no pending work. Invalid CSV can retain previously committed rows in `FAILED`, with **no jobs promoted**. A disconnected or crashed upload remains `INGESTING` until the worker marks it `FAILED` after 60 minutes without a batch write; no automatic retry or deletion occurs. `GET /api/imports/<uuid>/results?after=0&limit=100` returns an ordered JSON page (limit 1–500); `after` is the last seen ordinal. `GET /api/imports/<uuid>/export` streams a UTF-8 CSV with one row per original ordinal, including rejected and duplicate rows; results can be partial before completion. The export prefixes spreadsheet-formula candidates with an apostrophe and quotes/escapes every non-null cell. Avoid treating exported values as trusted input.

`DELETE /api/imports/<uuid>` cancels pending, running, retrying and not-yet-promoted work. It does **not** erase stored addresses, history or already-completed results. Repeated cancellation returns 200; an already-completed or failed import returns 409, an unknown ID 404. Running jobs obey Phase 7 lease fencing; a completion committed before cancellation stays completed. The worker promotes at most 250 unique rows per database transaction, under Phase 7's shared `JOBS_MAX_OUTSTANDING` cap; no DNS runs inside promotion transactions. Separate worker processes can safely compete. `JOBS_WORKER_CONCURRENCY` still bounds active DNS work; bulk admission does not bypass it.

Defaults: CSV file at most 16 MiB (`IMPORTS_MAX_UPLOAD_BYTES`), multipart request at most 17 MiB, 50,000 rows (`IMPORTS_MAX_ROWS`), 2,048 decoded characters per record (`IMPORTS_MAX_RECORD_CHARS`), 255 characters per stored email (`IMPORTS_MAX_EMAIL_CHARS`), 250 staged/promoted rows per batch (`IMPORTS_BATCH_SIZE`), two simultaneous multipart parses per API instance (`IMPORTS_MAX_CONCURRENT_UPLOADS`), results/export fetch page 500 (`IMPORTS_RESULTS_PAGE_SIZE`), 60-minute stale-ingest timeout (`IMPORTS_STALE_MINUTES`), and promoter poll interval 500 ms (`IMPORTS_WORKER_POLL_MILLIS`). Spring servlet multipart caps remain fixed at 16/17 MiB; raising application byte caps alone does not raise servlet limits. Rejected uploads return a sanitized error; a server-side permit returns 429 when busy. Temp multipart files follow servlet cleanup; upload permits are local to each API process. CSV addresses, job results and linked history persist indefinitely until an approved retention and deletion policy exists; do not upload personal lists to a public deployment.

Run the opt-in disposable PostgreSQL 17 workloads with `mvn -B "-Dimports.benchmark=true" "-Dtest=BulkBenchmarkTest" test`. They ingest 15,000 and 40,000 distinct synthetic invalid-syntax rows over loopback HTTP, complete them through the headless worker, and stream ordered CSV exports. Reported promotion time includes ingestion; database growth is measured against each run's starting size. These runs do not establish external DNS capacity or production throughput.

## Historical delivery evidence and suppression (Phase 9)

Set `JOBS_API_KEY` and `DELIVERY_SOURCE_ID` in the API environment. `DELIVERY_SOURCE_ID` identifies the trusted administrative JSON feed (1–128 ASCII letters, digits, `.`, `_`, `:`, or `-`; starts with a letter/digit). The API never receives a source identifier from an event body. This is an authenticated operator feed, **not** an unauthenticated provider webhook or an SMTP probe. Only ingest events from a source you control and can audit. All delivery routes require the nonempty `X-Job-API-Key` before reading the body. Keep the key private and the API behind TLS and private network access.

```powershell
$headers = @{ 'X-Job-API-Key' = $env:JOBS_API_KEY }
$event = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/delivery-events -Headers $headers -ContentType 'application/json' -Body '{"email":"sample@fixture.test","eventType":"HARD_BOUNCE","externalEventId":"local-001","occurredAt":"2026-10-18T12:00:00Z","enhancedStatusCode":"5.1.1"}'
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/delivery-evidence/lookup -Headers $headers -ContentType 'application/json' -Body '{"email":"sample@fixture.test"}'
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/delivery-events/$($event.id)/revoke" -Headers $headers -ContentType 'application/json' -Body '{"reasonCode":"SOURCE_CORRECTION"}'
```

Use an `occurredAt` in UTC from 2000 onward, no more than five minutes in the future, and at most six fractional-second digits. Feed `externalEventId` is unique per configured source (1–255 safe ASCII identifier characters). One event per JSON request: `HARD_BOUNCE`, `SOFT_BOUNCE`, `DELIVERED`, `COMPLAINT`, or `UNSUBSCRIBED`. An identical external ID and exact original payload replays with HTTP 200 and `reused=true`; a changed payload returns 409. A new event returns 201 with an ID. Event source, address, time, sanitized code, interpretation, and observation time are persisted. Correction uses `POST /api/delivery-events/{id}/revoke` with `{"reasonCode":"SOURCE_CORRECTION"}`; it records revocation and lifts only the linked suppression. Neither deletion nor silent replacement of audit history is supported.

Only a `HARD_BOUNCE` with exact enhanced code `5.1.1` is recipient-specific negative evidence and starts a suppression. Other hard bounces are `UNCERTAIN_BOUNCE`; a soft bounce with `4.x.x` is transient, and other soft codes remain uncertain. `COMPLAINT` and `UNSUBSCRIBED` start independent suppressions. `DELIVERED` is historical delivery evidence only: it never proves current mailbox validity or automatically lifts a bounce, complaint, unsubscribe, or manual suppression. The HTTP feed accepts at most 32 visible ASCII status characters other than `@`; malformed codes in that range retain only a SHA-256 fingerprint for exact replay and never suppress. Reject raw server replies, transcripts, and addresses in status fields.

`POST /api/suppressions` accepts `{"email":"sample@fixture.test","reasonCode":"OPERATOR_BLOCK"}` and creates a `MANUAL` suppression with the supplied reason in its audit column. `GET /api/suppressions?limit=50&after=<uuid>` lists historical entries in UUID order, including lifted rows; `limit` is 1–100. `DELETE /api/suppressions/{id}` takes `{"reasonCode":"OPERATOR_RELEASE"}` and audits a lift, not an erasure. Active manual and event suppressions have no automatic expiry. Only an explicit lift or audited event revocation changes them; multiple simultaneous reasons require lifting each relevant record. Missing/wrong/empty configured keys return 401; invalid JSON/IDs/fields return 400, bodies over 8 KiB return 413, unknown IDs 404, and conflicting replays/lifts 409. Error bodies are fixed and contain no supplied email, source ID, or event ID.

The address key preserves the exact local part (including quoted characters and case) and converts only the domain to canonical lowercase ASCII IDN. Thus `User@BÜCHER.example` matches `User@xn--bcher-kva.example`, while `user@...` is a different identity. The external-event replay comparison additionally requires the original email string exactly; normalization never silently changes source payload identity. The lookup has one indexed PostgreSQL snapshot: the latest unrevoked event by occurrence/observation time, plus an independently active suppression. `DELIVERY_FRESH_DAYS` defaults to 30 (allowed 1–3650); `freshness` is `FRESH`, `STALE`, or `NONE`. A stale event remains historical, and an existing suppression remains active. Empty lookup returns `available=false`, `freshness=NONE`, `suppressed=false`; evidence-store failure is `UNAVAILABLE` with unknown suppression, never a fabricated negative result.

Without a key, `POST /api/email/validate` still returns exactly seven legacy fields. With a valid key, a format-valid request can include `historicalEvidence` alongside the unchanged `UNVERIFIED` category; malformed syntax skips DNS and historical lookup. If only historical lookup fails while the normal history database remains writable, the authorized response reports `UNAVAILABLE` and `suppressed=null`. Completed Phase 7 jobs and Phase 8-promoted jobs persist an optional snapshot in V5's nullable JSONB column under the existing lease fence. Those completed snapshots are not retroactively edited by a later correction. Bulk CSV progress, result rows, and export columns remain unchanged; inspect each unique job through authenticated `GET /api/jobs/{id}` when historical detail is needed. This does not merge the separate internal `EvidenceClassifier` into the public category.

V5 is additive to applied V1–V4, with immutable event/suppression rows except audited first corrections/lifts and indexes on recent evidence and active suppressions. The configured five-connection pool bounds database work; no Valkey cache is involved. Delivery tables, addresses, job snapshots, IDs, and backups have **no approved retention or legal-hold policy**; database owners can bypass row-level audit triggers with `TRUNCATE`. The shared key grants every holder all event and suppression access, not tenant isolation. Set owner-scoped access, retention/deletion and backup-expiry rules, rate limits, audit privileges, and TLS/egress policy before public deployment. No bulk performance benchmark or production DNS capacity claim follows from Phase 9.

## Disposable DNS cache and HTTP rate limits (Phase 10)

Both features default **off**, so startup and existing development routes do not require Valkey. The Compose service binds Valkey to `127.0.0.1` without authentication and keeps at most 128 MiB without persistence or eviction; never expose it outside trusted local development. Start Compose and the database as above. For a private local test, set these API environment variables before launching the application:

```powershell
$env:VALKEY_NAMESPACE = 'mailguard-local'
$env:RATE_LIMIT_IDENTITY_SECRET = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:DNS_CACHE_ENABLED = 'true'
$env:RATE_LIMIT_ENABLED = 'true'
mvn -B spring-boot:run
```

Keep `RATE_LIMIT_IDENTITY_SECRET` private and stable across API instances sharing one namespace; use distinct namespaces and credentials between environments. The app connects to `VALKEY_HOST` (`127.0.0.1`), `VALKEY_PORT` (`6379`), optional `VALKEY_USERNAME`/`VALKEY_PASSWORD`, and `VALKEY_TLS` (`false` for local Compose). For production, require Valkey TLS, authentication, private network access, memory capacity and monitored availability. `VALKEY_CONNECT_TIMEOUT`, `VALKEY_COMMAND_TIMEOUT`, `VALKEY_POOL_MAX_ACTIVE`, `VALKEY_POOL_MAX_IDLE` and `VALKEY_POOL_MAX_WAIT` bound connection usage and timeout; defaults are 200 ms, 200 ms, eight, eight and 200 ms.

Configuration rejects empty/oversized Valkey hosts, ports outside 1–65535, negative pool waits, command/connect timeouts outside 1–2000 ms, pools above 64 or idle above active, and invalid feature quotas. `VALKEY_NAMESPACE` must match 1–32 ASCII letters/digits/`_`/`-`. Enabled limiting requires a stable 32–256-byte identity secret and a nonempty `JOBS_API_KEY` of at most 256 bytes; limiter capacities are 1–100,000 and refill windows 1–3,600 seconds. DNS positive/negative caps are 1–86,400 seconds, entries 128–65,536 bytes, local in-flight domains 1–4,096, and coalescing waits 1–30,000 ms. Defaults are shorter.

DNS entries are keyed by a versioned canonical domain hash, not by the email address. Positive observations expire no later than `DNS_CACHE_POSITIVE_TTL_SECONDS=30`; reliable negatives expire no later than `DNS_CACHE_NEGATIVE_TTL_SECONDS=5`. TTLs may be shorter when DNS records or negative SOA require it. Unknown, partial, transient, zero-TTL and unproven negative outcomes are not cached. `DNS_CACHE_MAX_ENTRY_BYTES=8192`, `DNS_CACHE_MAX_INFLIGHT=128` and `DNS_CACHE_WAIT_MILLIS=2500` bound payload and local request coalescing. TTL expiry or changing the namespace invalidates observations; there is no instant DNS invalidation. If Valkey fails, a lookup uses the existing bounded DNS resolver and does not serve stale cache data. PostgreSQL still owns the current historical evidence, suppression state, immutable completed-job snapshots and validation-history writes.

The stored DNS observation still contains the canonical domain and ordered MX hosts; hashing the key does **not** anonymize that value. Restrict Valkey access and backup/log export accordingly. An implicit A/AAAA route is cached only when a reliable negative MX answer and a positive address RR both provide an unexpired TTL.

Distributed admission uses one Redis-server-time Lua operation per public request: the global and HMAC-hashed socket-peer buckets share a timestamp and spend tokens only when both admit. Rejected peers cannot drain the global bucket; a global rejection creates no peer key. Administrative routes use single-key atomic buckets. Defaults per `RATE_LIMIT_WINDOW_SECONDS=60` refill window: validation 60 per socket peer plus 600 public aggregate; diagnostic `/api/email/test` 120 per peer plus the same aggregate; job write 30/read 120; import create/cancel 6, progress/results 120, export 12; delivery event ingest/revoke 60; evidence lookup 120; suppression create/lift 30 and list 60. Override individual values with matching `RATE_LIMIT_*_CAPACITY` environment variables. The source socket peer is used directly; forwarded IP headers are ignored unless a separate trusted-proxy policy is added. No addresses, raw peer IPs, API keys or request IDs appear in limiter keys or metric tags. Exceeding quota returns HTTP 429 with `Retry-After`, bounded rate headers and a sanitized JSON error; successful response bodies and the old shared-key checks are unchanged. Spring MVC's automatic OPTIONS requests preserve `Allow` without consuming quota; application-defined OPTIONS handlers remain subject to policy. The Phase 7 backlog cap may independently return 429.

If Valkey is unavailable, authenticated administrative routes fail closed with HTTP 503. Public validation and the diagnostic route use a local, shared, bounded fallback of `RATE_LIMIT_PUBLIC_FALLBACK_CAPACITY=30` per process/window; intermittent backend success or rejection does not reset that window. The fallback is **not** cluster-wide and may return 429. The two-key Lua operation targets a single Valkey instance: Valkey Cluster is not supported because the global and peer keys lack a shared hash slot. Background workers have no HTTP admission request and continue to use bounded DNS, PostgreSQL leases and job admission. Metrics have fixed route/outcome labels; `MANAGEMENT_ENDPOINTS` defaults to `health` only. Expose `health,metrics` only on private operator ingress. This does not add tenant authorization, approved personal-data retention, external SMTP, provider webhooks or production DNS capacity evidence.

`VALKEY_HEALTH_ENABLED` defaults to `false`: an intentionally absent cache/limiter must not mark an otherwise functional API unhealthy. Set it to `true` when your deployment requires Valkey readiness, and alert on rate backend errors when public fallback is in use. Administrative admission still fails closed on an outage regardless of health settings.

Operator-only metric names: `dns.resolver.lookup` and `dns.cache.lookup` timers, `dns.cache.operations` with fixed `result` values, `mailguard.http.rate.backend` timer with fixed policy, and `mailguard.http.rate.requests` with fixed policy/result. No raw address, request path, API key, peer IP or DNS domain is a metric label.

Optional, **do not run in default verification**: `mvn -B "-Dvalkey.benchmark=true" "-Dtest=Phase10BenchmarkTest" test` starts disposable PostgreSQL and Valkey containers. Six synthetic cases report cached/uncached DNS latency, concurrent cache-hit throughput, Redis token-bucket overhead, Valkey GET latency, and a 300-row real API/worker CSV import both with and without cache. DNS is a loopback-free in-process fixture, not an external resolver; results are local measurements only, not a production throughput or p95 promise.

## React operations dashboard (Phase 11)

The separate [React/TypeScript/Vite client](frontend/) uses the existing API without changing its routes or sharing backend credentials with Vite. It displays live health, individual validation, CSV imports with ordered results and export, jobs, exact-address delivery evidence, and suppression management. [The endpoint inventory](frontend/API.md) records method, authorization, response and pagination contracts. Import progress accepts the complete backend state machine: `INGESTING`, `READY`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED`; the first three continue polling and only the last three stop it. Session history contains only the ten most recent validations performed in this tab. Recent jobs and imports each retain up to 50 distinct IDs in newest-first order; they appear only when opened in this session or by entering a known UUID. The backend offers no global lists or validation-history read endpoint. There are no fabricated activity totals. The UI preserves `UNVERIFIED`, nullable MX/SMTP, and stale or unavailable historical evidence as uncertainty, not deliverability.

For local development, start PostgreSQL and Valkey as documented above. In one PowerShell terminal, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` to the existing local Compose database, generate a private `JOBS_API_KEY`, and run the API. For the independent worker, use the same database and key variables in another terminal; set `JOBS_WORKER_ENABLED=true`, then run the packaged jar with `--spring.main.web-application-type=none`. For trusted delivery-event ingestion also configure a stable nonempty `DELIVERY_SOURCE_ID`; do not submit synthetic events outside disposable tests. Never paste the administrative key into a `VITE_*` variable or a URL.

```powershell
# API terminal, after local PostgreSQL/Valkey start; set DB_* as above.
$secret = Read-Host 'Local administrator API key' -AsSecureString
$env:JOBS_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
mvn -B package
java -jar target/mailguard-0.0.1-SNAPSHOT.jar

# Separate worker terminal: copy the same DB_* and JOBS_API_KEY securely.
$env:JOBS_WORKER_ENABLED = 'true'
java -jar target/mailguard-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none

# Separate frontend terminal:
cd frontend
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`. The development server proxies `/api` and `/actuator/health` to `http://127.0.0.1:8080`; override only its upstream URL with `API_PROXY_TARGET` when the backend uses another local port. The browser uses relative same-origin paths, `credentials: omit`, `cache: no-store`, and a short-lived in-memory administrative key entered via **Connect**. The key disappears on disconnect, reload or tab close. Only the visual theme persists in localStorage when permitted; blocked or unavailable storage falls back to light and theme switching still works in memory. This shared key controls all administrative records, not user accounts or tenant access. Do not publish this UI or expose the dev proxy as a production auth boundary.

Production serving requires a separately approved TLS reverse proxy serving `frontend/dist` and forwarding `/api/*` plus `/actuator/health` to the API on the **same origin**, with SPA deep-link fallback to `index.html` only for frontend routes. Restrict key distribution, exports, address history and proxy logs; do not enable wildcard CORS or embed the shared key in built assets. Add owner-scoped authorization, approved retention/deletion and backup policy, deployment egress controls, and production identity before internet or multi-user exposure. Phase 11 does not add those controls.

Dashboard checks from `frontend/`: `npm ci`, `npm run lint`, `npm run typecheck`, `npm run test`, `npm run build`; then `npx playwright install chromium`, `mvn -B package` from repo root, and `npm run test:e2e` from `frontend/`. Browser tests start uniquely named disposable PostgreSQL 17 and Valkey 8 containers and the packaged API plus headless worker. They reserve ports `127.0.0.1:18094`, `127.0.0.1:16393` and `127.0.0.1:5173`, create no persistent volumes, and remove only their own containers and child processes. Docker Linux daemon and Java 21 must be available. The tests use synthetic addresses; they never contact SMTP.

## 📧 Email Validation
### Validate an Email Address

Request

```http
POST /api/email/validate
```

Request Body

```json
{
  "email": "john@example.com"
}
```

Invalid request bodies return HTTP 400 with a fixed message; unexpected errors return HTTP 500 with a generic message. Error responses retain `timestamp`, `status`, `message`, and `path` fields. Known API routes retain their path; unknown paths are redacted to `/`.

Response when an MX record is observed (example):

```json
{
  "email": "john@example.com",
  "domain": "example.com",
  "formatValid": true,
  "mxRecordFound": true,
  "smtpVerified": null,
  "status": "UNVERIFIED",
  "message": "MX record observed; mailbox not verified."
}
```

## 🌐 DNS Routing Observation
The Engine 1 validator supplies a canonical ASCII domain. The DNS engine queries its absolute name for MX records. A sole priority-0 MX pointing to `.` explicitly declines mail ([RFC 7505](https://www.rfc-editor.org/rfc/rfc7505.html#section-3)). With no MX answer, it checks the domain's A and then AAAA for an implicit route. With MX records, it instead checks exchanger A/AAAA addresses in preference order and stops at the first usable target ([RFC 5321, §5.1](https://www.rfc-editor.org/rfc/rfc5321.html#section-5.1)). NXDOMAIN does not trigger implicit fallback. MX-target CNAMEs are outside RFC 5321's address-record rule and remain uncertain. DNS routing evidence does not prove an SMTP server accepts mail or that any mailbox exists.

| DNS observation | Internal outcome | `mxRecordFound` | Message |
| --- | --- | --- | --- |
| MX with a usable exchanger address | `MAIL_ROUTE` | `true` | `MX record observed; mailbox not verified.` |
| Earlier exchanger failed, later MX target has an address | `MAIL_ROUTE` (degraded) | `true` | `MX route observed with an unresolved target; mailbox not verified.` |
| MX target lookup failed temporarily; no usable target observed | `UNKNOWN` (retryable) | `true` | `MX target address lookup unavailable; mailbox not verified.` |
| MX target has no A/AAAA; no usable target observed | `NO_MAIL_ROUTE` | `true` | `MX record observed without a usable target address; mailbox not verified.` |
| MX target returned malformed data or CNAME | `UNKNOWN` | `true` | `MX target address lookup unavailable; mailbox not verified.` |
| No MX, but the domain has a usable A/AAAA address | `MAIL_ROUTE` | `false` | `Implicit address route observed; mailbox not verified.` |
| Sole Null MX | `MAIL_NOT_ACCEPTED` | `false` | `Null MX declares mail not accepted; mailbox not verified.` |
| NXDOMAIN or no MX/A/AAAA route | `NO_MAIL_ROUTE` | `false` | `No MX record observed; mailbox not verified.` |
| Initial MX lookup timeout, SERVFAIL, refusal, I/O or malformed answer | `UNKNOWN` | `null` | `DNS lookup unavailable; mailbox not verified.` |

The nullable Boolean preserves the API and history schema: `true` means an MX was observed even if its target is unusable or uncertain, not that mail can be delivered. Internal `DnsObservation` contains outcome, reason, canonical domain, ordered MX preferences/hosts, retryability, and observation time. It is not persisted or returned in the API. `status` is `INVALID` only for local format failures. Every format-valid input remains `UNVERIFIED` with `smtpVerified=null`; no SMTP probe, outbound HTTP lookup, or email sending occurs.

The optional Phase 10 cache stores only eligible DNS observations with bounded TTLs; disabling it queries the resolver anew. Production DNS uses a 2-second per-resolver timeout with one attempt per configured server and a 2-second overall retry window; in-flight requests cannot be canceled by that window. The service admits at most 16 concurrent DNS operations, 24 resolver queries per observation, eight alias hops, 32 MX records, and 128 answer records per response. Many MX targets can exhaust the query budget; internal `DNS_QUERY_BUDGET_EXCEEDED` stays retryable `UNKNOWN`, preserving the same public Boolean and message as a failed target lookup. These bounds do not prove availability under load; no external resolver capacity is claimed.

Address evidence currently rejects unspecified/wildcard and multicast IPs, but accepts loopback, private, link-local, unique-local IPv6, and other non-public or reserved ranges. It measures DNS address presence, not public reachability. Changing route classification would change existing API/history results and documentation-address fixtures; decide a public-address and egress policy before any future SMTP probe. `mxRecordFound=true` still means only that an ordinary MX was advertised, including a dangling exchanger with no address; it never means that route is usable.

## Isolated SMTP fixture engine

Engine 3 is not part of `/api/email/validate`: the API never connects to SMTP, and `smtpVerified` remains `null`. Its default policy is disabled. Tests opt in with `SmtpVerificationPolicy.localOnly(port)`, which accepts only the exact numeric loopback addresses `127.0.0.1` or `::1` and the configured port. The transport pins that address without DNS resolution. External SMTP, hostnames, private network destinations and public API activation are unsupported; no production authorization or egress policy has been approved.

The fixture sends EHLO (or one HELO fallback), MAIL FROM, RCPT TO and, when possible, QUIT. It never sends DATA, message content or random catch-all probes. A 250/251 response means recipient acceptance at that stage only, not mailbox existence or delivery; there is no boolean mailbox-success adapter. Only a contextual 550 5.1.1 is negative recipient evidence; bare 550, policy failures and timeouts remain uncertain. Catch-all status is unknown.

Engine 3 is present in `origin/main` after PR #4. The API remains passive; activating SMTP requires separate authorization and egress approval.

## Internal evidence classification

`EvidenceClassifier` combines a local syntax result, optional `DnsObservation`, and optional SMTP evidence into an immutable `ClassificationResult`. Categories are `INVALID_SYNTAX`, `DOMAIN_NOT_FOUND` (DNS NXDOMAIN), `MAIL_NOT_ACCEPTED` (sole Null MX), `ROUTE_AVAILABLE` (usable DNS route), `ROUTE_UNCERTAIN` (absent, unusable, conflicting, or unknown route evidence), `RECIPIENT_ACCEPTED_UNCONFIRMED` (RCPT 250/251), and `RECIPIENT_REJECTED` (RCPT 550 with enhanced 5.1.1 and matching reason). Neither an MX route nor SMTP acceptance proves mailbox existence or delivery. A missing DNS route is not the same as NXDOMAIN; ambiguous or temporary SMTP refusals never become recipient rejection.

Classifier evidence retains syntax and DNS reason codes, canonical DNS domain and MX hosts, source and observation times. Optional SMTP snapshots retain typed outcome/reason and sanitized enhanced code, not transcripts. The classifier remains internal and in memory (D-024); it has no TTL or expiry policy. Phase 9 adds a separate delivery-history snapshot with freshness, not persisted classifier/DNS/SMTP observations. Existing public validation categories remain `INVALID` for bad syntax and `UNVERIFIED` for every format-valid address. No SMTP call or mailbox-success claim is added.

## Supported Email Syntax
MailGuard accepts ASCII dot-atoms (including `+` tags) and whole quoted ASCII local parts with printable escaped characters and quoted spaces. It rejects comments, folding whitespace, domain literals, SMTPUTF8 local parts, empty components, and unquoted spaces. This is a supported subset of RFC 5321/5322, not a full RFC parser.

Domains require at least two public-hostname labels and a nonnumeric final ASCII label; dotted IPv4 text and numeric TLDs are rejected. Unicode domain labels are NFC-normalized and converted to lowercase ASCII IDNA labels for DNS, the response `domain`, and history `domain`; the original address remains unchanged in the response and existing history. Java IDN uses IDNA2003/Nameprep with STD3 checks and round-trip validation, not full IDNA2008. These checks do not establish public TLD registration, mailbox reachability, or homograph safety.

Limits apply to the normalized SMTP ASCII representation: local part 64 bytes, each domain label 63 bytes, domain 253 bytes, total address 254 bytes. Raw input above 254 UTF-16 code units is unsupported. Address syntax failures within the request size limit return HTTP 200 and `status=INVALID`; the separate 255-character request limit still returns HTTP 400 when exceeded. Structured reason codes are internal; public response fields are unchanged.

## 📚 REST API
### Base URL

```
http://localhost:8080
```

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/email/validate` | Legacy seven-field validation without a key; a valid shared key permits an optional historical evidence field |
| POST | `/api/email/test` | Return the existing API diagnostic string |
| POST | `/api/jobs` | Submit a durable job (shared API key required) |
| GET | `/api/jobs/{id}` | Read a job and completed result (shared API key required) |
| DELETE | `/api/jobs/{id}` | Cancel outstanding work (shared API key required) |
| POST | `/api/imports` | Stage a bounded CSV upload (shared API key required) |
| GET | `/api/imports/{id}` | Read durable import progress (shared API key required) |
| GET | `/api/imports/{id}/results` | Read ordered paged rows (shared API key required) |
| GET | `/api/imports/{id}/export` | Stream ordered CSV results (shared API key required) |
| DELETE | `/api/imports/{id}` | Cancel unprocessed rows and linked jobs (shared API key required) |
| POST | `/api/delivery-events` | Ingest one authorized delivery event (shared key required) |
| POST | `/api/delivery-events/{id}/revoke` | Audit a correction and lift its linked suppression |
| POST | `/api/delivery-evidence/lookup` | Read one historical snapshot by address |
| GET | `/api/suppressions` | List suppression history, UUID keyset pagination |
| POST | `/api/suppressions` | Create a manual suppression |
| DELETE | `/api/suppressions/{id}` | Audit an explicit suppression lift |

## 📂 Project Structure
The root Maven project contains the Spring Boot API in `src/main/java/com/aash/mailguard` and offline tests in `src/test/java`.

## 🛠 Technologies
- Java 21
- Spring Boot 4
- Spring Data JPA
- PostgreSQL 17 and Flyway
- Maven
- DNSJava
- Swagger (OpenAPI)
- Lombok

## 📄 License and redistribution
The upstream [README](https://github.com/aasheesh06/MailGuard) claims MIT and names Copyright (c) 2026 Aasheesh Verma. This derivative preserves that attribution and includes canonical MIT text in [LICENSE](LICENSE), added by the derivative maintainer. Upstream provides no standalone LICENSE or independently confirmed grant; the derivative file does **not** establish upstream permission. Redistribution approval remains blocked pending upstream rights confirmation and a full dependency-license review.