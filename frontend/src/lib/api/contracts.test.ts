import { describe, expect, it } from 'vitest'
import { importSchema, type ImportProgress } from './contracts'

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
