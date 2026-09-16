import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api, queryString, tokenStore } from './api';
import { exportFileName } from './privacy';
import type {
  AcademicRecord,
  AdapterInfo,
  CandidateProfile,
  ConsentOverview,
  ConsentPurpose,
  ErasureView,
  ResumeSummary,
  DashboardResponse,
  CandidatePage,
  CompanyDiscoveryResult,
  MyPlacement,
  PlacementStage,
  StageChange,
  CreateRequirementPayload,
  DiscoveryQuery,
  ShortlistResult,
  StaffCreated,
  StaffRow,
  BatchView,
  DepartmentView,
  InstitutionOverview,
  JobDetail,
  JobPage,
  JobSummary,
  NotificationView,
  Page,
  PreferencesPayload,
  Requirement,
  RequirementPage,
  ResumeParseResult,
  ProposalView,
  ReviewResult,
  Decision,
  SourceDetail,
  SourceStats,
  SourceSummary,
  StudentDetail,
  StudentPage,
  StudentQueryParams,
  SystemStats,
  InstitutionSummary,
  ProvisionedInstitution,
  ProvisionInstitutionInput,
} from './types';

/** Query keys in one place so invalidation cannot drift from subscription. */
export const keys = {
  dashboard: ['dashboard'] as const,
  profile: ['candidate', 'profile'] as const,
  jobs: (params: unknown) => ['jobs', params] as const,
  job: (id: string) => ['job', id] as const,
  recommended: ['jobs', 'recommended'] as const,
  saved: ['jobs', 'saved'] as const,
  applied: ['jobs', 'applied'] as const,
  sources: (params: unknown) => ['sources', params] as const,
  source: (id: string) => ['source', id] as const,
  sourceStats: ['sources', 'stats'] as const,
  adapters: ['sources', 'adapters'] as const,
  notifications: ['notifications'] as const,
  unreadCount: ['notifications', 'unread'] as const,
  systemStats: ['admin', 'stats'] as const,
  pendingProposal: ['candidate', 'resume-proposal', 'pending'] as const,
  resumes: ['candidate', 'resumes'] as const,
  consents: ['candidate', 'consents'] as const,
  erasure: ['candidate', 'erasure'] as const,
  studentErasure: (userId: string) => ['institution', 'students', userId, 'erasure'] as const,
};

/* ------------------------------------------------------------- Dashboard */

export function useDashboard() {
  return useQuery({
    queryKey: keys.dashboard,
    queryFn: ({ signal }) => api.get<DashboardResponse>('/api/dashboard', signal),
  });
}

/* ------------------------------------------------------------- Candidate */

export function useProfile() {
  return useQuery({
    queryKey: keys.profile,
    queryFn: ({ signal }) => api.get<CandidateProfile>('/api/candidate/profile', signal),
  });
}

export function useUploadResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return api.upload<ResumeParseResult>('/api/candidate/resume', form);
    },
    onSuccess: (result) => {
      queryClient.setQueryData(keys.profile, result.profile);
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
      // The upload did not change the profile. What it produced is a reading
      // waiting for the student, so the review panel needs to refetch.
      queryClient.invalidateQueries({ queryKey: keys.pendingProposal });
    },
  });
}

/* --------------------------------------------------- AI profile proposals */

/** The reading still waiting for this student, if any. 204 arrives as undefined. */
export function usePendingProposal() {
  return useQuery({
    queryKey: keys.pendingProposal,
    queryFn: ({ signal }) =>
      api.get<ProposalView | undefined>('/api/candidate/resume-proposals/pending', signal),
  });
}

export function useApproveProposal(proposalId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (decisions: Decision[]) =>
      api.post<ReviewResult>(
        `/api/candidate/resume-proposals/${proposalId}/approve`,
        { decisions },
      ),
    onSuccess: (result) => {
      queryClient.setQueryData(keys.profile, result.profile);
      queryClient.setQueryData(keys.pendingProposal, undefined);
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

export function useRejectProposal(proposalId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<ReviewResult>(`/api/candidate/resume-proposals/${proposalId}/reject`),
    onSuccess: (result) => {
      queryClient.setQueryData(keys.profile, result.profile);
      queryClient.setQueryData(keys.pendingProposal, undefined);
    },
  });
}

