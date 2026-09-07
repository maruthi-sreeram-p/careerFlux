/** Shapes returned by the CareerFlux API. Mirrors the backend DTOs. */

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  violations?: { field: string; message: string }[];
  details?: Record<string, unknown>;
}

/**
 * The five roles the platform recognises. Kept as a union rather than a plain
 * string so a rename on the server breaks the build here instead of silently
 * turning a comparison into dead code.
 */
export type UserRole =
  | 'STUDENT'
  | 'PLACEMENT_COORDINATOR'
  | 'PLACEMENT_OFFICER'
  | 'COLLEGE_ADMIN'
  | 'PLATFORM_ADMIN';

export interface SessionUser {
  id: string;
  email: string;
  fullName: string;
  role: UserRole;
  /**
   * What this account may do, as granted by its role. Prefer checking a
   * permission over comparing the role: the question a screen actually has is
   * "may they do this?", and asking it that way survives new roles being added.
   */
  permissions: string[];
  institutionId: string | null;
  institutionName: string | null;
  candidateId: string | null;
  onboardingStage: OnboardingStage | null;
}

/** Convenience for the checks screens actually make. */
export function can(user: SessionUser | null, permission: string): boolean {
  return user?.permissions?.includes(permission) ?? false;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: SessionUser;
}

export type OnboardingStage =
  | 'RESUME_UPLOAD'
  | 'PROFILE_REVIEW'
  | 'PREFERENCES'
  | 'COMPLETE';

/* ------------------------------------------------------------- Candidate */

export interface SkillItem {
  id?: string;
  name: string;
  slug?: string;
  category?: string;
  proficiency?: string | null;
  years?: number | null;
  origin?: string;
  evidence?: string | null;
}

export interface ExperienceItem {
  id?: string;
  companyName: string;
  title: string;
  location?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  current: boolean;
  description?: string | null;
}

export interface EducationItem {
  id?: string;
  institution: string;
  degree?: string | null;
  fieldOfStudy?: string | null;
  startYear?: number | null;
  endYear?: number | null;
  grade?: string | null;
}

export interface PreferencesPayload {
  targetRoles: string[];
  industries: string[];
  locations: string[];
  workModes: string[];
  employmentTypes: string[];
  preferredCompanies: string[];
  salaryMin?: number | null;
  salaryMax?: number | null;
  salaryCurrency?: string | null;
  salaryPeriod?: string | null;
  openToRelocation: boolean;
  minExperienceYears?: number | null;
  maxExperienceYears?: number | null;
  immediateAlerts: boolean;
  dailyDigest: boolean;
}

export interface ResumeSummary {
  id: string;
  originalFilename: string;
  contentType: string | null;
  sizeBytes: number | null;
  parseStatus: 'PENDING' | 'PARSING' | 'PARSED' | 'NEEDS_REVIEW' | 'FAILED';
  parseEngine: string | null;
  parseError: string | null;
  uploadedAt: string;
  parsedAt: string | null;
}

export interface CandidateProfile {
  id: string;
  /** Null when nobody has recorded one. Absent is not zero. */
  cgpa?: number | null;
  cgpaScale?: number | null;
  /** STUDENT or INSTITUTION; only the latter is used for eligibility. */
  cgpaSource?: 'STUDENT' | 'INSTITUTION' | null;
  cgpaVerified?: boolean;
  fullName: string;
  email: string;
  headline: string | null;
  summary: string | null;
  location: string | null;
  phone: string | null;
  linkedinUrl: string | null;
  githubUrl: string | null;
  portfolioUrl: string | null;
  primaryRole: string | null;
  seniority: string | null;
  yearsExperience: number | null;
  onboardingStage: OnboardingStage;
  profileCompleteness: number;
  skills: SkillItem[];
  experiences: ExperienceItem[];
  education: EducationItem[];
  preferences: PreferencesPayload | null;
  resume: ResumeSummary | null;
}

export interface ResumeParseResult {
  resume: ResumeSummary;
  engine: string;
  aiAssisted: boolean;
  notice: string | null;
  profile: CandidateProfile;
}

/* ------------------------------------------------------------------ Jobs */

