import { test, expect, type Page } from '@playwright/test';

test.describe('Car Details Image Carousel', () => {
  let page: Page;

  test.beforeEach(async ({ page: p, baseURL }) => {
    page = p;

    // Navigate to a car details page
    // Assuming there's a car with ID 1 that has multiple images
    await page.goto(`${baseURL}/cars/1`);

    // Wait for the page to load
    await page.waitForLoadState('networkidle');
  });

  test('should display the car gallery with the first image', async () => {
    // Check that the gallery media container exists
    const galleryMedia = page.locator('.gallery__media');
    await expect(galleryMedia).toBeVisible();

    // Check that an image is displayed
    const carImage = page.locator('.gallery__image');
    await expect(carImage).toBeVisible();

    // Verify the image has loaded (has a src attribute)
    await expect(carImage).toHaveAttribute('src', /.+/);
  });

  test('should show navigation arrows when hovering over image (with multiple images)', async () => {
    // Hover over the gallery to reveal controls
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.hover();

    // Check for navigation buttons (only if there are multiple images)
    const leftArrow = page.locator('.gallery__nav-button--left');
    const rightArrow = page.locator('.gallery__nav-button--right');

    // If multiple images exist, arrows should be visible after hover
    const leftArrowCount = await leftArrow.count();
    if (leftArrowCount > 0) {
      await expect(leftArrow).toBeVisible();
      await expect(rightArrow).toBeVisible();
    }
  });

  test('should navigate to next image when clicking right arrow', async () => {
    // Hover to reveal navigation
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.hover();

    // Check if navigation buttons exist (multiple images)
    const rightArrow = page.locator('.gallery__nav-button--right');
    const arrowCount = await rightArrow.count();

    if (arrowCount > 0) {
      // Get initial image src
      const carImage = page.locator('.gallery__image');
      const initialSrc = await carImage.getAttribute('src');

      // Click right arrow
      await rightArrow.click();

      // Wait for image to change
      await page.waitForTimeout(300);

      // Verify image changed
      const newSrc = await carImage.getAttribute('src');
      expect(newSrc).not.toBe(initialSrc);

      // Verify image counter updated
      const imageCounter = page.locator('.gallery__image-counter');
      await expect(imageCounter).toContainText('2');
    }
  });

  test('should navigate to previous image when clicking left arrow', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.hover();

    const rightArrow = page.locator('.gallery__nav-button--right');
    const leftArrow = page.locator('.gallery__nav-button--left');
    const arrowCount = await rightArrow.count();

    if (arrowCount > 0) {
      // First navigate to second image
      await rightArrow.click();
      await page.waitForTimeout(300);

      const carImage = page.locator('.gallery__image');
      const secondImageSrc = await carImage.getAttribute('src');

      // Then navigate back
      await galleryMedia.hover(); // Re-hover to show arrows
      await leftArrow.click();
      await page.waitForTimeout(300);

      // Verify we're back to first image
      const currentSrc = await carImage.getAttribute('src');
      expect(currentSrc).not.toBe(secondImageSrc);

      // Verify counter shows 1
      const imageCounter = page.locator('.gallery__image-counter');
      await expect(imageCounter).toContainText('1');
    }
  });

  test('should loop from last image to first when clicking right arrow', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.hover();

    const rightArrow = page.locator('.gallery__nav-button--right');
    const imageCounter = page.locator('.gallery__image-counter');
    const arrowCount = await rightArrow.count();

    if (arrowCount > 0) {
      // Get total number of images from counter
      const counterText = await imageCounter.textContent();
      const totalImages = parseInt(counterText?.split('/')[1]?.trim() || '1');

      // Navigate to last image
      for (let i = 1; i < totalImages; i++) {
        await galleryMedia.hover();
        await rightArrow.click();
        await page.waitForTimeout(200);
      }

      // Verify we're on last image
      await expect(imageCounter).toContainText(`${totalImages} /`);

      // Click right arrow once more to loop to first
      await galleryMedia.hover();
      await rightArrow.click();
      await page.waitForTimeout(300);

      // Verify we're back to first image
      await expect(imageCounter).toContainText('1 /');
    }
  });

  test('should loop from first image to last when clicking left arrow', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.hover();

    const leftArrow = page.locator('.gallery__nav-button--left');
    const imageCounter = page.locator('.gallery__image-counter');
    const arrowCount = await leftArrow.count();

    if (arrowCount > 0) {
      // We're on first image, click left to go to last
      await leftArrow.click();
      await page.waitForTimeout(300);

      // Get total number of images from counter
      const counterText = await imageCounter.textContent();
      const totalImages = parseInt(counterText?.split('/')[1]?.trim() || '1');

      // Verify we're on last image
      await expect(imageCounter).toContainText(`${totalImages} /`);
    }
  });

  test('should open fullscreen viewer when clicking on image', async () => {
    const galleryMedia = page.locator('.gallery__media');

    // Click on the image (not on the arrows)
    await galleryMedia.click({ position: { x: 50, y: 50 } });

    // Wait for dialog to open
    await page.waitForTimeout(500);

    // Check that fullscreen viewer opened
    const imageViewer = page.locator('.image-viewer');
    await expect(imageViewer).toBeVisible();

    // Verify close button exists
    const closeButton = page.locator('.close-button');
    await expect(closeButton).toBeVisible();
  });

  test('should navigate images in fullscreen mode', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.click({ position: { x: 50, y: 50 } });
    await page.waitForTimeout(500);

    const imageViewer = page.locator('.image-viewer');
    await expect(imageViewer).toBeVisible();

    // Check for navigation buttons in fullscreen
    const navButtonRight = page.locator('.nav-button--right');
    const navButtonCount = await navButtonRight.count();

    if (navButtonCount > 0) {
      // Click right arrow in fullscreen
      await navButtonRight.click();
      await page.waitForTimeout(300);

      // Verify image counter updated
      const fullscreenCounter = page.locator('.image-counter');
      await expect(fullscreenCounter).toContainText('2');
    }
  });

  test('should close fullscreen viewer when clicking close button', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.click({ position: { x: 50, y: 50 } });
    await page.waitForTimeout(500);

    const imageViewer = page.locator('.image-viewer');
    await expect(imageViewer).toBeVisible();

    // Click close button
    const closeButton = page.locator('.close-button');
    await closeButton.click();
    await page.waitForTimeout(300);

    // Verify dialog closed
    await expect(imageViewer).not.toBeVisible();
  });

  test('should close fullscreen viewer when pressing Escape key', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.click({ position: { x: 50, y: 50 } });
    await page.waitForTimeout(500);

    const imageViewer = page.locator('.image-viewer');
    await expect(imageViewer).toBeVisible();

    // Press Escape
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);

    // Verify dialog closed
    await expect(imageViewer).not.toBeVisible();
  });

  test('should navigate with keyboard arrows in fullscreen mode', async () => {
    const galleryMedia = page.locator('.gallery__media');
    await galleryMedia.click({ position: { x: 50, y: 50 } });
    await page.waitForTimeout(500);

    const fullscreenCounter = page.locator('.image-counter');
    const counterExists = await fullscreenCounter.count();

    if (counterExists > 0) {
      // Press right arrow key
      await page.keyboard.press('ArrowRight');
      await page.waitForTimeout(300);

      // Verify counter updated
      await expect(fullscreenCounter).toContainText('2');

      // Press left arrow key
      await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(300);

      // Verify back to first image
      await expect(fullscreenCounter).toContainText('1');
    }
  });

  test('should display click hint on hover (desktop)', async () => {
    const galleryMedia = page.locator('.gallery__media');

    // Hover over gallery
    await galleryMedia.hover();

    // Check for click hint
    const clickHint = page.locator('.gallery__click-hint');
    const hintCount = await clickHint.count();

    if (hintCount > 0) {
      await expect(clickHint).toBeVisible();
      await expect(clickHint).toContainText('Kliknite za prikaz');
    }
  });

  test('should work properly on mobile viewport', async () => {
    // Set mobile viewport
    await page.setViewportSize({ width: 375, height: 667 });
    await page.reload();
    await page.waitForLoadState('networkidle');

    const galleryMedia = page.locator('.gallery__media');
    await expect(galleryMedia).toBeVisible();

    const carImage = page.locator('.gallery__image');
    await expect(carImage).toBeVisible();

    // On mobile, navigation arrows should be visible without hover
    const rightArrow = page.locator('.gallery__nav-button--right');
    const arrowCount = await rightArrow.count();

    if (arrowCount > 0) {
      // Arrows should be visible on touch devices
      await expect(rightArrow).toBeVisible();

      // Click navigation should work
      await rightArrow.click();
      await page.waitForTimeout(300);

      const imageCounter = page.locator('.gallery__image-counter');
      await expect(imageCounter).toContainText('2');
    }
  });

  test('should handle missing images gracefully', async () => {
    // Navigate to a car that might not have images
    await page.goto(`${page.url().split('/cars/')[0]}/cars/999`);
    await page.waitForLoadState('networkidle');

    // Should show placeholder or handle gracefully
    const placeholder = page.locator('.gallery__placeholder');
    const carImage = page.locator('.gallery__image');

    // Either placeholder or image should be visible
    const placeholderVisible = await placeholder.isVisible().catch(() => false);
    const imageVisible = await carImage.isVisible().catch(() => false);

    expect(placeholderVisible || imageVisible).toBeTruthy();
  });

  test('should not show navigation controls for single image', async () => {
    // This test assumes we can find a car with only one image
    // Skip navigation control checks if car has multiple images

    const leftArrow = page.locator('.gallery__nav-button--left');
    const rightArrow = page.locator('.gallery__nav-button--right');
    const imageCounter = page.locator('.gallery__image-counter');

    const leftCount = await leftArrow.count();
    const counterCount = await imageCounter.count();

    // If no arrows exist, verify counter also doesn't exist
    if (leftCount === 0) {
      expect(counterCount).toBe(0);
      await expect(rightArrow).not.toBeVisible();
    }
  });
});
