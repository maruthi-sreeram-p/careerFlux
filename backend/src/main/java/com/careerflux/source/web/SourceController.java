package com.careerflux.source.web;

import java.util.List;
import java.util.UUID;

import com.careerflux.source.dto.SourceDtos.AdapterInfo;
import com.careerflux.source.dto.SourceDtos.SourceDetail;
import com.careerflux.source.dto.SourceDtos.SourceStats;
import com.careerflux.source.dto.SourceDtos.SourceSummary;
import com.careerflux.source.service.SourceQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the source registry, for the Portal Admin only.
 *
 * <p>This used to be open to anyone signed in, on the reasoning that sources
 * hold no tenant data. But the detail view carries each source's policy record,
 * including the staff who reviewed it, and source governance belongs to the
 * platform operator (Decisions 12 and 15). A student still sees where a job came
 * from: a job's provenance is served with the job, not from here.
 *
 * <p>Guarded twice, like the rest of the platform console: the URL rule in
 * {@link com.careerflux.security.SecurityConfig} and {@code SOURCE_VIEW} below,
 * which only the platform role holds. Everything that <em>changes</em> a source
 * is on the platform admin routes and requires {@code SOURCE_MANAGE}.
 */
@RestController
@RequestMapping("/api/sources")
@Tag(name = "Source intelligence")
@PreAuthorize("hasAuthority('SOURCE_VIEW')")
public class SourceController {

    private final SourceQueryService queryService;

    public SourceController(SourceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "Sources CareerFlux monitors, optionally filtered by lifecycle state")
    public Page<SourceSummary> list(@RequestParam(required = false) List<String> state,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        return queryService.list(state, page, size);
    }

    @GetMapping("/stats")
    @Operation(summary = "Counts by lifecycle state and health")
    public SourceStats stats() {
        return queryService.stats();
    }

    @GetMapping("/adapters")
    @Operation(summary = "Adapters this build can talk to")
    public List<AdapterInfo> adapters() {
        return queryService.adapters();
    }

    @GetMapping("/{sourceId}")
    @Operation(summary = "One source: policy, health history, lifecycle and recent runs")
    public SourceDetail detail(@PathVariable UUID sourceId) {
        return queryService.detail(sourceId);
    }
}
