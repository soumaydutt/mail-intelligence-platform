# MailGuard live backend verification — 2026-11-21

**Local browser-to-database verification: PASS. Public-production approval: BLOCKED. Independent Gemini review: BLOCKED by provider availability.**

## A. Scope and real stack startup

All source work targeted `C:/Users/sbsam/MailGuard-phase12` on its existing `feature/production-hardening` branch. Entry inventory contained 28 modified files and two untracked source files; those existing changes were retained. The original checkout and its six modifications were not accessed or rehashed. The live-stack verification preceded separate owner authorization for commits, origin push, main synchronization and a draft PR. That authorization does not permit merging the PR, public deployment, retained-volume overwrite or database reset.

Read `AGENTS.md`, current plan/decisions/progress, Spring properties, `compose.prod.yaml`, `.env.example`, deployment instructions and CI/verifier scripts before startup.

The first Maven run failed because Docker Desktop's Linux named pipe was absent: 252 reported tests, 18 errors, nine skips. `docker desktop start` restored the daemon. The subsequent **`mvn -B verify` succeeded: 347 total, 338 passed, nine opt-in skips, zero failures/errors**. Nonfatal Flyway repository metadata HTTP 401 warnings remain distinct from application authentication.

Final disposable project: **`mailguard-p12-smoke-6875d02e`**, using a newly named PostgreSQL volume, random mounted test secrets and a generated localhost certificate. Existing deployment configuration started PostgreSQL 17, authenticated Valkey 8, the real Spring Boot API, separate headless worker, and React assets through Caddy HTTPS. All five services were healthy. Only proxy loopback ports 59995/59996 were published; PostgreSQL, Valkey, API and management ports were not published.

Observed: HTTPS root 200, hostname/generated-CA verification, HTTP redirect 301, health `UP`/200, anonymous validation 401, authenticated invalid-address validation 200, public Prometheus 404. PostgreSQL completed and subsequently validated Flyway V1–V5. Production readiness includes actual PostgreSQL and Redis health contributors; authenticated administrative requests also exercised shared Valkey admission.

Final verifier completed successfully, stopped its writers, restored into a separate network-isolated PostgreSQL container, and ran `down --remove-orphans` **without `-v`**. No project containers remained afterward. The test-owned named volume and private recovery artifacts remain for inspection. The private directory is `C:/Users/sbsam/AppData/Local/Temp/mailguard-p12-smoke-6875d02e-U4tjQL`; never publish its contents.

## B. Frontend-to-backend coverage matrix

Source roots: frontend components below are under `frontend/src/`; Java symbols are under `src/main/java/com/aash/mailguard/`. Client methods `Api.json`, `Api.empty` and `Api.download` use real same-origin `fetch`, omit cookies, request no-store responses and sanitize failures; `Api.json` validates response schemas. Full-stack suites contain no Playwright interception, MSW or fixture-generated HTTP responses. Synthetic inputs enter through the real UI/API and are processed by real services.

