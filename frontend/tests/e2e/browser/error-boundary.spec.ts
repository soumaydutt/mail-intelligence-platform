import { expect, test } from '@playwright/test'

test('a React render failure offers a working full-page reload without exposing its error', async ({ page }) => {
  await page.goto('/tests/e2e/browser/error-boundary.html')
  await expect(page.getByRole('heading', { name: 'The view could not load' })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('private-render-failure@example.invalid')
  await page.getByRole('button', { name: 'Reload dashboard' }).click()
  await expect(page.getByText('Dashboard recovered after reload.')).toBeVisible()
  expect(await page.evaluate(() => (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming).type)).toBe('reload')
})
