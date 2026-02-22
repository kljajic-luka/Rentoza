/**
 * Photo Compression Service
 *
 * Implements aggressive client-side image compression to handle Serbian 4G/Edge constraints.
 * **CRITICAL**: Preserves EXIF metadata using piexifjs to maintain timestamp/GPS for fraud prevention.
 *
 * ## Architecture Decision (Phase 2: Anti-Jank Layer)
 * Primary: Web Worker with OffscreenCanvas for 60fps UI during compression
 * Fallback: Main thread Canvas API for browsers without Worker/OffscreenCanvas support
 *
 * ## Web Worker Strategy
 * - Offloads CPU-intensive compression to a separate thread
 * - Prevents UI jank on low-end Android devices during "Snap & Go" flow
 * - Automatically falls back to main thread if Web Workers unavailable
 *
 * ## EXIF Preservation
 * The canvas.toBlob() API strips EXIF metadata. We use piexifjs to:
 * 1. Extract EXIF from original image BEFORE compression
 * 2. Re-inject EXIF into compressed image AFTER canvas processing
 * This preserves DateTimeOriginal, GPS coordinates, and device info for backend validation.
 *
 * ## Target
 * All images must be < 500KB before upload (backend safety limit is 10MB).
 *
 * ## Memory Management
 * - Web Worker: Explicitly closes ImageBitmaps after use to prevent OOM on iOS
 * - Main Thread: Revokes Blob URLs to prevent memory leaks on mobile browsers
 */

import { Injectable, signal, computed, OnDestroy } from '@angular/core';
import * as piexif from 'piexifjs';
import { generateUUID } from '../utils/uuid';

export interface CompressionResult {
  originalSize: number;
  compressedSize: number;
  compressionRatio: number;
  blob: Blob;
  width: number;
  height: number;
  mimeType: string;
  exifPreserved: boolean;
}

export interface CompressionOptions {
  maxWidth: number;
  maxHeight: number;
  quality: number; // 0-1
  targetSizeKB: number;
  mimeType: 'image/jpeg' | 'image/webp';
  preserveExif: boolean;
}

const DEFAULT_OPTIONS: CompressionOptions = {
  maxWidth: 1920,
  maxHeight: 1080,
  quality: 0.8,
  targetSizeKB: 500,
  mimeType: 'image/jpeg',
  preserveExif: true, // Default to preserving EXIF for check-in photos
};

@Injectable({ providedIn: 'root' })
export class PhotoCompressionService implements OnDestroy {
  // Reactive state for UI feedback
  private readonly _isCompressing = signal(false);
  private readonly _compressionProgress = signal(0);
  private readonly _currentFile = signal<string | null>(null);

  readonly isCompressing = this._isCompressing.asReadonly();
  readonly compressionProgress = this._compressionProgress.asReadonly();
  readonly currentFile = this._currentFile.asReadonly();

  // Track object URLs for cleanup
  private activeObjectUrls = new Set<string>();

  // Web Worker management
  private worker: Worker | null = null;
  private workerSupported = false;
  private pendingRequests = new Map<
    string,
    {
      resolve: (result: { data: ArrayBuffer; width: number; height: number }) => void;
      reject: (error: Error) => void;
    }
  >();

  constructor() {
    this.initializeWorker();
  }

  ngOnDestroy(): void {
    this.terminateWorker();
    this.revokeAllObjectUrls();
  }

  /**
   * Initialize Web Worker if supported.
   * Falls back to main thread if Worker or OffscreenCanvas not available.
   */
  private initializeWorker(): void {
    // Check for Web Worker and OffscreenCanvas support
    if (typeof Worker === 'undefined') {
      console.log('[Compression] Web Workers not supported, using main thread');
      return;
    }

    if (typeof OffscreenCanvas === 'undefined') {
      console.log('[Compression] OffscreenCanvas not supported, using main thread');
      return;
    }

    try {
      // Create worker from the TypeScript worker file
      // Angular's build system handles the worker bundling
      this.worker = new Worker(new URL('../workers/compression.worker', import.meta.url), {
        type: 'module',
      });

      this.worker.onmessage = (event) => this.handleWorkerMessage(event);
      this.worker.onerror = (error) => this.handleWorkerError(error);

      this.workerSupported = true;
      console.log('[Compression] Web Worker initialized successfully');
    } catch (error) {
      console.warn('[Compression] Failed to initialize Web Worker, using main thread:', error);
      this.worker = null;
      this.workerSupported = false;
    }
  }