| Feature/component and API call | Actual route | Backend controller / service / repository | Table or operational source | Real test, observed response and persisted outcome | Status |
| --- | --- | --- | --- | --- | --- |
| `features/overview/OverviewPage.tsx`, `app/App.tsx`; `api.json` with `healthSchema` | `GET /actuator/health` | Spring Boot Actuator health endpoint; datasource and Redis health contributors | Live API/PostgreSQL/Valkey health; recent session results come only from successful validation responses | Both Chromium suites rendered `API online`; independent browser observed real health 502 followed by 200 and rendered `Connected`. Health itself is operational, not a persisted business counter. | PASS |
| `features/validation/ValidationPage.tsx`, `ValidationResult.tsx`; `api.json` POST with `validationSchema` | `POST /api/email/validate` | `EmailValidationHistoryController.validate` / `EmailValidationHistoryService.validate,compute` / `EmailValidationHistoryRepository.save`; `DnsLookupService`; optional `DeliveryEvidenceService.lookup` | `email_validation_history`; actual DNS/Valkey observation cache; authorized delivery tables | Production UI returned `INVALID` for synthetic malformed syntax and displayed backend message/null checks. Development UI returned actual `UNVERIFIED` for `person@example.invalid`, without claiming mailbox existence. Writes persisted; final source contained seven history rows. | PASS |
| `features/history/HistoryPage.tsx`; `api.json` with `z.array(historySchema)`, limit 50 and optional `after` | `GET /api/email/history` | `EmailValidationHistoryController.history` / `EmailValidationHistoryService.recentHistory` / repository `findAllByOrderByIdDesc`, `findByIdLessThanOrderByIdDesc` | `email_validation_history` | Keyless read 401; authenticated read 200. Browser displayed stored address/status/message/booleans/timestamp, then retrieved the same row after browser reload and API restart. New restart scenario verified exactly one history row for its completed job after PostgreSQL/API/worker restart. Independent browser inspected actual persisted rows. | PASS |
| `features/bulk/BulkPage.tsx`; upload, progress, results, cancellation and download calls | `POST /api/imports`; `GET/DELETE /api/imports/{id}`; `GET /api/imports/{id}/results`; `GET /api/imports/{id}/export` | `BulkImportController` / `CsvImportService` / `BulkImportStore.begin,append,finish,find,results,cancel`; `BulkPromoter`; `JobStore`, `JobWorker` | `import_batch`, `import_row`, `verification_job`, linked history | Real two-row CSV upload accepted with durable UUID. Separate worker completed both rows. UI rendered persisted progress and results; actual CSV download retained order and escaped `=unsafe-at` as a spreadsheet-safe value. Reload retrieved the import. Separate pending import cancellation survived reload. Final source: two imports, three rows. | PASS |
| `features/jobs/JobsPage.tsx`; submit, poll/open UUID and cancel calls | `POST /api/jobs`; `GET/DELETE /api/jobs/{id}` | `JobController` / `JobStore.submit,find,cancel,claim,complete` / `JobWorker`; validation service | `verification_job`, `email_validation_history`, persisted result snapshot | UI submitted a real job, polled to `COMPLETED`, rendered backend `INVALID`, and reopened the same UUID after reload. Pending cancellation persisted. New test stopped worker, submitted through UI, restarted PostgreSQL/API, fetched the same `PENDING` job, restarted worker, and observed `COMPLETED`, attempts 1, exactly one history row. Final source: four completed jobs plus one canceled job. | PASS |
| `features/evidence/EvidencePage.tsx`; lookup, trusted ingest and revoke calls | `POST /api/delivery-evidence/lookup`; `POST /api/delivery-events`; `POST /api/delivery-events/{id}/revoke` | `DeliveryController.lookup,ingest,revoke` / `DeliveryEvidenceService.lookup,ingest,revoke`; JDBC lateral reads and transactional writes | `delivery_identity`, `delivery_event`, `delivery_suppression` | UI lookup initially showed no event. UI submitted one synthetic authorized test event with `5.1.1`; API created event and linked suppression. UI displayed `RECIPIENT_HARD_BOUNCE` and active suppression. Revocation lifted only linked suppression; source retained one audited event and its suppression row, including through database restart and full restore. This is not a real provider integration claim. | PASS |
| `features/suppressions/SuppressionsPage.tsx`; paged listing, create and audited lift | `GET/POST /api/suppressions`; `DELETE /api/suppressions/{id}` | `DeliveryController.suppressions,suppress,lift` / `DeliveryEvidenceService.suppressions,suppress,lift`; bounded JDBC keyset query | `delivery_identity`, `delivery_suppression` | UI created manual suppression, read active record, confirmed lift, rendered `LIFTED` with actual lifting reason, and retrieved it after reload. Source retained two suppression records, not fabricated counters. | PASS |
| `features/settings/SettingsPage.tsx`; live health and session operations | `GET /actuator/health`; connection validation uses `GET /api/suppressions?limit=1` | Actuator; `DeliveryController.suppressions` / `DeliveryEvidenceService.suppressions` | Live dependency health and authenticated suppression read; theme/local clock are explicitly local UI state | Settings showed actual `UP`. Stopped API produced genuine unavailable state; restart restored health. Memory-only credentials disappeared on reload/disconnect; theme storage was separate. No backend settings persistence is claimed. | PASS |
| `components/layout/CredentialDialog.tsx`, `lib/auth/session.tsx`; bounded authenticated connect | `GET /api/suppressions?limit=1`; production validation/admin routes | `HttpRateLimit`; controller constant-time shared-key checks; `RedisTokenBucket` | Mounted administrator credential, Valkey shared quotas and bounded PostgreSQL read | Wrong key returned 401 and visible sanitized error; valid key connected. Browser storage/cookies/URL did not contain test key. Varying spoofed forwarding headers yielded 401 and 429, not a reset quota. | PASS |
| `lib/api/client.ts`, page error states and settings | Real affected API route; health and history during API outage | Caddy upstream failure / real API admission and persistence failures | Actual stopped API or stopped Valkey, not mocked responses | Production API stop caused 502-class health failure, `Unavailable` and history error with no persisted rows rendered as fallback; restart recovered original record. Development Valkey stop caused actual 503 and rejected connect; restart recovered. Diagnostic quota returned 429 with `Retry-After`. | PASS |
| Aggregate charts/counts; global jobs/imports/events lists; history search/owner totals | No route | Not implemented, not duplicated | Unavailable | Overview, job/import/evidence pages and settings explicitly disclose absent global lists/aggregates. Session-only lists and ten recent API validation results remain labeled memory-only. No fabricated chart, business total, hardcoded history or demo-data fallback was found. | BLOCKED / explicitly unavailable |