export function useSaveProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      api.put<CandidateProfile>('/api/candidate/profile', body),
    onSuccess: (profile) => {
      queryClient.setQueryData(keys.profile, profile);
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

export function useSavePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: PreferencesPayload) =>
      api.put<CandidateProfile>('/api/candidate/preferences', body),
    onSuccess: (profile) => {
      queryClient.setQueryData(keys.profile, profile);
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

/* ------------------------------------------------------------------ Jobs */

export interface JobQueryParams {
  q?: string;
  location?: string;
  workMode?: string[];
  employmentType?: string[];
  seniority?: string[];
  maxExperience?: number;
  postedWithinDays?: number;
  sourceId?: string;
  skill?: string;
  minMatch?: number;
  includeClosed?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export function useJobs(params: JobQueryParams) {
  return useQuery({
    queryKey: keys.jobs(params),
    queryFn: ({ signal }) =>
      api.get<JobPage>(`/api/jobs${queryString(params as Record<string, unknown>)}`, signal),
    placeholderData: (previous) => previous,
  });
}

export function useJob(jobId: string | undefined) {
  return useQuery({
    queryKey: keys.job(jobId ?? ''),
    queryFn: ({ signal }) => api.get<JobDetail>(`/api/jobs/${jobId}`, signal),
    enabled: Boolean(jobId),
  });
}

export function useSavedJobs() {
  return useQuery({
    queryKey: keys.saved,
    queryFn: ({ signal }) => api.get<JobSummary[]>('/api/jobs/saved', signal),
  });
}

export function useAppliedJobs() {
  return useQuery({
    queryKey: keys.applied,
    queryFn: ({ signal }) => api.get<JobSummary[]>('/api/jobs/applied', signal),
  });
}

type InteractionAction = 'save' | 'unsave' | 'dismiss' | 'undismiss' | 'applied';

export function useJobInteraction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ jobId, action }: { jobId: string; action: InteractionAction }) => {
      switch (action) {
        case 'save':
          return api.post<void>(`/api/jobs/${jobId}/save`);
        case 'unsave':
          return api.delete<void>(`/api/jobs/${jobId}/save`);
        case 'dismiss':
          return api.post<void>(`/api/jobs/${jobId}/dismiss`);
        case 'undismiss':
          return api.delete<void>(`/api/jobs/${jobId}/dismiss`);
        case 'applied':
          return api.post<void>(`/api/jobs/${jobId}/applied`, { applicationStatus: 'APPLIED' });
      }
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
      queryClient.invalidateQueries({ queryKey: keys.job(variables.jobId) });
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

export function useRematch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<{ jobsScored: number; visibleMatches: number; notice: string | null }>(
        '/api/jobs/rematch',
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

/* --------------------------------------------------------------- Sources */

export function useSources(states: string[], page = 0, size = 50) {
  const params = { state: states, page, size };
  return useQuery({
    queryKey: keys.sources(params),
    queryFn: ({ signal }) =>
      api.get<Page<SourceSummary>>(`/api/sources${queryString(params)}`, signal),
    placeholderData: (previous) => previous,
  });
}

export function useSource(sourceId: string | undefined) {
  return useQuery({
    queryKey: keys.source(sourceId ?? ''),
    queryFn: ({ signal }) => api.get<SourceDetail>(`/api/sources/${sourceId}`, signal),
    enabled: Boolean(sourceId),
  });
}

export function useSourceStats() {
  return useQuery({
    queryKey: keys.sourceStats,
    queryFn: ({ signal }) => api.get<SourceStats>('/api/sources/stats', signal),
  });
}

export function useAdapters() {
  return useQuery({
    queryKey: keys.adapters,
    queryFn: ({ signal }) => api.get<AdapterInfo[]>('/api/sources/adapters', signal),
    staleTime: 60 * 60 * 1000,
  });
}

/* --------------------------------------------------------- Notifications */

export function useNotifications(page = 0) {
  return useQuery({
    queryKey: [...keys.notifications, page],
    queryFn: ({ signal }) =>
      api.get<Page<NotificationView>>(`/api/notifications?page=${page}&size=30`, signal),
  });
}

export function useUnreadCount() {
  return useQuery({
    queryKey: keys.unreadCount,
    queryFn: ({ signal }) => api.get<{ count: number }>('/api/notifications/unread-count', signal),
    // A quiet poll: enough to feel live, not enough to be a load problem.
    refetchInterval: 60_000,
  });
}

export function useMarkNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (notificationId?: string) => {
      if (notificationId) {
        await api.post<void>(`/api/notifications/${notificationId}/read`);
        return;
      }
      await api.post<{ updated: number }>('/api/notifications/read-all');
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.notifications });
      queryClient.invalidateQueries({ queryKey: keys.unreadCount });
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

/* ----------------------------------------------------------------- Admin */

export function useSystemStats() {
  return useQuery({
    queryKey: keys.systemStats,
    queryFn: ({ signal }) => api.get<SystemStats>('/api/admin/ops/stats', signal),
    refetchInterval: 30_000,
  });
}

export function useAdminSourceAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ sourceId, action, body }: { sourceId: string; action: string; body?: unknown }) =>
      api.post<unknown>(`/api/admin/sources/${sourceId}/${action}`, body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: keys.source(variables.sourceId) });
      queryClient.invalidateQueries({ queryKey: ['sources'] });
      queryClient.invalidateQueries({ queryKey: keys.systemStats });
    },
  });
}

