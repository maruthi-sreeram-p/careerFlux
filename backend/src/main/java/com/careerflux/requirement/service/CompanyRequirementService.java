package com.careerflux.requirement.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.TextUtils;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.IllegalStateTransitionException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.common.taxonomy.WorkMode;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.job.domain.SkillRequirement;
import com.careerflux.requirement.domain.CompanyRequirement;
import com.careerflux.requirement.domain.CompanyRequirementSkill;
import com.careerflux.requirement.domain.RequirementStatus;
import com.careerflux.requirement.dto.RequirementDtos.CreateRequirement;
import com.careerflux.requirement.dto.RequirementDtos.RequirementPage;
import com.careerflux.requirement.dto.RequirementDtos.RequirementView;
import com.careerflux.requirement.dto.RequirementDtos.SkillRequest;
import com.careerflux.requirement.dto.RequirementDtos.UpdateRequirement;
import com.careerflux.requirement.repository.CompanyRequirementRepository;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillResolver;
import com.careerflux.user.Permission;
import com.careerflux.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and maintaining the requirements a college is hiring against.
 *
 * <p>Two authorization boundaries, deliberately different. Reading is gated on
 * {@code PLACEMENT_DRIVE_VIEW}, which a coordinator holds; writing is gated on
 * {@code PLACEMENT_DRIVE_MANAGE}, which only a placement coordinator holds. That
 * split is not invented here — it is what the role model already granted, and
 * it matches how a placement office works: the placement coordinator takes the
 * company's brief, the department coordinator works their department against it.
 *
 * <p>Every read is scoped twice. The institution filter is in the query, so a
 * requirement from another college cannot be loaded at all; a department
 * coordinator is then narrowed again to requirements naming one of their
 * departments, plus those naming none, which mean the whole college. That rule
 * lives in {@link RequirementAccess}, which discovery, the shortlist and
 * placement history use too, so none of them can show what this refuses.
 *
 * <p>Nothing here discovers candidates. Turning a requirement into a ranked
 * list of students is the next phase, and the requirement's skills are stored
 * in the same tiers the job corpus uses precisely so that phase can reuse the
 * existing scorer rather than write a second one.
 */
@Service
public class CompanyRequirementService {

    private static final Logger log = LoggerFactory.getLogger(CompanyRequirementService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final CompanyRequirementRepository requirements;
    private final DepartmentRepository departments;
    private final InstitutionRepository institutions;
    private final UserRepository users;
    private final SkillResolver skillResolver;
    private final AccessGuard accessGuard;
    private final RequirementAccess requirementAccess;

    public CompanyRequirementService(CompanyRequirementRepository requirements,
                                     DepartmentRepository departments,
                                     InstitutionRepository institutions,
                                     UserRepository users,
                                     SkillResolver skillResolver,
                                     AccessGuard accessGuard,
                                     RequirementAccess requirementAccess) {
        this.requirements = requirements;
        this.departments = departments;
        this.institutions = institutions;
        this.users = users;
        this.skillResolver = skillResolver;
        this.accessGuard = accessGuard;
        this.requirementAccess = requirementAccess;
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public RequirementPage list(String status, int page, int size) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_VIEW);
        UUID institutionId = accessGuard.requireInstitutionId();
        AccessScope scope = accessGuard.scope();

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        RequirementStatus filter = parseStatus(status).orElse(null);

        Page<CompanyRequirement> found = filter == null
                ? requirements.findByInstitutionIdOrderByCreatedAtDesc(institutionId, pageable)
                : requirements.findByInstitutionIdAndStatusOrderByCreatedAtDesc(
                        institutionId, filter, pageable);

        // A coordinator sees the requirements aimed at their departments, and
        // the college-wide ones. Applying this after the page rather than in it
        // keeps the query simple at the cost of a short page; the alternative
        // is a second scoped query, which is worth doing once colleges run more
        // requirements than fit on a page.
        List<RequirementView> visible = found.getContent().stream()
                .filter(requirement -> requirementAccess.isVisibleTo(requirement, scope))
                .map(this::toView)
                .toList();

        return new RequirementPage(visible, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    /**
     * One requirement.
     *
     * <p>This is the route an id-tampering attempt takes. The lookup is by id
     * <em>and</em> institution, so a requirement belonging to another college is
     * never loaded, and a coordinator outside its departments is told the same
     * thing: not found. A 403 would confirm the requirement exists.
     */
    @Transactional(readOnly = true)
    public RequirementView get(UUID id) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_VIEW);
        return toView(requirementAccess.visible(id));
    }

