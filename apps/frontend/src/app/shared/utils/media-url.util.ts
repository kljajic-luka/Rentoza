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

const CHAT_ATTACHMENT_PREFIX = '/api/attachments/';

/**
 * Resolve a chat attachment URL to an absolute URL against the chat service origin.
 *
 * The chat service stores attachment paths as platform-relative paths:
 *   /api/attachments/booking-{id}/{uuid}.{ext}
 *
 * These must be resolved against the chat service origin so the browser
 * hits the correct host instead of the Angular SPA origin.
 *
 * @param mediaUrl   Raw mediaUrl from MessageDTO (e.g. '/api/attachments/booking-7/abc.jpg')
 * @param chatOrigin Chat service origin (e.g. 'https://chat.rentoza.rs')
 * @returns Absolute URL, or null if input is invalid/unrecognized
 */
export function resolveAttachmentUrl(
  mediaUrl: string | null | undefined,
  chatOrigin: string,
): string | null {
  if (!mediaUrl) return null;
  const url = mediaUrl.trim();
  if (!url) return null;

  // Our own platform attachment path — prepend chat service origin.
  if (url.startsWith(CHAT_ATTACHMENT_PREFIX)) {
    return chatOrigin + url;
  }

  // Already-absolute URLs (e.g. legacy Supabase CDN links) pass through unchanged.
  if (/^https?:\/\//i.test(url)) return url;

  return null;
}