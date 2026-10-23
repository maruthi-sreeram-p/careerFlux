package com.careerflux.institution.web;

import com.careerflux.institution.service.StudentImportService;
import com.careerflux.institution.service.StudentImportService.ImportResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Adding students to the portal from an Excel sheet. */
@RestController
@RequestMapping("/api/institution/students/import")
@Tag(name = "Student import")
@PreAuthorize("hasAuthority('STUDENT_IMPORT')")
public class StudentImportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final StudentImportService importService;

    public StudentImportController(StudentImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/template")
    @Operation(summary = "Download the sheet to fill in")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"students-template.xlsx\"")
                .body(importService.template());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Create student accounts from an uploaded sheet")
    public ImportResult upload(@RequestParam("file") MultipartFile file) {
        return importService.importStudents(file);
    }
}
