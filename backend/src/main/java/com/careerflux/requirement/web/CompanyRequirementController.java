package com.careerflux.requirement.web;

import java.util.UUID;

import com.careerflux.requirement.dto.RequirementDtos.CreateRequirement;
import com.careerflux.requirement.dto.RequirementDtos.RequirementPage;
import com.careerflux.requirement.dto.RequirementDtos.RequirementView;
import com.careerflux.requirement.dto.RequirementDtos.UpdateRequirement;
import com.careerflux.requirement.service.CompanyRequirementService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a company asked the placement office to find.
 *
 * <p>The {@code @PreAuthorize} annotations are the coarse gate — may this role
 * do this kind of thing at all? The service applies the fine one: which college
 * the requirement belongs to, and which departments a coordinator covers. Both
 * are needed. The annotation alone would let any coordinator in any college
 * read any requirement by id.
 *
 * <p>Reading and writing are separated on purpose. A coordinator holds
 * {@code PLACEMENT_DRIVE_VIEW} and works their department against a
 * requirement; only a placement officer holds {@code PLACEMENT_DRIVE_MANAGE}
 * and can create or publish one.
 */
@RestController
@RequestMapping("/api/requirements")
@Tag(name = "Company requirements")
public class CompanyRequirementController {

    private final CompanyRequirementService service;

    public CompanyRequirementController(CompanyRequirementService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Requirements this caller may see, newest first")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_VIEW')")
    public RequirementPage list(@RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One requirement, or not-found if it is not the caller's to see")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_VIEW')")
    public RequirementView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a company's hiring requirement. Always created as a draft.")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_MANAGE')")
    public RequirementView create(@Valid @RequestBody CreateRequirement request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change what is named and leave the rest, including status")
    @PreAuthorize("hasAuthority('PLACEMENT_DRIVE_MANAGE')")
    public RequirementView update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateRequirement request) {
        return service.update(id, request);
    }
}
