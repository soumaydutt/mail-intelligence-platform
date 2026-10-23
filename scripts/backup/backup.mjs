#!/usr/bin/env node
// PostgreSQL backup and isolated recovery drill. Never send database passwords to Docker CLI.
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, fstatSync, lstatSync, mkdtempSync, openSync, readSync, readFileSync, realpathSync, rmSync, statSync, writeSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = realpathSync(fileURLToPath(new URL('../../', import.meta.url)));
const IMAGE = 'postgres:17-alpine';
const MAX_LIST = 8 * 1024 * 1024;
const SAFE_NAME = /^[a-z][a-z0-9_-]{1,62}$/;
const SQL_NAME = /^[a-z_][a-z0-9_]*$/;

const REQUIRED_TABLES = ['email_validation_history', 'verification_job', 'import_batch', 'import_row',
  'delivery_identity', 'delivery_event', 'delivery_suppression', 'flyway_schema_history'];
const REQUIRED_INDEXES = ['idx_email_validation_history_recent', 'idx_verification_job_due',
  'idx_verification_job_lease', 'idx_import_row_unique_address', 'idx_import_row_unpromoted',
  'idx_import_batch_processing', 'idx_import_batch_ingesting_stale', 'idx_verification_job_bulk_created',
  'idx_verification_job_bulk_finished', 'idx_import_batch_created', 'idx_import_batch_finished',
  'idx_delivery_event_latest', 'idx_delivery_suppression_active'];
const REQUIRED_CONSTRAINTS = ['email_validation_history_pkey', 'email_validation_history_job_id_key',
  'email_validation_history_job_id_fkey', 'verification_job_pkey', 'verification_job_idempotency_key_key',
  'verification_job_lease_state', 'verification_job_terminal_state', 'verification_job_result_state',
  'verification_job_failure_state', 'import_batch_pkey', 'import_batch_terminal', 'import_batch_failure',
  'import_row_pkey', 'import_row_import_id_fkey', 'import_row_job_id_fkey',
  'import_row_state_fields', 'chk_import_row_duplicate_prior', 'delivery_identity_pkey',
  'delivery_event_pkey', 'delivery_event_source_unique', 'delivery_event_status_fingerprint',
  'delivery_event_revocation_audit', 'delivery_suppression_pkey',
  'delivery_suppression_event_identity', 'delivery_suppression_provenance',
  'verification_job_historical_evidence_state'];
const REQUIRED_TRIGGERS = ['verification_job_terminal_immutable', 'import_batch_terminal_immutable',
  'delivery_event_audit', 'delivery_suppression_audit', 'delivery_event_no_delete', 'delivery_suppression_no_delete'];

export function options(argv, env = process.env) {
  const [action, ...args] = argv;
  if (!['backup', 'restore'].includes(action) || args.length % 2) throw Error('Usage: backup|restore --project-name NAME --output|--input ABSOLUTE_PATH [--target-container mailguard-restore-UNIQUE]');
  const flags = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--project-name', '--output', '--input', '--target-container'].includes(args[i]) || !args[i + 1] || flags[args[i]]) throw Error('Invalid or duplicate option');
    flags[args[i]] = args[i + 1];
  }
  const project = flags['--project-name'];
  const username = env.DB_USERNAME;
  const database = env.DB_NAME;
  if (!project || !SAFE_NAME.test(project)) throw Error('Explicit valid source --project-name required');
  if (!username || !SQL_NAME.test(username) || !database || !SQL_NAME.test(database)) throw Error('Set DB_USERNAME and DB_NAME to simple PostgreSQL identifiers');
  const path = action === 'backup' ? flags['--output'] : flags['--input'];
  if (!path || !isAbsolute(path)) throw Error('Absolute archive path required');
  if (action === 'backup' && (flags['--input'] || flags['--target-container'])) throw Error('Backup accepts only --output');
  if (action === 'restore' && (flags['--output'] || !flags['--target-container'])) throw Error('Restore needs --input and explicit --target-container');
  const target = flags['--target-container'];
  if (target && (!/^mailguard-restore-[a-z0-9][a-z0-9-]{5,50}$/.test(target) || target === project)) throw Error('Target must be a distinct, explicitly named disposable mailguard-restore-* container');
  return { action, project, username, database, path, target };
}