  /**
   * Handle messages from the compression worker.
   */
  private handleWorkerMessage(event: MessageEvent): void {
    const { type, id, success, data, width, height, error, percent } = event.data;

    if (type === 'ready') {
      console.log('[Compression] Worker ready');
      return;
    }

    if (type === 'progress') {
      this._compressionProgress.set(percent);
      return;
    }

    if (type === 'result') {
      const pending = this.pendingRequests.get(id);
      if (!pending) return;

      this.pendingRequests.delete(id);

      if (success && data) {
        pending.resolve({ data, width, height });
      } else {
        pending.reject(new Error(error || 'Compression failed'));
      }
    }
  }

  /**
   * Handle worker errors.
   */
  private handleWorkerError(error: ErrorEvent): void {
    console.error('[Compression] Worker error:', error);

    // Reject all pending requests
    this.pendingRequests.forEach(({ reject }) => {
      reject(new Error('Worker error: ' + error.message));
    });
    this.pendingRequests.clear();

    // Terminate and recreate worker
    this.terminateWorker();
    this.initializeWorker();
  }

  /**
   * Terminate the worker and clean up.
   */
  private terminateWorker(): void {
    if (this.worker) {
      this.worker.terminate();
      this.worker = null;
    }
    this.pendingRequests.clear();
  }

