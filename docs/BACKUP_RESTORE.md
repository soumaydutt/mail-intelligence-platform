# PostgreSQL backup and isolated restore

PostgreSQL is authoritative. Valkey holds disposable cache and admission counters; do not treat Valkey as a backup. Run these commands from the Phase 12 worktree on the Docker host. Require Node.js 20+, Docker, and `postgres:17-alpine`. The named volume is never removed by these commands.

1. Set `DB_USERNAME` and `DB_NAME` to the values used by the source Compose project. Choose its explicit project name. Prefer a private backup directory **outside the repository** with filesystem access limited to the operator. Never commit, email, or log the archive; it contains addresses, history, jobs, events, and suppressions. Protect and encrypt it under an operator-approved retention policy. The repository does not supply automatic key management or retention.
2. Create a new archive; overwriting an existing file fails:
   ```sh
   node scripts/backup/backup.mjs backup --project-name PROJECT --output /private/backups/mailguard-YYYYMMDD.dump
   ```
   On Windows use an absolute path such as `C:/private/backups/mailguard-YYYYMMDD.dump`. The script checks Compose labels, streams `pg_dump` in custom format without passing a password to the CLI, verifies a complete catalog, and leaves no partial archive on failure. The dump is a consistent PostgreSQL snapshot, not a Valkey snapshot.
3. Restore **only** into a new uniquely named, network-isolated, temporary PostgreSQL container. This command refuses an existing target or archive inside the worktree:
   ```sh
   node scripts/backup/backup.mjs restore --project-name PROJECT --input /private/backups/mailguard-YYYYMMDD.dump --target-container mailguard-restore-unique123
   ```
   The script checks archive format/catalog, restores in one transaction, reports eight table counts, validates Flyway V1–V5 checksums against the packaged migration SQL, checks required schema objects and sequence, and executes read-only joins and JSONB queries. The archive alone cannot prove equality with a live source. `scripts/prod/verify.mjs` first stops its disposable API and worker, then compares streamed SHA-256 hashes of canonical rows in all eight tables and schema metadata between source and restored target. It also boots the packaged API as a read-only JDBC sidecar beside the network-isolated scratch PostgreSQL container and reads a restored import. The sidecar has no external network, disables writers and Flyway, and uses a synthetic administrator key; the scratch database still uses PostgreSQL trust authentication. The temporary database has no published port or persistent volume; inspect results while it runs. Only stop/remove a target you created; never run `compose down -v` on an operator project.

   The source/restore catalog comparison uses stable index and constraint properties because PostgreSQL reparses equivalent CHECK and partial-index predicates when restoring. It does not prove arbitrary predicate-body equivalence. Flyway migration checksums and the read-only application probe cover this repository's known schema; separately review any user-defined database objects before recovery.
4. For real incident recovery, first isolate traffic, preserve the current PostgreSQL volume and incident evidence, and take a fresh backup. Obtain owner approval for the target, archive age, privacy policy, downtime, schema compatibility, and rollback plan. This repository deliberately **does not** automate restoring over live data or promoting a scratch container. A verified snapshot and read-only API probe do not establish write-path or worker compatibility. Exercise both against a separately approved restore target before any cutover.

Run a drill on a **unique disposable Compose project** and archive; verify a committed record survives in the recovered target and the application can read it. Record recovery-point age and elapsed time. Retain failure artifacts only in approved private locations; no archive retention/encryption approval has been supplied for production.