/* ------------------------------------------------- College onboarding */

/** The colleges on this deployment. Platform operators only. */
export function useInstitutions() {
  return useQuery({
    queryKey: ['admin', 'institutions'],
    queryFn: ({ signal }) => api.get<InstitutionSummary[]>('/api/admin/institutions', signal),
  });
}

/**
 * Onboards a college.
 *
 * <p>No optimistic update and no cache write from the request: the server
 * derives the slug and normalises the domains, so what was sent is not what was
 * stored. The list is refetched instead, and the screen shows what actually
 * exists.
 */
export function useCreateInstitution() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ProvisionInstitutionInput) =>
      api.post<ProvisionedInstitution>('/api/admin/institutions', input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'institutions'] });
    },
  });
}

/* ---------------------------------------------------------- Institution */

/**
 * Scope-aware institutional counts. The server decides what "in scope" means
 * from the caller's grants; this hook never sends a department or a student id.
 */
export function useInstitutionOverview(enabled = true) {
  return useQuery({
    queryKey: ['institution', 'overview'],
    queryFn: ({ signal }) => api.get<InstitutionOverview>('/api/institution/overview', signal),
    enabled,
  });
}

/**
 * Turns the Students page's state into a query string.
 *
 * <p>Empty values are dropped rather than sent blank, so an unused filter never
 * reaches the server and the query key stays stable while the officer types.
 */
function studentQueryString(params: StudentQueryParams): string {
  const search = new URLSearchParams();
  if (params.q?.trim()) search.set('q', params.q.trim());
  if (params.departmentId) search.set('departmentId', params.departmentId);
  if (params.batchId) search.set('batchId', params.batchId);
  if (params.minCgpa) search.set('minCgpa', String(params.minCgpa));
  if (params.minProfileCompleteness) {
    search.set('minProfileCompleteness', String(params.minProfileCompleteness));
  }
  if (params.resumeUploaded !== undefined) {
    search.set('resumeUploaded', String(params.resumeUploaded));
  }
  if (params.sort) search.set('sort', params.sort);
  // Repeated rather than comma-joined: several skills mean ALL of them, and the
  // server reads one parameter per skill.
  (params.skills ?? []).forEach((skill) => search.append('skills', skill));
  search.set('page', String(params.page ?? 0));
  search.set('size', String(params.size ?? 25));
  return search.toString();
}

export function useInstitutionStudents(params: StudentQueryParams = {}, enabled = true) {
  const query = studentQueryString(params);
  return useQuery({
    queryKey: ['institution', 'students', query],
    queryFn: ({ signal }) => api.get<StudentPage>(`/api/institution/students?${query}`, signal),
    enabled,
    // Keeps the previous page on screen while the next one loads, so paging and
    // typing do not blank the table between keystrokes.
    placeholderData: (previous) => previous,
  });
}

export function useStudentDetail(userId: string | undefined) {
  return useQuery({
    queryKey: ['institution', 'student', userId],
    queryFn: ({ signal }) =>
      api.get<StudentDetail>(`/api/institution/students/${userId}`, signal),
    enabled: Boolean(userId),
  });
}

/* -------------------------------------------------------- Requirements */

export function useRequirements(status?: string) {
  return useQuery({
    queryKey: ['requirements', status ?? 'all'],
    queryFn: ({ signal }) =>
      api.get<RequirementPage>(`/api/requirements${status ? `?status=${status}` : ''}`, signal),
  });
}

export function useRequirement(id: string | undefined) {
  return useQuery({
    queryKey: ['requirements', id],
    queryFn: ({ signal }) => api.get<Requirement>(`/api/requirements/${id}`, signal),
    enabled: Boolean(id),
  });
}