function outsideRepository(path, output) {
  const resolved = resolve(path);
  const parent = realpathSync(dirname(resolved));
  const diff = relative(ROOT, parent);
  if (!diff || (!diff.startsWith('..' + sep) && diff !== '..' && !isAbsolute(diff))) throw Error('Archive must live outside the Git worktree');
  if (output && existsSync(resolved)) throw Error('Refusing to overwrite an existing archive');
  if (!output && (!statSync(resolved).isFile() || lstatSync(resolved).isSymbolicLink())) throw Error('Input must be a regular archive, not a symlink');
}

function docker(args, { stdin, stdout = 'pipe', maxBuffer = MAX_LIST } = {}) {
  const result = spawnSync('docker', args, { stdio: [stdin ?? 'ignore', stdout, 'pipe'], encoding: stdout === 'pipe' ? 'utf8' : undefined, maxBuffer });
  // Do not relay Docker/SQL stderr: it may contain credentials, addresses or row values.
  if (result.error || result.status !== 0) throw Error(`Docker operation failed (${args[0]}); check Docker daemon and private operator logs`);
  return result.stdout;
}

export function waitReady(container, username, database) {
  for (let i = 0; i < 60; i++) {
    const ready = spawnSync('docker', ['exec', container, 'pg_isready', '-U', username, '-d', database], { stdio: 'ignore' });
    if (ready.status === 0) return;
    if (ready.error) throw Error('Docker readiness check unavailable');
    // Startup only; never sleep between ordinary backup/restore steps.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000);
  }
  throw Error('Disposable PostgreSQL failed to become ready');
}

function archiveTables(container, file) {
  const fd = openSync(file, 'r');
  let list;
  try { list = docker(['exec', '-i', container, 'pg_restore', '--list'], { stdin: fd }); }
  finally { closeSync(fd); }
  const tables = new Set();
  const data = new Set();
  for (const line of list.split('\n')) {
    const match = /^\d+; \d+ \d+ (TABLE DATA|TABLE) ([a-z_][a-z0-9_]*) ([a-z_][a-z0-9_]*) /i.exec(line);
    if (match) (match[1] === 'TABLE' ? tables : data).add(`${match[2]}.${match[3]}`);
  }
  if (!tables.size || tables.size !== data.size || [...tables].some(table => !data.has(table))) throw Error('Archive lacks a complete supported table/data catalog');
  return tables;
}

export function backupContainer(container, file, username, database) {
  outsideRepository(file, true);
  const fd = openSync(file, 'wx', 0o600);
  try {
    docker(['exec', container, 'pg_dump', '--format=custom', '--no-password', '--username', username, '--dbname', database], { stdout: fd });
    if (fstatSync(fd).size < 5) throw Error('Empty PostgreSQL archive');
  } catch (error) {
    closeSync(fd);
    rmSync(file); // Only the new file exclusively created above; never an existing backup.
    throw error;
  }
  closeSync(fd);
  try { return archiveTables(container, file); }
  catch (error) { rmSync(file); throw error; }
}

