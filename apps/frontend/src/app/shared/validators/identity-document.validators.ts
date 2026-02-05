import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * Identity Document Validators for Serbian registration requirements.
 *
 * SECURITY NOTE: These validators are for UX only (immediate feedback).
 * Backend performs authoritative validation - do NOT rely on frontend validation alone.
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 207-321 for algorithms
 */

// ═══════════════════════════════════════════════════════════════════════════
// AGE VALIDATION
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Validates that the user is at least the specified age (default: 21).
 * Used for dateOfBirth field validation.
 *
 * @param minAge Minimum required age (default: 21)
 * @returns ValidatorFn that returns { minAge: true } if validation fails
 *
 * @example
 * ```typescript
 * dateOfBirth: ['', [Validators.required, minAgeValidator(21)]]
 * ```
 */
export function minAgeValidator(minAge: number = 21): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    if (!control.value) {
      return null; // Let required validator handle empty
    }

    const dob = new Date(control.value);
    if (isNaN(dob.getTime())) {
      return { invalidDate: true };
    }

    const today = new Date();
    let age = today.getFullYear() - dob.getFullYear();
    const monthDiff = today.getMonth() - dob.getMonth();

    // Adjust age if birthday hasn't occurred this year
    if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < dob.getDate())) {
      age--;
    }

    return age >= minAge ? null : { minAge: { required: minAge, actual: age } };
  };
}

/**
 * Validates that the date is in the past (not future).
 *
 * @returns ValidatorFn that returns { futureDate: true } if date is in the future
 */
export function pastDateValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    if (!control.value) {
      return null;
    }

    const date = new Date(control.value);
    if (isNaN(date.getTime())) {
      return { invalidDate: true };
    }

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    return date < today ? null : { futureDate: true };
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// JMBG VALIDATION (Serbian Personal ID - 13 digits)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Validates Serbian JMBG (Jedinstveni Matični Broj Građana) - 13-digit personal ID.
 *
 * Algorithm (Modulo 11):
 * 1. Extract 12 digits (d1-d12) and checksum (d13)
 * 2. Calculate: sum = 7*(d1+d7) + 6*(d2+d8) + 5*(d3+d9) + 4*(d4+d10) + 3*(d5+d11) + 2*(d6+d12)
 * 3. remainder = sum % 11
 * 4. If remainder == 0, checksum = 0
 * 5. If remainder == 1, JMBG is invalid
 * 6. Otherwise, checksum = 11 - remainder
 * 7. Compare calculated checksum with d13
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 266-279
 *
 * @returns ValidatorFn that returns { jmbgInvalid: true } if validation fails
 *
 * @example
 * ```typescript
 * jmbg: ['', [Validators.required, jmbgValidator()]]
 * ```
 */
export function jmbgValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;

    if (!value) {
      return null; // Let required validator handle empty
    }

    // Must be exactly 13 digits
    if (!/^\d{13}$/.test(value)) {
      return { jmbgFormat: true };
    }

    const digits = value.split('').map(Number);

    // Modulo 11 checksum calculation
    // Weights: 7, 6, 5, 4, 3, 2, 7, 6, 5, 4, 3, 2 for positions 0-11
    const weights = [7, 6, 5, 4, 3, 2, 7, 6, 5, 4, 3, 2];
    let sum = 0;

    for (let i = 0; i < 12; i++) {
      sum += digits[i] * weights[i];
    }

    const remainder = sum % 11;
    let expectedChecksum: number;

    if (remainder === 0) {
      expectedChecksum = 0;
    } else if (remainder === 1) {
      // Invalid JMBG - no valid checksum possible
      return { jmbgInvalid: true };
    } else {
      expectedChecksum = 11 - remainder;
    }

    const actualChecksum = digits[12];

    if (expectedChecksum !== actualChecksum) {
      return { jmbgInvalid: true };
    }

    return null;
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// PIB VALIDATION (Serbian Tax ID - 9 digits)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Validates Serbian PIB (Poreski Identifikacioni Broj) - 9-digit tax ID.
 *
 * Algorithm (Modulo 11 with recursive product):
 * 1. Start with sum = 10
 * 2. For each digit d1-d8:
 *    a. sum = (sum + digit) % 10
 *    b. if sum == 0, sum = 10
 *    c. sum = (sum * 2) % 11
 * 3. checksum = (11 - sum) % 10
 * 4. Compare calculated checksum with d9
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 281-293
 *
 * @returns ValidatorFn that returns { pibInvalid: true } if validation fails
 *
 * @example
 * ```typescript
 * pib: ['', [Validators.required, pibValidator()]]
 * ```
 */
