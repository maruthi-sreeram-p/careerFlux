package com.careerflux.institution.web;

import java.util.List;
import java.util.UUID;

import com.careerflux.institution.dto.InstitutionDtos.BatchView;
import com.careerflux.institution.dto.InstitutionDtos.DepartmentView;
import com.careerflux.institution.dto.InstitutionDtos.ScopeView;
import com.careerflux.institution.dto.InstitutionDtos.StudentPage;
import com.careerflux.institution.dto.InstitutionDtos.StudentRow;
import com.careerflux.institution.dto.OverviewDtos.InstitutionOverview;
import com.careerflux.institution.service.InstitutionOverviewService;
import com.careerflux.institution.service.StudentDirectoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff-facing view of one college.
 *
 * <p>No route here takes an institution id. The caller's institution comes from
 * their token and nothing else, which is the property that makes a tenant
 * boundary hold: there is no parameter to tamper with.
 *
 * <p>The {@code @PreAuthorize} annotations are the coarse gate — they answer
 * "may this kind of account call this?". The finer question, "which students may
 * <em>this</em> member of staff see?", is answered inside the service against
 * the caller's resolved scope.
 */
@RestController
@RequestMapping("/api/institution")
@Tag(name = "Institution")
public class InstitutionController {

    private final StudentDirectoryService directoryService;

    private final InstitutionOverviewService overviewService;

    public InstitutionController(StudentDirectoryService directoryService,
                                 InstitutionOverviewService overviewService) {
        this.overviewService = overviewService;
        this.directoryService = directoryService;
    }

    @GetMapping("/me/scope")
    @Operation(summary = "What the signed-in member of staff is allowed to see")
    public ScopeView myScope() {
        return directoryService.myScope();
    }

    @GetMapping("/students")
    @Operation(summary = "Students within the caller's scope")
    @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")
    public StudentPage students(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size) {
        return directoryService.students(page, size);
    }

    @GetMapping("/students/{userId}")
    @Operation(summary = "One student, if they fall inside the caller's scope")
    @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")
    public StudentRow student(@PathVariable UUID userId) {
        return directoryService.student(userId);
    }

    /**
     * The counts the coordinator, officer and college-admin dashboards render.
     *
     * <p>Gated on {@code ANALYTICS_VIEW}, which students do not hold. The scope
     * inside is what makes a coordinator's answer their department rather than
     * the college.
     */
    @GetMapping("/overview")
    @Operation(summary = "Scope-aware institutional counts for the staff dashboards")
    @PreAuthorize("hasAuthority('ANALYTICS_VIEW')")
    public InstitutionOverview overview() {
        return overviewService.overview();
    }

    @GetMapping("/departments")
    @Operation(summary = "Departments in the caller's institution")
    public List<DepartmentView> departments() {
        return directoryService.departments();
    }

    @GetMapping("/batches")
    @Operation(summary = "Batches in the caller's institution")
    public List<BatchView> batches() {
        return directoryService.batches();
    }
}
