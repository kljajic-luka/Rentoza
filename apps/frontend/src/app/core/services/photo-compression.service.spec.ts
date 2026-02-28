/**
 * Photo Compression Service - Unit Tests
 *
 * Tests the canvas-based image compression for check-in photos.
 * Critical requirement: All images must compress to < 500KB.
 */
import { TestBed } from '@angular/core/testing';
import {
  PhotoCompressionService,
  CompressionOptions,
  CompressionResult,
} from './photo-compression.service';

describe('PhotoCompressionService', () => {
  let service: PhotoCompressionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PhotoCompressionService],
    });
    service = TestBed.inject(PhotoCompressionService);
  });

  afterEach(() => {
    service.revokeAllObjectUrls();
  });

  describe('isValidImageType', () => {
    it('should accept JPEG files', () => {
      const file = new File([''], 'test.jpg', { type: 'image/jpeg' });
      expect(service.isValidImageType(file)).toBe(true);
    });

    it('should accept PNG files', () => {
      const file = new File([''], 'test.png', { type: 'image/png' });
      expect(service.isValidImageType(file)).toBe(true);
    });

    it('should accept HEIC files by extension', () => {
      const file = new File([''], 'test.heic', { type: '' });
      expect(service.isValidImageType(file)).toBe(true);
    });

    it('should accept HEIC files by mime type', () => {
      const file = new File([''], 'test.heic', { type: 'image/heic' });
      expect(service.isValidImageType(file)).toBe(true);
    });

    it('should accept WEBP files', () => {
      const file = new File([''], 'test.webp', { type: 'image/webp' });
      expect(service.isValidImageType(file)).toBe(true);
    });

    it('should reject non-image files', () => {
      const file = new File([''], 'test.pdf', { type: 'application/pdf' });
      expect(service.isValidImageType(file)).toBe(false);
    });

    it('should reject text files', () => {
      const file = new File([''], 'test.txt', { type: 'text/plain' });
      expect(service.isValidImageType(file)).toBe(false);
    });
  });

  describe('compressImage', () => {
    // Helper to create a test image blob
    function createTestImage(width: number, height: number): Promise<Blob> {
      return new Promise((resolve) => {
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d')!;

        // Draw some content to make the image non-trivial
        ctx.fillStyle = '#ff0000';
        ctx.fillRect(0, 0, width, height);
        ctx.fillStyle = '#00ff00';
        ctx.fillRect(width / 4, height / 4, width / 2, height / 2);

        canvas.toBlob(
          (blob) => {
            resolve(blob!);
          },
          'image/jpeg',
          0.95,
        );
      });
    }

    it('should compress a large image to under target size', async () => {
      // Create a 2000x2000 image (simulating a large phone photo)
      const blob = await createTestImage(2000, 2000);
      const file = new File([blob], 'large.jpg', { type: 'image/jpeg' });

      const result = await service.compressImage(file, { targetSizeKB: 500 });

      expect(result.compressedSize).toBeLessThan(500 * 1024);
    });

    it('should return correct compression ratio', async () => {
      const blob = await createTestImage(1000, 1000);
      const file = new File([blob], 'test.jpg', { type: 'image/jpeg' });

      const result = await service.compressImage(file, { targetSizeKB: 500 });

      expect(result.compressionRatio).toBeGreaterThan(0);
      expect(result.originalSize).toBe(file.size);
    });

    it('should preserve image dimensions within max bounds', async () => {
      const blob = await createTestImage(3000, 2000);
      const file = new File([blob], 'wide.jpg', { type: 'image/jpeg' });

      const result = await service.compressImage(file, {
        targetSizeKB: 500,
        maxWidth: 1920,
        maxHeight: 1080,
      });

      expect(result.width).toBeLessThanOrEqual(1920);
      expect(result.height).toBeLessThanOrEqual(1080);
    });

    it('should update reactive state during compression', async () => {
      const blob = await createTestImage(1000, 1000);
      const file = new File([blob], 'test.jpg', { type: 'image/jpeg' });

      expect(service.isCompressing()).toBe(false);

      const compressionPromise = service.compressImage(file, { targetSizeKB: 500 });

      // _isCompressing.set(true) runs synchronously before the first await inside
      // compressImage; no delay needed to observe the signal change.
      expect(service.isCompressing()).toBe(true);

      await compressionPromise;

      expect(service.isCompressing()).toBe(false);
    });

    it('should handle concurrent compressions', async () => {
      const blob1 = await createTestImage(500, 500);
      const blob2 = await createTestImage(600, 600);
      const file1 = new File([blob1], 'test1.jpg', { type: 'image/jpeg' });
      const file2 = new File([blob2], 'test2.jpg', { type: 'image/jpeg' });

      const [result1, result2] = await Promise.all([
        service.compressImage(file1, { targetSizeKB: 500 }),
        service.compressImage(file2, { targetSizeKB: 500 }),
      ]);

      expect(result1.blob).toBeTruthy();
      expect(result2.blob).toBeTruthy();
    });

    it('should return JPEG mime type', async () => {
      const blob = await createTestImage(500, 500);
      const file = new File([blob], 'test.jpg', { type: 'image/jpeg' });

      const result = await service.compressImage(file, { targetSizeKB: 500 });

      expect(result.mimeType).toBe('image/jpeg');
    });
  });

  describe('compressMultiple', () => {
    it('should compress multiple files in sequence', async () => {
      const createBlob = () =>
        new Promise<Blob>((resolve) => {
          const canvas = document.createElement('canvas');
          canvas.width = 200;
          canvas.height = 200;
          canvas.toBlob((b) => resolve(b!), 'image/jpeg');
        });

      const files = [
        new File([await createBlob()], 'test1.jpg', { type: 'image/jpeg' }),
        new File([await createBlob()], 'test2.jpg', { type: 'image/jpeg' }),
        new File([await createBlob()], 'test3.jpg', { type: 'image/jpeg' }),
      ];

      const results = await service.compressMultiple(files, { targetSizeKB: 500 });

      expect(results.length).toBe(3);
      results.forEach((result) => {
        expect(result.blob).toBeTruthy();
      });
    });
  });

  describe('object URL management', () => {
    it('should track created object URLs', () => {
      const blob = new Blob(['test'], { type: 'image/jpeg' });
      const url = service.createTrackedObjectUrl(blob);

      expect(url).toMatch(/^blob:/);
    });

    it('should revoke individual object URL', () => {
      const blob = new Blob(['test'], { type: 'image/jpeg' });
      const url = service.createTrackedObjectUrl(blob);

      const revokeSpy = spyOn(URL, 'revokeObjectURL');
      service.revokeObjectUrl(url);

      expect(revokeSpy).toHaveBeenCalledWith(url);
    });

    it('should revoke all tracked URLs', () => {
      const blob1 = new Blob(['test1'], { type: 'image/jpeg' });
      const blob2 = new Blob(['test2'], { type: 'image/jpeg' });
      service.createTrackedObjectUrl(blob1);
      service.createTrackedObjectUrl(blob2);

      const revokeSpy = spyOn(URL, 'revokeObjectURL');
      service.revokeAllObjectUrls();

      expect(revokeSpy).toHaveBeenCalledTimes(2);
    });
  });
});
