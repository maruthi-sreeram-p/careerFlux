package com.careerflux.common.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Raised when an operation would violate the source access policy, for example
 * activating a source whose robots.txt disallows the endpoint, or one that
 * requires authentication CareerFlux is not permitted to supply.
 */
public class SourcePolicyException extends AppException {

    private final List<String> blockers;

    public SourcePolicyException(String message, List<String> blockers) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_POLICY_VIOLATION", message);
        this.blockers = List.copyOf(blockers);
    }

    public List<String> getBlockers() {
        return blockers;
    }
}
