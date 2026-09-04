package com.careerflux.common.logging;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request an identifier that appears on its log lines.
 *
 * <p>Ordered first so the identifier is already set when authentication runs:
 * a rejected request is exactly the one an operator wants to trace, and it would
 * otherwise be the one with no id.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured so a request crossing from
 * another service keeps one thread of identity — but it is sanitised first. The
 * header is attacker-controlled and goes straight into log output, where a
 * newline would let a caller forge whole log lines; anything that is not a plain
 * short token is replaced with one of ours rather than trusted.
 *
 * <p>The identifier is echoed back on the response so a caller reporting a
 * problem can quote the exact value to search for.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** Letters, digits, dash and underscore only, and short. No newlines, ever. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = sanitise(request.getHeader(HEADER));
        if (id == null) {
            id = CorrelationId.generate();
        }
        response.setHeader(HEADER, id);

        String correlationId = id;
        try {
            CorrelationId.with(correlationId, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException | RuntimeException ex) {
                    // Rethrown below; wrapped only to cross the lambda boundary.
                    throw new FilterFailure(ex);
                }
            });
        } catch (FilterFailure wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof ServletException servlet) {
                throw servlet;
            }
            throw (RuntimeException) cause;
        }
    }

    /** Accepts a caller's identifier only if it is a plain token; otherwise null. */
    private String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return candidate;
    }

    /** Carries a checked exception out of the lambda without changing its type. */
    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Throwable cause) {
            super(cause);
        }
    }
}
