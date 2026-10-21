import { execFileSync, spawn } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { expect, test, type Page } from '@playwright/test'
import { runtimeFile, type OwnedRuntime } from './setup'

async function connect(page: Page) {
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await page.getByLabel('Administrative API key').fill(process.env.E2E_API_KEY!)
  await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible()
}

test.describe.serial('disposable PostgreSQL, Valkey, packaged API, and worker', () => {
  test('responsive navigation, health, theme, accessibility and protected routes', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Clarity for every address.' })).toBeVisible()
    await expect(page.getByText('API online')).toBeVisible()
    await page.getByRole('link', { name: 'Bulk validation' }).click()
    await expect(page.getByText('Administrator access required')).toBeVisible()
    await page.getByRole('link', { name: 'Settings' }).click()
    await page.getByRole('button', { name: 'Switch to dark theme' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await page.setViewportSize({ width: 360, height: 720 })
    await page.getByRole('button', { name: 'Open navigation' }).click()
    await expect(page.getByRole('link', { name: 'Validate email' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('button', { name: 'Open navigation' })).toHaveAttribute('aria-expanded', 'false')
    await page.keyboard.press('Tab')
    await expect(page.locator(':focus-visible')).toHaveCount(1)
    await page.goto('/unknown-route')
    await expect(page.getByText('Page not found')).toBeVisible()
  })

  test('individual validation shows backend uncertainty and only session history', async ({ page }) => {
    await page.goto('/validate')
    await page.getByLabel('Email address').fill('person@example.invalid')
    await page.getByRole('button', { name: 'Validate email' }).click()
    await expect(page.getByRole('heading', { name: 'Validation result' })).toBeVisible()
    await expect(page.getByText('UNVERIFIED means mailbox ownership and deliverability were not established. MX alone is not proof.')).toBeVisible()
    await page.getByLabel('Email address').fill('bad@example..invalid')
    await page.getByRole('button', { name: 'Validate email' }).click()
    await expect(page.getByText('INVALID means the backend rejected the address format.')).toBeVisible()
    await page.getByRole('link', { name: 'Session history' }).click()
    await expect(page.getByRole('cell', { name: 'person@example.invalid' })).toBeVisible()
    await expect(page.getByRole('cell', { name: 'bad@example..invalid' })).toBeVisible()
    await page.reload()
    await expect(page.getByText('No session validations')).toBeVisible()
  })

  test('rejects invalid key, clears draft, authenticates and never persists key', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await page.getByLabel('Administrative API key').fill('incorrect-admin-key')
    await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByText('Administrator key is missing or incorrect. Reconnect with a valid key.')).toBeVisible()
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click()
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByLabel('Administrative API key')).toHaveValue('')
    await page.getByLabel('Administrative API key').fill(process.env.E2E_API_KEY!)
    await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible()
    const key = process.env.E2E_API_KEY!
    expect(await page.evaluate(() => JSON.stringify({ local: localStorage, session: sessionStorage, url: location.href, cookies: document.cookie }))).not.toContain(key)
    await page.getByRole('button', { name: 'Disconnect', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: 'Connect', exact: true })).toBeVisible()
  })

  test('CSV import persists rows, worker completes, and export downloads real CSV', async ({ page }) => {
    await page.goto('/bulk')
    await connect(page)
    await page.getByLabel('Choose or drop a CSV file').setInputFiles({ name: 'cases.csv', mimeType: 'text/csv', buffer: Buffer.from('email\nmissing-at\nsecond-bad\n=foo@example.invalid\n') })
    await page.getByRole('button', { name: 'Upload import' }).click()
    await expect(page).toHaveURL(/\/bulk\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { name: 'Result rows' })).toBeVisible()
    await expect(page.getByRole('row', { name: /missing-at/ })).toBeVisible({ timeout: 45000 })
    await expect(page.getByRole('row', { name: /second-bad/ })).toBeVisible({ timeout: 45000 })
    await expect(page.getByRole('row', { name: /=foo@example.invalid/ })).toBeVisible({ timeout: 45000 })
    await expect(page.getByText('COMPLETED', { exact: true }).first()).toBeVisible({ timeout: 45000 })
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Export CSV' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('import-results.csv')
    const downloadPath = await download.path()
    if (!downloadPath) throw new Error('CSV download did not finish.')
    const text = await readFile(downloadPath, 'utf8')
    expect(text).toContain('missing-at')
    expect(text).toContain('second-bad')
    expect(text).toContain("'=foo@example.invalid")
    await page.reload()
    await expect(page.getByText('Administrator access required')).toBeVisible()
    await connect(page)
    await expect(page.getByRole('row', { name: /missing-at/ })).toBeVisible()
  })

  test('job detail survives navigation; backend worker writes terminal result', async ({ page }) => {
    await page.goto('/jobs')
    await connect(page)
    await page.getByLabel('Email address').fill('person@example.invalid')
    await page.getByRole('button', { name: 'Submit job' }).click()
    await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { name: 'Validation result' })).toBeVisible({ timeout: 45000 })
    await expect(page.getByText('person@example.invalid')).toBeVisible()
    await page.getByRole('link', { name: 'Back to jobs' }).click()
    await expect(page.getByRole('heading', { name: 'Validation jobs' })).toBeVisible()
  })

  test('trusted test fixture produces evidence; revocation lifts its suppression', async ({ page }) => {
    await page.goto('/evidence')
    await connect(page)
    const email = 'bounce@example.invalid'
    await page.getByLabel('Event email').fill(email)
    await page.getByLabel('Trusted external event ID').fill('phase11-e2e-bounce')
    await page.getByLabel('Occurred at (local time; sent as UTC)').fill(new Date(Date.now() - 120000).toISOString().slice(0, 16))
    await page.getByLabel('Enhanced status code (optional)').fill('5.1.1')
    await page.getByRole('button', { name: 'Ingest event' }).click()
    await expect(page.getByText(/New event created/)).toBeVisible()
    await page.getByLabel('Email address').fill(email)
    await page.getByRole('button', { name: 'Look up evidence' }).click()
    await expect(page.getByText('Suppression: Active')).toBeVisible()
    await expect(page.getByText(/RECIPIENT_HARD_BOUNCE/)).toBeVisible()
    await page.getByRole('button', { name: 'Revoke displayed event' }).click()
    await page.getByLabel('Reason code').fill('INCORRECT_EVENT')
    await page.getByRole('dialog').getByRole('button', { name: /Confirm/ }).click()
    await expect(page.getByText(/Event revoked/)).toBeVisible()
    await page.getByRole('button', { name: 'Look up evidence' }).click()
    await expect(page.getByText('Suppression: None reported')).toBeVisible()
  })

  test('manual suppression is listed and lift is audited', async ({ page }) => {
    await page.goto('/suppressions')
    await connect(page)
    await page.getByLabel('Email address').fill('manual@example.invalid')
    await page.getByLabel('Reason code').fill('OPERATOR_BLOCK')
    await page.getByRole('button', { name: 'Create suppression' }).click()
    const record = page.getByRole('row', { name: /manual@example.invalid/ })
    await expect(record).toBeVisible()
    await record.getByRole('button', { name: 'Lift' }).click()
    await page.getByLabel('Lifting reason code').fill('OPERATOR_REVIEW')
    await page.getByRole('dialog').getByRole('button', { name: /Confirm/ }).click()
    await expect(record).toContainText('LIFTED')
  })

  test('429 Retry-After and real Valkey 503 recover without false success', async ({ page, request }) => {
    await page.goto('/suppressions')
    await connect(page)
    expect((await request.post('/api/email/test')).status()).toBe(200)
    expect((await request.post('/api/email/test')).status()).toBe(200)
    const throttled = await request.post('/api/email/test')
    expect(throttled.status()).toBe(429)
    expect(throttled.headers()['retry-after']).toBeDefined()
    const name = process.env.E2E_VALKEY_NAME!
    execFileSync('docker', ['stop', name], { stdio: 'ignore', timeout: 15000 })
    try {
      const rejected = await request.get('/api/suppressions?limit=1', { headers: { 'X-Job-API-Key': process.env.E2E_API_KEY! } })
      expect(rejected.status()).toBe(503)
      await page.getByRole('button', { name: 'Disconnect', exact: true }).click()
      await page.getByRole('button', { name: 'Connect', exact: true }).click()
      await page.getByLabel('Administrative API key').fill(process.env.E2E_API_KEY!)
      await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
      await expect(page.getByRole('dialog').getByText('Shared admission or the backend is unavailable. Try again later.')).toBeVisible()
    } finally {
      execFileSync('docker', ['start', name], { stdio: 'ignore', timeout: 15000 })
    }
    await expect.poll(async () => (await request.get('/api/suppressions?limit=1', {
      headers: { 'X-Job-API-Key': process.env.E2E_API_KEY! },
    })).status(), { timeout: 30000 }).toBe(200)
    await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Suppression records' })).toBeVisible()
  })

  test('actual API outage shows an error, restart restores the same record', async ({ page }) => {
    const runtime = JSON.parse(await readFile(runtimeFile, 'utf8')) as OwnedRuntime
    await page.goto('/suppressions')
    await connect(page)
    if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(runtime.apiPid), '/T', '/F'], { stdio: 'ignore', timeout: 15000 })
    else process.kill(runtime.apiPid, 'SIGTERM')
    await page.getByRole('link', { name: 'Settings' }).click()
    await page.getByRole('button', { name: 'Refresh health' }).click()
    await expect(page.getByText('Unavailable', { exact: true })).toBeVisible({ timeout: 30000 })
    const jar = resolve(process.cwd(), '../target/mailguard-0.0.1-SNAPSHOT.jar')
    const child = spawn('java', ['-jar', jar, '--server.port=18094'], {
      env: { ...process.env, DB_URL: process.env.E2E_DB_URL, DB_USERNAME: 'mailguard', DB_PASSWORD: process.env.E2E_DB_PASSWORD,
        JOBS_API_KEY: process.env.E2E_API_KEY, DELIVERY_SOURCE_ID: 'phase11-fixture', VALKEY_HOST: '127.0.0.1', VALKEY_PORT: '16393',
        VALKEY_NAMESPACE: process.env.E2E_VALKEY_NAMESPACE, DNS_CACHE_ENABLED: 'true', RATE_LIMIT_ENABLED: 'true',
        RATE_LIMIT_IDENTITY_SECRET: process.env.E2E_RATE_SECRET, RATE_LIMIT_TEST_CAPACITY: '2', JOBS_WORKER_ENABLED: 'false' },
      stdio: 'ignore', windowsHide: true,
    })
    if (!child.pid) throw new Error('Could not restart packaged API')
    runtime.apiPid = child.pid
    await writeFile(runtimeFile, JSON.stringify(runtime))
    for (let attempt = 0; attempt < 100; attempt++) {
      try { if ((await fetch('http://127.0.0.1:18094/actuator/health')).ok) break }
      catch { /* Backend is restarting. */ }
      if (attempt === 99) throw new Error('Packaged API failed to recover')
      await delay(500)
    }
    await page.getByRole('button', { name: 'Refresh health' }).click()
    await expect(page.getByText('UP', { exact: true })).toBeVisible({ timeout: 15000 })
    await page.getByRole('link', { name: 'Suppressions' }).click()
    await expect(page.getByRole('row', { name: /manual@example.invalid/ })).toBeVisible()
  })
})
