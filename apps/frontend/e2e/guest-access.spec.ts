import { test, expect } from '@playwright/test';

/**
 * Phase 3: Guest Access E2E Tests
 *
 * These tests verify that non-authenticated users (guests) can access
 * public endpoints without receiving 401 Unauthorized errors.
 *
 * Prerequisites:
 * - Backend must be running on http://localhost:8080
 * - Frontend must be running on http://localhost:4200
 */

test.describe('Guest Access - Public Endpoints', () => {
  // Ensure we're testing as a guest (no auth tokens)
  test.beforeEach(async ({ context }) => {
    // Clear all cookies and storage to simulate guest access
    await context.clearCookies();
  });

  test.describe('API Endpoint Tests (Direct)', () => {
    const API_BASE = 'http://localhost:8080';

    test('GET /api/cars - should return 200 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/cars`);
      expect(response.status()).toBe(200);
      const data = await response.json();
      expect(Array.isArray(data)).toBe(true);
    });

    test('GET /api/cars/{id} - should return 200 for guest', async ({ request }) => {
      // First get list of cars to find a valid ID
      const listResponse = await request.get(`${API_BASE}/api/cars`);
      const cars = await listResponse.json();

      if (cars.length > 0) {
        const carId = cars[0].id;
        const response = await request.get(`${API_BASE}/api/cars/${carId}`);
        expect(response.status()).toBe(200);
        const car = await response.json();
        expect(car.id).toBeDefined();
      }
    });

    test('GET /api/cars/search - should return 200 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/cars/search`);
      expect(response.status()).toBe(200);
    });

    test('GET /api/cars/features - should return 200 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/cars/features`);
      expect(response.status()).toBe(200);
      const features = await response.json();
      expect(Array.isArray(features)).toBe(true);
    });

    test('GET /api/cars/makes - should return 200 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/cars/makes`);
      expect(response.status()).toBe(200);
      const makes = await response.json();
      expect(Array.isArray(makes)).toBe(true);
    });

    test('GET /api/bookings/car/{id}/public - should return 200 for guest', async ({ request }) => {
      // First get a car ID
      const carsResponse = await request.get(`${API_BASE}/api/cars`);
      const cars = await carsResponse.json();

      if (cars.length > 0) {
        const carId = cars[0].id;
        const response = await request.get(`${API_BASE}/api/bookings/car/${carId}/public`);
        expect(response.status()).toBe(200);
        const bookingSlots = await response.json();
        expect(Array.isArray(bookingSlots)).toBe(true);
      }
    });

    test('GET /api/availability/{carId} - should return 200 for guest', async ({ request }) => {
      // First get a car ID
      const carsResponse = await request.get(`${API_BASE}/api/cars`);
      const cars = await carsResponse.json();

      if (cars.length > 0) {
        const carId = cars[0].id;
        const response = await request.get(`${API_BASE}/api/availability/${carId}`);
        expect(response.status()).toBe(200);
        const blockedDates = await response.json();
        expect(Array.isArray(blockedDates)).toBe(true);
      }
    });

    test('GET /api/owners/{id}/public-profile - should return 200 for guest (if owner exists)', async ({ request }) => {
      // First get a car to find an owner ID
      const carsResponse = await request.get(`${API_BASE}/api/cars`);
      const cars = await carsResponse.json();

      if (cars.length > 0 && cars[0].ownerId) {
        const ownerId = cars[0].ownerId;
        const response = await request.get(`${API_BASE}/api/owners/${ownerId}/public-profile`);
        // May return 200 or 404 depending on data, but should NOT return 401
        expect(response.status()).not.toBe(401);
        expect(response.status()).not.toBe(403);
      }
    });

    test('GET /api/reviews/car/{id} - should return 200 for guest', async ({ request }) => {
      const carsResponse = await request.get(`${API_BASE}/api/cars`);
      const cars = await carsResponse.json();

      if (cars.length > 0) {
        const carId = cars[0].id;
        const response = await request.get(`${API_BASE}/api/reviews/car/${carId}`);
        expect(response.status()).toBe(200);
      }
    });
  });

  test.describe('Private Endpoints - Must Require Auth', () => {
    const API_BASE = 'http://localhost:8080';

    test('POST /api/bookings - should return 401 for guest', async ({ request }) => {
      const response = await request.post(`${API_BASE}/api/bookings`, {
        data: { carId: 1, startDate: '2025-02-01', endDate: '2025-02-03' }
      });
      expect(response.status()).toBe(401);
    });

    test('GET /api/bookings/me - should return 401 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/bookings/me`);
      expect(response.status()).toBe(401);
    });

    test('GET /api/favorites - should return 401 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/favorites`);
      expect(response.status()).toBe(401);
    });

    test('GET /api/users/me - should return 401 for guest', async ({ request }) => {
      const response = await request.get(`${API_BASE}/api/users/me`);
      expect(response.status()).toBe(401);
    });
  });

  test.describe('UI Flow Tests', () => {
    test('Guest can view car list page', async ({ page }) => {
      await page.goto('/cars');

      // Wait for page to load
      await page.waitForLoadState('networkidle');

      // Should not see any authentication error
      const errorAlert = page.locator('.error-401, .unauthorized');
      await expect(errorAlert).not.toBeVisible();

      // Should see car cards or empty state
      const carsContainer = page.locator('.car-grid, .car-list, .results-grid, [class*="car"]');
      await expect(carsContainer.first()).toBeVisible({ timeout: 10000 });
    });

    test('Guest can view car detail page without 401', async ({ page, request }) => {
      // Get a car ID from API first
      const carsResponse = await request.get('http://localhost:8080/api/cars');
      const cars = await carsResponse.json();

      if (cars.length > 0) {
        const carId = cars[0].id;

        // Monitor for 401 errors BEFORE navigation
        const errors: string[] = [];
        page.on('response', response => {
          if (response.status() === 401) {
            errors.push(response.url());
          }
        });

        // Navigate directly to car detail page
        await page.goto(`/cars/${carId}`);
        await page.waitForLoadState('networkidle');

        // Check we're on detail page
        await expect(page).toHaveURL(`/cars/${carId}`);

        // Wait for all API calls to complete
        await page.waitForTimeout(2000);

        // Should have NO 401 errors on public endpoints
        const publicEndpoint401s = errors.filter(url =>
          url.includes('/public') ||
          url.includes('/availability/') ||
          url.includes('/public-profile')
        );
        expect(publicEndpoint401s).toHaveLength(0);
      }
    });

    test('Guest sees login prompt when trying to book', async ({ page }) => {
      await page.goto('/cars');
      await page.waitForLoadState('networkidle');

      const carCard = page.locator('mat-card, .car-card').first();

      if (await carCard.isVisible()) {
        await carCard.click();
        await page.waitForLoadState('networkidle');

        // Find and click book button
        const bookButton = page.locator('button:has-text("Rezerviši"), button:has-text("Book"), button:has-text("Rezervisi")');

        if (await bookButton.isVisible()) {
          await bookButton.click();

          // Should either show login dialog or redirect to login
          const loginPrompt = page.locator('[class*="login"], [class*="auth"], mat-dialog-container');
          const isRedirected = page.url().includes('/auth/login');

          const hasLoginPrompt = await loginPrompt.isVisible().catch(() => false);
          expect(hasLoginPrompt || isRedirected).toBe(true);
        }
      }
    });
  });
});