export function tableCounts(container, username, database, expected) {
  const query = `SELECT coalesce(json_agg(json_build_array(schemaname, tablename) ORDER BY schemaname, tablename), '[]'::json) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')`;
  const found = JSON.parse(docker(['exec', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', username, '-d', database, '-c', query]).trim());
  const actual = new Set(found.map(([schema, table]) => `${schema}.${table}`));
  if (actual.size !== expected.size || [...expected].some(table => !actual.has(table))) throw Error('Restored tables differ from archive catalog');
  const counts = {};
  for (const table of [...expected].sort()) {
    const [schema, name] = table.split('.');
    if (!SQL_NAME.test(schema) || !SQL_NAME.test(name)) throw Error('Unsupported table identifier in archive');
    const value = docker(['exec', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', username, '-d', database, '-c', `SELECT count(*) FROM "${schema}"."${name}"`]).trim();
    if (!/^\d+$/.test(value)) throw Error('Restored row-count verification failed');
    counts[table] = value;
  }
  if (expected.has('public.flyway_schema_history')) {
    const failed = docker(['exec', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', username, '-d', database, '-c', 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success']).trim();
    if (failed !== '0') throw Error('Restored Flyway history contains unsuccessful migrations');
  }
  return counts;
}
function migrationChecksum(path) {
  // Flyway CRC32 updates each UTF-8 line without line terminators.
  let crc = 0xffffffff;
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    for (const byte of Buffer.from(line)) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) | 0;
}

function queryJson(container, username, database, sql) {
  return JSON.parse(docker(['exec', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1',
    '-U', username, '-d', database, '-c', sql]).trim());
}

// pg_restore reparses equivalent ANY(varchar[]) checks; compare stable catalog properties, not deparsed SQL.
// shortcut: predicate bodies are not compared, add canonical AST checks before accepting user-defined schema.
const SCHEMA_SQL = `SELECT json_build_object(
  'history', (SELECT coalesce(json_agg(json_build_array(version, checksum, success, script)
    ORDER BY installed_rank), '[]'::json) FROM public.flyway_schema_history),
  'objects', (SELECT coalesce(json_agg(json_build_array(kind, name, definition)
    ORDER BY kind, name), '[]'::json) FROM (
      SELECT 'column' AS kind, table_name || '.' || ordinal_position AS name,
        column_name || ':' || data_type || ':' || is_nullable || ':' || coalesce(column_default, '') ||
        ':' || coalesce(is_identity, '') AS definition
        FROM information_schema.columns WHERE table_schema = 'public'
      UNION ALL SELECT 'index', t.relname || '.' || i.relname,
        am.amname || ':' || x.indisunique || ':' || x.indisprimary || ':' ||
        x.indnkeyatts || ':' || x.indkey::text || ':' || (x.indpred IS NOT NULL) || ':' ||
        (x.indexprs IS NOT NULL)
        FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
        JOIN pg_class t ON t.oid = x.indrelid JOIN pg_am am ON am.oid = i.relam
        WHERE t.relnamespace = 'public'::regnamespace
      UNION ALL SELECT 'constraint', conrelid::regclass::text || '.' || conname,
        contype::text || ':' || coalesce(conkey::text, '') || ':' || coalesce(confkey::text, '') ||
        ':' || convalidated || ':' || condeferrable || ':' || condeferred || ':' ||
        confdeltype::text || ':' || confupdtype::text || ':' || confmatchtype::text || ':' || connoinherit
        FROM pg_constraint WHERE connamespace = 'public'::regnamespace
      UNION ALL SELECT 'trigger', tgrelid::regclass::text || '.' || tgname, pg_get_triggerdef(oid)
        FROM pg_trigger WHERE tgrelid IN (SELECT oid FROM pg_class
          WHERE relnamespace = 'public'::regnamespace) AND NOT tgisinternal
      UNION ALL SELECT 'sequence', sequencename,
        data_type || ':' || start_value || ':' || min_value || ':' || max_value || ':' ||
        increment_by || ':' || cycle || ':' || cache_size
        FROM pg_sequences WHERE schemaname = 'public'
      UNION ALL SELECT 'function', proname, pg_get_functiondef(oid)
        FROM pg_proc WHERE pronamespace = 'public'::regnamespace
    ) definitions),
  'invalid', (SELECT
    (SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND NOT convalidated)
    + (SELECT count(*) FROM pg_index WHERE indrelid IN (SELECT oid FROM pg_class
      WHERE relnamespace = 'public'::regnamespace) AND (NOT indisvalid OR NOT indisready))
    + (SELECT count(*) FROM pg_trigger WHERE tgrelid IN (SELECT oid FROM pg_class
      WHERE relnamespace = 'public'::regnamespace) AND NOT tgisinternal AND tgenabled <> 'O')),
  'sequence', (SELECT json_build_array(last_value::text, is_called)
    FROM public.email_validation_history_id_seq),
  'maxId', (SELECT coalesce(max(id), 0)::text FROM public.email_validation_history))`;

export function assertRestoredIntegrity(snapshot) {
  const expected = [1, 2, 3, 4, 5].map(version => {
    const name = ['create_validation_history', 'create_verification_jobs', 'create_bulk_imports',
      'terminal_import_batch_trigger', 'create_delivery_evidence'][version - 1];
    const script = `V${version}__${name}.sql`;
    return [String(version), migrationChecksum(resolve(ROOT, 'src/main/resources/db/migration', script)), true, script];
  });
  if (JSON.stringify(snapshot.history) !== JSON.stringify(expected)) {
    throw Error('Restored Flyway V1–V5 history/checksums do not match immutable migrations');
  }
  const names = new Set(snapshot.objects.map(([kind, name]) => `${kind}:${name}`));
  for (const table of REQUIRED_TABLES) {
    if (![...names].some(key => key.startsWith(`column:${table}.`))) throw Error(`Restored schema missing table ${table}`);
  }
  for (const name of REQUIRED_INDEXES) {
    if (![...names].some(key => key.startsWith('index:') && key.endsWith(`.${name}`))) throw Error(`Restored schema missing index ${name}`);
  }
  for (const name of REQUIRED_CONSTRAINTS) {
    if (![...names].some(key => key.startsWith('constraint:') && key.endsWith(`.${name}`))) throw Error(`Restored schema missing constraint ${name}`);
  }
  for (const name of REQUIRED_TRIGGERS) {
    if (![...names].some(key => key.startsWith('trigger:') && key.endsWith(`.${name}`))) throw Error(`Restored schema missing trigger ${name}`);
  }
  if (!names.has('sequence:email_validation_history_id_seq')
      || !snapshot.objects.some(([kind, name, definition]) =>
        kind === 'column' && name.startsWith('email_validation_history.')
        && definition === 'id:bigint:NO::YES')
      || snapshot.invalid !== 0) throw Error('Restored identity sequence, index, trigger or constraint is not usable');
  const [value, called] = snapshot.sequence ?? [];
  if (!/^\d+$/.test(value ?? '') || typeof called !== 'boolean'
      || BigInt(value) + (called ? 1n : 0n) <= BigInt(snapshot.maxId ?? '0')) {
    throw Error('Restored identity sequence is missing or cannot advance beyond existing history');
  }
}

export function schemaIntegrity(container, username, database) {
  const snapshot = queryJson(container, username, database, SCHEMA_SQL);
  assertRestoredIntegrity(snapshot);
  // Read-only SQL compatibility probe; network isolation prevents booting the application here.
  const readable = docker(['exec', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1',
    '-U', username, '-d', database, '-c',
    `BEGIN READ ONLY; SELECT
      (SELECT count(*) FROM public.email_validation_history h
        LEFT JOIN public.verification_job j ON j.id = h.job_id),
      (SELECT count(*) FROM public.import_row r LEFT JOIN public.import_batch b ON b.id = r.import_id),
      (SELECT count(*) FROM public.delivery_event e
        LEFT JOIN public.delivery_identity i ON i.email_key = e.email_key),
      (SELECT count(*) FROM public.delivery_suppression s
        LEFT JOIN public.delivery_identity i ON i.email_key = s.email_key),
      (SELECT count(*) FROM public.verification_job
        WHERE result_historical_evidence IS NULL OR jsonb_typeof(result_historical_evidence) IS NOT NULL);
      COMMIT;`]);
  if (!/^BEGIN\n\d+(?:\|\d+){4}\nCOMMIT\s*$/.test(readable)) throw Error('Restored application read-only query failed');
  return snapshot;
}

export async function rowDigests(container, username, database, tables) {
  const rows = {};
  for (const table of [...tables].sort()) {
    const [schema, name] = table.split('.');
    if (!SQL_NAME.test(schema) || !SQL_NAME.test(name)) throw Error('Unsupported table identifier in archive');
    // Stream canonical, C-sorted rows; never buffer, print or persist source data.
    const sql = `COPY (SELECT row_to_json(t)::text FROM "${schema}"."${name}" t
      ORDER BY row_to_json(t)::text COLLATE "C") TO STDOUT`;
    rows[table] = await new Promise((resolveDigest, reject) => {
      const hash = createHash('sha256');
      const child = spawn('docker', ['exec', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1',
        '-U', username, '-d', database, '-c', sql], { stdio: ['ignore', 'pipe', 'ignore'], timeout: 300000 });
      child.stdout.on('data', bytes => hash.update(bytes));
      child.once('error', () => reject(Error('Row-content digest Docker operation unavailable')));
      child.once('close', code => {
        if (code !== 0) reject(Error('Row-content digest failed; no row content disclosed'));
        else resolveDigest(hash.digest('hex'));
      });
    });
  }
  return rows;
}

export function assertMatchingIntegrity(source, restored) {
  if (JSON.stringify(source.objects) !== JSON.stringify(restored.objects)
      || JSON.stringify(source.history) !== JSON.stringify(restored.history)
      || JSON.stringify(source.sequence) !== JSON.stringify(restored.sequence)
      || source.invalid !== restored.invalid || source.maxId !== restored.maxId) {
    throw Error('Restored schema, migration or sequence differs from quiescent source');
  }
  if (JSON.stringify(source.rows) !== JSON.stringify(restored.rows)) {
    throw Error('Restored row content differs from quiescent source (even if counts agree)');
  }
}


export function restoreDisposable(file, project, target, username, database) {
  outsideRepository(file, false);
  const fd = openSync(file, 'r');
  try {
    const magic = Buffer.alloc(5);
    if (readSync(fd, magic, 0, 5, 0) !== 5 || magic.toString() !== 'PGDMP') throw Error('Input is not a PostgreSQL custom-format archive');
  } finally { closeSync(fd); }
  // Inspect all containers, including stopped ones. Docker run's global name lock closes the race.
  const names = docker(['container', 'ls', '-a', '--format', '{{.Names}}']).trim().split('\n');
  if (names.includes(target)) throw Error('Refusing pre-existing target container');
  if (target === project) throw Error('Source and target must be distinct');
  const listContainer = `mailguard-restore-list-${process.pid}-${Date.now()}`;
  let tables;
  docker(['run', '-d', '--rm', '--network', 'none', '--tmpfs=/var/lib/postgresql/data', '--name', listContainer, '--entrypoint', 'sleep', IMAGE, '120']);
  try { tables = archiveTables(listContainer, file); }
  finally { docker(['stop', listContainer]); }
  docker(['run', '-d', '--rm', '--network', 'none', '--tmpfs=/var/lib/postgresql/data', '--name', target,
    '-e', `POSTGRES_USER=${username}`, '-e', `POSTGRES_DB=${database}`, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', IMAGE]);
  // Preserve failed target for forensic inspection; never destroy containers/volumes here.
  waitReady(target, username, database);
  const input = openSync(file, 'r');
  try { docker(['exec', '-i', target, 'pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl', '--username', username, '--dbname', database], { stdin: input }); }
  finally { closeSync(input); }
  const counts = tableCounts(target, username, database, tables);
  schemaIntegrity(target, username, database);
  return counts;
}

function main() {
  const opts = options(process.argv.slice(2));
  if (opts.action === 'restore') {
    const counts = restoreDisposable(opts.path, opts.project, opts.target, opts.username, opts.database);
    console.log(JSON.stringify({ target: opts.target, tables: counts,
      proof: 'Flyway V1–V5 file checksums, required named schema and sequence, read-only SQL joins/JSONB verified; application startup and source row equality not proven without isolated app and quiescent source' }));
    return;
  }
  const ids = docker(['container', 'ls', '--filter', `label=com.docker.compose.project=${opts.project}`, '--filter', 'label=com.docker.compose.service=postgres', '--format', '{{.ID}}']).trim().split('\n').filter(Boolean);
  if (ids.length !== 1) throw Error('Expected exactly one running source PostgreSQL service');
  const labels = JSON.parse(docker(['inspect', '--format', '{{json .Config.Labels}}', ids[0]]));
  if (labels['com.docker.compose.project'] !== opts.project || labels['com.docker.compose.service'] !== 'postgres') throw Error('Source is not the requested Compose PostgreSQL service');
  backupContainer(ids[0], opts.path, opts.username, opts.database);
  console.log(`Custom-format backup verified: ${opts.path}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); }
  catch (error) { console.error(`Backup/restore aborted: ${error.message}`); process.exitCode = 1; }
}
