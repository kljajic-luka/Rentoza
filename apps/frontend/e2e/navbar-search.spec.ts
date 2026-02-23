import { test, expect } from '@playwright/test';

/**
 * E2E Test: Navbar car search (Turo-level UX)
 *
 * Validates end-to-end behavior: type in navbar search → Enter → /vozila?q=term
 * → results or empty-state visible.
 *
 * Defects fixed:
 *  - Navbar wrote `?search=term`; car-list didn't parse it → dead state.
 *  - Frontend criteria model had no q field → search never sent to backend.
 *  - Backend /api/cars/search accepted no free-text param → all cars returned.
 *  - distinctUntilChanged(JSON.stringify) could suppress stream update for new fields.
 *
 * These tests verify the complete flow is working post-fix.
 */

test.describe('Navbar search — happy path', () => {
  test('typing query + Enter lands on /vozila with q param in URL', async ({ page }) => {
    // Start from home page (transparent navbar)
    await page.goto('/pocetna');
    await page.waitForLoadState('networkidle');

    // Locate the navbar search input (id="search" on the form)
    const searchInput = page.locator('form#search input[type="search"]');
    await expect(searchInput).toBeVisible();

    // Type a search query and submit
    await searchInput.fill('Audi');
    await searchInput.press('Enter');

    // Should navigate to /vozila with q=Audi in URL
    await page.waitForURL(/\/vozila/, { timeout: 8_000 });
    expect(page.url()).toMatch(/[?&]q=Audi/i);
  });

  test('car-list page shows results or empty-state after navbar search', async ({ page }) => {
    // Navigate directly with q param (simulates navbar submit result)
    await page.goto('/vozila?q=Audi');
    await page.waitForLoadState('networkidle');

    // Wait for the loading skeleton to disappear
    await page.waitForSelector('.loading-skeleton, mat-progress-spinner', {
      state: 'detached',
      timeout: 12_000,
    }).catch(() => {
      // Skeleton may have already removed itself — that's fine
    });

    // Either the car grid OR the empty-state must be visible
    const carGrid = page.locator('.cars-grid');
    const emptyState = page.locator('app-empty-state');

    const hasGrid = await carGrid.isVisible().catch(() => false);
    const hasEmpty = await emptyState.isVisible().catch(() => false);

    expect(hasGrid || hasEmpty).toBe(true);
  });

  test('active search chip is displayed when q is set', async ({ page }) => {
    await page.goto('/vozila?q=Tesla');
    await page.waitForLoadState('networkidle');

    // The q chip should appear in the filter-chips area
    const qChip = page.locator('mat-chip.filter-chip--query');
    await expect(qChip).toBeVisible({ timeout: 8_000 });
    await expect(qChip).toContainText('Tesla');
  });

  test('removing q chip clears search and removes q from URL', async ({ page }) => {
    await page.goto('/vozila?q=BMW');
    await page.waitForLoadState('networkidle');

    // Find and click the remove (×) button inside the q chip
    const removeBtn = page.locator('mat-chip.filter-chip--query button[matChipRemove]');
    await expect(removeBtn).toBeVisible({ timeout: 8_000 });
    await removeBtn.click();

    // URL should no longer contain q param
    await page.waitForTimeout(500); // allow debounce + router to settle
    expect(page.url()).not.toMatch(/[?&]q=/);
  });

  test('submitting same query from any page arrives at /vozila', async ({ page }) => {
    // Start on a non-home page (e.g., /bookings redirect to login, but the nav is still present)
    await page.goto('/vozila');
    await page.waitForLoadState('networkidle');

    const searchInput = page.locator('form#search input[type="search"]');
    await expect(searchInput).toBeVisible();

    await searchInput.fill('Golf');
    await searchInput.press('Enter');

    await page.waitForURL(/\/vozila.*q=Golf/, { timeout: 8_000 });
    expect(page.url()).toMatch(/[?&]q=Golf/i);
  });

  test('non-authenticated user sees search bar in navbar', async ({ page }) => {
    // Owner-specific hiding is covered in owner-journey.spec.ts.
    // Here we verify the baseline: an unauthenticated (USER-role) visitor
    // on /vozila must always see the search form.
    await page.goto('/vozila');
    const searchForm = page.locator('form#search');
    await expect(searchForm).toBeVisible();
  });
});

test.describe('Navbar search — backward compatibility', () => {
  test('legacy ?search= param still shows results with q chip', async ({ page }) => {
    // The car-list reads `params.get('q') || params.get('search')`, so both
    // should produce the same chip+results behavior.
    await page.goto('/vozila?search=Volkswagen');
    await page.waitForLoadState('networkidle');

    // Either a result grid or empty state should appear
    const carGrid = page.locator('.cars-grid');
    const emptyState = page.locator('app-empty-state');
    const hasGrid = await carGrid.isVisible().catch(() => false);
    const hasEmpty = await emptyState.isVisible().catch(() => false);

    expect(hasGrid || hasEmpty).toBe(true);

    // And a q chip should be visible too (car-list reads search → q)
    const qChip = page.locator('mat-chip.filter-chip--query');
    await expect(qChip).toBeVisible({ timeout: 8_000 });
    await expect(qChip).toContainText('Volkswagen');
  });
});
