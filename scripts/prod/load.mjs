#!/usr/bin/env node
// Bounded offline-format workload: no third-party addresses or public DNS lookups.
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { request } from 'node:https';
import { performance } from 'node:perf_hooks';
import { setTimeout as pause } from 'node:timers/promises';

const project = process.env.PROD_TEST_PROJECT;
const base = process.env.PROD_TEST_BASE_URL;
if (!/^mailguard-p12-smoke-[a-f0-9]{8}$/.test(project ?? '')
    || !base || new URL(base).hostname !== 'localhost' || new URL(base).protocol !== 'https:') {
  throw Error('Load requires a generated disposable project and localhost HTTPS URL');
}
const ca = readFileSync(process.env.PROD_TEST_CA_FILE);
const key = readFileSync(process.env.PROD_TEST_API_KEY_FILE, 'utf8').trim();
const sizes = [100, 1000, 10000];
const samples = 5;
const deadline = 240000;
const thresholds = {
  100: { uploadMs: 500, terminalMs: 10000 },
  1000: { uploadMs: 3000, terminalMs: 40000 },
  10000: { uploadMs: 20000, terminalMs: 200000 },
};

function percentile(sorted, percent) { return Number(sorted[Math.ceil(percent * sorted.length) - 1].toFixed(1)); }
function summary(samplesMs) {
  const sorted = samplesMs.slice().sort((a, b) => a - b);
  return { samples: sorted.length, p50Ms: percentile(sorted, 0.5), p95Ms: percentile(sorted, 0.95), p99Ms: percentile(sorted, 0.99), maxMs: Number(sorted.at(-1).toFixed(1)) };
}
function call(path, method = 'GET', body = null, contentType = null) {
  return new Promise((resolve, reject) => {
    const headers = { 'X-Job-API-Key': key };
    if (contentType) headers['Content-Type'] = contentType;
    if (body) headers['Content-Length'] = body.length;
    const started = performance.now();
    const req = request(new URL(path, base), { method, ca, headers, timeout: 60000 }, response => {
      const chunks = [];
      response.on('data', bytes => chunks.push(bytes));
      response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString('utf8'), ms: performance.now() - started }));
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(Error('Timed out')));
    if (body) req.write(body);
    req.end();
  });
}
function demand(reply, expected) {
  if (reply.status !== expected) throw Error(`Load request expected HTTP ${expected}, received ${reply.status}`);
  return JSON.parse(reply.body);
}

const profile = [];
for (const size of sizes) {
  if (profile.length) await pause(61000); // shortcut: five uploads fit six/min, add pacing if samples grow.
  const uploads = [], completions = [];
  for (let sample = 0; sample < samples; sample++) {
    const token = randomBytes(4).toString('hex');
    const rows = Array.from({ length: size }, (_, i) => `invalid-${token}-${i}`).join('\r\n');
    const boundary = `mailguard${token}`;
    const body = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="bounded.csv"\r\nContent-Type: text/csv\r\n\r\nemail\r\n${rows}\r\n--${boundary}--\r\n`);
    const start = performance.now();
    const upload = await call('/api/imports', 'POST', body, `multipart/form-data; boundary=${boundary}`);
    const id = demand(upload, 202).id;
    uploads.push(upload.ms);
    let progress;
    do {
      if (performance.now() - start > deadline) throw Error(`CSV ${size} did not reach a terminal state`);
      await pause(2000);
      progress = demand(await call(`/api/imports/${id}`), 200);
    } while (progress.state !== 'COMPLETED' && progress.state !== 'FAILED');
    if (progress.state !== 'COMPLETED' || progress.inputRows !== size || progress.acceptedRows !== size
        || progress.completedRows !== size || progress.rejectedRows !== 0) throw Error(`CSV ${size} lost or misclassified rows`);
    completions.push(performance.now() - start);
  }
  const result = { rows: size, upload: summary(uploads), terminal: summary(completions), completedRows: size * samples };
  profile.push(result);
  console.log(JSON.stringify(result));
  if (result.upload.p95Ms > thresholds[size].uploadMs || result.terminal.p95Ms > thresholds[size].terminalMs) {
    throw Error(`CSV ${size} exceeded disposable p95 regression threshold`);
  }
}
console.log(JSON.stringify({ workload: 'synthetic-invalid-address-csv', runs: sizes.length * samples,
  totalRows: sizes.reduce((sum, count) => sum + count * samples, 0), project, profile }));
