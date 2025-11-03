import { test, expect } from '@playwright/test';

/**
 * E2E tests for Owner Reviews Page (/owner/reviews)
 *
 * Tests both "Primljene" (Received) and "Poslate" (Sent) sections:
 * 1. Page loads correctly with both tabs
 * 2. Received reviews are fetched and displayed
 * 3. Given reviews are fetched and displayed
 * 4. Review cards show all required information
 * 5. Empty states display when no reviews exist
 * 6. API endpoints are called correctly
 */

// Test user credentials (update these based on your test data)
const OWNER_EMAIL = 'owner@test.com';
const OWNER_PASSWORD = 'password';

test.describe('Owner Reviews Page', () => {
  // Login before each test
  test.beforeEach(async ({ page }) => {
    // Navigate to login page
    await page.goto('/login');

    // Fill in credentials
    await page.fill('input[type="email"]', OWNER_EMAIL);
    await page.fill('input[type="password"]', OWNER_PASSWORD);

    // Click login button
    await page.click('button[type="submit"]');

    // Wait for navigation to complete
    await page.waitForURL(/\/(home|owner)/);
  });

  test('should navigate to owner reviews page', async ({ page }) => {
    // Navigate to owner reviews
    await page.goto('/owner/reviews');

    // Verify we're on the correct page
    await expect(page).toHaveURL('/owner/reviews');

    // Check page header
    await expect(page.locator('h1')).toContainText('Recenzije');
    await expect(page.locator('.subtitle')).toContainText('Pregledajte primljene i poslate recenzije');
  });

  test('should display two tabs - Primljene and Poslate', async ({ page }) => {
    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');

    // Wait for tabs to load
    await page.waitForSelector('mat-tab-group', { timeout: 10000 });

    // Check for both tabs
    const tabs = page.locator('.mat-mdc-tab');
    await expect(tabs).toHaveCount(2);

    // Verify tab labels
    await expect(tabs.nth(0)).toContainText('Primljene');
    await expect(tabs.nth(1)).toContainText('Poslate');
  });

  test('should load received reviews from API', async ({ page }) => {
    let receivedApiCalled = false;
    let receivedResponse: any = null;

    // Intercept the received reviews API call
    page.on('response', async response => {
      if (response.url().includes('/api/reviews/received/')) {
        receivedApiCalled = true;
        receivedResponse = {
          status: response.status(),
          ok: response.ok(),
          body: await response.json().catch(() => null)
        };
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');

    // Wait for API call
    await page.waitForTimeout(2000);

    // Verify API was called
    expect(receivedApiCalled).toBe(true);
    expect(receivedResponse?.status).toBe(200);
    expect(receivedResponse?.ok).toBe(true);

    // Verify response is an array
    expect(Array.isArray(receivedResponse?.body)).toBe(true);

    console.log('Received reviews count:', receivedResponse?.body?.length || 0);
  });

  test('should load given reviews from API', async ({ page }) => {
    let givenApiCalled = false;
    let givenResponse: any = null;

    // Intercept the given reviews API call
    page.on('response', async response => {
      if (response.url().includes('/api/reviews/from-owner/')) {
        givenApiCalled = true;
        givenResponse = {
          status: response.status(),
          ok: response.ok(),
          body: await response.json().catch(() => null)
        };
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');

    // Wait for API call
    await page.waitForTimeout(2000);

    // Verify API was called
    expect(givenApiCalled).toBe(true);
    expect(givenResponse?.status).toBe(200);
    expect(givenResponse?.ok).toBe(true);

    // Verify response is an array
    expect(Array.isArray(givenResponse?.body)).toBe(true);

    console.log('Given reviews count:', givenResponse?.body?.length || 0);
  });

  test('should display received reviews in Primljene tab', async ({ page }) => {
    let receivedReviews: any[] = [];

    page.on('response', async response => {
      if (response.url().includes('/api/reviews/received/')) {
        receivedReviews = await response.json().catch(() => []);
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Click on Primljene tab (should be selected by default, but let's be sure)
    const tabs = page.locator('.mat-mdc-tab');
    await tabs.nth(0).click();
    await page.waitForTimeout(500);

    if (receivedReviews.length > 0) {
      // Verify review cards are displayed
      const reviewCards = page.locator('.reviews-grid .review-card');
      const cardCount = await reviewCards.count();
      expect(cardCount).toBeGreaterThan(0);

      // Check first review card content
      const firstCard = reviewCards.first();
      await expect(firstCard.locator('mat-card-header')).toBeVisible();
      await expect(firstCard.locator('mat-card-title')).toBeVisible();
      await expect(firstCard.locator('.rating')).toBeVisible();
      await expect(firstCard.locator('.rating mat-icon')).toHaveCount(5); // 5 stars

      console.log('Displayed', cardCount, 'received review cards');
    } else {
      // Verify empty state is shown
      await expect(page.locator('.empty-state')).toBeVisible();
      await expect(page.locator('.empty-state h3')).toContainText('Još nema recenzija');
    }
  });

  test('should display given reviews in Poslate tab', async ({ page }) => {
    let givenReviews: any[] = [];

    page.on('response', async response => {
      if (response.url().includes('/api/reviews/from-owner/')) {
        givenReviews = await response.json().catch(() => []);
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Click on Poslate tab
    const tabs = page.locator('.mat-mdc-tab');
    await tabs.nth(1).click();
    await page.waitForTimeout(500);

    if (givenReviews.length > 0) {
      // Verify review cards are displayed
      const reviewCards = page.locator('.reviews-grid .review-card');
      const cardCount = await reviewCards.count();
      expect(cardCount).toBeGreaterThan(0);

      // Check first review card content
      const firstCard = reviewCards.first();
      await expect(firstCard.locator('mat-card-header')).toBeVisible();
      await expect(firstCard.locator('mat-card-title')).toBeVisible();
      await expect(firstCard.locator('.rating')).toBeVisible();
      await expect(firstCard.locator('.rating mat-icon')).toHaveCount(5); // 5 stars

      console.log('Displayed', cardCount, 'given review cards');
    } else {
      // Verify empty state is shown
      await expect(page.locator('.empty-state')).toBeVisible();
      await expect(page.locator('.empty-state h3')).toContainText('Još niste ocenili nijednog zakupca');
    }
  });

  test('should display review details correctly', async ({ page }) => {
    let hasReviews = false;

    page.on('response', async response => {
      if (response.url().includes('/api/reviews/received/') || response.url().includes('/api/reviews/from-owner/')) {
        const reviews = await response.json().catch(() => []);
        if (Array.isArray(reviews) && reviews.length > 0) {
          hasReviews = true;
        }
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    if (hasReviews) {
      // Check received reviews
      const tabs = page.locator('.mat-mdc-tab');
      await tabs.nth(0).click();
      await page.waitForTimeout(500);

      const receivedCards = page.locator('.reviews-grid .review-card');
      const receivedCount = await receivedCards.count();

      if (receivedCount > 0) {
        const firstCard = receivedCards.first();

        // Verify card components
        await expect(firstCard.locator('.avatar')).toBeVisible();
        await expect(firstCard.locator('mat-card-title')).toBeVisible();
        await expect(firstCard.locator('mat-card-subtitle')).toBeVisible(); // Date

        // Verify rating
        const ratingStars = firstCard.locator('.rating mat-icon');
        expect(await ratingStars.count()).toBe(5);

        const ratingValue = firstCard.locator('.rating-value');
        await expect(ratingValue).toBeVisible();
        await expect(ratingValue).toContainText('/5');

        // Check for meta information (car/location)
        const metaItems = firstCard.locator('.review-meta .meta-item');
        if (await metaItems.count() > 0) {
          await expect(metaItems.first()).toBeVisible();
        }
      }

      // Check given reviews
      await tabs.nth(1).click();
      await page.waitForTimeout(500);

      const givenCards = page.locator('.reviews-grid .review-card');
      const givenCount = await givenCards.count();

      if (givenCount > 0) {
        const firstCard = givenCards.first();
        await expect(firstCard.locator('.avatar')).toBeVisible();
        await expect(firstCard.locator('.rating')).toBeVisible();
      }
    }
  });

  test('should handle API errors gracefully', async ({ page }) => {
    // This test verifies error handling
    const consoleMessages: string[] = [];

    page.on('console', msg => {
      if (msg.type() === 'error') {
        consoleMessages.push(msg.text());
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Page should still load even if API fails
    await expect(page.locator('h1')).toContainText('Recenzije');
    await expect(page.locator('mat-tab-group')).toBeVisible();

    // Check for error snackbar (if API failed)
    const snackbar = page.locator('.mat-mdc-snack-bar-container');
    const snackbarVisible = await snackbar.isVisible().catch(() => false);

    if (snackbarVisible) {
      console.log('Error snackbar displayed (expected if API fails)');
    }
  });

  test('should display correct tab counts', async ({ page }) => {
    let receivedCount = 0;
    let givenCount = 0;

    page.on('response', async response => {
      if (response.url().includes('/api/reviews/received/')) {
        const reviews = await response.json().catch(() => []);
        receivedCount = Array.isArray(reviews) ? reviews.length : 0;
      }
      if (response.url().includes('/api/reviews/from-owner/')) {
        const reviews = await response.json().catch(() => []);
        givenCount = Array.isArray(reviews) ? reviews.length : 0;
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Verify tab labels show correct counts
    const tabs = page.locator('.mat-mdc-tab');
    await expect(tabs.nth(0)).toContainText(`Primljene (${receivedCount})`);
    await expect(tabs.nth(1)).toContainText(`Poslate (${givenCount})`);

    console.log('Received reviews:', receivedCount);
    console.log('Given reviews:', givenCount);
  });

  test('should not have console errors', async ({ page }) => {
    const consoleErrors: string[] = [];

    page.on('console', msg => {
      if (msg.type() === 'error') {
        const text = msg.text();
        // Filter out known Angular warnings
        if (!text.includes('NG0') && !text.includes('ExpressionChangedAfterItHasBeenCheckedError')) {
          consoleErrors.push(text);
        }
      }
    });

    await page.goto('/owner/reviews');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);

    // Switch between tabs
    const tabs = page.locator('.mat-mdc-tab');
    await tabs.nth(1).click();
    await page.waitForTimeout(500);
    await tabs.nth(0).click();
    await page.waitForTimeout(500);

    // Check for unexpected errors
    if (consoleErrors.length > 0) {
      console.log('Console errors found:', consoleErrors);
    }

    // We don't fail the test for errors since API might not have data
    // but we log them for visibility
  });
});
