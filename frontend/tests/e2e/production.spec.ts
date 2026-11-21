import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const apiKey = readFileSync(process.env.PROD_TEST_API_KEY_FILE!, 'utf8').trim()

async function connect(page: Page) {
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await page.getByLabel('Administrative API key').fill(apiKey)
  await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible()
}

function compose(...args: string[]) {
  const { PROD_TEST_PROJECT: project, PROD_TEST_COMPOSE_FILE: file, PROD_TEST_COMPOSE_ENV_FILE: envFile } = process.env
  if (!project || !/^mailguard-p12-smoke-[0-9a-f]{8}$/.test(project) || !file || !envFile) {
    throw new Error('Outage test requires the verifier-owned disposable Compose project and absolute file paths.')
  }
  execFileSync('docker', ['compose', '--env-file', envFile, '-f', file, '--project-name', project, ...args],
    { timeout: 120000, stdio: 'pipe' })
}

test('HTTPS assets, private admin session, worker import, and real CSV download', async ({ page }) => {
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await expect(page.getByRole('heading', { name: 'Clarity for every address.' })).toBeVisible()
  await expect(page.getByText('API online')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('https-dashboard.png'), fullPage: true })

  await page.getByRole('link', { name: 'Bulk validation' }).click()
  await expect(page.getByText('Administrator access required')).toBeVisible()
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await page.getByLabel('Administrative API key').fill('disposable-wrong-key')
  await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('dialog').getByText('Administrator key is missing or incorrect. Reconnect with a valid key.')).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click()
  await connect(page)
  const browserState = await page.evaluate(() => JSON.stringify({ localStorage, sessionStorage, cookies: document.cookie, url: location.href }))
  expect(browserState.includes(apiKey)).toBe(false)

  await page.getByLabel('Choose or drop a CSV file').setInputFiles({
    name: 'production-smoke.csv', mimeType: 'text/csv', buffer: Buffer.from('email\nmissing-at\n=unsafe-at\n'),
  })
  await page.getByRole('button', { name: 'Upload import' }).click()
  await expect(page).toHaveURL(/\/bulk\/[0-9a-f-]{36}$/)
  await expect(page.getByRole('row', { name: /missing-at/ })).toBeVisible({ timeout: 60000 })
  await expect(page.getByText('COMPLETED', { exact: true }).first()).toBeVisible({ timeout: 60000 })
  await expect(page.getByText('Input rows', { exact: true }).locator('..')).toContainText('2')
  await expect(page.getByRole('row', { name: /=unsafe-at/ })).toBeVisible()
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export CSV' }).click()
  const csv = await downloaded
  expect(csv.suggestedFilename()).toBe('import-results.csv')
  const file = await csv.path()
  if (!file) throw Error('CSV download missing')
  const contents = readFileSync(file, 'utf8')
  expect(contents).toContain('missing-at')
  expect(contents).toContain("'=unsafe-at")
  expect(contents.indexOf('missing-at')).toBeLessThan(contents.indexOf("'=unsafe-at"))
  await page.screenshot({ path: test.info().outputPath('https-import.png'), fullPage: true })
  await page.reload()
  await expect(page.getByText('Administrator access required')).toBeVisible()
  await connect(page)
  await expect(page.getByRole('row', { name: /missing-at/ })).toBeVisible()
})

