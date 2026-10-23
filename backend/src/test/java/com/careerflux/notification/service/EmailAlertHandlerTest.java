package com.careerflux.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.notification.domain.Notification;
import com.careerflux.notification.repository.NotificationRepository;
import com.careerflux.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailAlertHandlerTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

    private final UUID notificationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Notification notification;
    private CandidateProfile profile;
    private EmailAlertHandler handler;

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        notification = new Notification();
        notification.setUser(user);
        notification.setTitle("82% match: Graduate Engineer at Acme");
        notification.setBody("Fits your Java and SQL skills.");
        profile = new CandidateProfile();
        when(notifications.findById(notificationId)).thenReturn(Optional.of(notification));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(provider.getIfAvailable()).thenReturn(sender);
        handler = new EmailAlertHandler(new ObjectMapper(), notifications, profiles, provider, "from@x.test");
    }

    @Test
    @DisplayName("an alert is emailed to the personal address and marked sent")
    void sends() {
        profile.setPersonalEmail("me@example.com");
        profile.setPersonalEmailVerifiedAt(java.time.Instant.now());
        handler.handle(notificationId.toString(), "{}");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("me@example.com");
        assertThat(sent.getValue().getSubject()).startsWith("82% match");
        assertThat(sent.getValue().getText()).contains("Fits your Java and SQL skills.");
        assertThat(notification.getEmailSentAt()).isNotNull();
    }

    @Test
    @DisplayName("a redelivered event does not send twice")
    void idempotent() {
        profile.setPersonalEmail("me@example.com");
        profile.setPersonalEmailVerifiedAt(java.time.Instant.now());
        handler.handle(notificationId.toString(), "{}");
        handler.handle(notificationId.toString(), "{}");
        verify(sender, org.mockito.Mockito.times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("no personal email means nothing is sent")
    void noAddress() {
        handler.handle(notificationId.toString(), "{}");
        verify(sender, never()).send(any(SimpleMailMessage.class));
        assertThat(notification.getEmailSentAt()).isNull();
    }

    @Test
    @DisplayName("an address that was never confirmed gets no alerts")
    void unconfirmed() {
        profile.setPersonalEmail("me@example.com");
        handler.handle(notificationId.toString(), "{}");
        verify(sender, never()).send(any(SimpleMailMessage.class));
        assertThat(notification.getEmailSentAt()).isNull();
    }

    @Test
    @DisplayName("without a mail server nothing is sent and the alert stays unsent")
    void noServer() {
        profile.setPersonalEmail("me@example.com");
        profile.setPersonalEmailVerifiedAt(java.time.Instant.now());
        when(provider.getIfAvailable()).thenReturn(null);
        handler.handle(notificationId.toString(), "{}");
        assertThat(notification.getEmailSentAt()).isNull();
    }
}
