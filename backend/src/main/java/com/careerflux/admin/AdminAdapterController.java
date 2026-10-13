package com.careerflux.admin;

import java.time.Instant;
import java.util.List;

import com.careerflux.security.CurrentUser;
import com.careerflux.source.adapter.RawJobPosting;
import com.careerflux.source.domain.AdapterDefinition;
import com.careerflux.source.service.AdapterDefinitionService;
import com.careerflux.source.service.AdapterDefinitionService.Definition;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adapters a portal admin defines without code.
 *
 * <p>A definition describes a public JSON feed: where it is and where each field
 * sits in it. Defining one lets sources be registered against it; it does not
 * activate anything. Every source using it still passes classification, the
 * robots.txt check and a named terms review.
 */
@RestController
@RequestMapping("/api/admin/adapters")
@Tag(name = "Admin: adapters")
public class AdminAdapterController {

    private final AdapterDefinitionService service;
    private final CurrentUser currentUser;

    public AdminAdapterController(AdapterDefinitionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Every admin-defined adapter, enabled or not")
    public List<AdapterDefinitionView> list() {
        return service.list().stream().map(AdapterDefinitionView::of).toList();
    }

    @PostMapping
    @Operation(summary = "Define an adapter for a public JSON job feed")
    public ResponseEntity<AdapterDefinitionView> create(@RequestBody Definition request) {
        AdapterDefinition saved = service.create(request, currentUser.describe());
        return ResponseEntity.status(HttpStatus.CREATED).body(AdapterDefinitionView.of(saved));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Change a definition. Its key stays: sources refer to it by key.")
    public AdapterDefinitionView update(@PathVariable String key, @RequestBody Definition request) {
        return AdapterDefinitionView.of(service.update(key, request, currentUser.describe()));
    }

    @PostMapping("/{key}/enable")
    @Operation(summary = "Switch a definition back on")
    public AdapterDefinitionView enable(@PathVariable String key) {
        return AdapterDefinitionView.of(service.setEnabled(key, true, currentUser.describe()));
    }

    @PostMapping("/{key}/disable")
    @Operation(summary = "Switch a definition off; its sources stop syncing")
    public AdapterDefinitionView disable(@PathVariable String key) {
        return AdapterDefinitionView.of(service.setEnabled(key, false, currentUser.describe()));
    }

    @PostMapping("/preview")
    @Operation(summary = "Fetch once through an unsaved definition, obeying robots.txt, and show what it reads")
    public PreviewView preview(@RequestBody PreviewRequest request) {
        var preview = service.preview(request.definition(), request.token(), currentUser.describe());
        return new PreviewView(preview.fetched(), preview.endpoint(),
                preview.robotsStatus() == null ? null : preview.robotsStatus().name(),
                preview.message(), preview.total(), preview.unreadable(),
                preview.postings().stream().map(PreviewPosting::of).toList());
    }

    public record PreviewRequest(Definition definition, String token) {
    }

    public record PreviewView(boolean fetched, String endpoint, String robotsStatus, String message,
                              int total, int unreadable, List<PreviewPosting> postings) {
    }

    public record PreviewPosting(String externalId, String title, String location, String employmentType,
                                 String department, String applyUrl, Instant postedAt, boolean hasDescription) {

        static PreviewPosting of(RawJobPosting posting) {
            return new PreviewPosting(posting.externalId(), posting.title(), posting.locationText(),
                    posting.employmentTypeText(), posting.departmentText(), posting.applyUrl(),
                    posting.postedAt(), posting.descriptionHtml() != null && !posting.descriptionHtml().isBlank());
        }
    }

    public record AdapterDefinitionView(
            String adapterKey,
            String displayName,
            String description,
            String documentationUrl,
            String endpointTemplate,
            String itemsPath,
            String idPath,
            String titlePath,
            String locationPath,
            String descriptionPath,
            String applyUrlPath,
            String applyUrlTemplate,
            String postedAtPath,
            String employmentTypePath,
            String departmentPath,
            boolean enabled,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt) {

        static AdapterDefinitionView of(AdapterDefinition d) {
            return new AdapterDefinitionView(d.getAdapterKey(), d.getDisplayName(), d.getDescription(),
                    d.getDocumentationUrl(), d.getEndpointTemplate(), d.getItemsPath(), d.getIdPath(),
                    d.getTitlePath(), d.getLocationPath(), d.getDescriptionPath(), d.getApplyUrlPath(),
                    d.getApplyUrlTemplate(), d.getPostedAtPath(), d.getEmploymentTypePath(),
                    d.getDepartmentPath(), d.isEnabled(), d.getCreatedBy(), d.getUpdatedBy(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }
}
