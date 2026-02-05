import { getPrimaryImageUrl, normalizeMediaUrl } from './media-url.util';

describe('media-url.util', () => {
  describe('normalizeMediaUrl', () => {
    it('should return null for empty input', () => {
      expect(normalizeMediaUrl(undefined)).toBeNull();
      expect(normalizeMediaUrl(null)).toBeNull();
      expect(normalizeMediaUrl('')).toBeNull();
      expect(normalizeMediaUrl('   ')).toBeNull();
    });

    it('should keep http(s) URLs unchanged', () => {
      expect(normalizeMediaUrl('https://example.com/a.jpg')).toBe('https://example.com/a.jpg');
      expect(normalizeMediaUrl('http://example.com/a.jpg')).toBe('http://example.com/a.jpg');
    });

    it('should normalize common whitespace mistakes in data URLs', () => {
      const malformed1 = 'data: image/jpeg;base64,AAA BBB\nCCC';
      expect(normalizeMediaUrl(malformed1)).toBe('data:image/jpeg;base64,AAABBBCCC');

      const malformed2 = 'data:image/jpeg; base64,AAA\nBBB';
      expect(normalizeMediaUrl(malformed2)).toBe('data:image/jpeg;base64,AAABBB');

      const ok = 'data:image/jpeg;base64,/9j/ABC123==';
      expect(normalizeMediaUrl(ok)).toBe(ok);
    });

    it('should prefix /uploads URLs with baseUrl (or keep relative in prod)', () => {
      // This assertion is intentionally flexible: in prod environment.baseUrl is ''.
      const result = normalizeMediaUrl('/uploads/profile-pictures/56.jpg?x=1');
      expect(result).toContain('/uploads/profile-pictures/56.jpg?x=1');
    });

    it('should reject absolute filesystem paths', () => {
      expect(normalizeMediaUrl('/Users/me/uploads/a.jpg')).toBeNull();
      expect(normalizeMediaUrl('C:\\temp\\a.jpg')).toBeNull();
    });
  });

  describe('getPrimaryImageUrl', () => {
    it('should choose imageUrls[0] when imageUrl is missing', () => {
      const primary = getPrimaryImageUrl({
        imageUrl: null,
        imageUrls: ['data:image/png;base64,AAA'],
      });
      expect(primary).toBe('data:image/png;base64,AAA');
    });

    it('should normalize malformed data URL and choose it as primary', () => {
      const primary = getPrimaryImageUrl({
        imageUrl: 'data: image/jpeg; base64,AAA BBB',
        imageUrls: [],
      });
      expect(primary).toBe('data:image/jpeg;base64,AAABBB');
    });

    it('should prefer imageUrls[0] over imageUrl when both exist', () => {
      const primary = getPrimaryImageUrl({
        imageUrl: 'data:image/jpeg;base64,SINGLE',
        imageUrls: ['data:image/jpeg;base64,ARRAY0', 'data:image/jpeg;base64,ARRAY1'],
      });
      expect(primary).toBe('data:image/jpeg;base64,ARRAY0');
    });
  });
});
