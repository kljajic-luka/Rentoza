/**
 * Compression Web Worker
 *
 * Offloads CPU-intensive image compression from the main thread to prevent UI jank.
 * Uses OffscreenCanvas (where supported) for maximum performance.
 *
 * ## Why a Web Worker?
 * Compressing 12MP HEIC/JPEG images blocks the UI thread for 300ms-2000ms per photo.
 * On low-end Android devices, this freezes the entire "Snap & Go" experience.
 * Web Workers run in a separate thread, keeping the UI at 60fps.
 *
 * ## Memory Management
 * CRITICAL: Workers have separate memory limits. We explicitly:
 * - Close ImageBitmaps after use to prevent OOM on iOS
 * - Terminate canvas contexts when done
 * - Send transferable objects to avoid memory copies
 *
 * ## Browser Support
 * - OffscreenCanvas: Chrome 69+, Firefox 105+, Safari 16.4+
 * - createImageBitmap: Chrome 50+, Firefox 42+, Safari 15+
 * - Falls back to main thread if not supported (handled by PhotoCompressionService)
 *
 * @see PhotoCompressionService for main thread fallback
 */

// Message types for type safety
interface CompressionRequest {
  type: 'compress';
  id: string;
  imageData: ArrayBuffer;
  mimeType: string;
  options: CompressionOptions;
}

interface CompressionOptions {
  maxWidth: number;
  maxHeight: number;
  quality: number;
  targetSizeKB: number;
  outputMimeType: 'image/jpeg' | 'image/webp';
}

interface CompressionResponse {
  type: 'result';
  id: string;
  success: boolean;
  data?: ArrayBuffer;
  width?: number;
  height?: number;
  originalSize: number;
  compressedSize?: number;
  error?: string;
}

interface ProgressResponse {
  type: 'progress';
  id: string;
  percent: number;
}

// Worker context
const ctx: Worker = self as unknown as Worker;

/**
 * Main message handler
 */
ctx.onmessage = async (event: MessageEvent<CompressionRequest>) => {
  const { type, id, imageData, mimeType, options } = event.data;

  if (type !== 'compress') {
    return;
  }

  try {
    // Report start
    sendProgress(id, 5);

    // Create ImageBitmap from the raw data
    const blob = new Blob([imageData], { type: mimeType });
    const imageBitmap = await createImageBitmap(blob);
    sendProgress(id, 20);

    // Calculate target dimensions
    const { width, height } = calculateDimensions(
      imageBitmap.width,
      imageBitmap.height,
      options.maxWidth,
      options.maxHeight
    );
    sendProgress(id, 30);

    // Compress using OffscreenCanvas
    const result = await compressWithOffscreenCanvas(imageBitmap, width, height, options, id);

    // CRITICAL: Close the ImageBitmap to free memory
    imageBitmap.close();

    // Send result (transfer the ArrayBuffer for zero-copy)
    const response: CompressionResponse = {
      type: 'result',
      id,
      success: true,
      data: result.data,
      width: result.width,
      height: result.height,
      originalSize: imageData.byteLength,
      compressedSize: result.data.byteLength,
    };

    ctx.postMessage(response, [result.data]);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Unknown compression error';

    const response: CompressionResponse = {
      type: 'result',
      id,
      success: false,
      originalSize: imageData.byteLength,
      error: errorMessage,
    };

    ctx.postMessage(response);
  }
};

/**
 * Calculate target dimensions maintaining aspect ratio.
 */
function calculateDimensions(
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

/**
 * Compress image using OffscreenCanvas.
 * Implements iterative quality reduction to meet target file size.
 */
async function compressWithOffscreenCanvas(
  imageBitmap: ImageBitmap,
  targetWidth: number,
  targetHeight: number,
  options: CompressionOptions,
  requestId: string
): Promise<{ data: ArrayBuffer; width: number; height: number }> {
  // Create OffscreenCanvas
  const canvas = new OffscreenCanvas(targetWidth, targetHeight);
  const ctx = canvas.getContext('2d', {
    alpha: false, // No transparency needed for photos
    desynchronized: true, // Hint for better perf
  });

  if (!ctx) {
    throw new Error('Failed to get OffscreenCanvas 2D context');
  }

  // Draw the image
  ctx.drawImage(imageBitmap, 0, 0, targetWidth, targetHeight);
  sendProgress(requestId, 50);

  // Iterative compression to meet target size
  const targetSizeBytes = options.targetSizeKB * 1024;
  let quality = options.quality;
  const minQuality = 0.3;
  const qualityStep = 0.1;
  let blob: Blob;
  let iterations = 0;
  const maxIterations = 10;

  do {
    blob = await canvas.convertToBlob({
      type: options.outputMimeType,
      quality,
    });

    iterations++;
    sendProgress(requestId, 50 + iterations * 4);

    if (blob.size <= targetSizeBytes || quality <= minQuality) {
      break;
    }

    quality -= qualityStep;
  } while (iterations < maxIterations);

  // If still too large, resize further
  if (blob.size > targetSizeBytes && iterations >= maxIterations) {
    const scaleFactor = Math.sqrt(targetSizeBytes / blob.size);
    const newWidth = Math.round(targetWidth * scaleFactor);
    const newHeight = Math.round(targetHeight * scaleFactor);

    // Create smaller canvas
    const smallerCanvas = new OffscreenCanvas(newWidth, newHeight);
    const smallerCtx = smallerCanvas.getContext('2d', { alpha: false });

    if (smallerCtx) {
      smallerCtx.drawImage(imageBitmap, 0, 0, newWidth, newHeight);
      blob = await smallerCanvas.convertToBlob({
        type: options.outputMimeType,
        quality: minQuality,
      });
    }
  }

  sendProgress(requestId, 90);

  // Convert Blob to ArrayBuffer
  const arrayBuffer = await blob.arrayBuffer();
  sendProgress(requestId, 100);

  return {
    data: arrayBuffer,
    width: targetWidth,
    height: targetHeight,
  };
}

/**
 * Send progress update to main thread.
 */
function sendProgress(id: string, percent: number): void {
  const response: ProgressResponse = {
    type: 'progress',
    id,
    percent,
  };
  ctx.postMessage(response);
}

// Signal that worker is ready
ctx.postMessage({ type: 'ready' });