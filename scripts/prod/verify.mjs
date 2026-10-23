#!/usr/bin/env node
// Owns only a fresh random Compose project and its disposable recovery container.
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { request as secureRequest } from 'node:https';
import { request as plainRequest } from 'node:http';
import { fileURLToPath } from 'node:url';
import { assertMatchingIntegrity, rowDigests, schemaIntegrity, tableCounts } from '../backup/backup.mjs';

const root = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const project = `mailguard-p12-smoke-${randomBytes(4).toString('hex')}`;
const dir = mkdtempSync(join(tmpdir(), `${project}-`));
const password = randomBytes(48).toString('base64url');
const apiKey = randomBytes(48).toString('base64url');
const secret = randomBytes(48).toString('base64url');
const identity = randomBytes(48).toString('base64url');
const values = { db_password: password, jobs_api_key: apiKey, valkey_password: secret, rate_limit_identity_secret: identity };
for (const [name, value] of Object.entries(values)) writeFileSync(join(dir, name), value, { mode: 0o600 });
writeFileSync(join(dir, 'valkey.acl'), `user default on >${secret} ~* &* +@all\n`, { mode: 0o600 });
const cert = join(dir, 'localhost.crt');
const key = join(dir, 'localhost.key');
const envFile = join(dir, '.env');
const archive = join(dir, 'recovery.dump');
const target = `mailguard-restore-${randomBytes(5).toString('hex')}`;
const baseArgs = ['compose', '--env-file', envFile, '-f', 'compose.prod.yaml', '--project-name', project];
const backupEnv = { ...process.env, DB_USERNAME: 'mailguard', DB_NAME: 'mailguard' };
let started = false;
let restored = false;

function run(binary, args, { cwd = root, env = process.env, timeout = 900000, diagnostics = false } = {}) {
  const result = spawnSync(binary, args, { cwd, env, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout });
  if (result.error || result.status !== 0) {
    throw Error(`${binary} ${args[0]} failed (${result.status ?? result.error?.code}); inspect test-owned project ${project}`
      + (diagnostics ? `: ${(result.stderr ?? '').trim().slice(-1800)}` : ''));
  }
  return result.stdout;
}
function compose(...args) { return run('docker', [...baseArgs, ...args]); }
function port() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const number = server.address().port;
      server.close(() => resolvePort(number));
    });
  });
}
function send(url, method = 'GET', body = null, auth = null, tls = true) {
  return new Promise((resolveResponse, reject) => {
    const headers = {};
    if (body) headers['Content-Type'] = 'application/json';
    if (auth) headers['X-Job-API-Key'] = auth;
    const query = (tls ? secureRequest : plainRequest)(url,
      { method, headers, timeout: 15000, ...(tls ? { ca: readFileSync(cert) } : {}) }, response => {
        let text = '';
        response.on('data', bytes => { text += bytes; });
        response.on('end', () => resolveResponse({ status: response.statusCode, body: text, headers: response.headers }));
      });
    query.once('timeout', () => query.destroy(Error('HTTPS request timed out')));
    query.once('error', reject);
    if (body) query.write(JSON.stringify(body));
    query.end();
  });
}
function expectStatus(response, status) {
  if (response.status !== status) throw Error(`Expected HTTP ${status}; got ${response.status}`);
}
async function probeRestoredApplication() {
  const image = run('docker', ['inspect', '--format', '{{.Image}}', compose('ps', '-a', '-q', 'api').trim()]).trim();
  if (!/^sha256:[0-9a-f]{64}$/.test(image)) throw Error('Disposable application image unavailable for restored-data probe');
  const importId = run('docker', ['exec', target, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1',
    '-U', 'mailguard', '-d', 'mailguard', '-c',
    'SELECT id::text FROM public.import_batch ORDER BY id LIMIT 1']).trim();
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(importId)) {
    throw Error('No restored import available for application read probe');
  }
  const sidecar = `${target}-app`;
  const probeKey = randomBytes(48).toString('base64url');
  let launched = false;
  try {
    run('docker', ['run', '-d', '--rm', '--network', `container:${target}`, '--name', sidecar,
      '--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
      '--memory', '768m', '-e', 'SPRING_PROFILES_ACTIVE=restore-probe',
      '-e', 'DB_URL=jdbc:postgresql://127.0.0.1:5432/mailguard?options=-c%20default_transaction_read_only%3Don',
      '-e', 'DB_USERNAME=mailguard', '-e', `DB_PASSWORD=${randomBytes(32).toString('base64url')}`,
      '-e', `JOBS_API_KEY=${probeKey}`, '-e', 'SPRING_FLYWAY_ENABLED=false',
      '-e', 'SPRING_JPA_HIBERNATE_DDL_AUTO=validate', '-e', 'SPRING_DATASOURCE_HIKARI_READ_ONLY=true',
      '-e', 'JOBS_WORKER_ENABLED=false', '-e', 'RATE_LIMIT_ENABLED=false',
      '-e', 'DNS_CACHE_ENABLED=false', '-e', 'VALKEY_HEALTH_ENABLED=false', image]);
    launched = true;
    for (let attempt = 0; attempt < 90; attempt++) {
      try {
        const body = run('docker', ['exec', sidecar, '/bin/busybox', 'wget', '-q', '-T', '5',
          '-O', '-', '--header', `X-Job-API-Key: ${probeKey}`,
          `http://127.0.0.1:8080/api/imports/${importId}`], { timeout: 10000 });
        const progress = JSON.parse(body);
        if (progress.id !== importId || !Number.isInteger(progress.inputRows) || progress.inputRows < 1) {
          throw Error('Restored application returned an invalid import record');
        }
        return;
      } catch (error) {
        if (error.message === 'Restored application returned an invalid import record') throw error;
        if (attempt === 89) throw Error('Read-only packaged application could not read restored import; inspect isolated recovery target');
        await new Promise(resolveDelay => setTimeout(resolveDelay, 1000));
      }
    }
  } finally {
    if (launched) {
      try { run('docker', ['stop', sidecar], { timeout: 30000 }); }
      catch { console.error(`Inspect test-owned application probe ${sidecar}`); }
    }
  }
}

