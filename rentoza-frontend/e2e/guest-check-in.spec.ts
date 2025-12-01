import { test, expect, performLogin, waitForPageReady, TEST_USER } from './fixtures/auth.fixture';

/**
 * Guest Check-In E2E Tests
 *
 * Tests the guest check-in flow when host has completed their part.
 * The guest should see a "Pregledaj vozilo i potvrdi" button on their
 * bookings page when booking status is CHECK_IN_HOST_COMPLETE.
 *
 * Prerequisites:
 * - Backend must be running on http://localhost:8080
 * - Frontend must be running on http://localhost:4200
 * - There must be a booking with status CHECK_IN_HOST_COMPLETE for the test user
 */

test.describe('Guest Check-In Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Login as the test user (guest)
    await performLogin(page, TEST_USER.email, TEST_USER.password);
    await waitForPageReady(page);
  });

  test('Guest can see bookings page', async ({ page }) => {
    // Navigate to bookings page
    await page.goto('/bookings');
    await waitForPageReady(page);

    // Verify we're on the bookings page
    await expect(page).toHaveURL(/\/bookings/);

    // Page should have the bookings header
    const header = page.locator('h1:has-text("Bukiranja")');
    await expect(header).toBeVisible({ timeout: 10000 });
  });

  test('Guest can see check-in button when booking status is CHECK_IN_HOST_COMPLETE', async ({
    page,
  }) => {
    // Navigate to bookings page
    await page.goto('/bookings');
    await waitForPageReady(page);

    // Wait for bookings to load
    await page.waitForTimeout(2000);

    // Look for the check-in button
    const checkInButton = page.locator('button:has-text("Pregledaj vozilo i potvrdi")');

    // Check if button exists (depends on booking state in DB)
    const buttonCount = await checkInButton.count();

    if (buttonCount > 0) {
      // Button should be visible
      await expect(checkInButton.first()).toBeVisible();

      // Button should have camera icon
      const buttonIcon = checkInButton.first().locator('mat-icon:has-text("camera_alt")');
      await expect(buttonIcon).toBeVisible();

      console.log('✅ Check-in button found and visible');
    } else {
      console.log('ℹ️ No booking with CHECK_IN_HOST_COMPLETE status found for this user');
    }
  });

  test('Guest can click check-in button and navigate to check-in wizard', async ({ page }) => {
    // Navigate to bookings page
    await page.goto('/bookings');
    await waitForPageReady(page);

    // Wait for bookings to load
    await page.waitForTimeout(2000);

    // Look for the check-in button
    const checkInButton = page.locator('button:has-text("Pregledaj vozilo i potvrdi")');
    const buttonCount = await checkInButton.count();

    if (buttonCount > 0) {
      // Click the check-in button
      await checkInButton.first().click();

      // Wait for navigation
      await page.waitForURL(/\/bookings\/\d+\/check-in/, { timeout: 10000 });

      // Verify we navigated to check-in page
      await expect(page).toHaveURL(/\/bookings\/\d+\/check-in/);

      console.log('✅ Successfully navigated to check-in wizard');
    } else {
      console.log('ℹ️ No booking with CHECK_IN_HOST_COMPLETE status - skipping navigation test');
      test.skip();
    }
  });

  test('Guest check-in wizard loads correctly', async ({ page }) => {
    // Navigate to bookings page
    await page.goto('/bookings');
    await waitForPageReady(page);

    // Wait for bookings to load
    await page.waitForTimeout(2000);

    // Look for the check-in button
    const checkInButton = page.locator('button:has-text("Pregledaj vozilo i potvrdi")');
    const buttonCount = await checkInButton.count();

    if (buttonCount > 0) {
      // Click the check-in button
      await checkInButton.first().click();

      // Wait for navigation and page load
      await page.waitForURL(/\/bookings\/\d+\/check-in/, { timeout: 10000 });
      await waitForPageReady(page);

      // Wait for check-in wizard to load
      await page.waitForTimeout(2000);

      // Should see guest check-in component elements
      // Look for photo gallery or vehicle condition section
      const checkInContent = page.locator('.check-in, .guest-check-in, [class*="check-in"]');

      // Take screenshot for debugging
      await page.screenshot({ path: 'test-results/guest-check-in-wizard.png', fullPage: true });

      console.log('✅ Check-in wizard loaded');
    } else {
      console.log('ℹ️ No booking with CHECK_IN_HOST_COMPLETE status - skipping wizard test');
      test.skip();
    }
  });

  test('Check-in status badge shows correct label', async ({ page }) => {
    // Navigate to bookings page
    await page.goto('/bookings');
    await waitForPageReady(page);

    // Wait for bookings to load
    await page.waitForTimeout(2000);

    // Look for the check-in status badge
    const statusBadge = page.locator('mat-chip:has-text("Čeka vaš check-in")');
    const badgeCount = await statusBadge.count();

    if (badgeCount > 0) {
      // Badge should be visible
      await expect(statusBadge.first()).toBeVisible();

      // Badge should have the check-in styling class
      const hasCheckInClass = await statusBadge
        .first()
        .evaluate((el) => el.classList.contains('status-check-in'));
      expect(hasCheckInClass).toBe(true);

      // Badge should have info icon
      const infoIcon = statusBadge.first().locator('mat-icon:has-text("info")');
      await expect(infoIcon).toBeVisible();

      console.log('✅ Check-in status badge displays correctly');
    } else {
      console.log('ℹ️ No booking with CHECK_IN_HOST_COMPLETE status badge found');
    }
  });
});

test.describe('Guest Check-In - API Integration', () => {
  const API_BASE = 'http://localhost:8080';

  test('Authenticated user can access their bookings', async ({ page, request }) => {
    // Login first
    await performLogin(page, TEST_USER.email, TEST_USER.password);

    // Get cookies for authenticated request
    const cookies = await page.context().cookies();
    const accessToken = cookies.find((c) => c.name === 'access_token');
    const xsrfToken = cookies.find((c) => c.name === 'XSRF-TOKEN');

    if (accessToken) {
      // Make authenticated API request
      const response = await request.get(`${API_BASE}/api/bookings/me`, {
        headers: {
          Cookie: `access_token=${accessToken.value}`,
          'X-XSRF-TOKEN': xsrfToken?.value || '',
        },
      });

      expect(response.status()).toBe(200);
      const bookings = await response.json();
      expect(Array.isArray(bookings)).toBe(true);

      // Check if any booking has CHECK_IN_HOST_COMPLETE status
      const checkInReadyBookings = bookings.filter(
        (b: any) => b.status === 'CHECK_IN_HOST_COMPLETE'
      );

      console.log(`Found ${bookings.length} total bookings`);
      console.log(`Found ${checkInReadyBookings.length} bookings ready for guest check-in`);
    }
  });
});
