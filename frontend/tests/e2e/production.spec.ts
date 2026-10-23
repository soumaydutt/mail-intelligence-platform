import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

const apiKey = readFileSync(process.env.PROD_TEST_API_KEY_FILE!, 'utf8').trim()

test('HTTPS assets, private admin session, worker import, and real CSV download', async ({ page }) => {
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await expect(page.getByRole('heading', { name: 'Clarity for every address.' })).toBeVisible()
  await expect(page.getByText('API online')).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('https-dashboard.png'), fullPage: true })

  await page.getByRole('link', { name: 'Bulk validation' }).click()
  await expect(page.getByText('Administrator access required')).toBeVisible()
  await page.getByRole('button', { name: 'Connect', exact: true }).click()
  await page.getByLabel('Administrative API key').fill(apiKey)
  await page.getByRole('dialog').getByRole('button', { name: 'Connect', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Disconnect', exact: true })).toBeVisible()
  const browserState = await page.evaluate(() => JSON.stringify({ localStorage, sessionStorage, cookies: document.cookie, url: location.href }))
  expect(browserState.includes(apiKey)).toBe(false)

  await page.getByLabel('Choose or drop a CSV file').setInputFiles({
    name: 'production-smoke.csv', mimeType: 'text/csv', buffer: Buffer.from('email\nmissing-at\n=unsafe-at\n'),
  })
  await page.getByRole('button', { name: 'Upload import' }).click()
  await expect(page).toHaveURL(/\/bulk\/[0-9a-f-]{36}$/)
  await expect(page.getByRole('row', { name: /missing-at/ })).toBeVisible({ timeout: 60000 })
  await expect(page.getByText('COMPLETED', { exact: true }).first()).toBeVisible({ timeout: 60000 })
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export CSV' }).click()
  const csv = await downloaded
  expect(csv.suggestedFilename()).toBe('import-results.csv')
  const file = await csv.path()
  if (!file) throw Error('CSV download missing')
  const contents = readFileSync(file, 'utf8')
  expect(contents).toContain('missing-at')
  expect(contents).toContain("'=unsafe-at")
  await page.screenshot({ path: test.info().outputPath('https-import.png'), fullPage: true })
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
