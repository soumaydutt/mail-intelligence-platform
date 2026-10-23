import { defineConfig, devices } from '@playwright/test'

if (!process.env.PROD_TEST_BASE_URL || !process.env.PROD_TEST_API_KEY_FILE) {
  throw new Error('Production browser tests need an explicit disposable HTTPS URL and key file.')
}
const disposableUrl = new URL(process.env.PROD_TEST_BASE_URL)
if (disposableUrl.protocol !== 'https:' || disposableUrl.hostname !== 'localhost' || !disposableUrl.port) {
  throw new Error('Production browser tests are restricted to disposable localhost HTTPS.')
}

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: 'production.spec.ts',
  timeout: 90000,
  expect: { timeout: 20000 },
  workers: 1,
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.PROD_TEST_BASE_URL,
    // Only the disposable generated localhost certificate is self-signed.
    ignoreHTTPSErrors: true,
  },
})