  /**
   * Compress an image file to meet size constraints.
   * Uses Web Worker when available, falls back to main thread.
   * EXIF metadata is preserved by default for check-in photo validation.
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
      // Extract EXIF from original file BEFORE compression (if preserving)
      let exifData: string | null = null;
      if (
        opts.preserveExif &&
        (file.type === 'image/jpeg' || file.name.toLowerCase().endsWith('.jpg'))
      ) {
        try {
          exifData = await this.extractExif(file);
          if (exifData) {
            console.log('[Compression] EXIF extracted from original image');
          } else {
            console.log('[Compression] No EXIF found in original image');
          }
        } catch (e) {
          console.warn('[Compression] Failed to extract EXIF, continuing without:', e);
        }
      }
      this._compressionProgress.set(10);

      // Try Web Worker first, fall back to main thread
      let result: CompressionResult;
      if (this.workerSupported && this.worker) {
        result = await this.compressWithWorker(file, opts);
      } else {
        result = await this.compressOnMainThread(file, opts);
      }

      // Re-inject EXIF into compressed image
      let exifPreserved = false;
      if (exifData && opts.mimeType === 'image/jpeg') {
        try {
          const blobWithExif = await this.injectExif(result.blob, exifData);
          result = { ...result, blob: blobWithExif, compressedSize: blobWithExif.size };
          exifPreserved = true;
          console.log('[Compression] EXIF re-injected into compressed image');
        } catch (e) {
          console.warn('[Compression] Failed to inject EXIF into compressed image:', e);
        }
      }

      this._compressionProgress.set(100);

      console.log(
        `[Compression] ${file.name}: ${this.formatSize(file.size)} → ${this.formatSize(
          result.compressedSize
        )} (${result.compressionRatio.toFixed(1)}x) | EXIF: ${
          exifPreserved ? '✓' : '✗'
        } | Worker: ${this.workerSupported ? '✓' : '✗'}`
      );

      return { ...result, exifPreserved };
    } finally {
      this._isCompressing.set(false);
      this._currentFile.set(null);
    }
  }

  /**
   * Compress image using Web Worker (off main thread).
   * Keeps UI responsive during heavy compression.
   */
  private async compressWithWorker(
    file: File,
    options: CompressionOptions
  ): Promise<CompressionResult> {
    const requestId = generateUUID();
    const arrayBuffer = await file.arrayBuffer();

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(requestId, {
        resolve: (result) => {
          const blob = new Blob([result.data], { type: options.mimeType });
          resolve({
            originalSize: file.size,
            compressedSize: blob.size,
            compressionRatio: file.size / blob.size,
            blob,
            width: result.width,
            height: result.height,
            mimeType: options.mimeType,
            exifPreserved: false, // Will be set by caller after EXIF injection
          });
        },
        reject,
      });

      // Send to worker (transfer the ArrayBuffer for zero-copy)
      this.worker!.postMessage(
        {
          type: 'compress',
          id: requestId,
          imageData: arrayBuffer,
          mimeType: file.type,
          options: {
            maxWidth: options.maxWidth,
            maxHeight: options.maxHeight,
            quality: options.quality,
            targetSizeKB: options.targetSizeKB,
            outputMimeType: options.mimeType,
          },
        },
        [arrayBuffer]
      );

      // Timeout after 30 seconds
      setTimeout(() => {
        if (this.pendingRequests.has(requestId)) {
          this.pendingRequests.delete(requestId);
          reject(new Error('Compression timeout'));
        }
      }, 30000);
    });
  }

  /**
   * Compress image on main thread (fallback).
   * Used when Web Workers are not supported.
   */
  private async compressOnMainThread(
    file: File,
    options: CompressionOptions
  ): Promise<CompressionResult> {
    // Load image
    const img = await this.loadImage(file);
    this._compressionProgress.set(25);

    // Calculate target dimensions maintaining aspect ratio
    const { width, height } = this.calculateDimensions(
      img.width,
      img.height,
      options.maxWidth,
      options.maxHeight
    );
    this._compressionProgress.set(40);

    // Compress with iterative quality reduction if needed
    const result = await this.compressWithQualityReduction(img, width, height, options);
    this._compressionProgress.set(85);

    return result;
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
      exifPreserved: false, // Will be set to true by caller if EXIF is injected
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

  // ========== EXIF PRESERVATION METHODS ==========

  /**
   * Extract EXIF data from a JPEG file as a binary string.
   * This data can later be re-injected into a compressed version.
   */
  private async extractExif(file: File): Promise<string | null> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();

      reader.onload = (e) => {
        try {
          const dataUrl = e.target?.result as string;
          if (!dataUrl) {
            resolve(null);
            return;
          }

          // piexifjs works with data URLs
          const exifObj = piexif.load(dataUrl);

          // Check if we have meaningful EXIF data
          if (
            exifObj &&
            (Object.keys(exifObj['0th'] || {}).length > 0 ||
              Object.keys(exifObj['Exif'] || {}).length > 0 ||
              Object.keys(exifObj['GPS'] || {}).length > 0)
          ) {
            // CRITICAL FIX: Reset Orientation to "Normal" (1)
            // After canvas processing, the image is already physically rotated.
            // If we keep the original Orientation tag (e.g., 6 = rotate 90°),
            // browsers with image-orientation:from-image will rotate AGAIN.
            // Setting Orientation to 1 prevents double rotation.
            if (exifObj['0th'] && exifObj['0th'][piexif.ImageIFD.Orientation]) {
              const originalOrientation = exifObj['0th'][piexif.ImageIFD.Orientation];
              exifObj['0th'][piexif.ImageIFD.Orientation] = 1; // Normal orientation
              console.log(
                `[EXIF] Reset Orientation from ${originalOrientation} to 1 (Normal) to prevent double rotation`
              );
            }

            // Dump EXIF to binary string
            const exifBytes = piexif.dump(exifObj);
            console.log('[EXIF] Extracted EXIF data:', {
              '0th': Object.keys(exifObj['0th'] || {}).length + ' tags',
              Exif: Object.keys(exifObj['Exif'] || {}).length + ' tags',
              GPS: Object.keys(exifObj['GPS'] || {}).length + ' tags',
            });
            resolve(exifBytes);
          } else {
            console.log('[EXIF] No meaningful EXIF data found in image');
            resolve(null);
          }
        } catch (error) {
          console.warn('[EXIF] Failed to extract EXIF:', error);
          resolve(null); // Don't reject, just continue without EXIF
        }
      };

      reader.onerror = () => {
        console.warn('[EXIF] FileReader error');
        resolve(null);
      };

      reader.readAsDataURL(file);
    });
  }

  /**
   * Inject EXIF data into a compressed JPEG blob.
   * Returns a new Blob with the EXIF metadata embedded.
   */
  private async injectExif(blob: Blob, exifBytes: string): Promise<Blob> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();

      reader.onload = (e) => {
        try {
          const dataUrl = e.target?.result as string;
          if (!dataUrl) {
            reject(new Error('Failed to read compressed image'));
            return;
          }

          // Insert EXIF into the data URL
          const newDataUrl = piexif.insert(exifBytes, dataUrl);

          // Convert data URL back to Blob
          const byteString = atob(newDataUrl.split(',')[1]);
          const mimeString = newDataUrl.split(',')[0].split(':')[1].split(';')[0];
          const ab = new ArrayBuffer(byteString.length);
          const ia = new Uint8Array(ab);

          for (let i = 0; i < byteString.length; i++) {
            ia[i] = byteString.charCodeAt(i);
          }

          const blobWithExif = new Blob([ab], { type: mimeString });
          console.log(
            `[EXIF] Injected EXIF into compressed image (${this.formatSize(
              blob.size
            )} → ${this.formatSize(blobWithExif.size)})`
          );
          resolve(blobWithExif);
        } catch (error) {
          console.error('[EXIF] Failed to inject EXIF:', error);
          reject(error);
        }
      };

      reader.onerror = () => reject(new Error('FileReader error'));

      reader.readAsDataURL(blob);
    });
  }
}