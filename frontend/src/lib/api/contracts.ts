import { z } from 'zod'

const nullableText = z.string().nullable()
const nullableBool = z.boolean().nullable()
const uuid = z.uuid()

export const evidenceSchema = z.object({
  available: z.boolean(), eventId: uuid.nullable(), eventType: nullableText,
  interpretation: nullableText, occurredAt: nullableText, sourceType: nullableText,
  sourceIdentifier: nullableText, freshness: z.string(), suppressed: nullableBool,
  suppressionReason: nullableText, observedAt: nullableText, limitation: nullableText,
})
export type Evidence = z.infer<typeof evidenceSchema>

export const validationSchema = z.object({
  email: z.string(), domain: nullableText, formatValid: nullableBool,
  mxRecordFound: nullableBool, smtpVerified: nullableBool,
  status: z.string(), message: z.string(), historicalEvidence: evidenceSchema.optional(),
})
export type Validation = z.infer<typeof validationSchema>

export const submissionSchema = z.object({ id: uuid, reused: z.boolean() })
export const jobSchema = z.object({
  id: uuid, state: z.enum(['PENDING', 'RUNNING', 'RETRY', 'COMPLETED', 'FAILED', 'CANCELLED']),
  attempts: z.number(), maxAttempts: z.number(), dueAt: nullableText,
  createdAt: z.string(), finishedAt: nullableText, failureCode: nullableText,
  result: validationSchema.nullable(),
})
export type Job = z.infer<typeof jobSchema>

export const importSchema = z.object({
  id: uuid, state: z.enum(['INGESTING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED']),
  inputRows: z.number(), acceptedRows: z.number(), duplicateRows: z.number(), rejectedRows: z.number(),
  pendingRows: z.number(), runningRows: z.number(), retryRows: z.number(),
  completedRows: z.number(), failedRows: z.number(), cancelledRows: z.number(),
  createdAt: z.string(), finishedAt: nullableText, failureCode: nullableText,
})
export type ImportProgress = z.infer<typeof importSchema>
export const resultRowSchema = z.object({
  ordinal: z.number(), email: nullableText, rowState: z.string(), duplicateOf: z.number().nullable(),
  reason: nullableText, jobState: nullableText, resultStatus: nullableText, failureCode: nullableText,
  formatValid: nullableBool, mxRecordFound: nullableBool, smtpVerified: nullableBool,
  domain: nullableText, message: nullableText,
})
export type ResultRow = z.infer<typeof resultRowSchema>

export const suppressionSchema = z.object({
  id: uuid, email: z.string(), reason: z.string(), effectiveAt: z.string(), expiresAt: nullableText,
  active: z.boolean(), liftedAt: nullableText, liftingReason: nullableText, sourceEventId: uuid.nullable(),
})
export type Suppression = z.infer<typeof suppressionSchema>
export const idSchema = z.object({ id: uuid })
export const ingestedSchema = z.object({ id: uuid, reused: z.boolean() })
export const healthSchema = z.object({ status: z.string() })
