import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { JobCard, JobRow } from '../components/job/JobCard';
import { Icon } from '../components/ui/Icon';
import { OptionGrid } from '../components/ui/TokenInput';
import {
  Badge,
  Button,
  Chip,
  EmptyState,
  ErrorState,
  Panel,
  Select,
  Skeleton,
  cn,
  useToast,
} from '../components/ui/primitives';
import { useJobInteraction, useJobs, type JobQueryParams } from '../lib/queries';
import {
  EMPLOYMENT_OPTIONS,
  SENIORITY_OPTIONS,
  WORK_MODE_OPTIONS,
  employmentLabel,
  pluralize,
  seniorityLabel,
  workModeLabel,
} from '../lib/format';
import type { JobSummary } from '../lib/types';

const FRESHNESS = [
  { value: 0, label: 'Any time' },
  { value: 1, label: 'Last 24 hours' },
  { value: 7, label: 'Last week' },
  { value: 30, label: 'Last month' },
];

const MATCH_FLOOR = [
  { value: 0, label: 'Any match' },
  { value: 70, label: '70% and above' },
  { value: 85, label: '85% and above' },
  { value: 95, label: '95% and above' },
];

/** A collapsible group in the filter rail. */
function FilterGroup({
  title,
  children,
  count,
}: {
  title: string;
  children: React.ReactNode;
  count?: number;
}) {
  return (
    <div className="grid" style={{ gap: 'var(--space-3)' }}>
      <div className="row between">
        <p className="eyebrow">{title}</p>
        {count ? <span className="mono text-faint" style={{ fontSize: 'var(--text-2xs)' }}>{count}</span> : null}
      </div>
      {children}
    </div>
  );
}

