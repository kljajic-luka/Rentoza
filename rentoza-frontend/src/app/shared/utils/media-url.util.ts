import { environment } from '@environments/environment';

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function normalizeDataUrl(rawUrl: string): string {
  const trimmed = rawUrl.trim();

  // Fix common whitespace issues in the scheme / header.
  // Examples seen in the wild:
  // - "data: image/jpeg;base64,..."  -> "data:image/jpeg;base64,..."
  // - "data:image/jpeg; base64,..." -> "data:image/jpeg;base64,..."
  let fixed = trimmed.replace(/^data:\s*/i, 'data:');
  fixed = fixed.replace(/;\s*base64\s*,/i, ';base64,');

  const commaIndex = fixed.indexOf(',');
  if (commaIndex === -1) {
    // Still remove whitespace defensively.
    return fixed.replace(/\s+/g, '');
  }

  const header = fixed.slice(0, commaIndex).replace(/\s+/g, '');
  const payload = fixed.slice(commaIndex + 1).replace(/\s+/g, '');

  return `${header},${payload}`;
}

/**
 * Normalize media URLs coming from the API.
 *
 * Rules:
 * - data: URLs are trimmed and whitespace-normalized (safe; does not change origin/privileges).
 * - /uploads or uploads are prefixed with environment.baseUrl (dev LAN mode) and left relative in prod.
 * - http(s) URLs are returned as-is.
 * - Absolute filesystem paths are rejected (return null) to avoid leaking local paths into the UI.
 */
export function normalizeMediaUrl(input?: string | null): string | null {
  if (!isNonEmptyString(input)) {
    return null;
  }

  const url = input.trim();

  if (/^data:/i.test(url)) {
    return normalizeDataUrl(url);
  }

  if (/^https?:\/\//i.test(url) || /^\/\//.test(url)) {
    return url;
  }

  // Hard reject absolute local filesystem paths.
  if (/^(?:[A-Za-z]:\\|\\\\|\/Users\/|\/home\/)/.test(url)) {
    return null;
  }

  const normalizedUploadsPath = url.startsWith('uploads/') ? `/${url}` : url;

  if (
    normalizedUploadsPath.startsWith('/uploads/') ||
    normalizedUploadsPath.startsWith('/user-uploads/')
  ) {
    // In production env.baseUrl is '', so this stays relative.
    return `${environment.baseUrl}${normalizedUploadsPath}`;
  }

  return url;
}

export function normalizeMediaUrlArray(urls?: Array<string | null | undefined> | null): string[] {
  if (!Array.isArray(urls)) {
    return [];
  }

  return urls
    .map((u) => normalizeMediaUrl(u))
    .filter((u): u is string => typeof u === 'string' && u.length > 0);
}

export function getPrimaryImageUrl(params: {
  imageUrl?: string | null;
  imageUrls?: Array<string | null | undefined> | null;
}): string | null {
  const normalizedImageUrls = normalizeMediaUrlArray(params.imageUrls);
  const normalizedSingle = normalizeMediaUrl(params.imageUrl);
  return normalizedImageUrls[0] ?? normalizedSingle;
}
