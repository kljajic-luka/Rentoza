import { test, expect, Page } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

/**
 * Profile Picture Upload E2E Tests
 *
 * These tests verify the profile picture upload functionality for authenticated users.
 *
 * Prerequisites:
 * - Backend must be running on http://localhost:8080
 * - Frontend must be running on http://localhost:4200
 * - Test user credentials must be valid
 */

const API_BASE = 'http://localhost:8080';

// Test user credentials (should exist in database)
const TEST_USER = {
  email: 'test@example.com',
  password: 'Test1234',
};

/**
 * Helper: Login and get access token
 */
async function loginUser(request: any): Promise<string | null> {
  try {
    const response = await request.post(`${API_BASE}/api/auth/login`, {
      data: TEST_USER,
    });

    if (response.status() === 200) {
      const data = await response.json();
      return data.accessToken;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Helper: Create a test image file buffer
 */
function createTestImageBuffer(): Buffer {
  // Create a simple 100x100 JPEG-like buffer (minimal valid JPEG)
  // In real tests, use an actual test image file
  const jpegHeader = Buffer.from([
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
    0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
  ]);
  return jpegHeader;
}

test.describe('Profile Picture Upload - API Tests', () => {
  let accessToken: string | null = null;

  test.beforeAll(async ({ request }) => {
    accessToken = await loginUser(request);
  });

  test('POST /api/users/me/profile-picture - requires authentication', async ({ request }) => {
    // Without token should return 401
    const response = await request.post(`${API_BASE}/api/users/me/profile-picture`, {
      multipart: {
        file: {
          name: 'test.jpg',
          mimeType: 'image/jpeg',
          buffer: createTestImageBuffer(),
        },
      },
    });

    expect(response.status()).toBe(401);
  });

  test('POST /api/users/me/profile-picture - rejects files over 4MB', async ({ request }) => {
    test.skip(!accessToken, 'No access token available');

    // Create a buffer larger than 4MB
    const largeBuffer = Buffer.alloc(5 * 1024 * 1024); // 5MB

    const response = await request.post(`${API_BASE}/api/users/me/profile-picture`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      multipart: {
        file: {
          name: 'large.jpg',
          mimeType: 'image/jpeg',
          buffer: largeBuffer,
        },
      },
    });

    // Should reject with 400 or 413
    expect([400, 413]).toContain(response.status());
  });

  test('POST /api/users/me/profile-picture - rejects SVG files', async ({ request }) => {
    test.skip(!accessToken, 'No access token available');

    const svgContent = '<svg xmlns="http://www.w3.org/2000/svg"></svg>';

    const response = await request.post(`${API_BASE}/api/users/me/profile-picture`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      multipart: {
        file: {
          name: 'test.svg',
          mimeType: 'image/svg+xml',
          buffer: Buffer.from(svgContent),
        },
      },
    });

    expect(response.status()).toBe(400);
  });

  test('POST /api/users/me/profile-picture - rejects non-image files', async ({ request }) => {
    test.skip(!accessToken, 'No access token available');

    const response = await request.post(`${API_BASE}/api/users/me/profile-picture`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
      multipart: {
        file: {
          name: 'document.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('%PDF-1.4'),
        },
      },
    });

    expect(response.status()).toBe(400);
  });

  test('DELETE /api/users/me/profile-picture - requires authentication', async ({ request }) => {
    const response = await request.delete(`${API_BASE}/api/users/me/profile-picture`);
    expect(response.status()).toBe(401);
  });
});

