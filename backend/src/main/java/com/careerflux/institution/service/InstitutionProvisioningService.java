package com.careerflux.institution.service;

import java.util.List;
import java.util.Locale;

import com.careerflux.audit.AuditService;
import com.careerflux.common.TextUtils;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.ConflictException;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.dto.ProvisioningDtos.InitialAdmin;
import com.careerflux.institution.dto.ProvisioningDtos.InstitutionSummary;
import com.careerflux.institution.dto.ProvisioningDtos.ProvisionInstitutionRequest;
import com.careerflux.institution.dto.ProvisioningDtos.ProvisionedInstitution;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating a college, which is the one operation that brings a tenant into
 * existence.
 *
 * <p>Until this existed, the only code that could create an institution was the
 * demo seeder, which runs under a profile a real deployment must never use. A
 * freshly deployed pilot could therefore not onboard its first college without
 * somebody writing an INSERT by hand — against the tenant boundary table, on a
 * production database, with no validation and no audit record.
 *
 * <p><b>Who may do this.</b> {@code INSTITUTION_PROVISION}, which has existed
 * since the roles were defined and is granted to the platform administrator
 * alone. No permission was added or moved for this feature; the capability was
 * always meant to exist and simply had nothing wired to it.
 *
 * <p><b>What it deliberately does not do.</b> It creates the college and, if
 * asked, its first administrator. It does not invent departments, batches,
 * students, requirements or jobs. A college's structure is the college's to
 * describe, and a tenant that arrives pre-populated with plausible-looking
 * fixtures is exactly how demonstration data ends up mistaken for real data.
 */
