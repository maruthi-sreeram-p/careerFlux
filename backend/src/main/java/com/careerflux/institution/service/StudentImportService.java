package com.careerflux.institution.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.careerflux.audit.AuditService;
import com.careerflux.auth.AuthService;
import com.careerflux.candidate.service.CandidateProfileService;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.NotFoundException;
import com.careerflux.institution.domain.Batch;
import com.careerflux.institution.domain.Department;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.repository.BatchRepository;
import com.careerflux.institution.repository.DepartmentRepository;
import com.careerflux.institution.repository.InstitutionRepository;
import com.careerflux.security.CurrentUser;
import com.careerflux.security.access.AccessGuard;
import com.careerflux.security.access.AccessScope;
import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRepository;
import com.careerflux.user.UserRole;
import com.careerflux.user.UserStatus;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Creates student accounts from a spreadsheet uploaded by placement staff.
 *
 * <p><b>Who may add whom.</b> The college is the caller's own, read from the
 * session. A department coordinator may add students only to departments in
 * their own scope (and batches, when they hold batch grants); a placement
 * coordinator may add to any department of the college. Every row is checked,
 * not just the sheet.
 *
 * <p><b>No password is chosen for anyone.</b> Each account is created with a
 * random password nobody knows, plus a seven-day set-your-password link emailed
 * to the student. The link is the same single-use token a password reset uses,
 * so it is stored only as a digest. Without a mail server accounts are still
 * created and the result says no invites were sent; the student can use
 * "Forgot password" once mail is configured.
 *
 * <p><b>Rows are independent.</b> A bad row is reported and skipped; it does not
 * stop the others. Re-uploading the same sheet creates nothing twice, because an
 * existing email is reported as already present.
 */
@Service
public class StudentImportService {

    private static final Logger log = LoggerFactory.getLogger(StudentImportService.class);

    static final int MAX_ROWS = 500;
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Duration INVITE_TTL = Duration.ofDays(7);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    // The sheet's columns, with the other names a college is likely to use for each.
    static final String H_NAME = "Name";
    static final String H_EMAIL = "College Mail";
    static final String H_BRANCH = "Branch";
    static final String H_YEAR = "Academic Year";
    static final String H_SECTION = "Section";
    private static final List<String> NAME_ALIASES = List.of(H_NAME, "Full Name", "Student Name");
    private static final List<String> EMAIL_ALIASES = List.of(H_EMAIL, "College Email", "Email", "Mail", "Email Id");
    private static final List<String> BRANCH_ALIASES = List.of(H_BRANCH, "Department", "Dept", "Department Code");
    private static final List<String> YEAR_ALIASES = List.of(H_YEAR, "Accy Year", "Academic Yr", "Year", "Batch",
            "Graduation Year");
    private static final List<String> SECTION_ALIASES = List.of(H_SECTION, "Sec", "Class Section");

    private final UserRepository users;
    private final DepartmentRepository departments;
    private final BatchRepository batches;
    private final InstitutionRepository institutions;
    private final CandidateProfileService profiles;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final AccessGuard accessGuard;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String appUrl;

    public StudentImportService(UserRepository users,
                                DepartmentRepository departments,
                                BatchRepository batches,
                                InstitutionRepository institutions,
                                CandidateProfileService profiles,
                                AuthService authService,
                                PasswordEncoder passwordEncoder,
                                AuditService audit,
                                CurrentUser currentUser,
                                AccessGuard accessGuard,
                                ObjectProvider<JavaMailSender> mailSender,
                                @Value("${careerflux.mail.from:no-reply@careerflux.local}") String from,
                                @Value("${careerflux.mail.app-url:http://localhost:5173}") String appUrl) {
        this.users = users;
        this.departments = departments;
        this.batches = batches;
        this.institutions = institutions;
        this.profiles = profiles;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.mailSender = mailSender;
        this.from = from;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    public record RowResult(int row, String email, String status, String message) {
    }

    public record ImportResult(int created, int skipped, boolean invitesEmailed, List<RowResult> rows) {
    }

    /** A blank sheet with the expected headings and one example row. */
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            String[] headings = {H_NAME, H_EMAIL, H_BRANCH, H_YEAR, H_SECTION};
            String[] example = {"Asha Verma", "asha.verma@your-college.edu", "CSE", "2026", "A"};
            Row sample = sheet.createRow(1);
            for (int i = 0; i < headings.length; i++) {
                header.createCell(i).setCellValue(headings[i]);
                sample.createCell(i).setCellValue(example[i]);
                sheet.setColumnWidth(i, 6000);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build the template", ex);
        }
    }

