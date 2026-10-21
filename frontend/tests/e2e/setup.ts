import { execFileSync, spawn } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'
import { access, mkdir, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { once } from 'node:events'

export interface OwnedRuntime { postgres: string; valkey: string; apiPid: number; workerPid: number }
export const runtimeFile = resolve(process.cwd(), 'test-results/runtime.json')

function docker(...args: string[]) {
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

export async function cleanupOwned(runtime: Partial<OwnedRuntime>) {
  for (const pid of [runtime.workerPid, runtime.apiPid]) {
    if (!pid) continue
    try {
      if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', timeout: 15000 })
      else process.kill(pid, 'SIGTERM')
    } catch { /* Only test-owned child processes are eligible for cleanup. */ }
  }
  for (const name of [runtime.valkey, runtime.postgres]) {
    if (name) try { docker('rm', '-f', name) } catch { /* The owned container may have exited already. */ }
  }
}

export default async function setup() {
  const runtime: Partial<OwnedRuntime> = {}
  const listen = createServer()
  try {
    listen.listen(18094, '127.0.0.1')
    await once(listen, 'listening')
    listen.close()
    const jar = resolve(process.cwd(), '../target/mailguard-0.0.1-SNAPSHOT.jar')
    try { await access(jar) } catch { throw new Error('Run mvn -B package before Playwright; packaged API jar is required.') }
    await mkdir(resolve(process.cwd(), 'test-results'), { recursive: true })
    const suffix = randomUUID().slice(0, 8)
    const password = randomBytes(24).toString('hex')
    const key = randomBytes(36).toString('hex')
    runtime.postgres = `mailguard-p11-pg-${suffix}`
    runtime.valkey = `mailguard-p11-vk-${suffix}`
    docker('run', '-d', '--name', runtime.postgres, '-e', 'POSTGRES_USER=mailguard', '-e', `POSTGRES_PASSWORD=${password}`, '-e', 'POSTGRES_DB=mailguard', '-p', '127.0.0.1::5432', 'postgres:17-alpine')
    docker('run', '-d', '--name', runtime.valkey, '-p', '127.0.0.1:16393:6379', 'valkey/valkey:8-alpine', 'valkey-server', '--save', '', '--appendonly', 'no', '--maxmemory', '128mb', '--maxmemory-policy', 'noeviction')
    const pgPort = docker('port', runtime.postgres, '5432/tcp').match(/:(\d+)$/)?.[1]
    const valkeyPort = docker('port', runtime.valkey, '6379/tcp').match(/:(\d+)$/)?.[1]
    if (!pgPort || !valkeyPort) throw new Error('Disposable service ports were not assigned.')
    for (let attempt = 0; attempt < 60; attempt++) {
      try { docker('exec', runtime.postgres, 'pg_isready', '-U', 'mailguard', '-d', 'mailguard'); break }
      catch { if (attempt === 59) throw new Error('Disposable PostgreSQL did not start.'); await delay(500) }
    }
    for (let attempt = 0; attempt < 40; attempt++) {
      try { if (docker('exec', runtime.valkey, 'redis-cli', 'ping') === 'PONG') break }
      catch { if (attempt === 39) throw new Error('Disposable Valkey did not start.') }
      await delay(250)
    }
    const env = {
      ...process.env, DB_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/mailguard`, DB_USERNAME: 'mailguard', DB_PASSWORD: password,
      JOBS_API_KEY: key, DELIVERY_SOURCE_ID: 'phase11-fixture', VALKEY_HOST: '127.0.0.1', VALKEY_PORT: valkeyPort,
      VALKEY_NAMESPACE: `p11-${suffix}`, DNS_CACHE_ENABLED: 'true', RATE_LIMIT_ENABLED: 'true',
      RATE_LIMIT_IDENTITY_SECRET: randomBytes(40).toString('hex'),
      RATE_LIMIT_TEST_CAPACITY: '2', JOBS_WORKER_ENABLED: 'false', MANAGEMENT_ENDPOINTS: 'health',
    }
    const api = spawn('java', ['-jar', jar, '--server.port=18094'], { env, stdio: 'ignore', windowsHide: true })
    if (!api.pid) throw new Error('Packaged API did not start.')
    runtime.apiPid = api.pid
    for (let attempt = 0; attempt < 120; attempt++) {
      try {
        const response = await fetch('http://127.0.0.1:18094/actuator/health', { signal: AbortSignal.timeout(1000) })
        if (response.ok && (await response.json()).status === 'UP') break
      } catch { /* Spring Boot and Flyway may still be starting. */ }
      if (api.exitCode !== null || attempt === 119) throw new Error('Packaged API did not become healthy.')
      await delay(500)
    }
    const worker = spawn('java', ['-jar', jar, '--spring.main.web-application-type=none'], { env: { ...env, JOBS_WORKER_ENABLED: 'true' }, stdio: 'ignore', windowsHide: true })
    if (!worker.pid) throw new Error('Packaged headless worker did not start.')
    runtime.workerPid = worker.pid
    process.env.E2E_API_KEY = key
    process.env.E2E_VALKEY_NAME = runtime.valkey
    process.env.E2E_DB_URL = env.DB_URL
    process.env.E2E_DB_PASSWORD = password
    process.env.E2E_VALKEY_NAMESPACE = env.VALKEY_NAMESPACE
    process.env.E2E_RATE_SECRET = env.RATE_LIMIT_IDENTITY_SECRET
    await writeFile(runtimeFile, JSON.stringify(runtime), { flag: 'wx' })
  } catch (error) {
    if (listen.listening) listen.close()
    await cleanupOwned(runtime)
    throw error
  }
}
