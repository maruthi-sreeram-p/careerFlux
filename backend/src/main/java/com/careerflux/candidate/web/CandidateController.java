package com.careerflux.candidate.web;

import java.util.List;
import java.util.UUID;

import com.careerflux.candidate.dto.CandidateDtos.CandidateProfileResponse;
import com.careerflux.candidate.dto.CandidateDtos.PreferencesPayload;
import com.careerflux.candidate.dto.CandidateDtos.ProfileUpdateRequest;
import com.careerflux.candidate.dto.CandidateDtos.ResumeParseResult;
import com.careerflux.candidate.dto.CandidateDtos.ResumeSummary;
import com.careerflux.candidate.service.CandidateMapper;
import com.careerflux.candidate.dto.CandidateDtos.AcademicRecord;
import com.careerflux.candidate.dto.CandidateDtos.AcademicUpdateRequest;
import com.careerflux.candidate.service.AcademicRecordService;
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

    private final AcademicRecordService academicRecords;

    public CandidateController(CandidateProfileService profileService,
                               AcademicRecordService academicRecords,
                               ResumeService resumeService,
                               CandidateMapper mapper,
                               CurrentUser currentUser) {
        this.profileService = profileService;
        this.academicRecords = academicRecords;
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

    /**
     * The student's own CGPA.
     *
     * <p>Separate from the profile update because it means something different:
     * the profile PUT replaces what it is given, and a client omitting a field
     * there would silently erase an academic record. This states one fact
     * deliberately.
     *
     * <p>Recorded as self-entered. It appears on their profile and is not what
     * a company's stated minimum is judged against.
     */
    @PutMapping("/academics")
    @Operation(summary = "Record your own CGPA. Self-entered, and not institutionally verified.")
    public AcademicRecord updateAcademics(@RequestBody AcademicUpdateRequest request) {
        return academicRecords.updateOwn(request.cgpa());
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
                // The stored type came from the client at upload time, so it may
                // not parse. Falling back keeps a download working instead of
                // turning somebody else's malformed header into a 500.
                .contentType(safeMediaType(resume.contentType()))
                .body(new ByteArrayResource(resume.content()));
    }

    private static MediaType safeMediaType(String declared) {
        if (declared == null || declared.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(declared);
        } catch (org.springframework.http.InvalidMediaTypeException malformed) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