    // ----------------------------------------------------------------- write

    @Transactional
    public RequirementView create(CreateRequirement request) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_MANAGE);
        UUID institutionId = accessGuard.requireInstitutionId();

        CompanyRequirement requirement = new CompanyRequirement();
        requirement.setInstitution(institutions.findById(institutionId)
                .orElseThrow(() -> NotFoundException.of("Institution", institutionId)));
        requirement.setCreatedBy(users.findById(accessGuard.currentUserId()).orElse(null));
        requirement.setCompanyName(request.companyName().strip());
        requirement.setRoleTitle(request.roleTitle().strip());
        requirement.setDescription(request.description());
        requirement.setMinExperienceYears(request.minExperienceYears());
        requirement.setMaxExperienceYears(request.maxExperienceYears());
        requirement.setGraduationYear(request.graduationYear());
        requirement.setMinCgpa(request.minCgpa());
        requirement.setWorkMode(parseWorkMode(request.workMode()));
        requirement.setLocationRaw(request.location());
        requirement.setDriveDate(request.driveDate());
        // A requirement always begins as a draft. Publishing is a separate,
        // deliberate act, because an OPEN requirement is one the college will
        // search students against.
        requirement.setStatus(RequirementStatus.DRAFT);
        requirement.setDepartments(resolveDepartments(request.departmentIds(), institutionId));

        requirements.save(requirement);
        applySkills(requirement, request.skills());
        requirements.save(requirement);

        log.info("Requirement created: {} at {} for institution {}",
                requirement.getRoleTitle(), requirement.getCompanyName(), institutionId);
        return toView(requirement, unresolvedSkills(request.skills()));
    }

    @Transactional
    public RequirementView update(UUID id, UpdateRequirement request) {
        accessGuard.requirePermission(Permission.PLACEMENT_DRIVE_MANAGE);
        CompanyRequirement requirement = requirementAccess.visible(id);

        if (TextUtils.hasText(request.companyName())) {
            requirement.setCompanyName(request.companyName().strip());
        }
        if (TextUtils.hasText(request.roleTitle())) {
            requirement.setRoleTitle(request.roleTitle().strip());
        }
        if (request.description() != null) {
            requirement.setDescription(request.description());
        }
        if (request.minExperienceYears() != null) {
            requirement.setMinExperienceYears(request.minExperienceYears());
        }
        if (request.maxExperienceYears() != null) {
            requirement.setMaxExperienceYears(request.maxExperienceYears());
        }
        if (request.graduationYear() != null) {
            requirement.setGraduationYear(request.graduationYear());
        }
        if (request.minCgpa() != null) {
            requirement.setMinCgpa(request.minCgpa());
        }
        if (TextUtils.hasText(request.workMode())) {
            requirement.setWorkMode(parseWorkMode(request.workMode()));
        }
        if (request.location() != null) {
            requirement.setLocationRaw(request.location());
        }
        if (request.driveDate() != null) {
            requirement.setDriveDate(request.driveDate());
        }
        if (TextUtils.hasText(request.status())) {
            RequirementStatus next = parseStatus(request.status())
                    .orElseThrow(() -> new BadRequestException(
                            "Unknown status '" + request.status() + "'"));
            if (!requirement.getStatus().canMoveTo(next)) {
                throw new IllegalStateTransitionException("A " + requirement.getStatus()
                        + " requirement cannot move to " + next);
            }
            requirement.setStatus(next);
        }
        if (request.departmentIds() != null) {
            requirement.setDepartments(resolveDepartments(request.departmentIds(),
                    requirement.getInstitution().getId()));
        }
        if (request.skills() != null) {
            applySkills(requirement, request.skills());
        }

        requirements.save(requirement);
        return toView(requirement, unresolvedSkills(request.skills()));
    }

    // --------------------------------------------------------------- helpers

    /**
     * Replaces the skill set.
     *
     * <p>Clearing and re-adding within one transaction makes Hibernate order
     * the inserts ahead of the orphan deletes, so any skill kept across an edit
     * collides with its own surviving row on {@code uq_requirement_skills}. The
     * flush between the two is what puts the DELETE on the wire first. This is
     * the same ordering that stopped the rules-2 corpus backfill dead.
     */
    private void applySkills(CompanyRequirement requirement, List<SkillRequest> requested) {
        requirement.clearSkills();
        requirements.saveAndFlush(requirement);
        if (requested == null || requested.isEmpty()) {
            return;
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (SkillRequest entry : requested) {
            Optional<Skill> resolved = skillResolver.lookup(entry.skill());
            if (resolved.isEmpty() || !seen.add(resolved.get().getId())) {
                // Unknown to the dictionary, or the same skill twice. Both are
                // dropped here and reported back in the view, so an officer can
                // see that what they typed will not be searched on.
                continue;
            }
            CompanyRequirementSkill skill = new CompanyRequirementSkill();
            skill.setSkill(resolved.get());
            skill.setTier(parseTier(entry.tier()));
            requirement.addSkill(skill);
        }
    }

    /** Departments must belong to the caller's own college. */
    private Set<Department> resolveDepartments(List<UUID> ids, UUID institutionId) {
        Set<Department> resolved = new LinkedHashSet<>();
        if (ids == null) {
            return resolved;
        }
        for (UUID id : ids) {
            departments.findByIdAndInstitutionId(id, institutionId)
                    .ifPresentOrElse(resolved::add, () -> {
                        throw NotFoundException.of("Department", id);
                    });
        }
        return resolved;
    }

    private static SkillRequirement parseTier(String raw) {
        if (!TextUtils.hasText(raw)) {
            return SkillRequirement.REQUIRED;
        }
        try {
            return SkillRequirement.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unknown skill tier '" + raw
                    + "'. Expected REQUIRED, PREFERRED or OPTIONAL.");
        }
    }

    private static WorkMode parseWorkMode(String raw) {
        if (!TextUtils.hasText(raw)) {
            return WorkMode.UNSPECIFIED;
        }
        try {
            return WorkMode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unknown work mode '" + raw + "'");
        }
    }

    private static Optional<RequirementStatus> parseStatus(String raw) {
        if (!TextUtils.hasText(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(RequirementStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private RequirementView toView(CompanyRequirement requirement) {
        return toView(requirement, List.of());
    }

    private RequirementView toView(CompanyRequirement requirement, List<String> unresolved) {
        List<com.careerflux.requirement.dto.RequirementDtos.SkillView> required = new ArrayList<>();
        List<com.careerflux.requirement.dto.RequirementDtos.SkillView> preferred = new ArrayList<>();
        List<com.careerflux.requirement.dto.RequirementDtos.SkillView> optional = new ArrayList<>();
        for (CompanyRequirementSkill skill : requirement.getSkills()) {
            var view = new com.careerflux.requirement.dto.RequirementDtos.SkillView(
                    skill.getSkill().getCanonicalName(), skill.getSkill().getSlug(),
                    skill.getTier().name());
            switch (skill.getTier()) {
                case REQUIRED -> required.add(view);
                case PREFERRED -> preferred.add(view);
                case OPTIONAL -> optional.add(view);
            }
        }

        List<com.careerflux.requirement.dto.RequirementDtos.DepartmentView> departmentViews =
                requirement.getDepartments().stream()
                        .map(department -> new com.careerflux.requirement.dto.RequirementDtos
                                .DepartmentView(department.getId(), department.getName(),
                                department.getCode()))
                        .sorted((left, right) -> left.name().compareTo(right.name()))
                        .toList();

        return new RequirementView(
                requirement.getId(),
                requirement.getCompanyName(),
                requirement.getRoleTitle(),
                requirement.getDescription(),
                requirement.getMinExperienceYears(),
                requirement.getMaxExperienceYears(),
                requirement.getGraduationYear(),
                requirement.getMinCgpa(),
                requirement.getWorkMode().name(),
                requirement.getLocationRaw(),
                requirement.getStatus().name(),
                requirement.getDriveDate(),
                requirement.getCreatedBy() == null ? null : requirement.getCreatedBy().getFullName(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt(),
                required, preferred, optional, departmentViews,
                unresolved);
    }

    /**
     * The skills a submission named that the dictionary could not resolve.
     *
     * <p>Computed from the request rather than the stored row, because by the
     * time it is stored the unresolved ones are gone. Reported so an officer
     * learns that "Sprint Boot" was not understood instead of wondering why no
     * candidate matches it.
     */
    public List<String> unresolvedSkills(List<SkillRequest> requested) {
        if (requested == null) {
            return List.of();
        }
        return requested.stream()
                .map(SkillRequest::skill)
                .filter(name -> skillResolver.lookup(name).isEmpty())
                .distinct()
                .toList();
    }
}
