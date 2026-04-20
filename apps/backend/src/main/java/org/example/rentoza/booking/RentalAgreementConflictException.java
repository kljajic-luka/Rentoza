package org.example.rentoza.booking;

public class RentalAgreementConflictException extends IllegalStateException {

    private final String code;

    public RentalAgreementConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}