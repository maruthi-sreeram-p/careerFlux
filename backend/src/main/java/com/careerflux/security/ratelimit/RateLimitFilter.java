package com.careerflux.security.ratelimit;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.careerflux.common.error.ApiError;
import com.careerflux.common.error.TooManyRequestsException;
import com.careerflux.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the account-keyed request ceilings.
 *
 * <p>Runs immediately after the bearer token is resolved, so the authenticated
 * account is available to key on and the charge is made before the request
 * reaches a controller, a query or the disk.
 *
 * <p><b>Sign-in is not here.</b> Its key needs the login identity, which lives in
 * the request body, and reading a body inside a filter means buffering it and
 * handing the controller a replayed copy of its own input. That is a fair amount
 * of machinery, on every request, to serve one endpoint. Sign-in charges itself
 * in {@code AuthController} instead, before the password is checked. The policy
 * still lives in one place: both call the same {@link RateLimiter} with the same
 * rule table, and only the point of collection differs.
 *
 * <p>Requests that arrive without an authenticated account are passed straight
 * through. They are about to be refused with a 401 by the security chain, which
 * costs nothing, and charging them would mean keying on the client address,
 * which on a college network is shared by everybody on it.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /**
     * The whole table, in order. First match wins, so more specific patterns
     * come first: a shortlist lives underneath a requirement, and the two are
     * charged differently.
     */
    private static final List<Route> ROUTES = List.of(
            new Route("POST", "/api/requirements/*/shortlist", RateLimitedAction.SHORTLIST_MUTATION),
            new Route("DELETE", "/api/requirements/*/shortlist/*", RateLimitedAction.SHORTLIST_MUTATION),
            new Route("GET", "/api/requirements/*/candidates", RateLimitedAction.CANDIDATE_DISCOVERY),
            new Route("POST", "/api/requirements", RateLimitedAction.REQUIREMENT_CREATE),
            new Route("POST", "/api/candidate/resume", RateLimitedAction.RESUME_UPLOAD));

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitedAction action = actionFor(request);
        if (action == null) {
            chain.doFilter(request, response);
            return;
        }

        UUID accountId = authenticatedAccount();
        if (accountId == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            rateLimiter.check(action, accountId);
        } catch (TooManyRequestsException refused) {
            // A filter sits outside the dispatcher servlet, so the
            // @RestControllerAdvice never sees this. The body is written here in
            // the same shape that advice produces, because a client should not
            // be able to tell where in the stack a refusal came from.
            writeRefusal(request, response, refused);
            return;
        }
        chain.doFilter(request, response);
    }

    static RateLimitedAction actionFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        for (Route route : ROUTES) {
            if (route.method().equals(method) && PATHS.match(route.pattern(), path)) {
                return route.action();
            }
        }
        return null;
    }

    private static UUID authenticatedAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            return null;
        }
        return principal.getUserId();
    }

    private void writeRefusal(HttpServletRequest request, HttpServletResponse response,
                              TooManyRequestsException refused) throws IOException {
        response.setStatus(refused.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(refused.getRetryAfterSeconds()));
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(refused.getStatus().value(),
                refused.getCode(), refused.getMessage(), request.getRequestURI()));
    }

    private record Route(String method, String pattern, RateLimitedAction action) {
    }
}
