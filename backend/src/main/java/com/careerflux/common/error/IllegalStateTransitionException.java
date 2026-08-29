package com.careerflux.common.error;

import org.springframework.http.HttpStatus;

public class IllegalStateTransitionException extends AppException {

    public IllegalStateTransitionException(String message) {
        super(HttpStatus.CONFLICT, "ILLEGAL_STATE_TRANSITION", message);
    }
}
