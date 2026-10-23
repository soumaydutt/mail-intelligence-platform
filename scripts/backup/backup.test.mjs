import { strict as assert } from 'node:assert';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { assertMatchingIntegrity, assertRestoredIntegrity, options } from './backup.mjs';

const env = { DB_USERNAME: 'mailguard', DB_NAME: 'mailguard' };
const archive = process.platform === 'win32' ? 'C:/private/mailguard.dump' : '/private/mailguard.dump';

test('recovery refuses to target a source or pre-existing-name-shaped live service', () => {
  assert.throws(() => options(['restore', '--project-name', 'operator', '--input', archive], env), /explicit --target-container/);
  assert.throws(() => options(['restore', '--project-name', 'operator', '--input', archive, '--target-container', 'operator'], env), /distinct/);
  assert.throws(() => options(['restore', '--project-name', 'operator', '--input', archive, '--target-container', 'mailguard-api-1'], env), /disposable/);
});

test('backup refuses relative locations, overwrites by alias, and unsafe SQL identifiers', () => {
  assert.throws(() => options(['backup', '--project-name', 'operator', '--output', 'archive.dump'], env), /Absolute/);
  assert.throws(() => options(['backup', '--project-name', 'operator', '--output', archive, '--input', archive], env), /Backup accepts only/);
  assert.throws(() => options(['backup', '--project-name', 'operator', '--output', archive], { DB_USERNAME: 'root;drop table', DB_NAME: 'mailguard' }), /identifiers/);
});

test('equal row counts do not conceal altered restored content', () => {
  const digest = value => createHash('sha256').update(value).digest('hex');
  const source = { objects: [], history: [], sequence: ['2', true], maxId: '2',
    rows: { 'public.email_validation_history': digest('1|original\n2|unchanged\n') } };
  const restored = { ...source,
    rows: { 'public.email_validation_history': digest('1|modified\n2|unchanged\n') } };
  assert.throws(() => assertMatchingIntegrity(source, restored), /row content differs/);
});

test('matching records do not conceal changed schema or sequence', () => {
  const source = { objects: [['constraint', 'public.import_row.chk_import_row_duplicate_prior', 'CHECK (duplicate_of < ordinal)']],
    history: [['1', 123, true, 'V1__create_validation_history.sql']],
    sequence: ['42', true], maxId: '42', rows: { 'public.import_row': 'same-content' } };
  assert.throws(() => assertMatchingIntegrity(source, { ...source, objects: [] }), /schema, migration or sequence differs/);
  assert.throws(() => assertMatchingIntegrity(source, { ...source,
    objects: [['constraint', 'public.import_row.chk_import_row_duplicate_prior', 'different check metadata']] }),
  /schema, migration or sequence differs/);
  assert.throws(() => assertMatchingIntegrity(source, { ...source, sequence: ['1', true] }), /schema, migration or sequence differs/);
  assert.throws(() => assertRestoredIntegrity({ ...source, history: [] }), /Flyway V1–V5 history\/checksums/);
});
