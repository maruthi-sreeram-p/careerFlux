package com.careerflux.notification.service;

import java.time.Instant;
import java.util.UUID;

import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.ingestion.event.PipelineEventHandler;
import com.careerflux.ingestion.event.PipelineTopics;
import com.careerflux.notification.domain.Notification;
import com.careerflux.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emails a match alert to the student's personal address, when they gave one.
 *
 * <p>Entering and confirming the address is the opt-in; clearing it is the opt-out. Without a
 * configured mail server nothing is sent, and that is logged rather than treated
 * as a failure: in-app alerts are unaffected.
 *
 * <p>Idempotent: {@code email_sent_at} is set on success, so a redelivered
 * event never sends the same alert twice. A send failure is rethrown so the
 * event is retried.
 */
@Component
public class EmailAlertHandler implements PipelineEventHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertHandler.class);

    private final ObjectMapper objectMapper;
    private final NotificationRepository notificationRepository;
    private final CandidateProfileRepository profileRepository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    public EmailAlertHandler(ObjectMapper objectMapper,
                             NotificationRepository notificationRepository,
                             CandidateProfileRepository profileRepository,
                             ObjectProvider<JavaMailSender> mailSender,
                             @Value("${careerflux.mail.from:no-reply@careerflux.local}") String from) {
        this.objectMapper = objectMapper;
        this.notificationRepository = notificationRepository;
        this.profileRepository = profileRepository;
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String topic() {
        return PipelineTopics.NOTIFICATION_CREATED;
    }

    @Override
    @Transactional
    public void handle(String eventKey, String payload) {
        UUID notificationId = readId(eventKey, payload);
        if (notificationId == null) {
            return;
        }
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || notification.getEmailSentAt() != null) {
            return;
        }
        // Only an address its owner confirmed: anything else may belong to someone else.
        String address = profileRepository.findByUserId(notification.getUser().getId())
                .filter(profile -> profile.getPersonalEmailVerifiedAt() != null)
                .map(profile -> profile.getPersonalEmail())
                .orElse(null);
        if (address == null || address.isBlank()) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.info("Notification {} not emailed: no mail server is configured", notificationId);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(address);
        message.setSubject(notification.getTitle());
        String content = notification.getBody() == null ? notification.getTitle() : notification.getBody();
        message.setText(content
                + "\n\nOpen CareerFlux to review and apply. To stop these emails, remove your personal "
                + "email from your profile.");
        sender.send(message);
        notification.setEmailSentAt(Instant.now());
        log.info("Notification {} emailed", notificationId);
    }

    private UUID readId(String eventKey, String payload) {
        try {
            return UUID.fromString(eventKey);
        } catch (IllegalArgumentException ignored) {
            // Fall back to the payload when the key is not the notification id.
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String value = node.path("notificationId").asText(null);
            return value == null ? null : UUID.fromString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Could not read a notification id from event {}: {}", eventKey, ex.getMessage());
            return null;
        }
    }
}
