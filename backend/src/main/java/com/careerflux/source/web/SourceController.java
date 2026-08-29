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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the source registry, available to anyone signed in.
 *
 * <p>Deliberately not gated behind {@code SOURCE_VIEW}. Source intelligence is a
 * product feature rather than an operational detail: a student looking at a job
 * should be able to see where it came from and whether that source is trusted
 * and healthy. The registry holds no tenant data — sources and jobs are global —
 * so there is nothing here to isolate.
 *
 * <p>Everything that <em>changes</em> a source is on the platform admin routes
 * and does require {@code SOURCE_MANAGE}.
 */
@RestController
@RequestMapping("/api/sources")
@Tag(name = "Source intelligence")
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
