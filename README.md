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
Start the Spring Boot application only after PostgreSQL is healthy. Flyway creates V1 on an empty database before Hibernate validates the schema. An existing nonempty database without Flyway history is refused; Hibernate will not repair or drop tables. Production pool defaults: five maximum and one minimum idle connection; adjust `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`, `DB_POOL_CONNECTION_TIMEOUT_MS`, `DB_POOL_IDLE_TIMEOUT_MS` and `DB_POOL_MAX_LIFETIME_MS` only after measuring workload.

Once the application starts successfully, open your browser:

```
http://localhost:8080/swagger-ui/index.html
```

You can now test all available email validation APIs directly from Swagger UI.

## PostgreSQL cutover and recovery

Flyway V1 creates history only on a **new empty PostgreSQL database**. It neither reads nor imports MySQL rows; changing `DB_URL` alone does not migrate existing installations. Automatic Flyway baseline and clean are disabled, and Hibernate validates rather than altering production schema. Keep a previous MySQL application build and its data intact until a separate migration is approved.

**Phase 6 supports fresh PostgreSQL 17 deployments only.** An installation with existing MySQL history is not eligible for a direct upgrade: this branch has no MySQL reader, import command, dual write or approved retention policy. No production MySQL inventory is available here; absence of a local MySQL container or connection does not prove deployed data is absent. Keep the old application and database until the owner confirms no rows need preservation or approves a separate rehearsed migration.

1. Before changing an existing installation, freeze writes and obtain owner approval for address storage, access, retention and deletion. Record the actual MySQL DDL, row count, id range, null counts, charset/collation, status/message lengths, timestamp semantics and transaction isolation. Back up the complete MySQL database using an approved consistent snapshot tool; encrypt, restrict and **test a restore** in a separate environment. Do not put credentials or exported addresses in this repository.
2. Provision a separate empty PostgreSQL staging database with no client traffic. Run the new application once to apply Flyway V1, then stop it before importing. Design and rehearse an explicit, audited transfer from the backed-up MySQL rows: preserve ids and original text, map nullable MySQL booleans to PostgreSQL booleans, validate UTF-8 and 255-character limits, and convert MySQL local timestamps to UTC instants **only after confirming their source timezone**. Reject or quarantine ambiguous/invalid rows rather than truncating, dropping or inventing data. This repository has no import script.
3. Reset the PostgreSQL identity to follow imported ids. Reconcile row counts, null distributions and representative records against the backup; verify Flyway history and Hibernate validation, then back up the PostgreSQL result. Test the passive API against the staged database. Only then plan a controlled traffic/write cutover; do not assume MySQL's usual `REPEATABLE READ` behavior under PostgreSQL's default `READ COMMITTED`.
4. Before any PostgreSQL writes, rollback means stop the new application and restart the preserved MySQL build against its unchanged database after checking its backup. **After PostgreSQL accepts writes, reverting directly to MySQL loses new records.** Pause writes, retain both snapshots and reconcile the delta or restore the PostgreSQL backup into a separate instance before any further cutover. Never delete a populated volume or run Flyway clean as a rollback.

If MySQL rows must be preserved, the **proposed, not implemented** importer is a separately invoked, access-controlled tool. Rehearse it against a consistent, encrypted MySQL snapshot and a newly provisioned isolated PostgreSQL database after Flyway V1. Stream source rows by primary-key order into typed, batched inserts without logging addresses; preserve original ids and nullable values. Confirm MySQL `DATETIME` timezone, `utf8mb4` conversion, boolean encodings and 255-character bounds before mapping. Fail and roll back on duplicates, truncated text, unknown timezone or invalid rows. Compare counts, null distributions, id ranges and protected canonical row digests; reset and test identity generation, then restore the PostgreSQL snapshot to another disposable database and start Hibernate validation before cutover. Rehearse failed imports and a post-cutover write delta. Do not run this proposal against an existing database without explicit approval.

The existing table retains full addresses without an approved retention/access policy; no public history-read endpoint is provided. Recent-history repository reads accept pagination and order by timestamp then id; an authorized caller must impose a page-size limit before exposing them. The classifier's typed DNS/SMTP observations are **not** persisted: it is not used by the live API, and authorization, retention, provenance and TTL are unresolved. D-025 documents an additive, privacy-preserving future schema. Phase 6 does not add jobs, CSV, Valkey, SMTP activation, a worker or a UI.

## 📖 Usage
MailGuard checks local email format, then observes DNS routing for format-valid input. It does not connect to recipient mail servers. Requests within the 255-character storage limit retain history writes. Longer values and malformed JSON return HTTP 400 before DNS or persistence; format-invalid email strings retain HTTP 200 with `status=INVALID`.

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

No DNS response is cached in this milestone: positive, negative, and transient results are each queried anew. TTL expiry and shared Valkey caching remain planned, not implemented. Production DNS uses a 2-second per-resolver timeout with one attempt per configured server and a 2-second overall retry window; in-flight requests cannot be canceled by that window. The service admits at most 16 concurrent DNS operations, 24 resolver queries per observation, eight alias hops, 32 MX records, and 128 answer records per response. Many MX targets can exhaust the query budget; internal `DNS_QUERY_BUDGET_EXCEEDED` then stays retryable `UNKNOWN`, preserving the same public Boolean and message as a failed target lookup. These bounds do not prove availability under load; no load benchmark or production DNS test has run.

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