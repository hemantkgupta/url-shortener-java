import { test, expect } from '@playwright/test';
import { TEST_AUTH_STORAGE_KEY, TEST_AUTH_STORAGE_VALUE } from '../src/auth/e2eAuth.js';

test.describe('URL Shortener Integration', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(
      ({ key, value }) => window.localStorage.setItem(key, value),
      { key: TEST_AUTH_STORAGE_KEY, value: TEST_AUTH_STORAGE_VALUE },
    );
  });

  test('should shorten a URL and redirect to the original destination', async ({ page }) => {
    await page.goto('/');
    const longUrl = `https://example.com/test-${Date.now()}`;

    await page.getByTestId('shorten-url-input').fill(longUrl);
    await page.getByTestId('shorten-url-submit').click();

    const resultBox = page.getByTestId('shorten-result');
    await expect(resultBox).toBeVisible({ timeout: 15000 });

    const shortLink = page.getByTestId('shorten-result-link');
    await expect(shortLink).toBeVisible();

    const href = await shortLink.getAttribute('href');
    expect(href).toBeTruthy();
    expect(href).toMatch(/^https?:\/\//);

    await page.goto(href, { waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(longUrl);
  });

  test('should display the analytics dashboard with snake_case fields', async ({ page }) => {
    await page.route('**/api/v1/analytics/top**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            short_code: 'analytics1',
            long_url: 'https://example.com/analytics',
            click_count: 42,
            created_at: '2026-03-23T12:00:00',
          },
        ]),
      });
    });

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Top Performing Links' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Short Link' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Destination' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Clicks' })).toBeVisible();
    await expect(page.getByRole('link', { name: /analytics1/ })).toBeVisible();
    await expect(page.getByText('42')).toBeVisible();
  });

  test('should let anonymous users dismiss sign-in and keep the home experience', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Sign In' }).click();
    await expect(page.getByRole('heading', { name: 'Sign In' })).toBeVisible();

    await page.getByRole('button', { name: 'Continue without signing in' }).click();
    await expect(page.getByRole('button', { name: 'Continue without signing in' })).toHaveCount(0);

    await page.goto('/my-links');
    await expect(page.getByRole('heading', { name: 'Shorten your link' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'My Links' })).toHaveCount(0);
  });

  test('should let users sign in locally and view their links', async ({ page }) => {
    await page.route('**/api/v1/history', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            short_code: 'e2eAuth1',
            long_url: 'https://example.com/private-link',
            created_at: '2026-03-23T12:00:00',
          },
        ]),
      });
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Sign In' }).click();
    await page.getByTestId('auth-local-login').click();

    await expect(page.getByText('playwright')).toBeVisible();
    await expect(page.getByRole('link', { name: /My Links/ })).toBeVisible();

    await page.getByRole('link', { name: 'My Links' }).click();
    await expect(page).toHaveURL(/\/my-links$/);
    await expect(page.getByRole('heading', { name: 'No links yet' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: /e2eAuth1/ })).toBeVisible();
    await expect(page.getByText('https://example.com/private-link')).toBeVisible();

    await page.getByRole('button', { name: /Logout/ }).click();
    await expect(page.getByRole('link', { name: 'My Links' })).toHaveCount(0);
  });
});
