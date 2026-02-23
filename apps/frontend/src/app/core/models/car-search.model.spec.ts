/**
 * Unit tests for car-search.model helper functions.
 * Covers: q param round-trip through merge and extract helpers.
 */
import {
  mergeFiltersIntoAvailabilityParams,
  extractFiltersFromAvailabilityParams,
  AvailabilitySearchParams,
  CarSearchCriteria,
} from './car-search.model';

describe('car-search.model', () => {
  const baseAvailParams: AvailabilitySearchParams = {
    location: 'Beograd',
    startTime: '2026-06-01T09:00:00',
    endTime: '2026-06-03T18:00:00',
    page: 0,
    size: 20,
  };

  // ── mergeFiltersIntoAvailabilityParams ────────────────────────────────────

  describe('mergeFiltersIntoAvailabilityParams', () => {
    it('should propagate q from filters into availability params', () => {
      const filters: CarSearchCriteria = { q: 'Audi', page: 0, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(baseAvailParams, filters);
      expect(merged.q).toBe('Audi');
    });

    it('should keep base q when filter q is empty/undefined', () => {
      const base: AvailabilitySearchParams = { ...baseAvailParams, q: 'BMW' };
      const filters: CarSearchCriteria = { page: 0, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(base, filters);
      expect(merged.q).toBe('BMW');
    });

    it('should override base q with filter q when both are set', () => {
      const base: AvailabilitySearchParams = { ...baseAvailParams, q: 'BMW' };
      const filters: CarSearchCriteria = { q: 'Tesla', page: 0, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(base, filters);
      expect(merged.q).toBe('Tesla');
    });

    it('should reset page to 0 when filters are applied', () => {
      const base: AvailabilitySearchParams = { ...baseAvailParams, page: 3 };
      const filters: CarSearchCriteria = { q: 'Golf', page: 2, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(base, filters);
      expect(merged.page).toBe(0);
    });

    it('should preserve core availability params', () => {
      const filters: CarSearchCriteria = { q: 'VW', page: 0, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(baseAvailParams, filters);
      expect(merged.location).toBe('Beograd');
      expect(merged.startTime).toBe('2026-06-01T09:00:00');
      expect(merged.endTime).toBe('2026-06-03T18:00:00');
    });
  });

  // ── extractFiltersFromAvailabilityParams ──────────────────────────────────

  describe('extractFiltersFromAvailabilityParams', () => {
    it('should extract q from availability params into criteria', () => {
      const availParams: AvailabilitySearchParams = {
        ...baseAvailParams,
        q: 'Tesla',
      };
      const criteria = extractFiltersFromAvailabilityParams(availParams);
      expect(criteria.q).toBe('Tesla');
    });

    it('should not include q key when it is absent', () => {
      const criteria = extractFiltersFromAvailabilityParams(baseAvailParams);
      expect(criteria.q).toBeUndefined();
    });

    it('should round-trip q through merge → extract', () => {
      const filters: CarSearchCriteria = { q: 'SUV', page: 0, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(baseAvailParams, filters);
      const extracted = extractFiltersFromAvailabilityParams(merged);
      expect(extracted.q).toBe('SUV');
    });
  });

  // ── URL parsing (q / legacy search) ──────────────────────────────────────

  describe('q URL param backward compatibility', () => {
    /**
     * This is effectively a contract test: ensure the ngOnInit parsing logic
     * (which reads `params.get('q') || params.get('search')`) is accounted for
     * in the model's helper functions.
     *
     * The actual Angular ParamMap parsing lives in car-list.component.ts; the
     * model helpers are responsible for correct q field propagation thereafter.
     */
    it('should accept q via criteria and pass it through the helpers', () => {
      const criteria: CarSearchCriteria = { q: 'Volkswagen', page: 0, size: 20 };
      const merged = mergeFiltersIntoAvailabilityParams(baseAvailParams, criteria);
      const extracted = extractFiltersFromAvailabilityParams(merged);
      expect(extracted.q).toBe('Volkswagen');
    });
  });
});