export function useCreateRequirement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: CreateRequirementPayload) =>
      api.post<Requirement>('/api/requirements', payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['requirements'] }),
  });
}

/** Status changes and edits share one endpoint; both invalidate the list. */
export function useUpdateRequirement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: Record<string, unknown> }) =>
      api.patch<Requirement>(`/api/requirements/${id}`, patch),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['requirements'] }),
  });
}

/** The caller's own college departments, for scoping a requirement. */
export function useDepartments() {
  return useQuery({
    queryKey: ['institution', 'departments'],
    queryFn: ({ signal }) => api.get<DepartmentView[]>('/api/institution/departments', signal),
  });
}

/* ------------------------------------------------- College administration */

/** Graduating batches at the caller's own college. */
export function useBatches() {
  return useQuery({
    queryKey: ['institution', 'batches'],
    queryFn: ({ signal }) => api.get<BatchView[]>('/api/institution/batches', signal),
  });
}

/** Placement staff at the caller's own college, with the scope each one covers. */
export function useStaff(enabled = true) {
  return useQuery({
    queryKey: ['institution', 'staff'],
    queryFn: ({ signal }) => api.get<StaffRow[]>('/api/institution/staff', signal),
    enabled,
  });
}

export function useCreateDepartment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; code: string }) =>
      api.post<DepartmentView>('/api/institution/departments', body),
    // The overview counts departments, and a requirement form offers them as
    // targets, so both go stale the moment one is added.
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['institution'] });
    },
  });
}

export function useCreateBatch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; graduationYear: number }) =>
      api.post<BatchView>('/api/institution/batches', body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['institution'] });
    },
  });
}

export function useCreateStaff() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      fullName: string;
      email: string;
      password: string;
      role: string;
      departmentCode?: string;
    }) => api.post<StaffCreated>('/api/institution/staff', body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['institution', 'staff'] });
    },
  });
}

/* ----------------------------------------------------- Candidate discovery */

/**
 * Students relevant to a requirement.
 *
 * The client sends filters only. Institution, department scope and the
 * candidate set are all decided by the server from the authenticated caller and
 * the requirement — a filter can narrow that, never widen it.
 */
export function useCandidateDiscovery(
  requirementId: string | undefined,
  query: DiscoveryQuery,
  enabled = true,
) {
  const search = queryString({
    eligibility: query.eligibility,
    minScore: query.minScore,
    sort: query.sort,
    shortlisted: query.shortlisted,
  });
  return useQuery({
    queryKey: ['discovery', requirementId, query],
    queryFn: ({ signal }) =>
      api.get<CandidatePage>(`/api/requirements/${requirementId}/candidates${search}`, signal),
    enabled: Boolean(requirementId) && enabled,
  });
}

/* ------------------------------------------------------------- Shortlist */

/** The students the placement team chose, scored as they stand today. */
export function useShortlist(requirementId: string | undefined) {
  return useQuery({
    queryKey: ['shortlist', requirementId],
    queryFn: ({ signal }) =>
      api.get<CandidatePage>(`/api/requirements/${requirementId}/shortlist`, signal),
    enabled: Boolean(requirementId),
  });
}

/**
 * Records a placement decision.
 *
 * <p>Both mutations refetch discovery and the shortlist rather than patching
 * the cache. This is a security-sensitive write that the server can refuse for
 * reasons the browser cannot know — scope, requirement state, a race with
 * another officer — so the UI shows the new state only once the server has
 * confirmed it, never before.
 */
export function useAddToShortlist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ requirementId, candidateId }: { requirementId: string; candidateId: string }) =>
      api.post<ShortlistResult>(`/api/requirements/${requirementId}/shortlist`, { candidateId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['discovery'] });
      queryClient.invalidateQueries({ queryKey: ['shortlist'] });
    },
  });
}