### Endpoint inventory and validation boundaries

Seventeen application method/route combinations exist; no duplicate endpoint was introduced:

- `EmailValidationHistoryController`: `POST /api/email/validate`, `POST /api/email/test`, `GET /api/email/history`.
- `JobController`: `POST /api/jobs`, `GET /api/jobs/{id}`, `DELETE /api/jobs/{id}`.
- `BulkImportController`: `POST /api/imports`, `GET /api/imports/{id}`, `DELETE /api/imports/{id}`, `GET /api/imports/{id}/results`, `GET /api/imports/{id}/export`.
- `DeliveryController`: `POST /api/delivery-events`, `POST /api/delivery-events/{id}/revoke`, `POST /api/delivery-evidence/lookup`, `GET /api/suppressions`, `POST /api/suppressions`, `DELETE /api/suppressions/{id}`.

Public operational health is `/actuator/health`; readiness, liveness and Prometheus remain on private management port 8081. Swagger/OpenAPI are disabled in the production profile. `/api/email/test` remains a rate-limited diagnostic without business writes. Production validation and administrative data routes require the shared key; development validation preserves its existing optional-auth contract.

History authenticates before parameter parsing, rejects unknown/duplicate/malformed parameters, bounds `limit` to 1–100, requires positive exclusive `after` IDs, returns a record DTO rather than JPA entities, orders by descending ID and uses no-store responses. Existing H2, real PostgreSQL HTTP and Valkey regressions passed. Suppressions use a maximum-100 UUID-keyset page; import results use ordinal-keyset pagination bounded by the configured maximum 500. Job details use UUID lookup rather than an invented global collection. Existing bounded JSON, multipart, UUID, email, event-time and reason-code validation remain in place.

## C. APIs implemented or reused

The needed durable history API and frontend integration were already present among entry changes: controller, service, repository query, `ValidationHistoryRow`, auth/admission, pagination, response schema and regression tests. This pass retained and exercised them; it did **not** invent or duplicate another history endpoint. No additional necessary dashboard read API was missing. Optional global lists, search and aggregates remain explicitly unavailable, as permitted by the requested data-provenance contract.

Additional changes in this pass: real PostgreSQL/API/worker restart browser regression; separate browser report/output locations; CI Java/Docker prerequisites and retained JUnit/security artifacts; verifier-generated startup, database and restore evidence; README endpoint inventory; Caddy XDG runtime fix and accurate TCP-versus-TLS health documentation.

