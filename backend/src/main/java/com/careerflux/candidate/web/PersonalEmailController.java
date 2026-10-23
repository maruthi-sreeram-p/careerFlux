package com.careerflux.candidate.web;

import com.careerflux.candidate.service.PersonalEmailVerification;
import com.careerflux.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Confirming and re-requesting the link for a student's personal alert email. */
@RestController
@Tag(name = "Personal email")
public class PersonalEmailController {

    private final PersonalEmailVerification verification;
    private final CurrentUser currentUser;

    public PersonalEmailController(PersonalEmailVerification verification, CurrentUser currentUser) {
        this.verification = verification;
        this.currentUser = currentUser;
    }

    /** Public on purpose: the emailed link is opened without a session, and the token is the proof. */
    @PostMapping("/api/public/personal-email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirm a personal email from the emailed link")
    public void confirm(@Valid @RequestBody ConfirmRequest request) {
        verification.confirm(request.token());
    }

    @PostMapping("/api/candidate/personal-email/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SELF_PROFILE_MANAGE')")
    @Operation(summary = "Send the confirmation link again")
    public void resend() {
        verification.resend(currentUser.requireId());
    }

    public record ConfirmRequest(@NotBlank @Size(max = 200) String token) {
    }
}
