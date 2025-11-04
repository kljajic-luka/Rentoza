/**
 * String normalization utilities for search and filtering
 * Handles Serbian Latin/Cyrillic characters, case insensitivity, and whitespace
 */

/**
 * Normalizes a string for search by:
 * - Converting to lowercase
 * - Trimming whitespace
 * - Removing accents (š→s, ć→c, č→c, ž→z, đ→dj)
 * - Collapsing multiple spaces to single space
 */
export function normalizeSearchString(input: string | null | undefined): string {
  if (!input) {
    return '';
  }

  return input
    .toLowerCase()
    .trim()
    .replace(/š/g, 's')
    .replace(/ć/g, 'c')
    .replace(/č/g, 'c')
    .replace(/ž/g, 'z')
    .replace(/đ/g, 'dj')
    .replace(/\s+/g, ' '); // Collapse multiple spaces
}

/**
 * Checks if two strings match after normalization
 */
export function normalizedMatch(
  str1: string | null | undefined,
  str2: string | null | undefined
): boolean {
  return normalizeSearchString(str1) === normalizeSearchString(str2);
}

/**
 * Checks if a string contains another string after normalization
 */
export function normalizedIncludes(
  haystack: string | null | undefined,
  needle: string | null | undefined
): boolean {
  if (!needle) {
    return true;
  }
  return normalizeSearchString(haystack).includes(normalizeSearchString(needle));
}

/**
 * Map of Serbian Latin characters and their normalized equivalents
 */
export const SERBIAN_ACCENT_MAP: Record<string, string> = {
  š: 's',
  Š: 'S',
  ć: 'c',
  Ć: 'C',
  č: 'c',
  Č: 'C',
  ž: 'z',
  Ž: 'Z',
  đ: 'dj',
  Đ: 'Dj',
};

/**
 * Removes accents from a string while preserving case
 */
export function removeAccents(input: string | null | undefined): string {
  if (!input) {
    return '';
  }

  let result = input;
  Object.entries(SERBIAN_ACCENT_MAP).forEach(([accented, plain]) => {
    result = result.replace(new RegExp(accented, 'g'), plain);
  });

  return result;
}