test.describe('Availability Search - Guest Access', () => {
  const API_BASE = 'http://localhost:8080';

  test('GET /api/cars/availability-search - should return 200 for guest', async ({ request }) => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const dayAfter = new Date();
    dayAfter.setDate(dayAfter.getDate() + 3);

    const params = new URLSearchParams({
      location: 'Beograd',
      startDate: tomorrow.toISOString().split('T')[0],
      startTime: '09:00',
      endDate: dayAfter.toISOString().split('T')[0],
      endTime: '18:00',
    });

    const response = await request.get(`${API_BASE}/api/cars/availability-search?${params}`);
    expect(response.status()).toBe(200);

    const data = await response.json();
    expect(data).toHaveProperty('content');
    expect(data).toHaveProperty('totalElements');
  });

  test('Home page availability search works for guest', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Look for search form
    const searchSection = page.locator('.hero__search-bar, .search-bar, [class*="search"]').first();

    if (await searchSection.isVisible()) {
      // Fill location
      const locationInput = page.locator('input[placeholder*="lokacij"], input[placeholder*="location"], input[matInput]').first();
      if (await locationInput.isVisible()) {
        await locationInput.fill('Beograd');
      }

      // Submit search
      const searchButton = page.locator('button[type="submit"], button:has-text("Pretraži"), button:has(mat-icon:has-text("search"))').first();
      if (await searchButton.isVisible()) {
        await searchButton.click();
        await page.waitForLoadState('networkidle');

        // Should navigate to results without 401
        await page.waitForTimeout(2000);
      }
    }
  });
});
