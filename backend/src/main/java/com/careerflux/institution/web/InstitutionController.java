package com.careerflux.institution.web;

import java.util.List;
import java.util.UUID;

import com.careerflux.audit.AuditQueryService;
import com.careerflux.audit.AuditQueryService.AuditView;
import com.careerflux.institution.dto.AdministrationDtos.CreateBatchRequest;
import com.careerflux.institution.dto.AdministrationDtos.CreateDepartmentRequest;
import com.careerflux.institution.dto.AdministrationDtos.CreateStaffRequest;
import com.careerflux.institution.dto.AdministrationDtos.EnrolmentRequest;
import com.careerflux.institution.dto.AdministrationDtos.EnrolmentView;
import com.careerflux.institution.dto.AdministrationDtos.StaffCreated;
import com.careerflux.institution.dto.InstitutionDtos.BatchView;
import com.careerflux.institution.dto.InstitutionDtos.DepartmentView;
import com.careerflux.institution.dto.InstitutionDtos.ScopeView;
import com.careerflux.institution.dto.InstitutionDtos.StaffRow;
import com.careerflux.institution.dto.InstitutionDtos.StudentDetail;
import com.careerflux.institution.dto.InstitutionDtos.StudentPage;
import com.careerflux.institution.dto.InstitutionDtos.StudentRow;
import com.careerflux.candidate.dto.CandidateDtos.AcademicRecord;
import com.careerflux.candidate.dto.CandidateDtos.AcademicUpdateRequest;
import com.careerflux.candidate.service.AcademicRecordService;
import com.careerflux.institution.dto.OverviewDtos.InstitutionOverview;
import com.careerflux.institution.service.InstitutionAdministrationService;
import com.careerflux.institution.service.InstitutionOverviewService;
import com.careerflux.institution.service.StudentDirectoryService;
import com.careerflux.institution.service.StudentDirectoryService.StudentFilter;
import com.careerflux.privacy.erasure.AccountErasureService;
import com.careerflux.privacy.erasure.AccountErasureService.ErasureView;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AcademicRecordService academicRecords;
    private final InstitutionAdministrationService administration;
    private final AuditQueryService auditQuery;

    private final AccountErasureService erasures;

    public InstitutionController(StudentDirectoryService directoryService,
                                 InstitutionOverviewService overviewService,
                                 AcademicRecordService academicRecords,
                                 InstitutionAdministrationService administration,
                                 AuditQueryService auditQuery,
                                 AccountErasureService erasures) {
        this.overviewService = overviewService;
        this.academicRecords = academicRecords;
        this.administration = administration;
        this.directoryService = directoryService;
        this.auditQuery = auditQuery;
        this.erasures = erasures;
    }

    @GetMapping("/me/scope")
    @Operation(summary = "What the signed-in member of staff is allowed to see")
    public ScopeView myScope() {
        return directoryService.myScope();
    }

    /**
     * Search and filter the directory.
     *
     * <p>Every parameter is optional, so the original {@code ?page&size} call
     * still means what it did: the whole scope, alphabetically.
     */
    @GetMapping("/students")
    @Operation(summary = "Search, filter and sort students within the caller's scope")
    @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")
    public StudentPage students(@RequestParam(required = false) String q,
                                @RequestParam(required = false) UUID departmentId,
                                @RequestParam(required = false) UUID batchId,
                                @RequestParam(required = false) Double minCgpa,
                                @RequestParam(required = false) List<String> skills,
                                @RequestParam(required = false) Integer minProfileCompleteness,
                                @RequestParam(required = false) Boolean resumeUploaded,
                                @RequestParam(required = false) String sort,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size) {
        StudentFilter filter = new StudentFilter(q, departmentId, batchId, minCgpa, skills,
                minProfileCompleteness, resumeUploaded);
        return directoryService.students(filter, page, size, sort);
    }

    @GetMapping("/students/{userId}")
    @Operation(summary = "One student in full, if they fall inside the caller's scope")
    @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")
    public StudentDetail student(@PathVariable UUID userId) {
        return directoryService.studentDetail(userId);
    }

    /**
     * The counts the coordinator dashboards render.
     *
     * <p>Gated on {@code ANALYTICS_VIEW}, which students do not hold. The scope
     * inside is what makes a department coordinator's answer their department
     * rather than the college.
     */
    @GetMapping("/overview")
    @Operation(summary = "Scope-aware institutional counts for the staff dashboards")
    @PreAuthorize("hasAuthority('ANALYTICS_VIEW')")
    public InstitutionOverview overview() {
        return overviewService.overview();
    }

    /**
     * This college's own audit trail.
     *
     * <p>What its staff and students did, for its placement coordinator, and
     * nothing from any other college: the college comes from the session. Rows
     * name people by account id and role, never by email address.
     */
    @GetMapping("/audit")
    @Operation(summary = "This college's audit trail")
    @PreAuthorize("hasAuthority('AUDIT_READ_INSTITUTION')")
    public Page<AuditView> audit(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return auditQuery.institutionEvents(page, size);
    }

    /**
     * A student's academic record, as staff see it.
     *
     * <p>Scoped exactly like the student row above it: a coordinator sees their
     * department, and anything outside is not-found rather than forbidden.
     */
    @GetMapping("/students/{userId}/academics")
    @Operation(summary = "One student's CGPA and where it came from")
    @PreAuthorize("hasAuthority('STUDENT_READ_SCOPED')")
    public AcademicRecord academics(@PathVariable UUID userId) {
        return academicRecords.forStudent(userId);
    }

    /**
     * Recording the institution's CGPA for a student.
     *
     * <p>Gated on {@code PLACEMENT_ELIGIBILITY_MANAGE} — the permission the role
     * model already defines as deciding who is officially eligible for a drive,
     * as distinct from who matches. Nothing was added to make this work.
     *
     * <p>A value entered here is verified, and is the only kind eligibility
     * reads. The user id in the path is checked against the caller's own scope
     * before anything is written.
     */
    @PutMapping("/students/{userId}/academics")
    @Operation(summary = "Record a student's institutional CGPA")
    @PreAuthorize("hasAuthority('PLACEMENT_ELIGIBILITY_MANAGE')")
    public AcademicRecord updateAcademics(@PathVariable UUID userId,
                                          @RequestBody AcademicUpdateRequest request) {
        return academicRecords.updateForStudent(userId, request.cgpa());
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

    // ---------------------------------------------------------- configuration
    //
    // A placement coordinator building their own college. Each of these is
    // gated on a permission that has existed since the roles were defined and
    // had nothing wired to it, so the role could read its college and change
    // nothing about it — no departments, so no coordinator scoping and no
    // requirement targeting; no staff, so no department coordinators.
    //
    // None of them take an institution id. The tenant comes from the session.

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a department to this college")
    @PreAuthorize("hasAuthority('DEPARTMENT_MANAGE')")
    public DepartmentView createDepartment(@Valid @RequestBody CreateDepartmentRequest request) {
        return administration.createDepartment(request);
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a graduating batch to this college")
    @PreAuthorize("hasAuthority('BATCH_MANAGE')")
    public BatchView createBatch(@Valid @RequestBody CreateBatchRequest request) {
        return administration.createBatch(request);
    }

    /**
     * Who works in placement at this college.
     *
     * <p>Gated on {@code STAFF_MANAGE} rather than a general read permission:
     * the list exists so an administrator can manage these accounts, and a staff
     * directory is not something every role needs to see.
     */
    @GetMapping("/staff")
    @Operation(summary = "Placement staff at this college, with their scope")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public List<StaffRow> staff() {
        return administration.listStaff();
    }

    /**
     * Appoints placement staff.
     *
     * <p>A department coordinator must be given a department; a placement
     * coordinator must not. That asymmetry is enforced rather than tidied away,
     * because a coordinator whose scope was quietly dropped can see every
     * student in the college.
     */
    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Appoint a department or placement coordinator")
    @PreAuthorize("hasAuthority('STAFF_MANAGE')")
    public StaffCreated createStaff(@Valid @RequestBody CreateStaffRequest request) {
        return administration.createStaff(request);
    }

    /**
     * Records which department and batch a student belongs to.
     *
     * <p>Students arrive by registering with a college address, which says
     * nothing about their course or year. Until this is set, a requirement
     * targeting a department or graduation year matches nobody — and reads as
     * "no suitable candidates" rather than as missing data.
     */
    @PutMapping("/students/{userId}/enrolment")
    @Operation(summary = "Set a student's department and batch")
    @PreAuthorize("hasAuthority('STUDENT_MANAGE')")
    public EnrolmentView setEnrolment(@PathVariable UUID userId,
                                      @Valid @RequestBody EnrolmentRequest request) {
        return administration.setEnrolment(userId, request);
    }

    // --------------------------------------------------------------- erasure
    //
    // STUDENT_MANAGE is held by the placement coordinator alone. The service
    // checks it again and then checks the student is in the caller's own college,
    // answering not-found for anyone who is not.

    @GetMapping("/students/{userId}/erasure")
    @Operation(summary = "The latest erasure request for a student, if any")
    @PreAuthorize("hasAuthority('STUDENT_MANAGE')")
    public ResponseEntity<ErasureView> erasureStatus(@PathVariable UUID userId) {
        return erasures.statusForStaff(userId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/students/{userId}/erasure")
    @Operation(summary = "Request erasure of a student's account, after the grace period")
    @PreAuthorize("hasAuthority('STUDENT_MANAGE')")
    public ErasureView requestErasure(@PathVariable UUID userId) {
        return erasures.requestByStaff(userId);
    }

    @PostMapping("/students/{userId}/erasure/cancel")
    @Operation(summary = "Cancel a student's erasure request during its grace period")
    @PreAuthorize("hasAuthority('STUDENT_MANAGE')")
    public ErasureView cancelErasure(@PathVariable UUID userId) {
        return erasures.cancelByStaff(userId);
    }
}