export default function Discover() {
  const [params, setParams] = useSearchParams();
  const toast = useToast();
  const interaction = useJobInteraction();

  const [query, setQuery] = useState(params.get('q') ?? '');
  const [debounced, setDebounced] = useState(query);
  const [location, setLocation] = useState('');
  const [workModes, setWorkModes] = useState<string[]>([]);
  const [employmentTypes, setEmploymentTypes] = useState<string[]>([]);
  const [seniorities, setSeniorities] = useState<string[]>([]);
  const [postedWithinDays, setPostedWithinDays] = useState(0);
  const [minMatch, setMinMatch] = useState(0);
  // Empty means "no explicit choice": the server then ranks by relevance when
  // there is a search term and by recency when there is not. Storing a concrete
  // default here was the bug — every search shipped sort=recent, so relevance
  // ranking was computed and then thrown away by the date ordering.
  const [sort, setSort] = useState('');
  const [dense, setDense] = useState(false);
  const [page, setPage] = useState(0);
  const [filtersOpen, setFiltersOpen] = useState(false);

  // Typing should not fire a request per keystroke.
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query), 280);
    return () => window.clearTimeout(timer);
  }, [query]);

  // The search term lives in the URL so a result set can be shared or bookmarked.
  useEffect(() => {
    setParams(debounced ? { q: debounced } : {}, { replace: true });
    setPage(0);
  }, [debounced, setParams]);

  const filters: JobQueryParams = useMemo(
    () => ({
      q: debounced || undefined,
      location: location || undefined,
      workMode: workModes.length ? workModes : undefined,
      employmentType: employmentTypes.length ? employmentTypes : undefined,
      seniority: seniorities.length ? seniorities : undefined,
      postedWithinDays: postedWithinDays || undefined,
      minMatch: minMatch || undefined,
      sort: sort || undefined,
      page,
      size: 20,
    }),
    [debounced, location, workModes, employmentTypes, seniorities, postedWithinDays, minMatch, sort, page],
  );

  const jobs = useJobs(filters);

  const activeFilterCount =
    workModes.length +
    employmentTypes.length +
    seniorities.length +
    (location ? 1 : 0) +
    (postedWithinDays ? 1 : 0) +
    (minMatch ? 1 : 0);

  const clearAll = () => {
    setLocation('');
    setWorkModes([]);
    setEmploymentTypes([]);
    setSeniorities([]);
    setPostedWithinDays(0);
    setMinMatch(0);
    setPage(0);
  };

  const onSave = (job: JobSummary) =>
    interaction.mutate(
      { jobId: job.id, action: job.interaction.saved ? 'unsave' : 'save' },
      { onSuccess: () => toast.show(job.interaction.saved ? 'Removed from saved' : 'Saved', 'success') },
    );

  const onDismiss = (job: JobSummary) =>
    interaction.mutate(
      { jobId: job.id, action: job.interaction.dismissed ? 'undismiss' : 'dismiss' },
      { onSuccess: () => toast.show('Dismissed') },
    );

  const rail = (
    <div className="grid" style={{ gap: 'var(--space-6)' }}>
      <FilterGroup title="Location">
        <div className="search">
          <Icon.Pin size={14} className="search__icon" />
          <input
            className="input"
            placeholder="City or region"
            aria-label="Filter by location"
            value={location}
            onChange={(event) => {
              setLocation(event.target.value);
              setPage(0);
            }}
          />
        </div>
      </FilterGroup>

      <FilterGroup title="Work mode" count={workModes.length}>
        <OptionGrid
          options={WORK_MODE_OPTIONS}
          selected={workModes}
          onChange={(next) => {
            setWorkModes(next);
            setPage(0);
          }}
          labelFor={workModeLabel}
        />
      </FilterGroup>

      <FilterGroup title="Employment type" count={employmentTypes.length}>
        <OptionGrid
          options={EMPLOYMENT_OPTIONS}
          selected={employmentTypes}
          onChange={(next) => {
            setEmploymentTypes(next);
            setPage(0);
          }}
          labelFor={employmentLabel}
        />
      </FilterGroup>

      <FilterGroup title="Seniority" count={seniorities.length}>
        <div className="row wrap gap-2">
          {SENIORITY_OPTIONS.map((option) => (
            <Chip
              key={option}
              selected={seniorities.includes(option)}
              onClick={() => {
                setSeniorities((current) =>
                  current.includes(option)
                    ? current.filter((entry) => entry !== option)
                    : [...current, option],
                );
                setPage(0);
              }}
            >
              {seniorityLabel(option)}
            </Chip>
          ))}
        </div>
      </FilterGroup>

      <FilterGroup title="Freshness">
        <Select
          value={postedWithinDays}
          aria-label="Filter by how recently the job was posted"
          onChange={(event) => {
            setPostedWithinDays(Number(event.target.value));
            setPage(0);
          }}
        >
          {FRESHNESS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </Select>
      </FilterGroup>

      <FilterGroup title="Match score">
        <Select
          value={minMatch}
          aria-label="Filter by minimum match score"
          onChange={(event) => {
            setMinMatch(Number(event.target.value));
            setPage(0);
          }}
        >
          {MATCH_FLOOR.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </Select>
        <p className="text-faint" style={{ fontSize: 'var(--text-2xs)' }}>
          Only scored jobs are returned when a floor is set.
        </p>
      </FilterGroup>

      {activeFilterCount > 0 && (
        <Button variant="ghost" size="sm" onClick={clearAll}>
          <Icon.Close size={13} />
          Clear {pluralize(activeFilterCount, 'filter')}
        </Button>
      )}
    </div>
  );

  return (
    <div className="page page--wide">
      <PageHeader
        title="Discover"
        subtitle="Everything CareerFlux has collected from the sources it is allowed to read."
        actions={
          <div className="row gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setFiltersOpen((open) => !open)}
              className="topbar__menu"
              aria-expanded={filtersOpen}
            >
              <Icon.Filter size={14} />
              Filters
              {activeFilterCount > 0 && <Badge tone="accent">{activeFilterCount}</Badge>}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              iconOnly
              aria-label={dense ? 'Switch to detailed view' : 'Switch to compact list'}
              aria-pressed={dense}
              onClick={() => setDense((value) => !value)}
            >
              <Icon.Menu size={14} />
            </Button>
          </div>
        }
      />

      <div className="grid grid--discovery">
        <aside
          className={cn('panel')}
          style={{ padding: 'var(--space-5)', position: 'sticky', top: 'calc(var(--topbar-height) + var(--space-4))' }}
        >
          {rail}
        </aside>

        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <div className="row between wrap gap-3">
            <div className="search" style={{ flex: 1, minWidth: 220, maxWidth: 420 }}>
              <Icon.Search size={15} className="search__icon" />
              <input
                type="search"
                className="input"
                placeholder="Search titles, companies, skills"
                aria-label="Search jobs"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </div>
            <div className="row gap-3">
              <span className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                {jobs.data ? pluralize(jobs.data.totalElements, 'role') : '—'}
              </span>
              <Select
                value={sort}
                aria-label="Sort results"
                style={{ width: 170 }}
                onChange={(event) => setSort(event.target.value)}
              >
                {/*
                  An empty value means "let the server decide", which is best
                  match while searching and recency while browsing. The label
                  changes with it so the control never shows a stale word, and
                  the explicit "recent" option is only offered during a search,
                  where it is a different thing from the default.
                */}
                <option value="">{debounced ? 'Best match' : 'Recently observed'}</option>
                {debounced && <option value="recent">Recently observed</option>}
                <option value="newest">Newly discovered</option>
                <option value="posted">Recently posted</option>
                <option value="title">Title A–Z</option>
              </Select>
            </div>
          </div>

          {jobs.isLoading && (
            <div className="grid" style={{ gap: 'var(--space-3)' }}>
              {[0, 1, 2, 3].map((index) => (
                <Skeleton key={index} height={dense ? 56 : 132} radius={12} />
              ))}
            </div>
          )}

          {jobs.isError && (
            <Panel>
              <ErrorState
                title="Jobs could not be loaded"
                body={(jobs.error as Error).message}
                onRetry={() => jobs.refetch()}
              />
            </Panel>
          )}

          {jobs.data && jobs.data.content.length === 0 && (
            <Panel>
              <EmptyState
                icon={<Icon.Search size={20} />}
                title="Nothing matches those filters"
                body={
                  activeFilterCount > 0 || debounced
                    ? 'Try widening the filters, or clear them to see everything CareerFlux has collected.'
                    : 'No jobs have been ingested yet. An administrator needs to activate a source before anything appears here.'
                }
                action={
                  activeFilterCount > 0 ? (
                    <Button variant="secondary" size="sm" onClick={clearAll}>
                      Clear filters
                    </Button>
                  ) : undefined
                }
              />
            </Panel>
          )}

          {jobs.data && jobs.data.content.length > 0 && (
            <>
              {dense ? (
                <Panel flush>
                  {jobs.data.content.map((job) => (
                    <JobRow key={job.id} job={job} />
                  ))}
                </Panel>
              ) : (
                <div className="grid" style={{ gap: 'var(--space-3)' }}>
                  {jobs.data.content.map((job) => (
                    <JobCard
                      key={job.id}
                      job={job}
                      onSave={onSave}
                      onDismiss={onDismiss}
                      showExplanation={Boolean(job.match)}
                    />
                  ))}
                </div>
              )}

              {jobs.data.totalPages > 1 && (
                <div className="row between" style={{ marginTop: 'var(--space-2)' }}>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={page === 0}
                    onClick={() => setPage((current) => Math.max(0, current - 1))}
                  >
                    <Icon.ChevronLeft size={14} />
                    Previous
                  </Button>
                  <span className="mono text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                    Page {page + 1} of {jobs.data.totalPages}
                  </span>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={page >= jobs.data.totalPages - 1}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    Next
                    <Icon.ChevronRight size={14} />
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
