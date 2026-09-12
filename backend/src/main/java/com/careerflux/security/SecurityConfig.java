package com.careerflux.security;

import java.util.List;

import com.careerflux.common.error.ApiError;
import com.careerflux.config.CareerFluxProperties;
import com.careerflux.config.DeploymentProfiles;
import com.careerflux.security.ratelimit.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authorization is layered on purpose.
 *
 * <p>The URL rules below are a coarse first gate: they keep a student out of the
 * platform console entirely. They are not the real protection. Every endpoint
 * that touches somebody's data additionally carries a {@code @PreAuthorize} for
 * the capability and calls {@link com.careerflux.security.access.AccessGuard}
 * for the record itself, because a path prefix cannot answer "is this student in
 * your department?".
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final CareerFluxProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Whether to publish the developer tooling.
     *
     * <p>Decided once, from the active profiles, rather than left permanently
     * open, and only for a profile that is explicitly {@code dev} or
     * {@code test}. An unnamed profile used to count as {@code dev} and open
     * both; it no longer does, and the application now refuses to start without
     * a profile at all (see {@link com.careerflux.config.DeploymentProfileGuard}).
     */
    private final boolean developmentTooling;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          CareerFluxProperties properties,
                          ObjectMapper objectMapper,
                          Environment environment) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.developmentTooling = DeploymentProfiles.isDevelopment(environment);
        if (!developmentTooling) {
            log.info("Developer tooling is not published: the H2 console and API documentation "
                    + "endpoints require authentication under the active profiles.");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")))
                .authorizeHttpRequests(auth -> {
                    // The developer conveniences. Both describe or expose the
                    // running system, and neither was profile-gated: a
                    // deployment that forgot to name a profile fell back to
                    // `dev` and served an open database console.
                    if (developmentTooling) {
                        auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**").permitAll();
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/public/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Everything else the actuator publishes describes the
                        // running system (metrics today, whatever is exposed
                        // tomorrow) and is the platform operator's alone. It used
                        // to fall through to "anyone signed in".
                        .requestMatchers("/actuator/**").hasRole("PLATFORM_ADMIN")
                        // Coarse gates only. Anything finer is a @PreAuthorize on
                        // the method, because a URL prefix cannot express "this
                        // student, in your department" — and that is the check
                        // that actually matters in a multi-tenant system.
                        .requestMatchers("/api/platform/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("PLATFORM_ADMIN")
                        // The source registry, read side included. Source
                        // governance is the platform operator's, and the policy
                        // records name the staff who reviewed each source.
                        .requestMatchers("/api/sources/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/api/institution/**").hasAnyRole(
                                "COLLEGE_ADMIN", "PLACEMENT_OFFICER", "PLACEMENT_COORDINATOR")
                        .anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, 401, "UNAUTHENTICATED", "Sign in to continue.", request.getRequestURI()))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, 403, "FORBIDDEN", "You do not have access to this resource.",
                                        request.getRequestURI())))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // After the token is resolved, so the ceiling can be charged to
                // an account rather than to whatever address a college happens
                // to share, and before any controller, query or disk write.
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String code,
                            String message, String path) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(status, code, message, path));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.security().cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT, "X-Requested-With"));
        configuration.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** BCrypt with cost 12: resumes and career history are sensitive enough to justify it. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
