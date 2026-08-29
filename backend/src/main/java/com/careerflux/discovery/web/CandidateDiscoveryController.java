package com.careerflux.discovery.web;

import java.util.UUID;

import com.careerflux.discovery.dto.DiscoveryDtos.CandidatePage;
import com.careerflux.discovery.service.CandidateDiscoveryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Students who are technically relevant to a company's requirement.
 *
 * <p>Hung off the requirement rather than standing alone, because the
 * requirement is what authorizes the search: it names the departments and batch
 * the company will consider, and it belongs to exactly one college.
 *
 * <p>The client sends filters. It never sends an institution, a department set
 * or a list of candidate ids — the server derives every one of those from the
 * authenticated caller and the requirement they are allowed to open. A filter
 * can only narrow what the server already decided to return.
 *
 * <p>{@code STUDENT_READ_SCOPED} is the gate, which is the same permission the
 * student directory requires. That is deliberate: discovery shows a subset of
 * what the directory already shows, plus scores. It grants no new access to
 * student data, and specifically no resume access.
 */
@RestController
@RequestMapping("/api/requirements/{requirementId}/candidates")
@Tag(name = "Candidate discovery")
public class CandidateDiscoveryController {

    private final CandidateDiscoveryService discovery;

    public CandidateDiscoveryController(CandidateDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping
    @Operation(summary = "Students relevant to this requirement, with eligibility shown separately")
    @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")
    public CandidatePage discover(@PathVariable UUID requirementId,
                                  @RequestParam(required = false) String eligibility,
                                  @RequestParam(required = false) Integer minScore,
                                  @RequestParam(required = false) Boolean shortlisted,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "25") int size) {
        return discovery.discover(requirementId, eligibility, minScore, shortlisted,
                sort, page, size);
    }
}
