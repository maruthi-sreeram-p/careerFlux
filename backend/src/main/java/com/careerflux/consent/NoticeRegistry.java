package com.careerflux.consent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes and serves the current notice version for each purpose.
 *
 * <p>The text comes from the classpath and is written to {@code notice_versions}
 * the first time it is needed, with its checksum. After that the stored row is
 * the version of record. If the file for a version that is already published has
 * changed, this refuses to serve it: a notice students have agreed to cannot be
 * rewritten underneath their agreement. New wording is a new version.
 *
 * <p>Published eagerly at start-up, so a mismatch stops the application instead
 * of surfacing on a student's screen, and lazily on first use, so a request that
 * arrives before start-up finishes still gets an answer.
 */
@Service
public class NoticeRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NoticeRegistry.class);

    /** Present in text that is engineering scaffolding rather than approved wording. */
    static final String PLACEHOLDER_MARKER = "ENGINEERING PLACEHOLDER";

    private final NoticeVersionRepository notices;
    private final ConsentProperties properties;
    private final TransactionTemplate ownTransaction;
    private final Map<ConsentPurpose, UUID> published = new ConcurrentHashMap<>();

    public NoticeRegistry(NoticeVersionRepository notices, ConsentProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.notices = notices;
        this.properties = properties;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void run(ApplicationArguments args) {
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            NoticeVersion notice = current(purpose);
            log.info("Notice for {} is version {}{}", purpose, notice.getVersion(),
                    notice.isPlaceholder() ? " (engineering placeholder, not approved wording)" : "");
        }
    }

    /** The version of the notice a student must accept for this purpose now. */
    public NoticeVersion current(ConsentPurpose purpose) {
        UUID known = published.get(purpose);
        if (known != null) {
            NoticeVersion notice = notices.findById(known).orElse(null);
            if (notice != null) {
                return notice;
            }
        }
        NoticeVersion notice = publish(purpose);
        published.put(purpose, notice.getId());
        return notice;
    }

    private NoticeVersion publish(ConsentPurpose purpose) {
        String version = properties.noticeVersionFor(purpose);
        String body = load(purpose, version);
        String checksum = NoticeVersion.checksumOf(body);

        NoticeVersion existing = notices.findByKindAndVersion(purpose, version).orElse(null);
        if (existing != null) {
            if (!existing.getChecksum().equals(checksum)) {
                throw new IllegalStateException("The " + purpose + " notice version '" + version
                        + "' has different text from when it was published. Publish the new text as a "
                        + "new version instead of editing a published one.");
            }
            return existing;
        }
        try {
            return ownTransaction.execute(status -> notices.saveAndFlush(NoticeVersion.publish(
                    purpose, version, body, body.contains(PLACEHOLDER_MARKER), Instant.now())));
        } catch (DataIntegrityViolationException raced) {
            // Another request published the same version a moment earlier.
            return notices.findByKindAndVersion(purpose, version).orElseThrow(() -> raced);
        }
    }

    private static String load(ConsentPurpose purpose, String version) {
        if (!version.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("Notice version names may use letters, digits, '.', '_' and '-' only.");
        }
        ClassPathResource resource = new ClassPathResource("notices/" + purpose.slug() + "/" + version + ".md");
        if (!resource.exists()) {
            throw new IllegalStateException("No notice text for " + purpose + " version '" + version + "'.");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the notice for " + purpose + ".", e);
        }
    }
}
