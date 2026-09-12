package com.careerflux.institution.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.careerflux.audit.AuditService;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.ScopeType;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.dto.AdministrationDtos.CreateBatchRequest;
import com.careerflux.institution.dto.AdministrationDtos.CreateDepartmentRequest;
import com.careerflux.institution.dto.AdministrationDtos.CreateStaffRequest;
import com.careerflux.institution.dto.AdministrationDtos.EnrolmentRequest;
import com.careerflux.institution.dto.AdministrationDtos.EnrolmentView;
import com.careerflux.institution.dto.AdministrationDtos.StaffCreated;
import com.careerflux.institution.dto.InstitutionDtos.StaffRow;
import com.careerflux.institution.dto.InstitutionDtos.BatchView;
import com.careerflux.institution.dto.InstitutionDtos.DepartmentView;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.security.CurrentUser;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A placement coordinator configuring their own college.
 *
 * <p>These operations existed as permissions long before they existed as code.
 * {@code DEPARTMENT_MANAGE}, {@code BATCH_MANAGE}, {@code STAFF_MANAGE} and
 * {@code STUDENT_MANAGE} were all granted and all had nothing wired to them.
 * Departments, batches and staff existed only inside the demo seeder, which a
 * real deployment must never run — meaning a freshly onboarded college could
 * not create a department, could not appoint staff, and could not put a student
 * in a batch.
 *
 * <p>That is not a missing convenience. Department coordinators are scoped
 * through a department, and a company requirement targets departments and
 * graduation years; with neither creatable, a department coordinator sees
 * nothing and a requirement matches nobody.
 *
 * <p><b>The tenant is never in the request.</b> Every method takes the
 * institution from the authenticated caller. There is no field a caller could
 * set to build inside somebody else's college, and every lookup of an existing
 * record goes through a repository method that filters by institution, so a
 * well-formed id belonging to another college is simply not found.
 */
