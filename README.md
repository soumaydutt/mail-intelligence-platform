# MailGuard
![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green?style=for-the-badge&logo=springboot)
![Maven](https://img.shields.io/badge/Maven-Build-red?style=for-the-badge&logo=apachemaven)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue?style=for-the-badge&logo=postgresql)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-green?style=for-the-badge&logo=swagger)

MailGuard exposes a synchronous, passive email check: local format validation followed by bounded DNS routing observation. It does not verify a mailbox or send email.

This repository preserves the history and attribution of [the original MailGuard](https://github.com/aasheesh06/MailGuard). The broader email intelligence architecture in [PLAN.md](PLAN.md) is planned, not implemented.

---
## ✨ Features
- ✔ Email format validation
- ✔ Domain extraction, MX advertisement, Null MX, and implicit A/AAAA routing observation
- ✔ Explicit unknown DNS outcome; no SMTP connection
- ✔ REST API built with Spring Boot
- ✔ Swagger/OpenAPI documentation
- ✔ PostgreSQL validation history with versioned Flyway schema
- ✔ PostgreSQL-backed single-address jobs with a separately runnable, bounded worker
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

Requires Java 21, Maven, Docker with a running Linux daemon for PostgreSQL integration tests and local Compose, and a reachable PostgreSQL 17 database for the application. The production configuration requires `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`; it has no implicit database fallback. Keep credentials in the username/password environment variables, not in `DB_URL`, which may appear in startup logs. The HTTP regression suite uses isolated H2 and disables Flyway; all PostgreSQL Testcontainers cases are mandatory and do not skip when Docker is unavailable. A first run needs Maven dependencies and `postgres:17-alpine` available locally; pre-cache both before disconnected use. No external DNS or SMTP is required for the tests.

On Windows PowerShell, start the **local-only** PostgreSQL service with a unique local password:

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

Stop the application with Ctrl+C, then run `docker compose stop` from a shell with the same Compose credentials. The named PostgreSQL volume and history survive restarts; do **not** run `docker compose down -v` against data you intend to keep. `DB_PORT` changes the localhost host port (default `5432`); update `DB_URL` to match. `DB_NAME` changes the initial database name (default `mailguard`). Compose is for local development, not production credential storage. On other shells, set equivalent environment variables before running the same commands.

## 🚀 Quick Start
Start the Spring Boot application only after PostgreSQL is healthy. Flyway creates V1 and V2 on an empty database; on a compatible V1 installation, V2 adds jobs without replacing existing history. An existing nonempty database without Flyway history is refused; Hibernate will not repair or drop tables. Production pool defaults: five maximum and one minimum idle connection; adjust `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`, `DB_POOL_CONNECTION_TIMEOUT_MS`, `DB_POOL_IDLE_TIMEOUT_MS` and `DB_POOL_MAX_LIFETIME_MS` only after measuring workload.

Once the application starts successfully, open your browser:

```
http://localhost:8080/swagger-ui/index.html
```

You can now test all available email validation APIs directly from Swagger UI.

## PostgreSQL cutover and recovery

Flyway V1 initializes a **new empty PostgreSQL 17 database**; Hibernate validates the schema rather than updating it. The owner reports that the previous MySQL database has two irrelevant records and explicitly does not want them imported. The old MySQL database is **not required** for the new installation. Do not delete its database, containers or volumes as part of this cutover.

Start with a separate empty PostgreSQL database and the `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` environment variables above. On first startup, verify successful Flyway V1 and V2 entries, the history table and `verification_job`. A compatible V1 database upgrades to V2 while retaining its history rows. Subsequent startups must reuse that schema and preserve its rows. No MySQL reader, import, dual-write or cross-engine migration is part of this installation.

**Never reset an existing populated PostgreSQL database automatically.** Flyway baseline and clean are disabled; Hibernate uses `ddl-auto=validate`. If V1 is already recorded and the schema matches, V2 adds the job table and history reference without rewriting V1. If a populated database lacks Flyway history or its checksum/schema is incompatible, startup must fail: investigate and back up the database rather than dropping tables, baselining, repairing or rerunning V1. PostgreSQL backup and restore, not the unused MySQL database, are the recovery path for new writes.

The existing table retains full addresses without an approved retention/access policy; no public history-read endpoint is provided. Recent-history repository reads accept pagination and order by timestamp then id; an authorized caller must impose a page-size limit before exposing them. The classifier's typed DNS/SMTP observations are **not** persisted: it is not used by the live API, and authorization, retention, provenance and TTL are unresolved. D-025 documents an additive, privacy-preserving future schema. Phase 6 does not add jobs, CSV, Valkey, SMTP activation, a worker or a UI.

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

The API defaults to `JOBS_WORKER_ENABLED=false`; the headless process serves no HTTP. `mvn -B package` builds the jar. `POST /api/jobs` accepts `{"email":"bad-address"}` with `X-Job-API-Key` and an optional printable ASCII `Idempotency-Key` (1–128 characters). It returns HTTP 202 with `{ "id": "<uuid>", "reused": false }` and `Location: /api/jobs/<uuid>`. `GET /api/jobs/<uuid>` returns `state`, `attempts`, `maxAttempts`, due/creation/completion times, a safe failure code if applicable, and the existing seven-field `result` **only** when completed. Invalid email syntax is a completed `INVALID` result, not a failed job. Existing `/api/email/validate` and `/api/email/test` stay synchronous and unchanged.

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

Run the same API and headless worker as above, backed by the same PostgreSQL database. Every import route requires the nonempty `X-Job-API-Key`; this is one trusted principal, **not** tenant isolation. Use TLS and private network access. The existing synchronous routes and single-address job API are unchanged.

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

No DNS response is cached in this milestone: positive, negative, and transient results are each queried anew. TTL expiry and shared Valkey caching remain planned, not implemented. Production DNS uses a 2-second per-resolver timeout with one attempt per configured server and a 2-second overall retry window; in-flight requests cannot be canceled by that window. The service admits at most 16 concurrent DNS operations, 24 resolver queries per observation, eight alias hops, 32 MX records, and 128 answer records per response. Many MX targets can exhaust the query budget; internal `DNS_QUERY_BUDGET_EXCEEDED` then stays retryable `UNKNOWN`, preserving the same public Boolean and message as a failed target lookup. These bounds do not prove availability under load; no production DNS load benchmark has run.

Address evidence currently rejects unspecified/wildcard and multicast IPs, but accepts loopback, private, link-local, unique-local IPv6, and other non-public or reserved ranges. It measures DNS address presence, not public reachability. Changing route classification would change existing API/history results and documentation-address fixtures; decide a public-address and egress policy before any future SMTP probe. `mxRecordFound=true` still means only that an ordinary MX was advertised, including a dangling exchanger with no address; it never means that route is usable.

## Isolated SMTP fixture engine

Engine 3 is not part of `/api/email/validate`: the API never connects to SMTP, and `smtpVerified` remains `null`. Its default policy is disabled. Tests opt in with `SmtpVerificationPolicy.localOnly(port)`, which accepts only the exact numeric loopback addresses `127.0.0.1` or `::1` and the configured port. The transport pins that address without DNS resolution. External SMTP, hostnames, private network destinations and public API activation are unsupported; no production authorization or egress policy has been approved.

The fixture sends EHLO (or one HELO fallback), MAIL FROM, RCPT TO and, when possible, QUIT. It never sends DATA, message content or random catch-all probes. A 250/251 response means recipient acceptance at that stage only, not mailbox existence or delivery; there is no boolean mailbox-success adapter. Only a contextual 550 5.1.1 is negative recipient evidence; bare 550, policy failures and timeouts remain uncertain. Catch-all status is unknown.

Engine 3 is present in `origin/main` after PR #4. The API remains passive; activating SMTP requires separate authorization and egress approval.

## Internal evidence classification

`EvidenceClassifier` combines a local syntax result, optional `DnsObservation`, and optional SMTP evidence into an immutable `ClassificationResult`. Categories are `INVALID_SYNTAX`, `DOMAIN_NOT_FOUND` (DNS NXDOMAIN), `MAIL_NOT_ACCEPTED` (sole Null MX), `ROUTE_AVAILABLE` (usable DNS route), `ROUTE_UNCERTAIN` (absent, unusable, conflicting, or unknown route evidence), `RECIPIENT_ACCEPTED_UNCONFIRMED` (RCPT 250/251), and `RECIPIENT_REJECTED` (RCPT 550 with enhanced 5.1.1 and matching reason). Neither an MX route nor SMTP acceptance proves mailbox existence or delivery. A missing DNS route is not the same as NXDOMAIN; ambiguous or temporary SMTP refusals never become recipient rejection.

Evidence retains syntax and DNS reason codes, canonical DNS domain and MX hosts, source and observation times. The optional SMTP snapshot retains typed outcome/reason, reply and sanitized enhanced code, but not host, address, reply text, or transcript. Results include a safe diagnostic, reason code, computation time, and retryability. No TTL or expiry policy exists, so evidence freshness is unknown. This is an internal, in-memory milestone (D-024), not the Phase 5 local product in [PLAN.md](PLAN.md). The public API and PostgreSQL history still return `INVALID` only for invalid syntax and `UNVERIFIED` for every format-valid input, without new fields or SMTP calls.

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
| POST | `/api/email/validate` | Validate an email address |
| POST | `/api/email/test` | Same synchronous validation contract |
| POST | `/api/jobs` | Submit a durable job (shared API key required) |
| GET | `/api/jobs/{id}` | Read a job and completed result (shared API key required) |
| DELETE | `/api/jobs/{id}` | Cancel outstanding work (shared API key required) |
| POST | `/api/imports` | Stage a bounded CSV upload (shared API key required) |
| GET | `/api/imports/{id}` | Read durable import progress (shared API key required) |
| GET | `/api/imports/{id}/results` | Read ordered paged rows (shared API key required) |
| GET | `/api/imports/{id}/export` | Stream ordered CSV results (shared API key required) |
| DELETE | `/api/imports/{id}` | Cancel unprocessed rows and linked jobs (shared API key required) |

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

## 📄 License
MIT License

Copyright (c) 2026 Aasheesh Verma

This project is licensed under the MIT License. See [LICENSE](LICENSE) for the full text.