export function useRemoveFromShortlist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ requirementId, candidateId }: { requirementId: string; candidateId: string }) =>
      api.delete<ShortlistResult>(`/api/requirements/${requirementId}/shortlist/${candidateId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['discovery'] });
      queryClient.invalidateQueries({ queryKey: ['shortlist'] });
    },
  });
}

/* ------------------------------------------------ Company source discovery */

/**
 * Resolving typed company names to domains.
 *
 * <p>A mutation rather than a query even though it reads: it reaches out to
 * candidate domains, so it must happen when the operator asks and never on a
 * render, a refetch or a cache revalidation.
 */
export function useResolveCompanies() {
  return useMutation({
    mutationFn: (companyNames: string[]) =>
      api.post<CompanyDiscoveryResult>('/api/admin/sources/discover-by-company', { companyNames }),
  });
}

/**
 * Probing the domains an operator confirmed, and registering what has a board.
 *
 * <p>Invalidates the source registry because this is the call that can add to
 * it. Nothing here decides whether a source may be used — a registered source
 * lands at DISCOVERED and still has to pass the policy gate.
 */
export function useDiscoverConfirmedDomains() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      confirmedDomains,
      companyNames,
    }: {
      confirmedDomains: string[];
      companyNames: string[];
    }) =>
      api.post<CompanyDiscoveryResult>('/api/admin/sources/discover-by-company', {
        companyNames,
        confirmedDomains,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sources'] });
      queryClient.invalidateQueries({ queryKey: ['source-stats'] });
    },
  });
}

/* --------------------------------------------------- Placement workflow */

/**
 * Moving a candidate through the drive.
 *
 * <p>Invalidated the same way as adding and removing, and for the same reason:
 * the server can refuse this for scope, for the requirement being closed, for
 * the stage having moved under us, or because the move is not one this kind of
 * actor may make. Nothing is shown as done until it comes back.
 *
 * <p>The candidate's history is invalidated too — a stage change is exactly the
 * event that adds a line to it.
 */
export function useChangeStage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      requirementId,
      candidateId,
      stage,
      note,
    }: {
      requirementId: string;
      candidateId: string;
      stage: PlacementStage;
      note?: string;
    }) =>
      api.patch<{ stage: PlacementStage }>(
        `/api/requirements/${requirementId}/shortlist/${candidateId}/stage`,
        { stage, note: note || null },
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['discovery'] });
      queryClient.invalidateQueries({ queryKey: ['shortlist'] });
      queryClient.invalidateQueries({ queryKey: ['stage-history'] });
    },
  });
}

/** How one candidate got to where they are. Fetched only when opened. */
export function useStageHistory(requirementId: string | undefined, candidateId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['stage-history', requirementId, candidateId],
    queryFn: ({ signal }) =>
      api.get<StageChange[]>(
        `/api/requirements/${requirementId}/shortlist/${candidateId}/history`,
        signal,
      ),
    enabled: enabled && Boolean(requirementId) && Boolean(candidateId),
  });
}

/* ------------------------------------------------------- My placements */

/**
 * A student's own placements.
 *
 * <p>No id is sent. The server resolves the records from the authenticated
 * student's profile, so there is nothing here for a browser to tamper with.
 */
export function useMyPlacements() {
  return useQuery({
    queryKey: ['my-placements'],
    queryFn: ({ signal }) => api.get<MyPlacement[]>('/api/candidate/placements', signal),
  });
}

export function useMyPlacementHistory(requirementId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['my-placement-history', requirementId],
    queryFn: ({ signal }) =>
      api.get<StageChange[]>(`/api/candidate/placements/${requirementId}/history`, signal),
    enabled: enabled && Boolean(requirementId),
  });
}

/** The student answering an invitation: the only placement write they can make. */
export function useRespondToPlacement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      requirementId,
      response,
      note,
    }: {
      requirementId: string;
      response: 'interested' | 'declined';
      note?: string;
    }) =>
      api.patch<MyPlacement>(`/api/candidate/placements/${requirementId}/response`, {
        response,
        note: note || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-placements'] });
      queryClient.invalidateQueries({ queryKey: ['my-placement-history'] });
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
  });
}

/* -------------------------------------------------------- Academic record */

/**
 * The student recording their own CGPA.
 *
 * <p>Separate from the profile save because the profile PUT replaces what it is
 * given, and a client omitting a field there would silently erase an academic
 * record. This states one fact deliberately.
 *
 * <p>Self-entered, so it appears on their profile and is not what a company's
 * stated minimum is judged against.
 */
export function useUpdateOwnAcademics() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (cgpa: number | null) =>
      api.put<AcademicRecord>('/api/candidate/academics', { cgpa }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.profile }),
  });
}

/** Placement staff recording the institution's record for a student. */
export function useUpdateStudentAcademics() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, cgpa }: { userId: string; cgpa: number | null }) =>
      api.put<AcademicRecord>(`/api/institution/students/${userId}/academics`, { cgpa }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['institution', 'students'] });
      queryClient.invalidateQueries({ queryKey: ['discovery'] });
      queryClient.invalidateQueries({ queryKey: ['shortlist'] });
    },
  });
}

/* --------------------------------------------- Privacy: resumes, consent, erasure */

/** Every resume the student has uploaded, newest first. */
export function useResumes() {
  return useQuery({
    queryKey: keys.resumes,
    queryFn: ({ signal }) => api.get<ResumeSummary[]>('/api/candidate/resumes', signal),
  });
}

/** Deletes one of the student's own resumes: file, record, text and any reading from it. */
export function useDeleteResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (resumeId: string) => api.delete<void>(`/api/candidate/resumes/${resumeId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.resumes });
      queryClient.invalidateQueries({ queryKey: keys.profile });
      queryClient.invalidateQueries({ queryKey: keys.pendingProposal });
      queryClient.invalidateQueries({ queryKey: keys.dashboard });
    },
  });
}

