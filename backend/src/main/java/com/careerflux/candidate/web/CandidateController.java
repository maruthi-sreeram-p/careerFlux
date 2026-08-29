package com.careerflux.candidate.web;

import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.dto.CandidateDtos.CandidateProfileResponse;
import com.careerflux.candidate.dto.CandidateDtos.PreferencesPayload;
import com.careerflux.candidate.dto.CandidateDtos.ProfileUpdateRequest;
import com.careerflux.candidate.dto.CandidateDtos.ResumeParseResult;
import com.careerflux.candidate.dto.CandidateDtos.ResumeSummary;
import com.careerflux.candidate.service.CandidateMapper;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.candidate.service.ResumeService;
import com.careerflux.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The signed-in student's own data.
 *
 * <p>Every route here is guarded by {@code SELF_PROFILE_MANAGE} and operates on
 * the caller's own profile, resolved from the token. No endpoint on this
 * controller takes a candidate id, which is what makes it structurally
 * impossible to read somebody else's profile through it.
 */
@RestController
@RequestMapping("/api/candidate")
@Tag(name = "Candidate profile")
@PreAuthorize("hasAuthority('SELF_PROFILE_MANAGE')")
public class CandidateController {

    private final CandidateProfileService profileService;
    private final ResumeService resumeService;
    private final CandidateMapper mapper;
    private final CurrentUser currentUser;

    public CandidateController(CandidateProfileService profileService,
                               ResumeService resumeService,
                               CandidateMapper mapper,
                               CurrentUser currentUser) {
        this.profileService = profileService;
        this.resumeService = resumeService;
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    @Operation(summary = "The signed-in candidate's full career profile")
    public CandidateProfileResponse profile() {
        return profileService.getProfile(currentUser.requireId());
    }

    @PutMapping("/profile")
    @Operation(summary = "Replace profile details, skills, experience and education")
    public CandidateProfileResponse updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return profileService.updateProfile(currentUser.requireId(), request);
    }

    @PutMapping("/preferences")
    @Operation(summary = "Replace career preferences")
    public CandidateProfileResponse updatePreferences(@RequestBody PreferencesPayload payload) {
        return profileService.savePreferences(currentUser.requireId(), payload);
    }

    @PostMapping(value = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a resume and return the extraction for review")
    public ResumeParseResult uploadResume(@RequestParam("file") MultipartFile file) {
        return resumeService.upload(currentUser.requireId(), file);
    }

    @GetMapping("/resumes")
    @Operation(summary = "Every resume this candidate has uploaded, newest first")
    public List<ResumeSummary> resumes() {
        return resumeService.history(currentUser.requireId()).stream()
                .map(mapper::toResumeSummary)
                .toList();
    }

    @GetMapping("/resumes/{resumeId}/file")
    @Operation(summary = "Download one of the candidate's own resumes")
    public ResponseEntity<ByteArrayResource> downloadResume(@PathVariable UUID resumeId) {
        var resume = resumeService.download(currentUser.requireId(), resumeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resume.filename().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(resume.contentType()))
                .body(new ByteArrayResource(resume.content()));
    }
}
