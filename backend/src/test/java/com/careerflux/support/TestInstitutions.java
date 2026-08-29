package com.careerflux.support;

import java.util.UUID;

import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two colleges, committed before any test runs.
 *
 * <p>Registration now refuses an address no institution claims, so every
 * integration test needs a tenant to register into. This runs as an
 * {@link ApplicationRunner} rather than a {@code @BeforeEach} on purpose: the
 * integration tests are {@code @Transactional} and roll back, so anything they
 * create themselves disappears. These rows are committed once with the context
 * and outlive every rollback.
 *
 * <p>There are two of them because one is not enough to prove isolation. Most
 * tests live in {@link #EXAMPLE_DOMAIN}; {@link #RIVAL_DOMAIN} exists so a test
 * can put a student in a different college and show that a member of staff here
 * cannot see them.
 */
@Component
@Profile("test")
@Order(0)
public class TestInstitutions implements ApplicationRunner {

    public static final String EXAMPLE_SLUG = "example-university";
    public static final String EXAMPLE_DOMAIN = "example.com";
    public static final String EXAMPLE_CODE = "EXAMPLE-TEST";

    public static final String RIVAL_SLUG = "rival-college";
    public static final String RIVAL_DOMAIN = "rival.edu";
    public static final String RIVAL_CODE = "RIVAL-TEST";

    private final InstitutionRepository institutions;
    private final DepartmentRepository departments;
    private final BatchRepository batches;

    private UUID exampleId;
    private UUID rivalId;
    private UUID exampleCseId;
    private UUID exampleMechId;
    private UUID exampleBatch2026Id;
    private UUID exampleBatch2027Id;

    public TestInstitutions(InstitutionRepository institutions,
                            DepartmentRepository departments,
                            BatchRepository batches) {
        this.institutions = institutions;
        this.departments = departments;
        this.batches = batches;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Institution example = institutions.findBySlug(EXAMPLE_SLUG)
                .orElseGet(() -> institutions.save(
                        institution("Example University", EXAMPLE_SLUG, EXAMPLE_DOMAIN, EXAMPLE_CODE)));
        Institution rival = institutions.findBySlug(RIVAL_SLUG)
                .orElseGet(() -> institutions.save(
                        institution("Rival College", RIVAL_SLUG, RIVAL_DOMAIN, RIVAL_CODE)));

        exampleId = example.getId();
        rivalId = rival.getId();

        exampleCseId = departmentId(example, "Computer Science", "CSE");
        exampleMechId = departmentId(example, "Mechanical Engineering", "MECH");
        exampleBatch2026Id = batchId(example, "Class of 2026", 2026);
        exampleBatch2027Id = batchId(example, "Class of 2027", 2027);
    }

    private Institution institution(String name, String slug, String domain, String code) {
        Institution institution = new Institution();
        institution.setName(name);
        institution.setSlug(slug);
        institution.setShortName(name);
        institution.setStatus(InstitutionStatus.ACTIVE);
        institution.setEmailDomains(domain);
        institution.setRegistrationCode(code);
        return institution;
    }

    private UUID departmentId(Institution institution, String name, String code) {
        return departments.findByInstitutionIdAndCode(institution.getId(), code)
                .orElseGet(() -> {
                    Department department = new Department();
                    department.setInstitution(institution);
                    department.setName(name);
                    department.setCode(code);
                    return departments.save(department);
                })
                .getId();
    }

    private UUID batchId(Institution institution, String name, int graduationYear) {
        return batches.findByInstitutionIdAndName(institution.getId(), name)
                .orElseGet(() -> {
                    Batch batch = new Batch();
                    batch.setInstitution(institution);
                    batch.setName(name);
                    batch.setGraduationYear(graduationYear);
                    return batches.save(batch);
                })
                .getId();
    }

    /** The institution behind {@code @example.com}, where most test users live. */
    public Institution example() {
        return institutions.findById(exampleId).orElseThrow();
    }

    /** A second, unrelated college, used to prove tenant isolation. */
    public Institution rival() {
        return institutions.findById(rivalId).orElseThrow();
    }

    public Department exampleCse() {
        return departments.findById(exampleCseId).orElseThrow();
    }

    public Department exampleMech() {
        return departments.findById(exampleMechId).orElseThrow();
    }

    public Batch exampleBatch2026() {
        return batches.findById(exampleBatch2026Id).orElseThrow();
    }

    public Batch exampleBatch2027() {
        return batches.findById(exampleBatch2027Id).orElseThrow();
    }
}
