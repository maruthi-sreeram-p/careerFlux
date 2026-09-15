package com.careerflux.bootstrap;

import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates the first administrator account, and only when the operator explicitly
 * asks for one by setting both {@code CAREERFLUX_ADMIN_EMAIL} and
 * {@code CAREERFLUX_ADMIN_PASSWORD}.
 *
 * <p>There is deliberately no default admin with a well-known password. A
 * fixed credential that ships in the source is a backdoor, however convenient it
 * would make the first run.
 */
@Component
@Order(2)
public class AdminAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountSeeder.class);
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public AdminAccountSeeder(UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              @Value("${CAREERFLUX_ADMIN_EMAIL:}") String adminEmail,
                              @Value("${CAREERFLUX_ADMIN_PASSWORD:}") String adminPassword,
                              @Value("${CAREERFLUX_ADMIN_NAME:CareerFlux Operator}") String adminName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(adminEmail) || !StringUtils.hasText(adminPassword)) {
            if (userRepository.countByRole(UserRole.PORTAL_ADMIN) == 0) {
                log.info("No administrator account exists. Set CAREERFLUX_ADMIN_EMAIL and "
                        + "CAREERFLUX_ADMIN_PASSWORD to create one on the next start.");
            }
            return;
        }
        if (adminPassword.length() < MIN_PASSWORD_LENGTH) {
            log.error("CAREERFLUX_ADMIN_PASSWORD is shorter than {} characters. No account was created.",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        String email = adminEmail.strip().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return;
        }

        User admin = new User();
        admin.setEmail(email);
        admin.setFullName(adminName);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(UserRole.PORTAL_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerified(true);
        userRepository.save(admin);
        // By id. The address is in the environment the operator set; the log
        // has no need of a copy.
        log.info("Created the administrator account {}", admin.getId());
    }
}