export interface CompanyRef {
  id: string;
  name: string;
  logoUrl: string | null;
  website: string | null;
  industry: string | null;
}

export interface SkillRef {
  name: string;
  slug: string;
  category: string;
  requirement: 'REQUIRED' | 'PREFERRED';
  extractedBy: 'DICTIONARY' | 'AI' | 'SOURCE';
}

export interface MatchComponentView {
  kind: 'STRENGTH' | 'GAP' | 'NEUTRAL';
  dimension: 'SKILLS' | 'EXPERIENCE' | 'ROLE' | 'LOCATION' | 'SENIORITY' | 'CONTEXT';
  label: string;
  detail: string | null;
}

export type MatchTier = 'EXCELLENT' | 'STRONG' | 'MODERATE' | 'WEAK' | 'HIDDEN';

export interface MatchAnalysis {
  overall: number;
  tier: MatchTier;
  skills: number;
  experience: number;
  role: number;
  location: number;
  seniority: number;
  strengths: MatchComponentView[];
  gaps: MatchComponentView[];
  context: MatchComponentView[];
  narrative: string | null;
  narrativeEngine: string | null;
  scorerVersion: string;
  computedAt: string;
}

export interface ProvenanceEntry {
  sourceId: string;
  sourceName: string;
  sourceType: string;
  atsProvider: string;
  discoveryMethod: string;
  sourceUrl: string | null;
  sourceState: SourceState;
  healthStatus: SourceHealthStatus;
  accessPolicy: string | null;
  policyVerifiedAt: string | null;
  firstObservedAt: string;
  lastObservedAt: string;
  observationCount: number;
  active: boolean;
  sampleData: boolean;
}

export interface ChangeEntry {
  changeType: string;
  summary: string;
  fieldName: string | null;
  previousValue: string | null;
  newValue: string | null;
  detectedAt: string;
}

export interface JobSummary {
  id: string;
  title: string;
  company: CompanyRef | null;
  location: string | null;
  city: string | null;
  workMode: string;
  employmentType: string;
  seniority: string;
  experience: { min: number | null; max: number | null } | null;
  salary: {
    min: number | null;
    max: number | null;
    currency: string | null;
    period: string | null;
  } | null;
  status: string;
  postedAt: string | null;
  firstObservedAt: string;
  lastObservedAt: string;
  sourceCount: number;
  primarySourceName: string | null;
  primarySourceType: string | null;
  sampleData: boolean;
  topSkills: SkillRef[];
  match: MatchAnalysis | null;
  interaction: {
    saved: boolean;
    dismissed: boolean;
    applied: boolean;
    applicationStatus: string | null;
  };
  recentChangeCount: number;
}

export interface JobDetail {
  summary: JobSummary;
  description: string | null;
  responsibilities: string | null;
  requirements: string | null;
  applyUrl: string | null;
  skills: SkillRef[];
  provenance: ProvenanceEntry[];
  changes: ChangeEntry[];
}

