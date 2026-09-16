package com.careerflux.candidate.web;

import com.careerflux.candidate.service.StudentExportService;
import com.careerflux.candidate.service.StudentExportService.StudentDataExport;
import com.careerflux.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student's copy of their own data.
 *
 * <p>No parameters: whose data is exported comes from the session alone, and the
 * authority is the one every other student-owned route uses. Staff do not hold it
 * and cannot reach this at all.
 */
@RestController
@RequestMapping("/api/candidate/export")
@Tag(name = "Candidate profile")
@PreAuthorize("hasAuthority('SELF_PROFILE_MANAGE')")
public class StudentExportController {

    private final StudentExportService exports;
    private final CurrentUser currentUser;

    public StudentExportController(StudentExportService exports, CurrentUser currentUser) {
        this.exports = exports;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Download everything CareerFlux holds about you that you can see, as JSON")
    public ResponseEntity<StudentDataExport> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"careerflux-my-data.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(exports.exportFor(currentUser.requireId()));
    }
}
