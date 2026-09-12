package com.careerflux.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the bearer token on every request.
 *
 * <p>The user record is re-read from the database rather than trusted from the
 * token body, so everything that ends a session takes effect on the next request
 * instead of at token expiry:
 *
 * <ul>
 *   <li>a disabled account;
 *   <li>an account whose college has been suspended;
 *   <li>a token issued before the account's session watermark, which is how a
 *       password reset or change ends every earlier session.
 * </ul>
 *
 * <p>The role comes from that record as well. The token's {@code role} claim is
 * never read here, so a token cannot carry a role its owner does not have, and a
 * role changed in the database applies at once.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        JwtService.ParsedToken parsed = jwtService.parse(header.substring(PREFIX.length()).trim(), false);
        if (parsed != null) {
            User user = userRepository.findById(parsed.userId()).orElse(null);
            if (user != null && user.canHoldSession() && user.acceptsSessionIssuedAt(parsed.issuedAt())) {
                AuthenticatedUser principal = new AuthenticatedUser(user);
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
