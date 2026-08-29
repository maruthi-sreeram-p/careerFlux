package com.careerflux.bootstrap;

import java.util.List;
import java.util.Locale;

import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.domain.StaffScope;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.institution.repository.StaffScopeRepository;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates a demonstration college so a fresh checkout has somewhere for a
 * student to register into.
 *
 * <p>Runs only under the {@code demo} profile. The institution, its departments
 * and its batches are created unconditionally, because none of that is
 * sensitive. Staff accounts are created only when {@code CAREERFLUX_DEMO_PASSWORD}
 * is set: shipping working placement-officer credentials in source would be a
 * backdoor, whatever the profile is called.
 *
 * <p>The scoping here is the interesting part, and it is what the authorization
 * tests exercise. The officer sees the whole college. The coordinator is granted
 * one department, so computer science students are visible to them and
 * mechanical students are not.
 */
@Component
@Profile("demo")
@Order(4)
public class DemoInstitutionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoInstitutionSeeder.class);

    public static final String DEMO_SLUG = "northgate";
    public static final String DEMO_DOMAIN = "northgate.edu";
    public static final String DEMO_CODE = "NORTHGATE-2026";

    private final InstitutionRepository institutionRepository;
    private final DepartmentRepository departmentRepository;
    private final BatchRepository batchRepository;
    private final StaffScopeRepository staffScopeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public DemoInstitutionSeeder(InstitutionRepository institutionRepository,
                                 DepartmentRepository departmentRepository,
                                 BatchRepository batchRepository,
                                 StaffScopeRepository staffScopeRepository,
                                 UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${CAREERFLUX_DEMO_PASSWORD:}") String demoPassword) {
        this.institutionRepository = institutionRepository;
        this.departmentRepository = departmentRepository;
        this.batchRepository = batchRepository;
        this.staffScopeRepository = staffScopeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (institutionRepository.existsBySlug(DEMO_SLUG)) {
            return;
        }

        Institution institution = new Institution();
        institution.setName("Northgate Institute of Technology (demo)");
        institution.setSlug(DEMO_SLUG);
        institution.setShortName("Northgate");
        institution.setCity("Hyderabad");
        institution.setCountry("India");
        institution.setStatus(InstitutionStatus.ACTIVE);
        institution.setEmailDomains(DEMO_DOMAIN);
        institution.setRegistrationCode(DEMO_CODE);
        institution.setStudentAiDailyQuota(25);
        institutionRepository.save(institution);

        List<Department> departments = List.of(
                department(institution, "Computer Science and Engineering", "CSE"),
                department(institution, "Electronics and Communication", "ECE"),
                department(institution, "Mechanical Engineering", "MECH"));
        departmentRepository.saveAll(departments);

        List<Batch> batches = List.of(
                batch(institution, "Class of 2026", 2026),
                batch(institution, "Class of 2027", 2027));
        batchRepository.saveAll(batches);

        log.info("Seeded demo institution '{}' with {} departments and {} batches. "
                        + "Students register with an @{} address or the code {}.",
                institution.getName(), departments.size(), batches.size(), DEMO_DOMAIN, DEMO_CODE);

        if (!StringUtils.hasText(demoPassword)) {
            log.info("No demo staff accounts created. Set CAREERFLUX_DEMO_PASSWORD to create a "
                    + "placement officer, a scoped coordinator and a college administrator.");
            return;
        }
        seedStaff(institution, departments.get(0));
    }

    private void seedStaff(Institution institution, Department computerScience) {
        User officer = staff(institution, "officer@" + DEMO_DOMAIN, "Priya Raman",
                UserRole.PLACEMENT_OFFICER);
        User admin = staff(institution, "college-admin@" + DEMO_DOMAIN, "Anil Kumar",
                UserRole.COLLEGE_ADMIN);
        User coordinator = staff(institution, "cse-coordinator@" + DEMO_DOMAIN, "Sneha Rao",
                UserRole.PLACEMENT_COORDINATOR);

        // The officer and the administrator see the whole college by role, so they
        // need no grant. The coordinator sees exactly one department — which is
        // the boundary the authorization tests are written against.
        staffScopeRepository.save(StaffScope.forDepartment(coordinator, institution, computerScience));

        log.info("Seeded demo staff: {} (officer), {} (college admin), {} (coordinator, scoped to {})",
                officer.getEmail(), admin.getEmail(), coordinator.getEmail(), computerScience.getCode());
    }

    private User staff(Institution institution, String email, String name, UserRole role) {
        User user = new User();
        user.setEmail(email.toLowerCase(Locale.ROOT));
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode(demoPassword));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setInstitution(institution);
        return userRepository.save(user);
    }

    private Department department(Institution institution, String name, String code) {
        Department department = new Department();
        department.setInstitution(institution);
        department.setName(name);
        department.setCode(code);
        return department;
    }

    private Batch batch(Institution institution, String name, int graduationYear) {
        Batch batch = new Batch();
        batch.setInstitution(institution);
        batch.setName(name);
        batch.setGraduationYear(graduationYear);
        return batch;
    }

}
