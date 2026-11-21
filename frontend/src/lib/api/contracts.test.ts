import { describe, expect, it } from 'vitest'
import { evidenceSchema, idSchema, importSchema, ingestedSchema, jobSchema, submissionSchema, suppressionSchema, uuidSchema, type ImportProgress } from './contracts'

// Matches the JSON field names and nullable timestamps returned by BulkImportStore.Progress.
const progress = {
  id: 'f21f5da5-7898-43e7-95c5-241a99ad63db', state: 'READY',
  inputRows: 1, acceptedRows: 1, duplicateRows: 0, rejectedRows: 0,
  pendingRows: 1, runningRows: 0, retryRows: 0, completedRows: 0,
  failedRows: 0, cancelledRows: 0, createdAt: '2026-01-01T00:00:00Z',
  finishedAt: null, failureCode: null,
}

describe('GET /api/imports/{id} contract', () => {
  it.each(['INGESTING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const)(
    'accepts backend state %s', state => {
      const parsed = importSchema.parse({ ...progress, state, finishedAt: ['COMPLETED', 'FAILED', 'CANCELLED'].includes(state) ? '2026-01-01T00:00:01Z' : null })
      const inferredState: ImportProgress['state'] = parsed.state
      expect(inferredState).toBe(state)
    },
  )

  it.each(['WAITING', 'processing', '', null])('rejects unsupported state %s', state => {
    expect(importSchema.safeParse({ ...progress, state }).success).toBe(false)
  })
})

describe('backend canonical UUID contract', () => {
  const identifierSchemas = [
    idSchema, ingestedSchema, submissionSchema,
    jobSchema.pick({ id: true }), importSchema.pick({ id: true }),
    evidenceSchema.pick({ eventId: true }),
    suppressionSchema.pick({ id: true, sourceEventId: true }),
  ]

  it.each([
    '12345678-abcd-0abc-1abc-123456789abc',
    '12345678-ABCD-0ABC-1ABC-123456789ABC',
    'f21f5da5-7898-43e7-95c5-241a99ad63db',
    '00000000-0000-0000-0000-000000000000',
    'ffffffff-ffff-ffff-ffff-ffffffffffff',
  ])('accepts canonical hexadecimal ID %s without RFC version or variant restrictions', id => {
    expect(uuidSchema.parse(id)).toBe(id)
    for (const schema of identifierSchemas) {
      expect(schema.safeParse({ id, eventId: id, sourceEventId: id, reused: false }).success).toBe(true)
    }
  })

  it.each([
    '1-1-1-1-1',
    '12345678abcd0abc1abc123456789abc',
    '12345678-abcd-0abc-1abc-123456789ab',
    '12345678-abcd-0abc-1abc-123456789abcd',
    '12345678-abcd-0abc-1abc-123456789abg',
    '12345678_abcd-0abc-1abc-123456789abc',
    ' 12345678-abcd-0abc-1abc-123456789abc',
    '12345678-abcd-0abc-1abc-123456789abc ',
    '12345678-abcd-0abc-1abc-123456789abc\n',
    '12345678-abcd-0abc-1abc-123456789abc\r',
    '',
  ])('rejects malformed ID %s in lookups and payloads', id => {
    expect(uuidSchema.safeParse(id).success).toBe(false)
    for (const schema of identifierSchemas) {
      expect(schema.safeParse({ id, eventId: id, sourceEventId: id, reused: false }).success).toBe(false)
    }
  })
})