export function pibValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;

    if (!value) {
      return null; // Let required validator handle empty
    }

    // Must be exactly 9 digits
    if (!/^\d{9}$/.test(value)) {
      return { pibFormat: true };
    }

    const digits = value.split('').map(Number);

    // Modulo 11 recursive product algorithm
    let sum = 10;

    for (let i = 0; i < 8; i++) {
      sum = (sum + digits[i]) % 10;
      if (sum === 0) {
        sum = 10;
      }
      sum = (sum * 2) % 11;
    }

    const expectedChecksum = (11 - sum) % 10;
    const actualChecksum = digits[8];

    if (expectedChecksum !== actualChecksum) {
      return { pibInvalid: true };
    }

    return null;
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// IBAN VALIDATION (Serbian Bank Account)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Validates Serbian IBAN format (RS + 22 digits) with mod 97 checksum.
 *
 * Algorithm (ISO 7064 Mod 97-10):
 * 1. Move first 4 characters (RS + 2-digit check) to end
 * 2. Convert letters to numbers (A=10, B=11, ..., Z=35)
 * 3. Calculate mod 97 of the resulting number
 * 4. Valid if result equals 1
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 295-315
 *
 * @returns ValidatorFn that returns { ibanInvalid: true } if validation fails
 *
 * @example
 * ```typescript
 * bankAccountNumber: ['', [Validators.required, ibanValidator()]]
 * ```
 */
export function ibanValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;

    if (!value) {
      return null; // Let required validator handle empty
    }

    // Remove spaces and convert to uppercase
    const iban = value.replace(/\s/g, '').toUpperCase();

    // Serbian IBAN must be RS + 22 digits (24 total)
    if (!/^RS\d{22}$/.test(iban)) {
      return { ibanFormat: true };
    }

    // ISO 7064 Mod 97-10 validation
    // Move first 4 chars to end, convert letters to numbers
    const rearranged = iban.slice(4) + iban.slice(0, 4);

    // Convert letters to numbers (R=27, S=28)
    let numericString = '';
    for (const char of rearranged) {
      if (char >= 'A' && char <= 'Z') {
        numericString += (char.charCodeAt(0) - 55).toString();
      } else {
        numericString += char;
      }
    }

    // Calculate mod 97 using string-based arithmetic (avoid BigInt for compatibility)
    let remainder = 0;
    for (const digit of numericString) {
      remainder = (remainder * 10 + parseInt(digit, 10)) % 97;
    }

    if (remainder !== 1) {
      return { ibanInvalid: true };
    }

    return null;
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// PHONE VALIDATION
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Validates phone number format (8-15 digits).
 *
 * @returns ValidatorFn that returns { phoneInvalid: true } if validation fails
 */
export function phoneValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;

    if (!value) {
      return null;
    }

    // Remove common separators for validation
    const cleaned = value.replace(/[\s\-\(\)]/g, '');

    if (!/^\d{8,15}$/.test(cleaned)) {
      return { phoneInvalid: true };
    }

    return null;
  };
}

// ═══════════════════════════════════════════════════════════════════════════
// UTILITY: Format Masking for Display
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Masks a JMBG for display (shows first 4 and last 3 digits).
 * Example: 1234567890123 → 1234*****123
 */
export function maskJmbg(jmbg: string): string {
  if (!jmbg || jmbg.length !== 13) return jmbg;
  return `${jmbg.slice(0, 4)}*****${jmbg.slice(-3)}`;
}

/**
 * Masks a PIB for display (shows first 3 and last 2 digits).
 * Example: 123456789 → 123****89
 */
export function maskPib(pib: string): string {
  if (!pib || pib.length !== 9) return pib;
  return `${pib.slice(0, 3)}****${pib.slice(-2)}`;
}

/**
 * Masks an IBAN for display (shows RS + first 2 digits and last 4).
 * Example: RS35000000000000000000000 → RS35 **** **** **** 0000
 */
export function maskIban(iban: string): string {
  if (!iban || iban.length < 10) return iban;
  const clean = iban.replace(/\s/g, '');
  return `${clean.slice(0, 4)} **** **** **** ${clean.slice(-4)}`;
}