@Service
public class InstitutionAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionAdministrationService.class);

    private final InstitutionRepository institutions;
    private final DepartmentRepository departments;
    private final BatchRepository batches;
    private final StaffScopeRepository staffScopes;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public InstitutionAdministrationService(InstitutionRepository institutions,
                                            DepartmentRepository departments,
                                            BatchRepository batches,
                                            StaffScopeRepository staffScopes,
                                            UserRepository users,
                                            PasswordEncoder passwordEncoder,
                                            CurrentUser currentUser,
                                            AuditService audit) {
        this.institutions = institutions;
        this.departments = departments;
        this.batches = batches;
        this.staffScopes = staffScopes;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------ departments

    @Transactional
    public DepartmentView createDepartment(CreateDepartmentRequest request) {
        Institution institution = callersInstitution();
        String code = request.code().strip().toUpperCase(Locale.ROOT);
        String name = request.name().strip();

        // Codes are how staff refer to a department out loud and how a
        // department coordinator's scope is written down, so one college cannot
        // have two.
        if (departments.findByInstitutionIdAndCode(institution.getId(), code).isPresent()) {
            throw new ConflictException("A department with the code " + code + " already exists.");
        }

        Department department = new Department();
        department.setInstitution(institution);
        department.setName(name);
        department.setCode(code);
        departments.save(department);

        audit.record("DEPARTMENT_CREATED", "Department", department.getId(),
                code + " in " + institution.getSlug());
        return new DepartmentView(department.getId(), name, code, 0);
    }

    // --------------------------------------------------------------- batches

    @Transactional
    public BatchView createBatch(CreateBatchRequest request) {
        Institution institution = callersInstitution();
        String name = request.name().strip();

        if (batches.findByInstitutionIdAndName(institution.getId(), name).isPresent()) {
            throw new ConflictException("A batch called \"" + name + "\" already exists.");
        }

        Batch batch = new Batch();
        batch.setInstitution(institution);
        batch.setName(name);
        batch.setGraduationYear(request.graduationYear());
        batches.save(batch);

        audit.record("BATCH_CREATED", "Batch", batch.getId(),
                name + " (" + request.graduationYear() + ") in " + institution.getSlug());
        return new BatchView(batch.getId(), name, request.graduationYear(), 0);
    }

    // ----------------------------------------------------------- staff listing

    /**
     * The college's placement staff.
     *
     * <p>Name, role and scope — what a placement coordinator needs in order to
     * see who is responsible for what. No password hash, no reset token, no
     * sign-in trail: this answers "who works here", not "tell me about this
     * person".
     */
    @Transactional(readOnly = true)
    public List<StaffRow> listStaff() {
        Institution institution = callersInstitution();
        return users.findStaffInInstitution(institution.getId()).stream()
                .map(this::toStaffRow)
                .toList();
    }

    /**
     * How far one member of staff can see, exactly as {@code AccessScopeResolver}
     * will decide it.
     *
     * <p>A placement coordinator covers the institution by role, whatever grant
     * rows they happen to hold. A department coordinator sees their DEPARTMENT and
     * BATCH grants and nothing else — an INSTITUTION grant is not honoured for
     * them, so it is not listed — and one with no grants sees nobody. That used to
     * be reported as "whole institution", which was the opposite of the truth.
     */
    private StaffRow toStaffRow(User staff) {
        boolean institutionWide = staff.getRole() == UserRole.PLACEMENT_COORDINATOR;
        List<String> labels = institutionWide
                ? List.of()
                : staffScopes.findByUserId(staff.getId()).stream()
                        .filter(scope -> scope.getScopeType() != ScopeType.INSTITUTION)
                        .map(scope -> scope.getDepartment() != null
                                ? scope.getDepartment().getCode()
                                : scope.getBatch() != null ? scope.getBatch().getName() : null)
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
        return new StaffRow(staff.getId(), staff.getFullName(), staff.getEmail(),
                staff.getRole().name(), institutionWide, labels);
    }

    // ----------------------------------------------------------------- staff

    @Transactional
    public StaffCreated createStaff(CreateStaffRequest request) {
        Institution institution = callersInstitution();
        UserRole role = parseStaffRole(request.role());
        String email = request.email().strip().toLowerCase(Locale.ROOT);

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with that email already exists.");
        }

        Department scopedTo = resolveScopeDepartment(institution, role, request.departmentCode());

        User staff = new User();
        staff.setEmail(email);
        staff.setFullName(request.fullName().strip());
        staff.setPasswordHash(passwordEncoder.encode(request.password()));
        staff.setRole(role);
        staff.setStatus(UserStatus.ACTIVE);
        staff.setEmailVerified(true);
        staff.setInstitution(institution);
        if (scopedTo != null) {
            staff.setDepartment(scopedTo);
        }
        users.save(staff);

        String scope = "the whole institution";
        if (scopedTo != null) {
            staffScopes.save(StaffScope.forDepartment(staff, institution, scopedTo));
            scope = scopedTo.getCode();
        }

        audit.record("STAFF_CREATED", "User", staff.getId(),
                role.name() + " for " + institution.getSlug() + ", scoped to " + scope);
        log.info("Created {} {} for institution {} scoped to {}",
                role, staff.getId(), institution.getSlug(), scope);
        return new StaffCreated(staff.getId(), staff.getFullName(), staff.getEmail(), role.name(), scope);
    }

    /**
     * Which roles a college may hand out: its two staff roles, and nothing else.
     *
     * <p>A portal administrator would be escalating out of the tenant into the
     * operator of every college on the deployment, which is the one thing this
     * endpoint must never allow. The retired role names are refused like any
     * other unknown value rather than quietly mapped, so a client that still sends
     * them is told so.
     */
    private static UserRole parseStaffRole(String raw) {
        UserRole role;
        try {
            role = UserRole.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("\"" + raw + "\" is not a role this college can assign.");
        }
        if (!role.isInstitutional()) {
            throw new BadRequestException("A college cannot create portal administrators.");
        }
        if (role == UserRole.STUDENT) {
            // Students arrive by registering; creating one here would produce an
            // account with no candidate profile behind it.
            throw new BadRequestException(
                    "Students register themselves with a college email address; they are not created here.");
        }
        return role;
    }

    /**
     * A department coordinator's department, refused rather than ignored for the
     * placement coordinator.
     *
     * <p>Silently dropping the scope would create a department coordinator who
     * sees nobody, or — worse, for an old client that still meant a department
     * role by "placement coordinator" — an account with the whole college in
     * reach. Both are refused out loud.
     */
    private Department resolveScopeDepartment(Institution institution, UserRole role, String code) {
        boolean supplied = code != null && !code.isBlank();
        if (role == UserRole.DEPARTMENT_COORDINATOR) {
            if (!supplied) {
                throw new BadRequestException(
                        "A department coordinator needs a department to be responsible for.");
            }
            return departments.findByInstitutionIdAndCode(
                            institution.getId(), code.strip().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new BadRequestException(
                            "No department with the code " + code.strip().toUpperCase(Locale.ROOT)
                                    + " exists in this college."));
        }
        if (supplied) {
            throw new BadRequestException(
                    "Only a department coordinator is scoped to a department; "
                            + "a placement coordinator covers the whole institution.");
        }
        return null;
    }

    // ------------------------------------------------------------- enrolment

    /**
     * Puts a student in a department and a batch.
     *
     * <p>A student registering by email domain arrives with neither, because
     * nothing in their address says which course they are on. Until this is
     * recorded, a requirement targeting a department or a graduation year
     * matches nobody — which reads as "no suitable candidates" rather than as
     * missing data.
     */
    @Transactional
    public EnrolmentView setEnrolment(java.util.UUID userId, EnrolmentRequest request) {
        Institution institution = callersInstitution();
        User student = users.findById(userId)
                .filter(candidate -> institution.getId().equals(candidate.getInstitutionId()))
                // Not-found rather than forbidden, matching the rest of the
                // product: a college cannot learn that a user id exists
                // somewhere else by watching which error comes back.
                .orElseThrow(() -> new NotFoundException("No such student in this institution."));

        if (student.getRole() != UserRole.STUDENT) {
            throw new BadRequestException("Only students are enrolled in a department and batch.");
        }

        Department department = request.departmentId() == null ? null
                : departments.findByIdAndInstitutionId(request.departmentId(), institution.getId())
                        .orElseThrow(() -> new BadRequestException("No such department in this institution."));
        Batch batch = request.batchId() == null ? null
                : batches.findByIdAndInstitutionId(request.batchId(), institution.getId())
                        .orElseThrow(() -> new BadRequestException("No such batch in this institution."));

        student.setDepartment(department);
        student.setBatch(batch);
        users.save(student);

        audit.record("STUDENT_ENROLMENT_SET", "User", student.getId(),
                "department=" + (department == null ? "none" : department.getCode())
                        + ", batch=" + (batch == null ? "none" : batch.getName()));
        return new EnrolmentView(student.getId(), student.getFullName(),
                department == null ? null : department.getName(),
                batch == null ? null : batch.getName());
    }

    // --------------------------------------------------------------- helpers

    /**
     * The college the caller belongs to.
     *
     * <p>The single reason none of these operations can reach another tenant:
     * the institution is read from the session, and the request has no say in it.
     */
    private Institution callersInstitution() {
        java.util.UUID institutionId = Optional.ofNullable(currentUser.require().getInstitutionId())
                .orElseThrow(() -> new BadRequestException(
                        "This account does not belong to a college, so it cannot configure one."));
        return institutions.findById(institutionId)
                .orElseThrow(() -> new NotFoundException("Institution not found."));
    }
}
