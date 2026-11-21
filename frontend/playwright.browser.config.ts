import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e/browser',
  outputDir: './test-results/browser',
  workers: 1,
  retries: 0,
  use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:5187' },
  webServer: {
    command: 'npm run dev -- --port 5187 --strictPort',
    url: 'http://127.0.0.1:5187',
    reuseExistingServer: false,
  },
})
