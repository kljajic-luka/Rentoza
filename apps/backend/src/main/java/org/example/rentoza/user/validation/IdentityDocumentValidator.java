package org.example.rentoza.user.validation;

import org.springframework.stereotype.Service;

/**
 * Validator for Serbian identity documents (JMBG, PIB, IBAN).
 * 
 * <p>Implements checksum validation algorithms per Serbian Tax Administration standards.
 * 
 * <p>This service throws ValidationException with specific error messages rather than
 * returning boolean, enabling clear error reporting to users.
 */
@Service
public class IdentityDocumentValidator {

    /**
     * Validate JMBG (Jedinstveni Matični Broj Građana - Serbian personal ID).
     * 
     * <p>Format: 13 digits with modulo 11 checksum
     * <p>Algorithm:
     * <ol>
     *   <li>Extract first 12 digits</li>
     *   <li>Calculate modulo 11 checksum</li>
     *   <li>Compare with 13th digit</li>
     * </ol>
     * 
     * @param jmbg 13-digit personal ID
     * @throws ValidationException if invalid
     */
    public void validateJmbg(String jmbg) throws ValidationException {
        if (jmbg == null || !jmbg.matches("^[0-9]{13}$")) {
            throw new ValidationException("JMBG must be exactly 13 digits");
        }

        int checkDigit = Integer.parseInt(jmbg.substring(12));
        int calculated = calculateModulo11Checksum(jmbg.substring(0, 12));

        if (checkDigit != calculated) {
            throw new ValidationException("Invalid JMBG checksum");
        }
    }

    /**
     * Validate PIB (Poreski Identifikacioni Broj - Serbian tax ID).
     * 
     * <p>Format: 9 digits with modulo 11 recursive product checksum
     * <p>Algorithm (Modulo 11 recursive product - ISO 7064):
     * <ol>
     *   <li>Multiply each digit by its position (starting from 2)</li>
     *   <li>Sum the products</li>
     *   <li>Calculate modulo 11</li>
     *   <li>Subtract from 11 to get check digit</li>
     * </ol>
     * 
     * @param pib 9-digit tax ID
     * @throws ValidationException if invalid
     */
    public void validatePib(String pib) throws ValidationException {
        if (pib == null || !pib.matches("^[0-9]{9}$")) {
            throw new ValidationException("PIB must be exactly 9 digits");
        }

        int checkDigit = Integer.parseInt(pib.substring(8));
        int calculated = calculateModulo11RecursiveProduct(pib.substring(0, 8));

        if (checkDigit != calculated) {
            throw new ValidationException("Invalid PIB checksum");
        }
    }

    /**
     * Validate Serbian IBAN.
     * 
     * <p>Format: RS + 22 digits (IBAN check digit + account number)
     * 
     * @param iban Serbian IBAN
     * @throws ValidationException if invalid
     */
    public void validateIban(String iban) throws ValidationException {
        if (iban == null || !iban.matches("^RS[0-9]{22}$")) {
            throw new ValidationException("Invalid IBAN format. Expected: RS followed by 22 digits");
        }

        // Validate IBAN checksum (mod 97)
        if (!isValidIbanChecksum(iban)) {
            throw new ValidationException("Invalid IBAN checksum");
        }
    }

    /**
     * Calculate Modulo 11 checksum for JMBG.
     * Uses weights 7,6,5,4,3,2 repeating.
     */
    private int calculateModulo11Checksum(String digits) {
        int sum = 0;
        int multiplier = 7;

        for (int i = 0; i < digits.length(); i++) {
            int digit = Integer.parseInt(String.valueOf(digits.charAt(i)));
            sum += digit * multiplier;
            multiplier--;
            if (multiplier < 1) multiplier = 7;
        }

        int remainder = sum % 11;
        if (remainder == 1) {
            // Mathematically impossible for valid JMBG
            return -1; // Will never match a valid check digit
        }
        return remainder == 0 ? 0 : 11 - remainder;
    }

    /**
     * Calculate Modulo 11 recursive product checksum for PIB.
     * ISO 7064 compliant algorithm.
     */
    private int calculateModulo11RecursiveProduct(String digits) {
        int product = 10;

        for (int i = 0; i < digits.length(); i++) {
            int digit = Integer.parseInt(String.valueOf(digits.charAt(i)));
            product = (digit + product) % 10;
            if (product == 0) product = 10;
            product *= 2;
            product %= 11;
        }

        return product == 1 ? 0 : 11 - product;
    }

    /**
     * Validate IBAN checksum using mod 97 algorithm.
     */
    private boolean isValidIbanChecksum(String iban) {
        // Move first 4 chars to end and replace letters with numbers (A=10, B=11, etc.)
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();

        for (char c : rearranged.toCharArray()) {
            if (c >= '0' && c <= '9') {
                numeric.append(c);
            } else {
                numeric.append(c - 'A' + 10);
            }
        }

        // Check mod 97 == 1
        int remainder = 0;
        for (char c : numeric.toString().toCharArray()) {
            remainder = (remainder * 10 + (c - '0')) % 97;
        }

        return remainder == 1;
    }

    /**
     * Exception thrown when identity document validation fails.
     */
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