test('admin-only validation history persists through browser reload without exposing the key', async ({ page, request }) => {
  const email = `history-${randomUUID().slice(0, 8)}@example..invalid`
  await page.goto('/history')
  await expect(page.getByText('Administrator access required')).toBeVisible()
  expect((await request.get('/api/email/history?limit=1')).status()).toBe(401)
  await connect(page)
  await page.getByRole('link', { name: 'Validate email' }).click()
  await page.getByLabel('Email address').fill(email)
  await page.getByRole('button', { name: 'Validate email' }).click()
  await expect(page.getByText('INVALID means the backend rejected the address format.')).toBeVisible()
  await page.getByRole('link', { name: 'History', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Persisted validations' })).toBeVisible()
  await expect(page.getByRole('table').first().getByRole('row', { name: new RegExp(email.replaceAll('.', '\\.')) })).toContainText('INVALID')
  await page.reload()
  await expect(page.getByText('Administrator access required')).toBeVisible()
  await expect(page.getByText('No session validations.')).toBeVisible()
  await connect(page)
  await expect(page.getByRole('table').first().getByRole('row', { name: new RegExp(email.replaceAll('.', '\\.')) })).toContainText('INVALID')
})

test('worker-completed job can be reopened by durable UUID', async ({ page }) => {
  const email = `job..${randomUUID().slice(0, 8)}@example.invalid`
  await page.goto('/jobs')
  await connect(page)
  await page.getByLabel('Email address').fill(email)
  await page.getByRole('button', { name: 'Submit job' }).click()
  await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/)
  const jobUrl = page.url()
  await expect(page.getByRole('heading', { name: 'Validation result' })).toBeVisible({ timeout: 60000 })
  await expect(page.getByText(email)).toBeVisible()
  await expect(page.getByText('INVALID', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByText('Administrator access required')).toBeVisible()
  await connect(page)
  await expect(page).toHaveURL(jobUrl)
  await expect(page.getByRole('heading', { name: 'Validation result' })).toBeVisible()
})

test('synthetic disposable delivery event yields evidence and revocable bounce suppression', async ({ page }) => {
  const email = `bounce-${randomUUID().slice(0, 8)}@example.invalid`
  await page.goto('/evidence')
  await connect(page)
  await page.getByLabel('Email address').fill(email)
  await page.getByRole('button', { name: 'Look up evidence' }).click()
  await expect(page.getByText('No delivery event observed for this address.')).toBeVisible()
  await page.getByLabel('Event email').fill(email)
  await page.getByLabel('Trusted external event ID').fill(`disposable-${randomUUID()}`)
  await page.getByLabel('Occurred at (local time; sent as UTC)').fill(new Date(Date.now() - 120000).toISOString().slice(0, 16))
  await page.getByLabel('Enhanced status code (optional)').fill('5.1.1')
  await page.getByRole('button', { name: 'Ingest event' }).click()
  await expect(page.getByText(/New event created/)).toBeVisible()
  await expect(page.getByText(/RECIPIENT_HARD_BOUNCE/)).toBeVisible()
  await expect(page.getByText(/Suppression: Active/)).toBeVisible()
  await page.getByRole('button', { name: 'Revoke displayed event' }).click()
  await page.getByLabel('Reason code').fill('DISPOSABLE_TEST_REVOKE')
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByText('Suppression: None reported')).toBeVisible()
})

test('manual suppression remains audited after lift and reload', async ({ page }) => {
  const email = `manual-${randomUUID().slice(0, 8)}@example.invalid`
  await page.goto('/suppressions')
  await connect(page)
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Reason code').fill('DISPOSABLE_TEST')
  await page.getByRole('button', { name: 'Create suppression' }).click()
  const row = page.getByRole('row', { name: new RegExp(email.replaceAll('.', '\\.')) })
  await expect(row).toContainText('ACTIVE')
  await row.getByRole('button', { name: 'Lift' }).click()
  await page.getByLabel('Lifting reason code').fill('DISPOSABLE_TEST_LIFT')
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click()
  await expect(row).toContainText('LIFTED')
  await expect(row).toContainText('DISPOSABLE_TEST_LIFT')
  await page.reload()
  await connect(page)
  await expect(page.getByRole('row', { name: new RegExp(email.replaceAll('.', '\\.')) })).toContainText('LIFTED')
})

test('real API outage is visible and persisted history recovers after container restart', async ({ page, request }) => {
  test.setTimeout(180000)
  const email = `outage-${randomUUID().slice(0, 8)}@example..invalid`
  await page.goto('/validate')
  await connect(page)
  await page.getByLabel('Email address').fill(email)
  await page.getByRole('button', { name: 'Validate email' }).click()
  await expect(page.getByText('INVALID means the backend rejected the address format.')).toBeVisible()
  await page.getByRole('link', { name: 'History', exact: true }).click()
  const row = page.getByRole('heading', { name: 'Persisted validations' }).locator('..')
    .getByRole('row', { name: new RegExp(email.replaceAll('.', '\\.')) })
  await expect(row).toContainText('INVALID')
  let stopped = false
  try {
    compose('stop', 'api')
    stopped = true
    const failed = await request.get('/actuator/health')
    expect(failed.status()).toBeGreaterThanOrEqual(500)
    await page.getByRole('link', { name: 'Settings' }).click()
    await page.getByRole('button', { name: 'Refresh health' }).click()
    await expect(page.getByText('Unavailable', { exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'History', exact: true }).click()
    await expect(page.getByRole('alert').getByText('Request failed')).toBeVisible()
    await expect(row).toHaveCount(0)
  } finally {
    if (stopped) compose('start', '--wait', 'api')
  }
  await expect.poll(async () => (await request.get('/actuator/health')).status(), { timeout: 60000 }).toBe(200)
  await page.getByRole('button', { name: 'Try again' }).click()
  await expect(row).toContainText('INVALID')
  await page.getByRole('link', { name: 'Settings' }).click()
  await page.getByRole('button', { name: 'Refresh health' }).click()
  await expect(page.getByText('UP', { exact: true })).toBeVisible()
})

test('pending job and import cancellation survive worker restart', async ({ page }) => {
  test.setTimeout(180000)
  let stopped = false
  try {
    compose('stop', 'worker')
    stopped = true
    await page.goto('/jobs')
    await connect(page)
    await page.getByLabel('Email address').fill(`cancel-${randomUUID().slice(0, 8)}@example.invalid`)
    await page.getByRole('button', { name: 'Submit job' }).click()
    await expect(page.getByRole('button', { name: 'Cancel job' })).toBeVisible()
    const jobUrl = page.url()
    await page.getByRole('button', { name: 'Cancel job' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click()
    await expect(page.getByText('CANCELLED', { exact: true })).toBeVisible()

    await page.getByRole('link', { name: 'Bulk validation' }).click()
    await page.getByLabel('Choose or drop a CSV file').setInputFiles({
      name: 'cancel.csv', mimeType: 'text/csv', buffer: Buffer.from('email\ncancel-me@example..invalid\n'),
    })
    await page.getByRole('button', { name: 'Upload import' }).click()
    await expect(page.getByRole('button', { name: 'Cancel import' })).toBeVisible()
    const importUrl = page.url()
    await page.getByRole('button', { name: 'Cancel import' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click()
    await expect(page.getByRole('row', { name: /cancel-me@example/ }).getByText('CANCELLED')).toBeVisible()
    await page.reload()
    await connect(page)
    await expect(page).toHaveURL(importUrl)
    await expect(page.getByRole('row', { name: /cancel-me@example/ }).getByText('CANCELLED')).toBeVisible()
    await page.goto(jobUrl)
    await expect(page.getByText('Administrator access required')).toBeVisible()
    await connect(page)
    await expect(page.getByText('CANCELLED', { exact: true })).toBeVisible()
  } finally {
    if (stopped) compose('start', '--wait', 'worker')
  }
})

test('PostgreSQL and worker restarts preserve a pending job and complete it exactly once', async ({ page, request }) => {
  test.setTimeout(240000)
  const email = `restart..${randomUUID().slice(0, 8)}@example.invalid`
  const headers = { 'X-Job-API-Key': apiKey }
  let workerStopped = false
  let storageStopped = false
  try {
    compose('stop', 'worker')
    workerStopped = true
    await page.goto('/jobs')
    await connect(page)
    await page.getByLabel('Email address').fill(email)
    await page.getByRole('button', { name: 'Submit job' }).click()
    await expect(page).toHaveURL(/\/jobs\/[0-9a-f-]{36}$/)
    await expect(page.getByText('PENDING', { exact: true })).toBeVisible()
    const id = new URL(page.url()).pathname.split('/').at(-1)!
    const before = await request.get(`/api/jobs/${id}`, { headers })
    expect(before.status()).toBe(200)
    expect((await before.json()).state).toBe('PENDING')

    // Restart storage with writers stopped; never replace or reset its named volume.
    storageStopped = true
    compose('stop', 'api', 'postgres')
    compose('start', '--wait', 'postgres', 'api')
    storageStopped = false
    const retained = await request.get(`/api/jobs/${id}`, { headers })
    expect(retained.status()).toBe(200)
    expect((await retained.json()).state).toBe('PENDING')
    compose('start', '--wait', 'worker')
    workerStopped = false
    await expect(page.getByRole('heading', { name: 'Validation result' })).toBeVisible({ timeout: 60000 })
    await expect(page.getByText(email)).toBeVisible()
    const completed = await request.get(`/api/jobs/${id}`, { headers })
    expect(completed.status()).toBe(200)
    const result = await completed.json()
    expect(result.state).toBe('COMPLETED')
    expect(result.attempts).toBe(1)
    expect(result.result.email).toBe(email)
    expect(result.result.status).toBe('INVALID')
    const history = await request.get('/api/email/history?limit=100', { headers })
    expect(history.status()).toBe(200)
    expect((await history.json()).filter((row: { email: string }) => row.email === email)).toHaveLength(1)
    await page.getByRole('link', { name: 'History', exact: true }).click()
    await expect(page.getByRole('table').first().getByRole('row', {
      name: new RegExp(email.replaceAll('.', '\\.')),
    })).toContainText('INVALID')
  } finally {
    if (storageStopped) compose('start', '--wait', 'postgres', 'api')
    if (workerStopped) compose('start', '--wait', 'worker')
  }
})

test('forwarded-client spoofing cannot reset the shared wrong-key quota', async ({ request }) => {
  expect((await request.get('/actuator/prometheus')).status()).toBe(404)
  expect((await request.post('/api/jobs', { data: { email: 'invalid-address' } })).status()).toBe(401)
  const statuses: number[] = []
  for (let index = 0; index < 66; index++) {
    const response = await request.post('/api/email/validate', {
      headers: {
        'X-Job-API-Key': 'invalid-test-key',
        'X-Forwarded-For': `192.0.2.${index}`,
        Forwarded: `for=198.51.100.${index}`,
      },
      data: { email: 'invalid-address' },
    })
    statuses.push(response.status())
  }
  expect(statuses).toContain(401)
  expect(statuses).toContain(429)
  expect(statuses.every(status => status === 401 || status === 429)).toBe(true)
})
