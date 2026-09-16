package com.careerflux.candidate.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.careerflux.ai.proposal.ProfileProposalService;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposalView;
import com.careerflux.audit.AuditService;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.Resume;
import com.careerflux.candidate.dto.CandidateDtos.CandidateProfileResponse;
import com.careerflux.candidate.dto.CandidateDtos.ResumeSummary;
import com.careerflux.consent.ConsentService;
import com.careerflux.consent.ConsentService.ConsentEvent;
import com.careerflux.engagement.domain.JobInteraction;
import com.careerflux.engagement.repository.JobInteractionRepository;
import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.privacy.erasure.AccountErasureService.ErasureView;
import com.careerflux.shortlist.domain.PlacementStage;
import com.careerflux.shortlist.domain.PlacementStageChange;
import com.careerflux.shortlist.domain.ShortlistEntry;
import com.careerflux.shortlist.repository.PlacementStageChangeRepository;
import com.careerflux.shortlist.service.PlacementWorkflowService;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything CareerFlux holds about a student that the student may see, in one
 * document, for the student.
 *
 * <p>Built only from reads the student can already make, each answering for the
 * signed-in account alone: their profile, their resumes, their own job activity,
 * their placements as the student-facing placement view shows them, their
 * consent history and the readings proposed from their resumes. Resume files are
 * listed with the download path the student already uses, which checks
 * ownership itself; this service never reads a file.
 *
 * <p><b>Left out on purpose</b>, and said so in the document: the extracted text
 * of resumes, which is removed once it has been used; notes and names written by
 * college staff on placement records, whose visibility to students has not been
 * decided; scores and eligibility the college computes when discovering
 * candidates; the college's own requirement details beyond the company and role
 * a student is told about; and audit records.
 */
@Service
public class StudentExportService {

    static final String FORMAT = "careerflux-student-export/1";

    static final List<String> NOT_INCLUDED = List.of(
            "The extracted text of your resumes. It is removed once the suggestions made from it are answered, "
                    + "or after the retention period; your resume files themselves are listed under resumes.",
            "Notes and names written by college staff on your placement records.",
            "Match scores, eligibility and other assessments your college's staff see when finding candidates.",
            "Your college's internal details of a requirement beyond the company and role you are told about.",
            "Audit records of actions taken by staff or by CareerFlux itself.");

    public record StudentDataExport(String format, Instant exportedAt, AccountData account,
                                    CandidateProfileResponse profile, List<ResumeData> resumes,
                                    List<JobInteractionData> jobInteractions, List<PlacementData> placements,
                                    List<ConsentEvent> consentHistory, List<ProposalView> aiProposals,
                                    ErasureView erasureRequest, List<String> notIncluded) {
    }

    public record AccountData(UUID userId, String email, String fullName, String institution, String department,
                              String batch, Integer graduationYear) {
    }

    /** One resume; the file itself is at {@code downloadPath}. */
    public record ResumeData(ResumeSummary resume, boolean active, String downloadPath) {
    }

    public record JobInteractionData(UUID jobId, String jobTitle, String companyName, String type,
                                     String applicationStatus, String note, Instant recordedAt) {
    }

    public record PlacementData(UUID requirementId, String companyName, String roleTitle, String stage,
                                String stageLabel, Instant shortlistedAt, Instant stageChangedAt,
                                List<StageData> history) {
    }

    /**
     * @param madeBy   YOU or COLLEGE
     * @param yourNote the note you wrote with your own response; staff notes are not included
     */
    public record StageData(String fromStage, String toStage, String madeBy, String yourNote, Instant occurredAt) {
    }

    private final UserRepository users;
    private final CandidateProfileService profiles;
    private final CandidateMapper mapper;
    private final ResumeService resumes;
    private final JobInteractionRepository interactions;
    private final PlacementWorkflowService placements;
    private final PlacementStageChangeRepository stageChanges;
    private final ConsentService consents;
    private final ProfileProposalService proposals;
    private final AccountErasureService erasures;
    private final AuditService audit;

    public StudentExportService(UserRepository users, CandidateProfileService profiles, CandidateMapper mapper,
                                ResumeService resumes, JobInteractionRepository interactions,
                                PlacementWorkflowService placements, PlacementStageChangeRepository stageChanges,
                                ConsentService consents, ProfileProposalService proposals,
                                AccountErasureService erasures, AuditService audit) {
        this.users = users;
        this.profiles = profiles;
        this.mapper = mapper;
        this.resumes = resumes;
        this.interactions = interactions;
        this.placements = placements;
        this.stageChanges = stageChanges;
        this.consents = consents;
        this.proposals = proposals;
        this.erasures = erasures;
        this.audit = audit;
    }

    /** The signed-in student's export. The id comes from the session, never from a request. */
    @Transactional(readOnly = true)
    public StudentDataExport exportFor(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> NotFoundException.of("Account", userId));
        CandidateProfile profile = profiles.requireByUserId(userId);

        StudentDataExport export = new StudentDataExport(
                FORMAT,
                Instant.now(),
                new AccountData(user.getId(), user.getEmail(), user.getFullName(),
                        user.getInstitution() == null ? null : user.getInstitution().getName(),
                        user.getDepartment() == null ? null : user.getDepartment().getName(),
                        user.getBatch() == null ? null : user.getBatch().getName(),
                        user.getBatch() == null ? null : user.getBatch().getGraduationYear()),
                profiles.getProfile(userId),
                resumes.history(userId).stream().map(this::resumeData).toList(),
                interactions.findByCandidateIdOrderByCreatedAtDesc(profile.getId()).stream()
                        .map(StudentExportService::interactionData).toList(),
                placements.myPlacements().stream().map(this::placementData).toList(),
                consents.history(userId),
                proposals.list(userId).stream().map(summary -> proposals.get(userId, summary.id())).toList(),
                erasures.statusForSelf(userId).orElse(null),
                NOT_INCLUDED);

        audit.record("DATA_EXPORTED", "User", userId, "format=" + FORMAT);
        return export;
    }

    private ResumeData resumeData(Resume resume) {
        return new ResumeData(mapper.toResumeSummary(resume), resume.isActive(),
                "/api/candidate/resumes/" + resume.getId() + "/file");
    }

    private static JobInteractionData interactionData(JobInteraction interaction) {
        return new JobInteractionData(
                interaction.getJob().getId(),
                interaction.getJob().getTitle(),
                interaction.getJob().getCompany() == null ? null : interaction.getJob().getCompany().getName(),
                interaction.getInteractionType().name(),
                interaction.getApplicationStatus() == null ? null : interaction.getApplicationStatus().name(),
                interaction.getNote(),
                interaction.getCreatedAt());
    }

    private PlacementData placementData(ShortlistEntry entry) {
        List<StageData> history = stageChanges.findByShortlistIdOrderByOccurredAtAsc(entry.getId()).stream()
                .map(StudentExportService::stageData)
                .toList();
        return new PlacementData(entry.getRequirement().getId(), entry.getRequirement().getCompanyName(),
                entry.getRequirement().getRoleTitle(), entry.getStage().name(), entry.getStage().label(),
                entry.getCreatedAt(), entry.getStageChangedAt(), history);
    }

    private static StageData stageData(PlacementStageChange change) {
        boolean byStudent = change.getActorKind() == PlacementStage.ActorKind.STUDENT;
        return new StageData(change.getFromStage() == null ? null : change.getFromStage().name(),
                change.getToStage().name(), byStudent ? "YOU" : "COLLEGE", byStudent ? change.getNote() : null,
                change.getOccurredAt());
    }
}
