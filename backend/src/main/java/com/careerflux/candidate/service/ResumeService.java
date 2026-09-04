package com.careerflux.candidate.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.careerflux.ai.ResumeExtractionService;
import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.domain.ResumeParseStatus;
import com.careerflux.candidate.dto.CandidateDtos.ResumeParseResult;
import com.careerflux.candidate.repository.ResumeRepository;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.security.access.AccessGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Resume upload and parsing.
 *
 * <p>Parsing runs inline rather than in the background: the onboarding flow shows
 * the extraction for review straight away, and a candidate who has just dropped a
 * file in is willing to wait a few seconds. Making it asynchronous would buy
 * nothing except a polling loop and a spinner.
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final ResumeRepository resumeRepository;
    private final ResumeStorageService storageService;
    private final ResumeTextExtractor textExtractor;
    private final ResumeExtractionService extractionService;
    private final CandidateProfileService profileService;
    private final CandidateMapper mapper;
    private final AccessGuard accessGuard;
    private final AuditService auditService;

    public ResumeService(ResumeRepository resumeRepository,
                         ResumeStorageService storageService,
                         ResumeTextExtractor textExtractor,
                         ResumeExtractionService extractionService,
                         CandidateProfileService profileService,
                         CandidateMapper mapper,
                         AccessGuard accessGuard,
                         AuditService auditService) {
        this.resumeRepository = resumeRepository;
        this.storageService = storageService;
        this.textExtractor = textExtractor;
        this.extractionService = extractionService;
        this.profileService = profileService;
        this.mapper = mapper;
        this.accessGuard = accessGuard;
        this.auditService = auditService;
    }

    @Transactional
    public ResumeParseResult upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a resume file to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("That file is larger than the 8 MB limit.");
        }
        requireResumeDocument(file);

        CandidateProfile profile = profileService.requireByUserId(userId);
        byte[] content = readBytes(file);
        String filename = sanitizeFilename(file.getOriginalFilename());

        ResumeTextExtractor.Result text = textExtractor.extract(content, filename);

        // Previous resumes stay on record but stop being the active one, so the
        // profile always has a single unambiguous source document.
        resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profile.getId())
                .forEach(previous -> previous.setActive(false));

        Resume resume = new Resume();
        resume.setCandidate(profile);
        resume.setOriginalFilename(filename);
        resume.setContentType(file.getContentType());
        resume.setSizeBytes(file.getSize());
        resume.setChecksum(TextUtils.sha256(content));
        resume.setStoragePath(storageService.store(profile.getId(), content, filename));
        resume.setExtractedText(text.text());
        resume.setParseStatus(ResumeParseStatus.PARSING);
        resume.setUploadedAt(Instant.now());
        resumeRepository.save(resume);

        ResumeExtractionService.Extraction extraction = extractionService.extract(userId, text.text());
        profileService.applyExtraction(profile, extraction.resume());

        resume.setParseEngine(extraction.engine());
        resume.setParsedAt(Instant.now());
        resume.setParseStatus(extraction.aiAssisted()
                ? ResumeParseStatus.PARSED
                : ResumeParseStatus.NEEDS_REVIEW);
        resume.setParseError(extraction.notice());

        auditService.record("RESUME_UPLOADED", "Resume", resume.getId(),
                "engine=" + extraction.engine() + " format=" + text.format());
        log.info("Parsed resume {} for candidate {} using {}", resume.getId(), profile.getId(), extraction.engine());

        return new ResumeParseResult(
                mapper.toResumeSummary(resume),
                extraction.engine(),
                extraction.aiAssisted(),
                extraction.notice(),
                profileService.toResponse(profile));
    }

    @Transactional(readOnly = true)
    public List<Resume> history(UUID userId) {
        CandidateProfile profile = profileService.requireByUserId(userId);
        return resumeRepository.findByCandidateIdOrderByUploadedAtDesc(profile.getId());
    }

    /**
     * Reads a stored resume.
     *
     * <p>Authorization goes through {@link AccessGuard#requireCanReadResume},
     * which allows the owner, and allows placement staff only when they hold
     * {@code STUDENT_RESUME_READ} <em>and</em> the student falls inside their
     * scope. A coordinator who can see a student's readiness still cannot open
     * their document. Every refusal is a 404, so the endpoint cannot be used to
     * confirm that a resume id exists.
     */
    @Transactional(readOnly = true)
    public DownloadableResume download(UUID requestingUserId, UUID resumeId) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> NotFoundException.of("Resume", resumeId));

        accessGuard.requireCanReadResume(resume.getCandidate());

        if (!storageService.exists(resume.getStoragePath())) {
            throw new NotFoundException("That file is no longer stored.");
        }
        // Opening somebody else's document is exactly the act that has to be
        // defensible later, so it is audited whenever it is not the owner.
        if (resume.getCandidate().getUser() != null
                && !requestingUserId.equals(resume.getCandidate().getUser().getId())) {
            auditService.record("RESUME_READ_BY_STAFF", "Resume", resume.getId(),
                    "candidate=" + resume.getCandidate().getId());
        }
        return new DownloadableResume(resume.getOriginalFilename(),
                resume.getContentType() == null ? "application/octet-stream" : resume.getContentType(),
                storageService.read(resume.getStoragePath()));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new BadRequestException("That upload could not be read. Try again.");
        }
    }

    /** The stored name is for display only; strip anything that looks like a path. */
    private String sanitizeFilename(String original) {
        if (!TextUtils.hasText(original)) {
            return "resume";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        return name.isEmpty() ? "resume" : TextUtils.truncate(name, 255);
    }

    public record DownloadableResume(String filename, String contentType, byte[] content) {
    }

    /**
     * The document types a resume is actually written in.
     *
     * <p>Nothing else is accepted. The stored file is already given a generated
     * name under the candidate's own directory, and downloads are forced to
     * attachment, so an arbitrary upload was not directly executable — but there
     * was no reason to hold one, and the text extractor should never be handed
     * bytes nobody expected.
     *
     * <p>The extension and the declared type must agree with the same document
     * kind, since the client controls both and either alone can lie.
     */
    private static final Map<String, Set<String>> ACCEPTED_DOCUMENTS = Map.of(
            ".pdf", Set.of("application/pdf"),
            ".doc", Set.of("application/msword"),
            ".docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            ".txt", Set.of("text/plain"),
            ".rtf", Set.of("application/rtf", "text/rtf"),
            ".odt", Set.of("application/vnd.oasis.opendocument.text"));

    private static void requireResumeDocument(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf(46);
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);

        Set<String> expectedTypes = ACCEPTED_DOCUMENTS.get(extension);
        if (expectedTypes == null) {
            throw new BadRequestException("A resume must be a PDF, Word, ODT, RTF or text "
                    + "document. That file type is not accepted.");
        }
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank()) {
            String bare = declared.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            // A generic type is what several browsers send for .doc and .odt, so
            // it is tolerated; a type that names a different kind of file is not.
            boolean generic = bare.equals("application/octet-stream");
            if (!generic && !expectedTypes.contains(bare)) {
                throw new BadRequestException("That file says it is " + bare
                        + ", which does not match a " + extension + " document.");
            }
        }
    }
}
