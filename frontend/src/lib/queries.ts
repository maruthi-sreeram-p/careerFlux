import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api, queryString } from './api';
import type {
  AdapterInfo,
  CandidateProfile,
  DashboardResponse,
  CandidatePage,
  CreateRequirementPayload,
  DiscoveryQuery,
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
  SourceDetail,
  SourceStats,
  SourceSummary,
  StudentPage,
  SystemStats,
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

export function useInstitutionStudents(page = 0, size = 25, enabled = true) {
  return useQuery({
    queryKey: ['institution', 'students', page, size],
    queryFn: ({ signal }) =>
      api.get<StudentPage>(`/api/institution/students?page=${page}&size=${size}`, signal),
    enabled,
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
  });
  return useQuery({
    queryKey: ['discovery', requirementId, query],
    queryFn: ({ signal }) =>
      api.get<CandidatePage>(`/api/requirements/${requirementId}/candidates${search}`, signal),
    enabled: Boolean(requirementId) && enabled,
  });
}