    @Transactional
    public ImportResult importStudents(MultipartFile file) {
        accessGuard.requirePermission(Permission.STUDENT_IMPORT);
        Institution institution = callersInstitution();
        AccessScope scope = accessGuard.scope();
        byte[] bytes = readUpload(file);

        // A branch is written as its code (CSE) or its name (Computer Science), in any case.
        Map<String, Department> byDepartment = new java.util.HashMap<>();
        for (Department department : departments.findByInstitutionIdOrderByNameAsc(institution.getId())) {
            byDepartment.putIfAbsent(department.getCode().strip().toUpperCase(Locale.ROOT), department);
            byDepartment.putIfAbsent(department.getName().strip().toUpperCase(Locale.ROOT), department);
        }
        boolean restrictDomain = institution.getEmailDomains() != null && !institution.getEmailDomains().isBlank();
        List<Batch> allBatches = batches.findByInstitutionIdOrderByGraduationYearDescNameAsc(institution.getId());

        List<RowResult> results = new ArrayList<>();
        List<PendingInvite> invites = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int created = 0;

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> columns = columnsOf(sheet.getRow(0));
            int nameColumn = columnFor(columns, NAME_ALIASES);
            int emailColumn = columnFor(columns, EMAIL_ALIASES);
            int branchColumn = columnFor(columns, BRANCH_ALIASES);
            int yearColumn = columnFor(columns, YEAR_ALIASES);
            int sectionColumn = columnFor(columns, SECTION_ALIASES);
            if (nameColumn < 0 || emailColumn < 0 || yearColumn < 0) {
                throw new BadRequestException("The sheet needs Name, College Mail and Academic Year columns. "
                        + "Download the template.");
            }
            int lastRow = sheet.getLastRowNum();
            if (lastRow > MAX_ROWS) {
                throw new BadRequestException("A sheet can add at most " + MAX_ROWS + " students at a time.");
            }
            DataFormatter formatter = new DataFormatter();
            for (int index = 1; index <= lastRow; index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter)) {
                    continue;
                }
                String name = cell(row, nameColumn, formatter);
                String email = cell(row, emailColumn, formatter).toLowerCase(Locale.ROOT);
                String branch = cell(row, branchColumn, formatter);
                String year = cell(row, yearColumn, formatter);
                String section = cell(row, sectionColumn, formatter);
                int shown = index + 1;

                String problem = validate(name, email, section, seen);
                if (problem == null && restrictDomain && !institution.acceptsEmail(email)) {
                    problem = "Email must be a college address (" + institution.getEmailDomains().strip() + ").";
                }
                Department department = null;
                Batch batch = null;
                if (problem == null) {
                    DepartmentOrProblem resolved = resolveDepartment(branch, byDepartment, scope);
                    problem = resolved.problem();
                    department = resolved.department();
                }
                if (problem == null) {
                    BatchOrProblem resolved = resolveBatch(year, department, allBatches, scope);
                    problem = resolved.problem();
                    batch = resolved.batch();
                }
                if (problem == null && users.existsByEmailIgnoreCase(email)) {
                    problem = "An account with this email already exists.";
                }
                if (problem != null) {
                    results.add(new RowResult(shown, email, "SKIPPED", problem));
                    continue;
                }

