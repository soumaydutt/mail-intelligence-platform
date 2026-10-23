import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 60000,
  testIgnore: 'production.spec.ts',
  expect: { timeout: 12000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './tests/e2e/setup.ts',
  globalTeardown: './tests/e2e/teardown.ts',
  use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:5173', trace: 'off', screenshot: 'off', video: 'off' },
  webServer: {
    command: 'npm run dev -- --port 5173 --strictPort',
    url: 'http://127.0.0.1:5173',
    timeout: 60000,
    reuseExistingServer: false,
    env: { API_PROXY_TARGET: 'http://127.0.0.1:18094' },
  },
})
