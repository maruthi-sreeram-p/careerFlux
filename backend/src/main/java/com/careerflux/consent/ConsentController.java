package com.careerflux.consent;

import java.util.UUID;

import com.careerflux.common.error.BadRequestException;
import com.careerflux.consent.ConsentService.ConsentOverview;
import com.careerflux.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in student's own consent.
 *
 * <p>No route takes a user id. Whose consent is recorded comes from the session
 * alone, so a request cannot name somebody else, and staff, who do not hold
 * {@code SELF_PROFILE_MANAGE}, cannot reach this controller at all.
 */
@RestController
@RequestMapping("/api/consents")
@Tag(name = "Consent")
@PreAuthorize("hasAuthority('SELF_PROFILE_MANAGE')")
public class ConsentController {

    private final ConsentService consents;
    private final CurrentUser currentUser;

    public ConsentController(ConsentService consents, CurrentUser currentUser) {
        this.consents = consents;
        this.currentUser = currentUser;
    }

    public record AcceptRequest(UUID noticeVersionId, String source) {
    }

    public record WithdrawRequest(String source) {
    }

    @GetMapping
    @Operation(summary = "Current notices, what you have agreed to, and your full consent history")
    public ConsentOverview overview() {
        return consents.overview(currentUser.requireId());
    }

    @PostMapping("/{purpose}/accept")
    @Operation(summary = "Agree to the current notice for one purpose")
    public ConsentOverview accept(@PathVariable String purpose, @RequestBody AcceptRequest request) {
        return consents.accept(currentUser.requireId(), purposeOf(purpose),
                request == null ? null : request.noticeVersionId(),
                ConsentSource.parseOrSettings(request == null ? null : request.source()));
    }

    @PostMapping("/{purpose}/withdraw")
    @Operation(summary = "Withdraw your agreement for one purpose; nothing already recorded is changed")
    public ConsentOverview withdraw(@PathVariable String purpose,
                                    @RequestBody(required = false) WithdrawRequest request) {
        return consents.withdraw(currentUser.requireId(), purposeOf(purpose),
                ConsentSource.parseOrSettings(request == null ? null : request.source()));
    }

    private static ConsentPurpose purposeOf(String raw) {
        ConsentPurpose purpose = ConsentPurpose.parse(raw);
        if (purpose == null) {
            throw new BadRequestException("Unknown consent purpose.");
        }
        return purpose;
    }
}
