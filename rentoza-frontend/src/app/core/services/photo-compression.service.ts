/**
 * Photo Compression Service
 *
 * Implements aggressive client-side image compression to handle Serbian 4G/Edge constraints.
 *
 * ## Architecture Decision
 * Uses Canvas API instead of Web Workers for broader browser compatibility on mobile.
 * The compression is async and yields to the main thread periodically.
 *
 * ## Target
 * All images must be < 500KB before upload (backend safety limit is 10MB).
 *
 * ## Memory Management
 * Properly revokes Blob URLs to prevent memory leaks on mobile browsers.
 */

import { Injectable, signal, computed } from '@angular/core';

export interface CompressionResult {
  originalSize: number;
  compressedSize: number;
  compressionRatio: number;
  blob: Blob;
  width: number;
  height: number;
  mimeType: string;
}

export interface CompressionOptions {
  maxWidth: number;
  maxHeight: number;
  quality: number; // 0-1
  targetSizeKB: number;
  mimeType: 'image/jpeg' | 'image/webp';
}

const DEFAULT_OPTIONS: CompressionOptions = {
  maxWidth: 1920,
  maxHeight: 1080,
  quality: 0.8,
  targetSizeKB: 500,
  mimeType: 'image/jpeg',
};

@Injectable({ providedIn: 'root' })
export class PhotoCompressionService {
  // Reactive state for UI feedback
  private readonly _isCompressing = signal(false);
  private readonly _compressionProgress = signal(0);
  private readonly _currentFile = signal<string | null>(null);

  readonly isCompressing = this._isCompressing.asReadonly();
  readonly compressionProgress = this._compressionProgress.asReadonly();
  readonly currentFile = this._currentFile.asReadonly();

  // Track object URLs for cleanup
  private activeObjectUrls = new Set<string>();

  /**
   * Compress an image file to meet size constraints.
   *
   * @param file - Original image file
   * @param options - Compression options (optional)
   * @returns Promise<CompressionResult>
   * @throws Error if file is not an image or compression fails
   */
  async compressImage(
    file: File,
    options: Partial<CompressionOptions> = {}
  ): Promise<CompressionResult> {
    const opts = { ...DEFAULT_OPTIONS, ...options };

    // Validate file type
    if (!this.isValidImageType(file)) {
      throw new Error('Nevažeći format slike. Podržani formati: JPEG, PNG, HEIC, WebP');
    }

    this._isCompressing.set(true);
    this._compressionProgress.set(0);
    this._currentFile.set(file.name);

    try {
      // Load image
      const img = await this.loadImage(file);
      this._compressionProgress.set(20);

      // Calculate target dimensions maintaining aspect ratio
      const { width, height } = this.calculateDimensions(
        img.width,
        img.height,
        opts.maxWidth,
        opts.maxHeight
      );
      this._compressionProgress.set(40);

      // Compress with iterative quality reduction if needed
      const result = await this.compressWithQualityReduction(img, width, height, opts);
      this._compressionProgress.set(100);

      console.log(
        `[Compression] ${file.name}: ${this.formatSize(file.size)} → ${this.formatSize(
          result.compressedSize
        )} (${result.compressionRatio.toFixed(1)}x)`
      );

      return result;
    } finally {
      this._isCompressing.set(false);
      this._currentFile.set(null);
    }
  }

  /**
   * Compress multiple images in sequence.
   * Yields to main thread between compressions to keep UI responsive.
   */
  async compressMultiple(
    files: File[],
    options: Partial<CompressionOptions> = {}
  ): Promise<CompressionResult[]> {
    const results: CompressionResult[] = [];

    for (let i = 0; i < files.length; i++) {
      // Yield to main thread
      await this.yieldToMain();

      const result = await this.compressImage(files[i], options);
      results.push(result);
    }

    return results;
  }

  /**
   * Validate if file is a supported image type.
   */
  isValidImageType(file: File): boolean {
    const validTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif'];
    return (
      validTypes.includes(file.type) ||
      file.name.toLowerCase().endsWith('.heic') ||
      file.name.toLowerCase().endsWith('.heif')
    );
  }

