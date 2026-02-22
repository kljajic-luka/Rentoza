import { Pipe, PipeTransform } from '@angular/core';

/**
 * Pipe to check password strength criteria for visual feedback.
 *
 * Usage: {{ passwordValue | passwordCheck:'uppercase' }}
 * Returns true if the criteria is met.
 */
@Pipe({
  name: 'passwordCheck',
  standalone: true,
  pure: true,
})
export class PasswordCheckPipe implements PipeTransform {
  transform(value: string, check: 'uppercase' | 'lowercase' | 'digit' | 'special'): boolean {
    if (!value) return false;

    switch (check) {
      case 'uppercase':
        return /[A-Z]/.test(value);
      case 'lowercase':
        return /[a-z]/.test(value);
      case 'digit':
        return /[0-9]/.test(value);
      case 'special':
        return /[^A-Za-z0-9]/.test(value);
      default:
        return false;
    }
  }
}