export interface JobPage {
  content: JobSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/* --------------------------------------------------------------- Sources */

export type SourceState =
  | 'DISCOVERED'
  | 'CLASSIFIED'
  | 'POLICY_REVIEW'
  | 'APPROVED'
  | 'ACTIVE'
  | 'PENDING_REVIEW'
  | 'DEGRADED'
  | 'BLOCKED'
  | 'RETIRED';

export type SourceHealthStatus =
  | 'UNKNOWN'
  | 'HEALTHY'
  | 'DEGRADED'
  | 'FAILING'
  | 'UNREACHABLE';

export interface PolicyView {
  robotsStatus: string;
  robotsUrl: string | null;
  robotsRule: string | null;
  robotsCheckedAt: string | null;
  crawlDelaySeconds: number | null;
  tosStatus: string;
  tosUrl: string | null;
  tosNotes: string | null;
  tosReviewedAt: string | null;
  tosReviewedBy: string | null;
  accessPolicy: string;
  requiresAuthentication: boolean;
  requiresCaptcha: boolean;
  hasAntiBot: boolean;
  paywalled: boolean;
  allowedFields: string | null;
  decision: string;
  decisionReason: string | null;
  decidedBy: string | null;
  verifiedAt: string | null;
}

export interface SourceSummary {
  id: string;
  name: string;
  baseUrl: string;
  sourceType: string;
  atsProvider: string;
  adapterKey: string | null;
  discoveryMethod: string;
  state: SourceState;
  healthStatus: SourceHealthStatus;
  companyName: string | null;
  companyId: string | null;
  discoveredAt: string;
  stateChangedAt: string;
  lastSuccessfulSyncAt: string | null;
  lastHealthCheckAt: string | null;
  nextReviewAt: string | null;
  consecutiveFailures: number;
  /** -1 means no sync has been attempted, which is not the same as 0%. */
  reliabilityPercent: number;
  jobsIngestedTotal: number;
  activeJobCount: number;
  rateLimitPerMinute: number;
  sampleData: boolean;
  needsAttention: boolean;
  policy: PolicyView | null;
}

export interface HealthCheckView {
  status: SourceHealthStatus;
  httpStatus: number | null;
  latencyMs: number | null;
  jobsSeen: number | null;
  message: string | null;
  checkedAt: string;
}

export interface LifecycleEntry {
  fromState: SourceState | null;
  toState: SourceState;
  reason: string | null;
  actor: string;
  occurredAt: string;
}

export interface IngestionRunView {
  id: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'SKIPPED';
  trigger: string;
  correlationId: string;
  rawCount: number;
  newCount: number;
  updatedCount: number;
  duplicateCount: number;
  closedCount: number;
  errorCount: number;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface SourceDetail {
  summary: SourceSummary;
  verdict: { passed: boolean; blockers: string[]; warnings: string[] };
  recentHealth: HealthCheckView[];
  lifecycle: LifecycleEntry[];
  recentRuns: IngestionRunView[];
  allowedNextStates: SourceState[];
  notes: string | null;
}

export interface SourceStats {
  total: number;
  byState: Record<string, number>;
  byHealth: Record<string, number>;
  needingAttention: number;
  jobsFromActiveSources: number;
  ingestionTransport: string;
  aiEnabled: boolean;
}

export interface AdapterInfo {
  key: string;
  displayName: string;
  sourceType: string;
  atsProvider: string;
  intendedAccessPolicy: string;
  documentationUrl: string | null;
  description: string;
}

/* ------------------------------------------------------------- Dashboard */

export interface DashboardSummary {
  greetingName: string;
  newSinceLastVisit: number;
  excellentMatches: number;
  strongMatches: number;
  totalVisibleMatches: number;
  sourcesMonitored: number;
  sourcesActive: number;
  openJobs: number;
  lastMatchComputedAt: string | null;
  profileCompleteness: number;
  onboardingStage: OnboardingStage;
  hasResume: boolean;
  aiEnabled: boolean;
  notice: string | null;
}

export interface ActivityItem {
  kind: string;
  title: string;
  detail: string;
  occurredAt: string;
  jobId: string | null;
  sourceId: string | null;
}

export interface DashboardResponse {
  summary: DashboardSummary;
  recommendations: JobSummary[];
  recentActivity: ActivityItem[];
  unreadNotifications: number;
  engagement: { saved: number; applied: number; dismissed: number };
}

/* --------------------------------------------------------- Notifications */

export interface NotificationView {
  id: string;
  category: string;
  priority: 'IMMEDIATE' | 'HIGH' | 'DIGEST' | 'LOW';
  title: string;
  body: string | null;
  jobId: string | null;
  jobTitle: string | null;
  readAt: string | null;
  createdAt: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/* ----------------------------------------------------------------- Admin */

export interface SystemStats {
  totalJobs: number;
  openJobs: number;
  closedJobs: number;
  jobsIngestedLast24h: number;
  jobChangesLast24h: number;
  ingestionRunsLast24h: number;
  failedRunsLast24h: number;
  pendingPipelineEvents: number;
  failedPipelineEvents: number;
  pipelineEventsLast24hByTopic: Record<string, number>;
  users: number;
  admins: number;
  ingestionTransport: string;
  aiEnabled: boolean;
  aiModel: string;
  sources: SourceStats;
}

export interface PipelineEventView {
  id: string;
  topic: string;
  eventKey: string;
  status: 'PENDING' | 'PROCESSED' | 'FAILED';
  attempts: number;
  error: string | null;
  createdAt: string;
  processedAt: string | null;
}

export interface AuditView {
  id: string;
  actor: string;
  action: string;
  entityType: string | null;
  entityId: string | null;
  detail: string | null;
  occurredAt: string;
}

/* ------------------------------------------------- Institutional overview */

/** One slice of a cohort: a department, a batch, or a skill. */
export interface CohortCount {
  label: string;
  count: number;
}

/**
 * The counts behind the coordinator, placement officer and college admin
 * dashboards. Scope is applied on the server, so `studentsInScope` means "the
 * students this caller may see" and nothing wider.
 *
 * `averageProfileCompleteness` is null when nobody in scope has a profile, so
 * the screen can say "no data" instead of showing a measured-looking 0%.
 */
export interface InstitutionOverview {
  institutionName: string;
  scopeLabel: string;
  institutionWide: boolean;
  studentsInScope: number;
  activeStudents: number;
  withCandidateProfile: number;
  withCareerProfile: number;
  withResume: number;
  withSkills: number;
  averageProfileCompleteness: number | null;
  studentsWhoApplied: number;
  totalApplications: number;
  studentsWithoutApplications: number;
  savedJobs: number;
  byDepartment: CohortCount[];
  byBatch: CohortCount[];
  topSkills: CohortCount[];
  departmentCount: number;
  batchCount: number;
  staffCount: number;
}

/** One row of the scoped staff directory, exactly as the server sends it. */
export interface StudentRow {
  userId: string;
  candidateId: string | null;
  fullName: string;
  email: string;
  rollNumber: string | null;
  departmentName: string | null;
  batchName: string | null;
  primaryRole: string | null;
  onboardingStage: string | null;
  /** Always a number: the server sends 0 for a student with no profile yet. */
  profileCompleteness: number;
  resumeUploaded: boolean;
  /** The stored value on the institution's own scale, or null if never recorded. */
  cgpa: string | null;
  cgpaScale: string | null;
  /** The same figure on a ten-point scale, which is what filters compare. */
  normalisedCgpa: string | null;
  joinedAt: string | null;
}

export interface StudentSkillRef {
  id: string;
  name: string;
  slug: string;
}

export interface StudentPlacement {
  requirementId: string;
  companyName: string;
  roleTitle: string;
  stage: string;
  stageChangedAt: string | null;
}

export interface StudentActivityEntry {
  at: string | null;
  type: string;
  summary: string;
}

export interface StudentDetail {
  summary: StudentRow;
  headline: string | null;
  location: string | null;
  cgpa: string | null;
  cgpaScale: string | null;
  normalisedCgpa: string | null;
  cgpaSource: string | null;
  skills: StudentSkillRef[];
  preferences: string[];
  placements: StudentPlacement[];
  activity: StudentActivityEntry[];
}

/** Everything the Students page can ask the directory for. */
export interface StudentQueryParams {
  q?: string;
  departmentId?: string;
  batchId?: string;
  minCgpa?: number;
  skills?: string[];
  minProfileCompleteness?: number;
  resumeUploaded?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

export interface StudentPage {
  content: StudentRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/* ------------------------------------------------- Company requirements */

export type RequirementStatus = 'DRAFT' | 'OPEN' | 'CLOSED';

export interface RequirementSkill {
  skill: string;
  slug: string;
  tier: 'REQUIRED' | 'PREFERRED' | 'OPTIONAL';
}

export interface RequirementDepartment {
  id: string;
  name: string;
  code: string;
}

/**
 * A company's hiring brief, as the placement office recorded it.
 *
 * `minCgpa` is stored but nothing evaluates it yet — student CGPA is not
 * modelled. It is shown back as what the company asked for, never as an
 * eligibility verdict about any student.
 *
 * `skillsUnresolved` names skills CareerFlux did not recognise. They are not
 * stored and will not be searched on, so the screen has to say so.
 */
export interface Requirement {
  id: string;
  companyName: string;
  roleTitle: string;
  description: string | null;
  minExperienceYears: number | null;
  maxExperienceYears: number | null;
  graduationYear: number | null;
  minCgpa: number | null;
  workMode: string;
  location: string | null;
  status: RequirementStatus;
  driveDate: string | null;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  requiredSkills: RequirementSkill[];
  preferredSkills: RequirementSkill[];
  optionalSkills: RequirementSkill[];
  departments: RequirementDepartment[];
  skillsUnresolved: string[];
}

export interface RequirementPage {
  content: Requirement[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateRequirementPayload {
  companyName: string;
  roleTitle: string;
  description?: string | null;
  minExperienceYears?: number | null;
  maxExperienceYears?: number | null;
  graduationYear?: number | null;
  minCgpa?: number | null;
  workMode?: string;
  location?: string | null;
  driveDate?: string | null;
  departmentIds?: string[];
  skills?: { skill: string; tier: string }[];
}

export interface DepartmentView {
  id: string;
  name: string;
  code: string;
  studentCount: number;
}

export interface BatchView {
  id: string;
  name: string;
  graduationYear: number;
  studentCount: number;
}

/**
 * A member of placement staff, as an administrator sees them.
 *
 * `institutionWide` is true when no scope row narrows the account: an officer or
 * an administrator covers the whole college. `scopeLabels` lists the department
 * codes a coordinator is responsible for.
 */
export interface StaffRow {
  userId: string;
  fullName: string;
  email: string;
  role: string;
  institutionWide: boolean;
  scopeLabels: string[];
}

/** What creating a staff account returns. Never the password. */
export interface StaffCreated {
  id: string;
  fullName: string;
  email: string;
  role: string;
  scope: string;
}

/* ---------------------------------------------------- Candidate discovery */

export interface DiscoveryDimension {
  dimension: string;
  score: number | null;
  unknownSide: string | null;
}

export interface DiscoveryReason {
  kind: string;
  dimension: string | null;
  label: string;
  detail: string | null;
}

/**
 * One student measured against a company requirement.
 *
 * Two answers, never blended. `compatibility` is how well their profile lines
 * up with what the company described; `eligibility` is whether the conditions
 * the company stated are satisfied. A high number beside NOT_ELIGIBLE is the
 * case this feature exists for, not a contradiction.
 *
 * `compatibility` is null when the scorer had too little to go on — shown as
 * "not enough information", never as 0%.
 */
export interface DiscoveredCandidate {
  candidateId: string;
  userId: string;
  fullName: string;
  department: string | null;
  batch: string | null;
  cgpa: number | null;
  compatibility: number | null;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | 'INSUFFICIENT';
  confidenceCoverage: number;
  eligibility: 'ELIGIBLE' | 'ELIGIBLE_WITH_GAPS' | 'NOT_ELIGIBLE' | 'UNKNOWN';
  eligibilityReasons: string[];
  matchedRequiredSkills: string[];
  missingRequiredSkills: string[];
  matchedPreferredSkills: string[];
  missingPreferredSkills: string[];
  yearsExperience: number | null;
  primaryRole: string | null;
  dimensions: DiscoveryDimension[];
  strengths: DiscoveryReason[];
  gaps: DiscoveryReason[];
  /** Whether the placement team has already chosen to put this student forward. */
  shortlisted: boolean;
  /** Where they have got to in the drive. Null unless shortlisted. */
  placementStage: PlacementStage | null;
}

/**
 * The six states a shortlisted candidate can be in.
 *
 * <p>Decided by the server; see PlacementStage.java. NOT_PROCEEDING is the
 * college's decision and DECLINED is the student's, and they are kept apart
 * because afterwards that is the only thing anybody asks.
 */
export type PlacementStage =
  | 'SHORTLISTED'
  | 'INVITED'
  | 'INTERESTED'
  | 'SELECTED'
  | 'NOT_PROCEEDING'
  | 'DECLINED';

/** One move in a candidate's placement history. Append-only on the server. */
export interface StageChange {
  id: string;
  fromStage: PlacementStage | null;
  toStage: PlacementStage;
  actorLabel: string | null;
  actorKind: 'STAFF' | 'STUDENT';
  note: string | null;
  occurredAt: string;
}

/**
 * One drive, as the student it is about sees it.
 *
 * <p>Notably missing: match scores, eligibility verdicts and skill gaps. Those
 * are the college's working notes about a person and the server does not return
 * them here.
 */
export interface MyPlacement {
  requirementId: string;
  companyName: string;
  roleTitle: string;
  location: string | null;
  workMode: string | null;
  requirementStatus: string;
  stage: PlacementStage;
  stageLabel: string;
  awaitingYou: boolean;
  closed: boolean;
  shortlistedAt: string | null;
  stageChangedAt: string | null;
}

export interface CandidatePage {
  requirementId: string;
  companyName: string;
  roleTitle: string;
  requirementStatus: string;
  requiredSkills: string[];
  preferredSkills: string[];
  targetDepartments: string[];
  targetGraduationYear: number | null;
  minCgpa: number | null;
  scopeLabel: string;
  consideredStudents: number;
  /** False while CareerFlux holds no verified numeric CGPA, so the page explains the UNKNOWNs once. */
  cgpaAvailable: boolean;
  /** Read from the shortlist table, never accumulated in the browser. */
  shortlistedCount: number;
  content: DiscoveredCandidate[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DiscoveryQuery {
  eligibility?: string;
  minScore?: number;
  sort?: string;
  /** Undefined shows everyone; true or false narrows to one side. */
  shortlisted?: boolean;
}

/** What the server returns after a shortlist decision is recorded. */
export interface ShortlistResult {
  candidateId: string;
  shortlisted: boolean;
  shortlistedCount: number;
}

/** A student's academic record as the server returns it after a change. */
export interface AcademicRecord {
  cgpa: number | null;
  cgpaScale: number | null;
  source: 'STUDENT' | 'INSTITUTION' | null;
  verified: boolean;
  recordedByName: string | null;
  recordedAt: string | null;
}

/** A college as the platform operator sees it in the onboarding list. */
export interface InstitutionSummary {
  id: string;
  name: string;
  slug: string;
  emailDomains: string | null;
  status: string;
  createdAt: string;
}

/** What onboarding returns. No password, no session, no token. */
export interface ProvisionedInstitution extends InstitutionSummary {
  registrationCode: string | null;
  collegeAdminId: string | null;
  collegeAdminEmail: string | null;
}

/** What a platform operator may state when onboarding a college. */
export interface ProvisionInstitutionInput {
  name: string;
  emailDomains?: string;
  shortName?: string;
  city?: string;
  registrationCode?: string;
  initialAdmin?: { fullName: string; email: string; password: string };
}

/* ------------------------------------------------- Company source discovery */

/**
 * What the resolver concluded about one typed company name.
 *
 * <p>Three outcomes, three different next steps for the operator: confirm,
 * choose, or type a domain in. Decided by the server; see CompanyResolver.java.
 */
export type ResolutionOutcome = 'RESOLVED' | 'AMBIGUOUS' | 'NOT_FOUND';

/** Why a domain is being proposed. Shown so an operator can weigh it. */
export type CandidateEvidence = 'REGISTRY' | 'SEED_LIST' | 'VERIFIED_SITE';

export interface DomainCandidate {
  domain: string;
  evidence: CandidateEvidence;
  detail: string;
}

export interface ResolvedCompany {
  companyName: string;
  slug: string;
  outcome: ResolutionOutcome;
  detail: string;
  candidates: DomainCandidate[];
}

/** One source that discovery registered, straight from the existing service. */
export interface DiscoveredSource {
  sourceId: string;
  name: string;
  domain: string;
  provider: string;
  boardToken: string;
  howFound: string;
  detail: string;
}

/**
 * The reply to both phases of discover-by-company.
 *
 * <p>A resolve fills the first three lists and leaves the rest empty; a
 * confirmed-domain call does the opposite. Keeping one shape means the screen
 * does not have to model two endpoints.
 */
export interface CompanyDiscoveryResult {
  resolved: ResolvedCompany[];
  ambiguous: ResolvedCompany[];
  notFound: ResolvedCompany[];
  registered: DiscoveredSource[];
  alreadyKnown: string[];
  withoutBoard: string[];
}