  /**
   * Create an object URL and track it for cleanup.
   */
  createTrackedObjectUrl(blob: Blob): string {
    const url = URL.createObjectURL(blob);
    this.activeObjectUrls.add(url);
    return url;
  }

  /**
   * Revoke a tracked object URL.
   */
  revokeObjectUrl(url: string): void {
    if (this.activeObjectUrls.has(url)) {
      URL.revokeObjectURL(url);
      this.activeObjectUrls.delete(url);
    }
  }

  /**
   * Revoke all tracked object URLs.
   * Call this when leaving the check-in wizard to prevent memory leaks.
   */
  revokeAllObjectUrls(): void {
    this.activeObjectUrls.forEach((url) => URL.revokeObjectURL(url));
    this.activeObjectUrls.clear();
    console.log('[Compression] Revoked all object URLs to prevent memory leak');
  }

  // ========== PRIVATE METHODS ==========

  private loadImage(file: File): Promise<HTMLImageElement> {
    return new Promise((resolve, reject) => {
      const img = new Image();
      const url = URL.createObjectURL(file);

      img.onload = () => {
        URL.revokeObjectURL(url);
        resolve(img);
      };

      img.onerror = () => {
        URL.revokeObjectURL(url);
        reject(new Error('Greška pri učitavanju slike'));
      };

      img.src = url;
    });
  }

  private calculateDimensions(
    originalWidth: number,
    originalHeight: number,
    maxWidth: number,
    maxHeight: number
  ): { width: number; height: number } {
    let width = originalWidth;
    let height = originalHeight;

    // Scale down if larger than max dimensions
    if (width > maxWidth) {
      height = Math.round((height * maxWidth) / width);
      width = maxWidth;
    }

    if (height > maxHeight) {
      width = Math.round((width * maxHeight) / height);
      height = maxHeight;
    }

    return { width, height };
  }

  private async compressWithQualityReduction(
    img: HTMLImageElement,
    width: number,
    height: number,
    options: CompressionOptions
  ): Promise<CompressionResult> {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;

    const ctx = canvas.getContext('2d');
    if (!ctx) {
      throw new Error('Canvas context nije dostupan');
    }

    // Draw image to canvas
    ctx.drawImage(img, 0, 0, width, height);
    this._compressionProgress.set(60);

    // Try compression with decreasing quality until target size is met
    let quality = options.quality;
    let blob: Blob | null = null;
    const targetSizeBytes = options.targetSizeKB * 1024;
    const minQuality = 0.3;
    const qualityStep = 0.1;

    while (quality >= minQuality) {
      blob = await this.canvasToBlob(canvas, options.mimeType, quality);

      if (blob.size <= targetSizeBytes) {
        break;
      }

      quality -= qualityStep;
      this._compressionProgress.set(
        60 + Math.round(((options.quality - quality) / options.quality) * 30)
      );
    }

    // If still too large, resize further
    if (blob && blob.size > targetSizeBytes) {
      const scaleFactor = Math.sqrt(targetSizeBytes / blob.size);
      const newWidth = Math.round(width * scaleFactor);
      const newHeight = Math.round(height * scaleFactor);

      canvas.width = newWidth;
      canvas.height = newHeight;
      ctx.drawImage(img, 0, 0, newWidth, newHeight);
      blob = await this.canvasToBlob(canvas, options.mimeType, minQuality);
    }

    if (!blob) {
      throw new Error('Kompresija slike nije uspela');
    }

    return {
      originalSize: img.width * img.height * 4, // Approximate original in-memory size
      compressedSize: blob.size,
      compressionRatio: (img.width * img.height * 4) / blob.size,
      blob,
      width: canvas.width,
      height: canvas.height,
      mimeType: options.mimeType,
    };
  }

  private canvasToBlob(
    canvas: HTMLCanvasElement,
    mimeType: string,
    quality: number
  ): Promise<Blob> {
    return new Promise((resolve, reject) => {
      canvas.toBlob(
        (blob) => {
          if (blob) {
            resolve(blob);
          } else {
            reject(new Error('Canvas toBlob nije uspeo'));
          }
        },
        mimeType,
        quality
      );
    });
  }

  private yieldToMain(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
  }

  private formatSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  }
}
