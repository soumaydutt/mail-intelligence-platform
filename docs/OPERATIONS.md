# Single-host operations (operator-only)

## Prepare and start

Prerequisites: Docker Compose v2, Docker engine, Node.js 24 and npm for browser verification, Node.js 20+ for backup verification, owner-supplied TLS certificate and key for the chosen `PUBLIC_HOST`, and approved DNS egress. `compose.yaml` is the legacy local-development database stack; **always name `compose.prod.yaml` explicitly**. Work from the Phase 12 worktree.

Copy `.env.example` to ignored `.env` and set a project-unique `--project-name` for disposable testing; use a stable name for an operator-owned installation. Create ignored `secrets/` or an external restricted directory. Generate four independent random secrets (database, administrator key, Valkey, HMAC identity; each 32–256 bytes, administrator key at least 32), with **no trailing newline**. Put the same Valkey password into `valkey.acl` as `user default on >PASSWORD ~* &* +@all`, using a private file editor; never print it in shell history or command arguments. Set the seven `*_FILE` paths in `.env`. Obtain TLS certificate/key from the operator; do not store a private key in Git or an image. Disposable local drills can use a fresh self-signed localhost certificate, trusted only for that drill.

```sh
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT config --quiet
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT up --build -d --wait
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT ps
```

Expect PostgreSQL and Valkey healthy before API, API readiness before proxy/worker, and only proxy HTTP/HTTPS host ports bound to `127.0.0.1`. The proxy redirects HTTP to HTTPS. Verify HTTPS using the operator-supplied CA, a browser, and a real authenticated import/job. The API's private management port 8081 exposes health/readiness and Prometheus **inside the network only**; exact `/actuator/health` is publicly visible for the dashboard, while other `/actuator/*` paths must return 404 through the proxy. Worker is headless: Docker health requires recent successful job-dispatch and import-promotion polls, not just a running JVM. Cold start has a 90-second grace. Inspect coarse worker counters/last successful poll in logs and submit a real job. Keep metric scrape access private. Logs may contain database driver details on failure; limit log access and retention.

## Stop, restart, recover

Use `docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT stop` for a planned stop, `start --wait` for restart. `down` without `-v` retains PostgreSQL volume, but do not issue it during an incident until you identify project ownership and take an archive. **Never use `down -v`, `volume prune`, `system prune`, or `flyway clean` against an operator project.** Validate schema by querying private PostgreSQL `flyway_schema_history`; scripts V1–V5 are immutable and Flyway stops on incompatible changes. Back up before applying new migrations; no auto-repair.

On Valkey outage, administrative admission fails closed; no permanent records should be accepted without shared admission. PostgreSQL outage makes readiness fail and data operations fail; do not restart in a loop to conceal failure. Pause ingress, preserve logs/volume, diagnose host storage and container health, then restore dependency, inspect schema and complete a test job. A Valkey restart clears cache and quota buckets. Docker does not restart a worker merely because its health probe reports unhealthy. Inspect dispatcher and promoter polls, repair the dependency or stalled process, and restart only the affected worker after preserving evidence. Lost worker leases recover after their existing timeout; a restart may delay work, not drop committed jobs. Use [BACKUP_RESTORE.md](BACKUP_RESTORE.md) for a **separate isolated recovery drill**. Do not overwrite the source volume or expose the recovery target.

For a planned upgrade, first record current image digests and Flyway version and create a verified full archive outside the worktree. Do not update applied V1–V5 migration SQL. Review the next migration on a restored, isolated copy before replacing the API/worker images. Drain or pause ingress, then run `docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT up --build -d --wait` and confirm readiness and a synthetic invalid-address job. A binary rollback is safe only if the older binary explicitly supports the new schema; do not run `flyway repair`, overwrite a live volume, or restore a stale archive as an automatic rollback. Escalate an incompatible migration for operator-approved incident recovery.

## Monitoring and thresholds

Monitor HTTPS availability, management readiness, PostgreSQL/Valkey health, Docker worker health, worker log last-successful-poll age, queued jobs, active leases, import backlog, HTTP status-class rates, quota rejection/outage, DNS cache errors, and host free disk/volume usage. Each of the five containers uses Docker's `local` log driver with three 10 MiB files; apply host access controls and operator-approved retention to exported logs. Metrics are low-cardinality aggregates, not traces of addresses. Alert if API readiness or worker health fails, if backlog grows, if backup verification fails, or on repeated 429/503s. Each worker marker expires 20 seconds plus its configured poll interval rounded up to seconds (21 seconds at defaults; at most 80 seconds). Docker checks every 10 seconds and requires three failures; active leases do not excuse a blocked dispatcher or promoter. Record real baseline latency and throughput from unique disposable stacks before setting numeric SLOs; no universal throughput threshold is claimed. Keep performance tests separate from production volumes, operator addresses, and public DNS.

The disposable 15-import profile, measured quantiles, and provisional per-size p95 regression ceilings are in [RELEASE_READINESS.md](RELEASE_READINESS.md). Manual CI exercises these ceilings; ordinary pull-request smoke skips the expensive load. A passing synthetic invalid-address profile does not calibrate real DNS traffic or license public exposure.

Read private signals without publishing the management port:

```sh
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT exec -T api wget -qO- http://127.0.0.1:8081/actuator/prometheus
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT ps worker
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT logs --since=5m worker
docker compose --env-file .env -f compose.prod.yaml --project-name PROJECT exec -T postgres psql -U mailguard -d mailguard -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;'
```

Use the configured `DB_USERNAME` and `DB_NAME` instead of `mailguard` if changed. Metric/worker logs belong to operators; do not expose them via the public proxy. A worker can appear running during cold start or while blocked on a dependency: inspect Docker health and both loop counters, then verify a completed job. A failed dependency poll removes its marker until a successful poll restores it.

## Release gates

Read [DEPLOYMENT.md](DEPLOYMENT.md), [PRIVACY.md](PRIVACY.md), and [BACKUP_RESTORE.md](BACKUP_RESTORE.md). Public exposure, retention/erasure, backup encryption and expiry, license provenance, trusted DNS egress, per-user ownership and multi-host operation remain blocked pending owner review. A generated test certificate is not a production TLS credential.