async function drillWorkerHealth() {
  const worker = compose('ps', '-q', 'worker').trim();
  if (!worker) throw Error('Disposable worker container unavailable');
  run('docker', ['kill', '--signal=SIGSTOP', worker]);
  try {
    let unhealthy = false;
    for (let attempt = 0; attempt < 75; attempt++) {
      if (run('docker', ['inspect', '--format', '{{.State.Health.Status}}', worker]).trim() === 'unhealthy') {
        unhealthy = true;
        break;
      }
      await new Promise(resolveDelay => setTimeout(resolveDelay, 1000));
    }
    if (!unhealthy) throw Error('Stalled worker did not become unhealthy');
  } finally {
    run('docker', ['kill', '--signal=SIGCONT', worker]);
  }
  for (let attempt = 0; attempt < 30; attempt++) {
    if (run('docker', ['inspect', '--format', '{{.State.Health.Status}}', worker]).trim() === 'healthy') {
      console.log('Verified worker Docker health expires on a stalled JVM and recovers after progress resumes');
      return;
    }
    await new Promise(resolveDelay => setTimeout(resolveDelay, 1000));
  }
  throw Error('Worker health did not recover after progress resumed');
}

async function main() {
  if (process.argv.slice(2).some(value => value !== '--load') || process.argv.slice(2).length > 1) {
    throw Error('Only the optional --load disposable profile is supported');
  }
  const http = await port();
  const https = await port();
  const certResult = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '2', '-nodes',
    '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', key, '-out', cert],
  { stdio: 'ignore', timeout: 30000 });
  if (certResult.status !== 0) throw Error('Local OpenSSL certificate generation failed');
  const settings = {
    BIND_ADDRESS: '127.0.0.1', PUBLIC_HOST: 'localhost', HTTP_PORT: http, HTTPS_PORT: https,
    DB_NAME: 'mailguard', DB_USERNAME: 'mailguard', DELIVERY_SOURCE_ID: 'disposable-smoke',
    DB_PASSWORD_FILE: join(dir, 'db_password'), JOBS_API_KEY_FILE: join(dir, 'jobs_api_key'),
    VALKEY_PASSWORD_FILE: join(dir, 'valkey_password'), RATE_LIMIT_IDENTITY_SECRET_FILE: join(dir, 'rate_limit_identity_secret'),
    VALKEY_ACL_FILE: join(dir, 'valkey.acl'), TLS_CERT_FILE: cert, TLS_KEY_FILE: key,
  };
  writeFileSync(envFile, Object.entries(settings).map(([name, value]) => `${name}=${String(value).replaceAll('\\', '/')}`).join('\n') + '\n', { mode: 0o600 });
  console.log(`Disposable Compose project ${project}; private artifacts ${dir}`);
  compose('config', '--quiet');
  started = true;
  compose('up', '--build', '-d', '--wait', '--wait-timeout', '180');
  const url = `https://localhost:${https}`;
  expectStatus(await send(`${url}/`), 200);
  const redirect = await send(`http://localhost:${http}/validate`, 'GET', null, null, false);
  expectStatus(redirect, 301);
  if (redirect.headers.location !== `${url}/validate`) throw Error('HTTP redirect target is incorrect');
  const health = await send(`${url}/actuator/health`);
  expectStatus(health, 200);
  if (JSON.parse(health.body).status !== 'UP') throw Error('Public health status is not UP');
  expectStatus(await send(`${url}/actuator/prometheus`), 404);
  expectStatus(await send(`${url}/api/email/validate`, 'POST', { email: 'invalid-address' }), 401);
  const validation = await send(`${url}/api/email/validate`, 'POST', { email: 'invalid-address' }, apiKey);
  expectStatus(validation, 200);
  if (JSON.parse(validation.body).status !== 'INVALID') throw Error('Unexpected validation result');
  run(process.execPath, [join(root, 'frontend/node_modules/@playwright/test/cli.js'), 'test', '--config=playwright.production.config.ts'], {
    cwd: join(root, 'frontend'), timeout: 240000,
    env: { ...process.env, PROD_TEST_BASE_URL: url, PROD_TEST_API_KEY_FILE: join(dir, 'jobs_api_key') },
  });
  await drillWorkerHealth();
  if (process.argv.includes('--load')) {
    console.log(run(process.execPath, ['scripts/prod/load.mjs'], {
      env: { ...process.env, PROD_TEST_PROJECT: project, PROD_TEST_BASE_URL: url,
        PROD_TEST_CA_FILE: cert, PROD_TEST_API_KEY_FILE: join(dir, 'jobs_api_key') },
      timeout: 1800000, diagnostics: true,
    }));
  }
  // Freeze all application writers before the dump and both source snapshots.
  compose('stop', 'worker', 'api');
  const source = compose('ps', '-q', 'postgres').trim();
  run('node', ['scripts/backup/backup.mjs', 'backup', '--project-name', project, '--output', archive], { env: backupEnv, timeout: 120000 });
  const sourceSchema = schemaIntegrity(source, 'mailguard', 'mailguard');
  const recovery = JSON.parse(run('node', ['scripts/backup/backup.mjs', 'restore', '--project-name', project,
    '--input', archive, '--target-container', target], { env: backupEnv, timeout: 300000 }));
  const tables = new Set(Object.keys(recovery.tables));
  const sourceCounts = tableCounts(source, 'mailguard', 'mailguard', tables);
  if (Object.entries(sourceCounts).some(([table, count]) => recovery.tables[table] !== count)) {
    throw Error('Restored row counts differ from quiescent disposable source');
  }
  const restoredSchema = schemaIntegrity(target, 'mailguard', 'mailguard');
  sourceSchema.rows = await rowDigests(source, 'mailguard', 'mailguard', tables);
  restoredSchema.rows = await rowDigests(target, 'mailguard', 'mailguard', tables);
  assertMatchingIntegrity(sourceSchema, restoredSchema);
  if (recovery.tables['public.flyway_schema_history'] !== '5' || Number(recovery.tables['public.email_validation_history']) < 1
      || Number(recovery.tables['public.import_batch']) < 1) throw Error('Recovered database lacks expected records');
  await probeRestoredApplication();
  restored = true;
  console.log(`Verified HTTPS/browser, Flyway V1–V5 checksums, required schema/sequence, isolated read-only packaged-app import GET and SHA-256 content equality across ${tables.size} tables`);
}

try { await main(); }
catch (error) { console.error(`Disposable production verification failed: ${error.message}`); process.exitCode = 1; }
finally {
  if (restored) {
    try { run('docker', ['stop', target], { timeout: 30000 }); }
    catch { console.error(`Inspect test-owned recovery container ${target}`); }
  }
  if (started) {
    try { compose('down', '--remove-orphans'); }
    catch { console.error(`Inspect test-owned Compose project ${project}`); }
  }
  console.log(`Test-owned artifacts, if created, remain for inspection: ${dir}, ${project}_postgres_data`);
}
