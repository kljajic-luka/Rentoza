import { TestBed } from '@angular/core/testing';
import { TimeFormatPipe } from './time-format.pipe';

describe('TimeFormatPipe', () => {
  let pipe: TimeFormatPipe;

  beforeEach(() => {
    pipe = new TimeFormatPipe();
  });

  describe('relative format', () => {
    it('should return "Just now" for timestamps less than 1 minute ago', () => {
      const now = new Date();
      expect(pipe.transform(now.toISOString(), 'relative')).toBe('Just now');
    });

    it('should return "Xm" for timestamps less than 1 hour ago', () => {
      const date = new Date(Date.now() - 5 * 60 * 1000); // 5 minutes ago
      expect(pipe.transform(date.toISOString(), 'relative')).toBe('5m');
    });

    it('should return "Xh" for timestamps less than 1 day ago', () => {
      const date = new Date(Date.now() - 3 * 60 * 60 * 1000); // 3 hours ago
      expect(pipe.transform(date.toISOString(), 'relative')).toBe('3h');
    });

    it('should return "Xd" for timestamps less than 1 week ago', () => {
      const date = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000); // 3 days ago
      expect(pipe.transform(date.toISOString(), 'relative')).toBe('3d');
    });

    it('should return formatted date for timestamps more than 1 week ago', () => {
      const date = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000); // 14 days ago
      const result = pipe.transform(date.toISOString(), 'relative');
      // Should contain month abbreviation
      expect(result).toMatch(/[A-Z][a-z]+ \d+/);
    });
  });

  describe('time format', () => {
    it('should return formatted time HH:MM AM/PM', () => {
      const date = new Date('2025-12-08T14:30:00');
      const result = pipe.transform(date.toISOString(), 'time');
      // Should match pattern like "2:30 PM"
      expect(result).toMatch(/\d{1,2}:\d{2} (AM|PM)/);
    });
  });

  describe('full format', () => {
    it('should return "Today HH:MM" for today', () => {
      const now = new Date();
      now.setHours(14, 30, 0, 0);
      const result = pipe.transform(now.toISOString(), 'full');
      expect(result).toContain('Today');
    });

    it('should return "Yesterday HH:MM" for yesterday', () => {
      const yesterday = new Date();
      yesterday.setDate(yesterday.getDate() - 1);
      yesterday.setHours(14, 30, 0, 0);
      const result = pipe.transform(yesterday.toISOString(), 'full');
      expect(result).toContain('Yesterday');
    });
  });

  describe('edge cases', () => {
    it('should return empty string for undefined input', () => {
      expect(pipe.transform(undefined)).toBe('');
    });

    it('should return empty string for invalid date', () => {
      expect(pipe.transform('invalid-date')).toBe('');
    });

    it('should accept Date object directly', () => {
      const date = new Date();
      expect(pipe.transform(date, 'relative')).toBe('Just now');
    });
  });
});
