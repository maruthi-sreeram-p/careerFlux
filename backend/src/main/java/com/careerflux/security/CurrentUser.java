package com.careerflux.security;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.common.error.ForbiddenException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single place that answers "who is calling?". Services depend on this
 * rather than reaching into the security context themselves, which keeps
 * candidate data isolation enforceable in one spot.
 */
@Component
public class CurrentUser {

    public Optional<AuthenticatedUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public AuthenticatedUser require() {
        return find().orElseThrow(() -> new ForbiddenException("Sign in to continue."));
    }

    public UUID requireId() {
        return require().getUserId();
    }

    public String describe() {
        return find().map(AuthenticatedUser::getEmail).orElse("system");
    }
}
