package com.careerflux.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when CareerFlux cannot tell which college a registrant belongs to.
 *
 * <p>Distinct from a plain bad request because the sign-up form reacts to it:
 * it reveals the registration-code field rather than only showing a message.
 * That needs a stable signal, and matching on prose would break the first time
 * somebody reworded it.
 */
public class InstitutionUnresolvedException extends AppException {

    public InstitutionUnresolvedException(String message) {
        super(HttpStatus.BAD_REQUEST, "INSTITUTION_UNRESOLVED", message);
    }
}
