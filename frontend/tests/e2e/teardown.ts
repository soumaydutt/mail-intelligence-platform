import { readFile, unlink } from 'node:fs/promises'
import { cleanupOwned, runtimeFile, type OwnedRuntime } from './setup'

export default async function teardown() {
  let runtime: OwnedRuntime
  try { runtime = JSON.parse(await readFile(runtimeFile, 'utf8')) as OwnedRuntime }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return
    throw error
  }
  try { await cleanupOwned(runtime) }
  finally { await unlink(runtimeFile) }
}
