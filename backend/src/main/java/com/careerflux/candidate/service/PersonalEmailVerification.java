package com.careerflux.candidate.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.error.BadRequestException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves a student owns the personal email they entered, so alerts are never
 * sent to an address that belongs to someone else.
 *
 * <p>Only a SHA-256 of the token is stored; the raw token exists only in the
 * emailed link. Neither the token nor the address is written to the log. A
 * failure to send is logged and does not fail the profile save: the student can
 * ask for the link again.
 */
@Service
public class PersonalEmailVerification {

    private static final Logger log = LoggerFactory.getLogger(PersonalEmailVerification.class);
    private static final long TOKEN_TTL_HOURS = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CandidateProfileRepository profileRepository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String appUrl;

    public PersonalEmailVerification(CandidateProfileRepository profileRepository,
                                     ObjectProvider<JavaMailSender> mailSender,
                                     @Value("${careerflux.mail.from:no-reply@careerflux.local}") String from,
                                     @Value("${careerflux.mail.app-url:http://localhost:5173}") String appUrl) {
        this.profileRepository = profileRepository;
        this.mailSender = mailSender;
        this.from = from;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    /**
     * Resets confirmation for the profile's current address and, when there is
     * one, emails a link. Call after the address changes.
     */
    @Transactional
    public void begin(CandidateProfile profile) {
        profile.setPersonalEmailVerifiedAt(null);
        profile.setPersonalEmailTokenHash(null);
        profile.setPersonalEmailTokenExpiresAt(null);
        if (profile.getPersonalEmail() == null) {
            return;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        profile.setPersonalEmailTokenHash(digest(token));
        profile.setPersonalEmailTokenExpiresAt(Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS));

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.info("Personal email confirmation for profile {} not sent: no mail server is configured",
                    profile.getId());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(profile.getPersonalEmail());
            message.setSubject("Confirm your email for CareerFlux job alerts");
            message.setText("Open this link to confirm this address and start receiving job alerts here:\n\n"
                    + appUrl + "/confirm-email?token=" + token
                    + "\n\nThe link works for " + TOKEN_TTL_HOURS + " hours. If you did not ask for this, "
                    + "ignore this email and no alerts will be sent.");
            sender.send(message);
        } catch (RuntimeException ex) {
            log.warn("Personal email confirmation for profile {} could not be sent: {}",
                    profile.getId(), ex.getClass().getSimpleName());
        }
    }

    /** Sends a fresh link for an address that is set but not yet confirmed. */
    @Transactional
    public void resend(UUID userId) {
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("There is no profile to confirm an email for."));
        if (profile.getPersonalEmail() == null) {
            throw new BadRequestException("Add a personal email first.");
        }
        if (profile.getPersonalEmailVerifiedAt() != null) {
            return;
        }
        begin(profile);
    }

    @Transactional
    public void confirm(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("That confirmation link is not valid.");
        }
        CandidateProfile profile = profileRepository.findByPersonalEmailTokenHash(digest(token.strip()))
                .orElseThrow(() -> new BadRequestException("That confirmation link is not valid."));
        if (profile.getPersonalEmailTokenExpiresAt() == null
                || profile.getPersonalEmailTokenExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("That confirmation link has expired. Ask for a new one from your profile.");
        }
        profile.setPersonalEmailVerifiedAt(Instant.now());
        profile.setPersonalEmailTokenHash(null);
        profile.setPersonalEmailTokenExpiresAt(null);
    }

    static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