@Service
public class InstitutionProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionProvisioningService.class);

    /** Any local part will do; only the domain participates in the match. */
    private static final String PROBE_LOCAL_PART = "probe@";

    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public InstitutionProvisioningService(InstitutionRepository institutionRepository,
                                          UserRepository userRepository,
                                          PasswordEncoder passwordEncoder,
                                          AuditService auditService) {
        this.institutionRepository = institutionRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<InstitutionSummary> list() {
        return institutionRepository.findAll().stream()
                .sorted((left, right) -> left.getName().compareToIgnoreCase(right.getName()))
                .map(institution -> new InstitutionSummary(
                        institution.getId(),
                        institution.getName(),
                        institution.getSlug(),
                        institution.getEmailDomains(),
                        institution.getStatus().name(),
                        institution.getCreatedAt()))
                .toList();
    }

    @Transactional
    public ProvisionedInstitution provision(ProvisionInstitutionRequest request) {
        String name = request.name().strip();
        String slug = TextUtils.slugify(name);
        if (slug.isEmpty()) {
            throw new BadRequestException(
                    "That name does not produce a usable identifier. Use letters or numbers in the college name.");
        }
        String domains = EmailDomains.normalize(request.emailDomains());
        String registrationCode = TextUtils.hasText(request.registrationCode())
                ? request.registrationCode().strip()
                : null;

        if (domains == null && registrationCode == null) {
            // Neither claim means nobody can ever join: domain matching has
            // nothing to match and there is no code to hand out. Better to say
            // so now than to let an operator discover it when the first student
            // is turned away.
            throw new BadRequestException(
                    "Give the college an email domain, a registration code, or both — "
                            + "otherwise nobody can register against it.");
        }

        if (institutionRepository.existsBySlug(slug)) {
            throw new ConflictException("A college called \"" + name + "\" already exists.");
        }
        rejectOverlappingDomains(domains);
        rejectDuplicateRegistrationCode(registrationCode);

        Institution institution = new Institution();
        institution.setName(name);
        institution.setSlug(slug);
        institution.setShortName(blankToNull(request.shortName()));
        institution.setCity(blankToNull(request.city()));
        institution.setCountry("India");
        institution.setEmailDomains(domains);
        institution.setRegistrationCode(registrationCode);
        // ACTIVE, not the entity's PROVISIONING default. Nothing in the
        // application distinguishes the two — only SUSPENDED is enforced, at
        // registration — so leaving a new college in a state whose name says
        // "students are not yet using it" while students can in fact register
        // would be a label that lies.
        institution.setStatus(InstitutionStatus.ACTIVE);

        try {
            institutionRepository.saveAndFlush(institution);
        } catch (DataIntegrityViolationException collision) {
            // Two operators onboarding the same college at the same moment. The
            // checks above both passed for each of them; the database is what
            // actually decides, and the loser is told the same thing they would
            // have been told a moment earlier.
            throw new ConflictException("That college has just been created by someone else.");
        }

        User admin = request.initialAdmin() == null
                ? null
                : createInitialPlacementCoordinator(institution, request.initialAdmin());

        auditService.record("INSTITUTION_PROVISIONED", "Institution", institution.getId(),
                "Created " + institution.getSlug()
                        + (admin == null ? " with no administrator" : " with an initial placement coordinator"));
        log.info("Provisioned institution {} ({}), domains={}, initialAdmin={}",
                institution.getSlug(), institution.getId(),
                domains == null ? "none" : domains, admin != null);

        return new ProvisionedInstitution(
                institution.getId(),
                institution.getName(),
                institution.getSlug(),
                institution.getEmailDomains(),
                institution.getRegistrationCode(),
                institution.getStatus().name(),
                institution.getCreatedAt(),
                admin == null ? null : admin.getId(),
                admin == null ? null : admin.getEmail());
    }

    /**
     * Creates the college's first placement coordinator — the college's own
     * administrator, who then appoints everybody else.
     *
     * <p>Reuses the ordinary account mechanism — same entity, same encoder, same
     * active status — rather than introducing a second kind of user. The account
     * is bound to the institution just created, never to one named by the
     * caller.
     */
    private User createInitialPlacementCoordinator(Institution institution, InitialAdmin details) {
        String email = details.email().strip().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with that email already exists.");
        }

        User admin = new User();
        admin.setEmail(email);
        admin.setFullName(details.fullName().strip());
        admin.setPasswordHash(passwordEncoder.encode(details.password()));
        admin.setRole(UserRole.PLACEMENT_COORDINATOR);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerified(true);
        admin.setInstitution(institution);
        userRepository.save(admin);

        // Events recorded before the four-role model carry COLLEGE_ADMIN_CREATED
        // and are left as they were written.
        auditService.record("PLACEMENT_COORDINATOR_CREATED", "User", admin.getId(),
                "Initial placement coordinator for " + institution.getSlug());
        // The password is not logged, here or anywhere.
        log.info("Created initial placement coordinator {} for institution {}",
                admin.getId(), institution.getSlug());
        return admin;
    }

    /**
     * Refuses a claim that any existing college already answers to, in either
     * direction.
     *
     * <p>The check runs through {@link Institution#acceptsEmail} rather than
     * comparing strings, so it inherits the real matching rule — including that
     * a claim covers its subdomains. A college claiming {@code northgate.edu}
     * already answers for {@code cse.northgate.edu}, and a second college
     * claiming the subdomain would make both answer for the same student.
     *
     * <p>That matters because {@code EnrolmentService} refuses to guess when two
     * colleges claim one address: every affected student is turned away at
     * registration. Catching it here means the operator is told at the moment
     * they created the overlap, instead of a student discovering it weeks later.
     */
    private void rejectOverlappingDomains(String domains) {
        if (domains == null) {
            return;
        }
        List<String> claimed = EmailDomains.split(domains);
        Institution candidate = new Institution();
        candidate.setEmailDomains(domains);

        for (Institution existing : institutionRepository.findAll()) {
            for (String domain : claimed) {
                if (existing.acceptsEmail(PROBE_LOCAL_PART + domain)) {
                    throw new ConflictException("The email domain \"" + domain
                            + "\" is already claimed by " + existing.getName() + ".");
                }
            }
            for (String domain : EmailDomains.split(existing.getEmailDomains())) {
                if (candidate.acceptsEmail(PROBE_LOCAL_PART + domain)) {
                    throw new ConflictException("That email domain would also claim addresses belonging to "
                            + existing.getName() + " (" + domain + ").");
                }
            }
        }
    }

    /**
     * Registration codes are matched case-insensitively when a student uses one,
     * so two colleges must not share one under any spelling.
     */
    private void rejectDuplicateRegistrationCode(String registrationCode) {
        if (registrationCode == null) {
            return;
        }
        boolean taken = institutionRepository.findAll().stream()
                .anyMatch(existing -> registrationCode.equalsIgnoreCase(existing.getRegistrationCode()));
        if (taken) {
            throw new ConflictException("That registration code is already in use by another college.");
        }
    }

    private static String blankToNull(String value) {
        return TextUtils.hasText(value) ? value.strip() : null;
    }
}