## D. Reconciled real test results

Generated reports, not historical documentation, establish these final-run counts. Before PR publication, synchronization with `origin/main` at `e2490db` preserved three additional SMTP parser tests. A fresh full `mvn -B verify` passed 350 total / 341 passed / nine skipped; the earlier 347-test snapshot remains historical. Main adds tests and milestone documentation, not new application runtime behavior. Previously exercised browser, image-scan and restore evidence remains unchanged.

| Suite | Total | Passed | Failed/errors | Skipped | Evidence |
| --- | ---: | ---: | ---: | ---: | --- |
| Maven backend, 30 Surefire suites | 350 | 341 | 0 | 9 | `target/surefire-reports/TEST-*.xml`; `target/evidence/backend-counts.json` |
| PostgreSQL/Valkey integration subset of backend above | 96 | 96 | 0 | 0 | Store, HTTP, migration/startup and Redis Testcontainers suites; not additional tests |
| Frontend units, 11 files | 72 | 72 | 0 | 0 | `target/evidence/frontend-unit.xml` |
| Development real-service Chromium | 9 | 9 | 0 | 0 | `target/evidence/development-browser.xml` |
| Production HTTPS real-service Chromium | 9 | 9 | 0 | 0 | `target/evidence/production-browser.xml`, `production-browser.log` |
| Backup isolation/integrity boundaries | 4 | 4 | 0 | 0 | `target/evidence/backup.xml` |
| Combined, without counting integration subset twice | 444 | 435 | 0 | 9 | `target/evidence/test-counts.json` |

Also passed: locked `npm ci`, frontend typecheck, lint, production build, production dependency audit, verifier syntax check and CI YAML structural validation. Hosted GitHub Actions was not run.

Initial failures are retained rather than rewritten as successes. The first new restart scenario used an address rejected by the native HTML email input, so no job request occurred; corrected test input uses an invalid local-part accepted by the browser but rejected by the real Java validator. The initial development run lost its memory-only administrator session while frontend source was being edited; its captured error shows a disconnected page, not successful import rendering. A Vite reload caused by concurrent edits is **[INFERENCE]**, not a demonstrated backend defect. With stable frontend source, the full suite passed, including import completion in 11.4 seconds. Separate output directories prevent browser suites from deleting each other's artifacts. Initial reports remain `*-initial-failure.xml` and are excluded from final totals.

## E. Persistence and worker processing

The final live PostgreSQL snapshot was:

```json
{"flyway":["1","2","3","4","5"],"historyRows":7,"jobs":{"COMPLETED":4,"CANCELLED":1},"linkedJobHistoryRows":4,"imports":2,"importRows":3,"events":1,"suppressions":2}
```

These are SQL observations of this disposable source, not frontend dashboard totals. The restart test proved one pending job survives PostgreSQL/API restart, then a restarted worker completes it on its first attempt with exactly one history row. Browser reload and API stop/start separately preserved history, imports, jobs and audited suppressions. Worker SIGSTOP made Docker health unhealthy; SIGCONT restored healthy only after its independent dispatcher/promoter progress markers resumed.

After stopping API/worker writers, the custom-format backup restored to a unique `--network none` PostgreSQL container. Flyway V1–V5 checksums, required schema/sequence and canonical SHA-256 row-content equality matched across all eight public tables. A separately started packaged API read a restored import with read-only JDBC settings. This does not prove writable production restore, predicate-AST equivalence, backup encryption or approved expiry.

## F. Security and container scans

Trivy **0.76.1**, scanner image digest `sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c`, used downloaded vulnerability/Java databases. Final scans explicitly targeted the immutable images exercised by the live containers:

| Target | Exercised/scanned image ID | HIGH | CRITICAL |
| --- | --- | ---: | ---: |
| API and worker, Alpine/JAR | `sha256:4467dfeef7174a36634a1dcbf150415c176665c412e6dc2d8cb85c80cfdcb554` | 0 | 0 |
| Proxy, Alpine/Go binary | `sha256:d189e14dce29d44326cafc0de31df8ff15b2b76e73dc1ba99416a5e6374524e4` | 0 | 0 |
| Repository vulnerabilities/secrets/misconfiguration | Source scan; generated artifacts, dependencies and preserved backup diff excluded | 0 | 0 |

Reports: `target/evidence/api-scan.json`, `proxy-scan.json`, `filesystem-scan.json`, `security-summary.json`, `exercised-image-identities.json`. Java DB download completed; API scan was not waived on a timeout. LOW/MODERATE findings are not cleared by HIGH/CRITICAL filtering.

Production-only npm audit reported zero vulnerabilities. Full npm audit reported **seven MODERATE development dependency findings** through TypeScript ESLint's nested `minimatch`, [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249). No `npm audit fix --force`, unreviewed dependency upgrade or advisory suppression was applied. The initial full-audit command timed out; the bounded-retry command returned the actual advisory report.

Live logs exposed Caddy storage maintenance attempting `/.local` on its read-only root. `frontend/Dockerfile` now sets `XDG_DATA_HOME=/data` and `XDG_CONFIG_HOME=/config`, reusing existing non-root writable tmpfs mounts. A container smoke confirmed both directories writable and no storage-maintenance/read-only errors. The corrected image passed the real TLS stack and scan. Proxy log checks found none of the mounted test secrets after authenticated API outage. Forwarded-header spoofing did not reset wrong-key quotas. Worker stall/recovery monitoring passed; an unhealthy Docker status does not automatically restart a worker.

CI now explicitly provisions Java 21 and requires Docker/Compose, builds real API/proxy images, runs real-service browser verification, retains JUnit and scan JSON artifacts, and keeps filesystem/API/proxy HIGH/CRITICAL gates fail-closed. YAML structure was checked locally; hosted execution and release-time rescan remain required.

## G. Remaining defects and approval gates

- Seven MODERATE development-tool dependency findings remain; production dependencies and HIGH/CRITICAL scanned targets are distinct.
- Gemini review could not start; configured provider returned HTTP 404 `NOT_FOUND`.
- No hosted CI run or owner approval for public deployment.
- One shared administrator key can read all stored addresses; no per-user identity or owner-scoped authorization.
- No approved retention, erasure, legal holds or backup encryption/expiry; writable production recovery remains unproved.
- Upstream MIT grant provenance and Java transitive license review remain open.
- Production certificate/trust, approved DNS egress and private-service security/TLS require operator review. SMTP remains disabled; no external SMTP or personal accounts were used.
- Proxy uses an upstream-merged untagged Caddy security commit; a vetted tagged replacement and release-time rescan require review.
- Compose's proxy health probe checks its TCP listener, not TLS identity. The successful verifier separately checked an actual CA-pinned HTTPS response.
- Global jobs/imports/events lists, aggregate charts/counts and history search remain explicitly unavailable, not simulated.

## H. Independent Gemini audit handoff

See [PHASE12_AUDIT_HANDOFF.md](PHASE12_AUDIT_HANDOFF.md). Supply tracked diff plus untracked source inventory from this worktree only, this evidence matrix, generated test counts and matching-image scan summaries. Exclude private secrets/certificates, recovery archive, named volumes, preserved entry diff and browser artifacts containing address data from external submissions.

Ask Gemini to challenge authentication-before-parsing, page bounds, full-address exposure, quota trust, lease/completion fencing, import accounting, real HTTP provenance, disconnected rendering, PostgreSQL/worker restart persistence, Caddy writable paths, log secrecy, image-ID matching and CI artifact/gate behavior. Require severity-ranked file/line findings, safe reproductions and an independent READY / CHANGES REQUIRED / BLOCKED verdict.

Both attempted Gemini-backed scout calls failed at provider startup with Cloud Code Assist HTTP 404 for `google-antigravity/gemini-3.7-flash`. No external Gemini findings, approval or independent production-ready verdict are claimed.