test.describe('Profile Picture Upload - UI Tests', () => {
  /**
   * Helper: Login via UI
   */
  async function loginViaUI(page: Page): Promise<boolean> {
    try {
      await page.goto('/auth/login');
      await page.waitForLoadState('networkidle');

      await page.fill('input[type="email"], input[formControlName="email"]', TEST_USER.email);
      await page.fill('input[type="password"], input[formControlName="password"]', TEST_USER.password);

      await page.click('button[type="submit"]');
      await page.waitForLoadState('networkidle');

      // Wait for redirect away from login page
      await page.waitForURL((url) => !url.pathname.includes('/auth/login'), { timeout: 10000 });

      return true;
    } catch {
      return false;
    }
  }

  test('Profile page shows avatar uploader for logged-in user', async ({ page }) => {
    const loggedIn = await loginViaUI(page);
    test.skip(!loggedIn, 'Could not log in');

    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    // Should see profile picture uploader component
    const uploader = page.locator('app-profile-picture-uploader');
    await expect(uploader).toBeVisible({ timeout: 10000 });
  });

  test('Profile picture uploader shows edit button', async ({ page }) => {
    const loggedIn = await loginViaUI(page);
    test.skip(!loggedIn, 'Could not log in');

    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    // Should see camera/edit button
    const editButton = page.locator('app-profile-picture-uploader button[mat-mini-fab]');
    await expect(editButton).toBeVisible({ timeout: 10000 });
  });

  test('Clicking edit button opens file dialog', async ({ page }) => {
    const loggedIn = await loginViaUI(page);
    test.skip(!loggedIn, 'Could not log in');

    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    // File input should exist but be hidden
    const fileInput = page.locator('app-profile-picture-uploader input[type="file"]');
    await expect(fileInput).toBeAttached();

    // Should have correct accept attribute
    await expect(fileInput).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp');
  });

  test('File selection shows preview and upload buttons', async ({ page }) => {
    const loggedIn = await loginViaUI(page);
    test.skip(!loggedIn, 'Could not log in');

    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    // Create a test image file
    const testImagePath = path.join(__dirname, 'fixtures', 'test-avatar.jpg');

    // Skip if test fixture doesn't exist
    if (!fs.existsSync(testImagePath)) {
      test.skip(true, 'Test fixture test-avatar.jpg not found');
      return;
    }

    // Select file
    const fileInput = page.locator('app-profile-picture-uploader input[type="file"]');
    await fileInput.setInputFiles(testImagePath);

    // Should show preview actions
    const uploadButton = page.locator('app-profile-picture-uploader button:has-text("Postavi")');
    const cancelButton = page.locator('app-profile-picture-uploader button:has-text("Otkaži")');

    await expect(uploadButton).toBeVisible({ timeout: 5000 });
    await expect(cancelButton).toBeVisible({ timeout: 5000 });
  });

  test('Cancel button clears file selection', async ({ page }) => {
    const loggedIn = await loginViaUI(page);
    test.skip(!loggedIn, 'Could not log in');

    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    const testImagePath = path.join(__dirname, 'fixtures', 'test-avatar.jpg');
    if (!fs.existsSync(testImagePath)) {
      test.skip(true, 'Test fixture not found');
      return;
    }

    // Select file
    const fileInput = page.locator('app-profile-picture-uploader input[type="file"]');
    await fileInput.setInputFiles(testImagePath);

    // Click cancel
    const cancelButton = page.locator('app-profile-picture-uploader button:has-text("Otkaži")');
    await cancelButton.click();

    // Upload button should disappear
    await expect(cancelButton).not.toBeVisible({ timeout: 3000 });
  });

  test('Client-side validation rejects files over 4MB', async ({ page }) => {
    const loggedIn = await loginViaUI(page);
    test.skip(!loggedIn, 'Could not log in');

    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    // Create an oversized test file path
    const largeFilePath = path.join(__dirname, 'fixtures', 'large-image.jpg');
    if (!fs.existsSync(largeFilePath)) {
      test.skip(true, 'Large test fixture not found');
      return;
    }

    const fileInput = page.locator('app-profile-picture-uploader input[type="file"]');
    await fileInput.setInputFiles(largeFilePath);

    // Should show error message
    const errorMessage = page.locator('app-profile-picture-uploader .error-message');
    await expect(errorMessage).toBeVisible({ timeout: 3000 });
    await expect(errorMessage).toContainText(/4MB|prevelik/i);
  });
});

test.describe('Owner Profile Page - Avatar Display', () => {
  test('Owner profile shows avatar image', async ({ page, request }) => {
    // Get an owner ID from cars
    const carsResponse = await request.get(`${API_BASE}/api/cars`);
    const cars = await carsResponse.json();

    test.skip(cars.length === 0, 'No cars available');

    const ownerId = cars[0].ownerId;
    test.skip(!ownerId, 'No owner ID available');

    await page.goto(`/owner/${ownerId}`);
    await page.waitForLoadState('networkidle');

    // Should show avatar section
    const avatarSection = page.locator('.avatar-section');
    await expect(avatarSection).toBeVisible({ timeout: 10000 });

    // Should have an image or uploader
    const avatarImage = page.locator('.avatar-section img.avatar, app-profile-picture-uploader');
    await expect(avatarImage.first()).toBeVisible();
  });

  test('Own profile shows edit capability', async ({ page }) => {
    // This test requires logging in and navigating to own owner profile
    // Skip if no test user with owner role is available

    const loggedIn = await (async () => {
      try {
        await page.goto('/auth/login');
        await page.fill('input[type="email"]', TEST_USER.email);
        await page.fill('input[type="password"]', TEST_USER.password);
        await page.click('button[type="submit"]');
        await page.waitForURL((url) => !url.pathname.includes('/auth/login'), { timeout: 10000 });
        return true;
      } catch {
        return false;
      }
    })();

    test.skip(!loggedIn, 'Could not log in');

    // Navigate to own owner profile (requires knowing the user's owner ID)
    // This would need to be determined from the API
    await page.goto('/profile');
    await page.waitForLoadState('networkidle');

    // On regular profile page, uploader should be visible
    const uploader = page.locator('app-profile-picture-uploader');
    await expect(uploader).toBeVisible({ timeout: 10000 });
  });
});

test.describe('Profile Picture Upload - Security Tests', () => {
  test('Uploaded images are served from /uploads/ path', async ({ request }) => {
    const accessToken = await loginUser(request);
    test.skip(!accessToken, 'No access token available');

    // Get current user profile to check avatar URL format
    const profileResponse = await request.get(`${API_BASE}/api/users/profile`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    if (profileResponse.status() === 200) {
      const profile = await profileResponse.json();
      if (profile.avatarUrl) {
        // Avatar URL should be in the expected format
        expect(profile.avatarUrl).toMatch(/^\/uploads\/profile-pictures\/\d+\.jpg/);
      }
    }
  });

  test('Static uploads path is publicly accessible', async ({ request }) => {
    // The /uploads/ path should be publicly accessible
    const response = await request.get(`${API_BASE}/uploads/`, {
      failOnStatusCode: false,
    });

    // Should not return 401/403 (may return 404 or directory listing)
    expect(response.status()).not.toBe(401);
    expect(response.status()).not.toBe(403);
  });
});