                seen.add(email);
                User student = new User();
                student.setEmail(email);
                student.setFullName(name);
                student.setPasswordHash(passwordEncoder.encode(randomSecret()));
                student.setRole(UserRole.STUDENT);
                student.setStatus(UserStatus.ACTIVE);
                student.setInstitution(institution);
                student.setDepartment(department);
                student.setBatch(batch);
                student.setSection(section.isEmpty() ? null : section);
                String token = authService.issueInviteToken(student, INVITE_TTL);
                users.save(student);
                profiles.createForUser(student);
                audit.record("STUDENT_IMPORTED", "User", student.getId(),
                        "department=" + department.getCode() + ", batch=" + batch.getName());
                invites.add(new PendingInvite(student.getFullName(), email, token));
                results.add(new RowResult(shown, email, "CREATED", "Account created."));
                created++;
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof BadRequestException bad) {
                throw bad;
            }
            log.warn("Student import could not be read: {}", ex.getClass().getSimpleName());
            throw new BadRequestException("That file could not be read. Use the template (.xlsx).");
        }

        boolean canEmail = mailSender.getIfAvailable() != null;
        if (!invites.isEmpty()) {
            sendAfterCommit(invites);
        }
        log.info("Student import for institution {}: {} created, {} skipped",
                institution.getSlug(), created, results.size() - created);
        return new ImportResult(created, results.size() - created, canEmail && created > 0, results);
    }

    // ------------------------------------------------------------ validation

    private static String validate(String name, String email, String section, Set<String> seen) {
        if (name.isEmpty()) {
            return "Full Name is required.";
        }
        if (name.length() > 200) {
            return "Full Name is too long.";
        }
        if (email.isEmpty() || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            return "Email is missing or not a valid address.";
        }
        if (section.length() > 40) {
            return "Section is too long.";
        }
        if (seen.contains(email)) {
            return "This email appears twice in the sheet.";
        }
        return null;
    }

    private record DepartmentOrProblem(Department department, String problem) {
    }

    private record BatchOrProblem(Batch batch, String problem) {
    }

    private DepartmentOrProblem resolveDepartment(String branch, Map<String, Department> byDepartment,
                                                  AccessScope scope) {
        Department department;
        if (branch.isEmpty()) {
            // A coordinator with exactly one department need not repeat it on every row.
            if (!scope.institutionWide() && scope.departmentIds().size() == 1) {
                UUID only = scope.departmentIds().iterator().next();
                department = byDepartment.values().stream().filter(d -> d.getId().equals(only)).findFirst().orElse(null);
            } else {
                return new DepartmentOrProblem(null, "Branch is required.");
            }
        } else {
            department = byDepartment.get(branch.strip().toUpperCase(Locale.ROOT));
        }
        if (department == null) {
            return new DepartmentOrProblem(null, "No branch called that in this college.");
        }
        if (!scope.institutionWide() && !scope.departmentIds().contains(department.getId())) {
            // Same wording as an unknown branch: a coordinator learns nothing about
            // departments that are not theirs.
            return new DepartmentOrProblem(null, "No branch called that in this college.");
        }
        return new DepartmentOrProblem(department, null);
    }

    private BatchOrProblem resolveBatch(String year, Department department, List<Batch> allBatches,
                                        AccessScope scope) {
        int graduationYear = graduationYearOf(year);
        if (graduationYear < 0) {
            return new BatchOrProblem(null, "Academic Year must be the year the student graduates (2026) "
                    + "or their batch (2022-2026).");
        }
        Optional<Batch> batch = allBatches.stream()
                .filter(b -> b.getGraduationYear() == graduationYear)
                .filter(b -> b.getDepartment() == null || b.getDepartment().getId().equals(department.getId()))
                .findFirst();
        if (batch.isEmpty()) {
            return new BatchOrProblem(null, "No " + graduationYear + " batch exists for " + department.getCode()
                    + ". Ask the placement coordinator to create it.");
        }
        if (!scope.covers(department.getId(), batch.get().getId())) {
            return new BatchOrProblem(null, "That batch is outside your access.");
        }
        return new BatchOrProblem(batch.get(), null);
    }

    // ----------------------------------------------------------------- sheet

    private byte[] readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a spreadsheet to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BadRequestException("That file is larger than 2 MB.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) {
            throw new BadRequestException("Upload an Excel .xlsx file. Download the template to start.");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            // .xlsx is a zip: anything else is not what the name claims.
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
                throw new BadRequestException("That file is not a valid .xlsx workbook.");
            }
            return bytes;
        } catch (IOException ex) {
            throw new BadRequestException("That file could not be read.");
        }
    }

    private static String key(String heading) {
        return heading.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private Map<String, Integer> columnsOf(Row header) {
        if (header == null) {
            throw new BadRequestException("The sheet is empty. Download the template.");
        }
        DataFormatter formatter = new DataFormatter();
        Map<String, Integer> columns = new java.util.HashMap<>();
        for (Cell cell : header) {
            columns.putIfAbsent(key(formatter.formatCellValue(cell)), cell.getColumnIndex());
        }
        return columns;
    }

    /** The first of these headings the sheet has, or -1. */
    private static int columnFor(Map<String, Integer> columns, List<String> aliases) {
        for (String alias : aliases) {
            Integer column = columns.get(key(alias));
            if (column != null) {
                return column;
            }
        }
        return -1;
    }

    private static String cell(Row row, int column, DataFormatter formatter) {
        if (column < 0) {
            return "";
        }
        Cell cell = row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }

    private static final Pattern GRADUATION_YEAR = Pattern.compile("^(20\\d{2})(?:\\.0+)?$");
    private static final Pattern BATCH_RANGE = Pattern.compile("^(20\\d{2})\\s*[-/]\\s*(?:20)?(\\d{2})$");

    /**
     * The graduation year an Academic Year cell means, or -1 when it is neither
     * a graduation year ({@code 2026}) nor a batch ({@code 2022-2026} or
     * {@code 2022-26}, taken as its end year).
     *
     * <p>A year of study ("3rd year") is deliberately not accepted. What it means
     * depends on the programme's length, which differs between B.Tech, M.Tech,
     * MBA and the rest, and a wrong guess would put a student in the wrong batch.
     * Colleges issue accounts to their final-year students, so the sheet names
     * the year they graduate.
     */
    public static int graduationYearOf(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher year = GRADUATION_YEAR.matcher(value);
        if (year.matches()) {
            return Integer.parseInt(year.group(1));
        }
        java.util.regex.Matcher range = BATCH_RANGE.matcher(value);
        if (range.matches()) {
            int start = Integer.parseInt(range.group(1));
            int end = 2000 + Integer.parseInt(range.group(2));
            return end > start && end - start <= 6 ? end : -1;
        }
        return -1;
    }

    private static boolean isBlank(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ----------------------------------------------------------------- email

    private record PendingInvite(String name, String email, String token) {
    }

    private void sendAfterCommit(List<PendingInvite> invites) {
        Runnable send = () -> sendInvites(invites);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void sendInvites(List<PendingInvite> invites) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.info("{} student invites not emailed: no mail server is configured", invites.size());
            return;
        }
        int failed = 0;
        for (PendingInvite invite : invites) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(invite.email());
                message.setSubject("Your CareerFlux account is ready");
                message.setText("Hello " + invite.name() + ",\n\nYour college has added you to CareerFlux. "
                        + "Open this link to choose your password and sign in:\n\n"
                        + appUrl + "/reset-password?token=" + invite.token()
                        + "\n\nThe link works once and expires in 7 days. If you were not expecting this, "
                        + "ignore this email.");
                sender.send(message);
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("{} of {} student invites could not be sent", failed, invites.size());
        }
    }

    private Institution callersInstitution() {
        UUID institutionId = Optional.ofNullable(currentUser.require().getInstitutionId())
                .orElseThrow(() -> new BadRequestException("This account does not belong to a college."));
        return institutions.findById(institutionId)
                .orElseThrow(() -> new NotFoundException("Institution not found."));
    }
}