const PURPOSE_PATH: Record<ConsentPurpose, string> = {
  PRIVACY_NOTICE: 'privacy-notice',
  RESUME_PROCESSING: 'resume-processing',
  AI_PROCESSING: 'ai-processing',
};

/** Current notices, what the student has agreed to, and their consent history. */
export function useConsents() {
  return useQuery({
    queryKey: keys.consents,
    queryFn: ({ signal }) => api.get<ConsentOverview>('/api/consents', signal),
  });
}

export function useAcceptConsent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ purpose, noticeVersionId }: { purpose: ConsentPurpose; noticeVersionId: string }) =>
      api.post<ConsentOverview>(`/api/consents/${PURPOSE_PATH[purpose]}/accept`, {
        noticeVersionId,
        source: 'SETTINGS',
      }),
    onSuccess: (overview) => queryClient.setQueryData(keys.consents, overview),
  });
}

/** Withdrawing AI consent also removes unanswered AI readings, so the pending review is refreshed too. */
export function useWithdrawConsent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (purpose: ConsentPurpose) =>
      api.post<ConsentOverview>(`/api/consents/${PURPOSE_PATH[purpose]}/withdraw`, { source: 'SETTINGS' }),
    onSuccess: (overview) => {
      queryClient.setQueryData(keys.consents, overview);
      queryClient.invalidateQueries({ queryKey: keys.pendingProposal });
    },
  });
}

/** The student's latest erasure request, or undefined when there has never been one. */
export function useErasure() {
  return useQuery({
    queryKey: keys.erasure,
    queryFn: ({ signal }) => api.get<ErasureView | undefined>('/api/candidate/erasure', signal),
  });
}

export function useRequestErasure() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<ErasureView>('/api/candidate/erasure'),
    onSuccess: (view) => queryClient.setQueryData(keys.erasure, view),
  });
}

export function useCancelErasure() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<ErasureView>('/api/candidate/erasure/cancel'),
    onSuccess: (view) => queryClient.setQueryData(keys.erasure, view),
  });
}

/** A student's latest erasure request, for their placement coordinator. */
export function useStudentErasure(userId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: keys.studentErasure(userId ?? ''),
    enabled: enabled && Boolean(userId),
    queryFn: ({ signal }) =>
      api.get<ErasureView | undefined>(`/api/institution/students/${userId}/erasure`, signal),
  });
}

export function useRequestStudentErasure(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<ErasureView>(`/api/institution/students/${userId}/erasure`),
    onSuccess: (view) => queryClient.setQueryData(keys.studentErasure(userId), view),
  });
}

export function useCancelStudentErasure(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<ErasureView>(`/api/institution/students/${userId}/erasure/cancel`),
    onSuccess: (view) => queryClient.setQueryData(keys.studentErasure(userId), view),
  });
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/** Downloads everything CareerFlux holds about the signed-in student, as JSON. */
export async function downloadMyData(): Promise<void> {
  const data = await api.get<unknown>('/api/candidate/export');
  saveBlob(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), exportFileName());
}

/**
 * Downloads one of the student's own resume files. Fetched with the session's
 * token rather than opened as a link, because the file route requires it.
 */
export async function downloadResumeFile(resume: ResumeSummary): Promise<void> {
  const response = await fetch(`/api/candidate/resumes/${resume.id}/file`, {
    headers: tokenStore.access ? { Authorization: `Bearer ${tokenStore.access}` } : {},
  });
  if (!response.ok) {
    throw new Error('That file could not be downloaded.');
  }
  saveBlob(await response.blob(), resume.originalFilename || 'resume');
}
