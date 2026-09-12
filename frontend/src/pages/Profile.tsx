import { useEffect, useRef, useState } from 'react';

import { PageHeader } from '../components/layout/AppShell';
import { AcademicPanel } from '../components/profile/AcademicPanel';
import { ProposalReviewPanel } from '../components/profile/ProposalReviewPanel';
import { PasswordPanel } from '../components/profile/PasswordPanel';
import { Icon } from '../components/ui/Icon';
import { OptionGrid, TokenInput } from '../components/ui/TokenInput';
import {
  Badge,
  Button,
  ErrorState,
  Field,
  Panel,
  Select,
  Skeleton,
  TextArea,
  TextInput,
  cn,
  useToast,
} from '../components/ui/primitives';
import { ApiError } from '../lib/api';
import { IMMEDIATE_ALERTS_HINT, RESUME_HANDLING, SHOW_DIGEST_PREFERENCE } from '../lib/productCopy';
import {
  usePendingProposal,
  useProfile,
  useRematch,
  useSavePreferences,
  useSaveProfile,
  useUploadResume,
} from '../lib/queries';
import {
  EMPLOYMENT_OPTIONS,
  SENIORITY_OPTIONS,
  WORK_MODE_OPTIONS,
  employmentLabel,
  formatDateTime,
  pluralize,
  relativeTime,
  seniorityLabel,
  workModeLabel,
} from '../lib/format';
import type { CandidateProfile, PreferencesPayload } from '../lib/types';

const TABS = ['Profile', 'Preferences', 'Resume'] as const;

function Completeness({ value }: { value: number }) {
  return (
    <div className="completeness">
      <div className="completeness__track">
        <span className="completeness__fill" style={{ width: `${value}%` }} />
      </div>
      <span className="mono" style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)' }}>
        {value}%
      </span>
    </div>
  );
}

function ProfileTab({ profile }: { profile: CandidateProfile }) {
  const save = useSaveProfile();
  const toast = useToast();

  const [headline, setHeadline] = useState(profile.headline ?? '');
  const [summary, setSummary] = useState(profile.summary ?? '');
  const [location, setLocation] = useState(profile.location ?? '');
  const [phone, setPhone] = useState(profile.phone ?? '');
  const [linkedin, setLinkedin] = useState(profile.linkedinUrl ?? '');
  const [github, setGithub] = useState(profile.githubUrl ?? '');
  const [portfolio, setPortfolio] = useState(profile.portfolioUrl ?? '');
  const [primaryRole, setPrimaryRole] = useState(profile.primaryRole ?? '');
  const [seniority, setSeniority] = useState(profile.seniority ?? 'UNSPECIFIED');
  const [years, setYears] = useState(
    profile.yearsExperience === null ? '' : String(profile.yearsExperience),
  );
  const [skills, setSkills] = useState<string[]>(profile.skills.map((skill) => skill.name));

  const submit = () =>
    save.mutate(
      {
        headline: headline || null,
        summary: summary || null,
        location: location || null,
        phone: phone || null,
        linkedinUrl: linkedin || null,
        githubUrl: github || null,
        portfolioUrl: portfolio || null,
        primaryRole: primaryRole || null,
        seniority,
        yearsExperience: years === '' ? null : Number(years),
        skills: skills.map((name) => ({ name, origin: 'MANUAL' })),
        experiences: profile.experiences.map((item) => ({ ...item })),
        education: profile.education.map((item) => ({ ...item })),
      },
      {
        onSuccess: () => toast.show('Profile saved', 'success'),
        onError: (caught) =>
          toast.show(
            caught instanceof ApiError ? caught.message : 'Your profile could not be saved.',
            'error',
          ),
      },
    );

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      <Panel title="Who you are">
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <Field label="Headline">
            {({ id }) => (
              <TextInput
                id={id}
                value={headline}
                onChange={(event) => setHeadline(event.target.value)}
                placeholder="Java Backend Developer"
              />
            )}
          </Field>
          <Field label="Professional summary">
            {({ id }) => (
              <TextArea
                id={id}
                value={summary}
                onChange={(event) => setSummary(event.target.value)}
              />
            )}
          </Field>
          <div className="grid grid--two" style={{ gap: 'var(--space-4)' }}>
            <Field label="Primary role">
              {({ id }) => (
                <TextInput
                  id={id}
                  value={primaryRole}
                  onChange={(event) => setPrimaryRole(event.target.value)}
                />
              )}
            </Field>
            <Field label="Based in">
              {({ id }) => (
                <TextInput
                  id={id}
                  value={location}
                  onChange={(event) => setLocation(event.target.value)}
                />
              )}
            </Field>
          </div>
          <div className="grid grid--two" style={{ gap: 'var(--space-4)' }}>
            <Field label="Seniority">
              {({ id }) => (
                <Select id={id} value={seniority} onChange={(event) => setSeniority(event.target.value)}>
                  <option value="UNSPECIFIED">Prefer not to say</option>
                  {SENIORITY_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {seniorityLabel(option)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Field label="Years of experience">
              {({ id }) => (
                <TextInput
                  id={id}
                  type="number"
                  min={0}
                  max={50}
                  step={0.5}
                  value={years}
                  onChange={(event) => setYears(event.target.value)}
                />
              )}
            </Field>
          </div>
        </div>
      </Panel>

      <Panel
        title="Skills"
        action={<Badge>{pluralize(skills.length, 'skill')}</Badge>}
      >
        <p className="text-muted" style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-3)' }}>
          These carry 40% of every match score. Matched against the same canonical dictionary the
          job descriptions are resolved through, so spelling variants do not cost you anything.
        </p>
        <TokenInput values={skills} onChange={setSkills} maxValues={40} label="Skills" />
      </Panel>

      <AcademicPanel profile={profile} />

      <Panel title="Links">
        <div className="grid grid--two" style={{ gap: 'var(--space-4)' }}>
          <Field label="LinkedIn">
            {({ id }) => (
              <TextInput id={id} value={linkedin} onChange={(event) => setLinkedin(event.target.value)} />
            )}
          </Field>
          <Field label="GitHub">
            {({ id }) => (
              <TextInput id={id} value={github} onChange={(event) => setGithub(event.target.value)} />
            )}
          </Field>
          <Field label="Portfolio">
            {({ id }) => (
              <TextInput id={id} value={portfolio} onChange={(event) => setPortfolio(event.target.value)} />
            )}
          </Field>
          <Field label="Phone">
            {({ id }) => (
              <TextInput id={id} value={phone} onChange={(event) => setPhone(event.target.value)} />
            )}
          </Field>
        </div>
      </Panel>

      {(profile.experiences.length > 0 || profile.education.length > 0) && (
        <Panel title="Experience and education">
          <div className="grid" style={{ gap: 'var(--space-4)' }}>
            {profile.experiences.map((item, index) => (
              <div key={item.id ?? index}>
                <p style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>{item.title}</p>
                <p className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                  {item.companyName}
                  {item.location ? ` · ${item.location}` : ''}
                  {item.startDate ? ` · ${item.startDate}` : ''}
                  {item.current ? ' – present' : item.endDate ? ` – ${item.endDate}` : ''}
                </p>
              </div>
            ))}
            {profile.education.map((item, index) => (
              <div key={item.id ?? index}>
                <p style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>
                  {item.degree ?? 'Studied'} {item.fieldOfStudy ? `· ${item.fieldOfStudy}` : ''}
                </p>
                <p className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                  {item.institution}
                  {item.endYear ? ` · ${item.startYear ?? ''}–${item.endYear}` : ''}
                </p>
              </div>
            ))}
          </div>
        </Panel>
      )}

      <div className="row between" style={{ paddingTop: 'var(--space-2)' }}>
        <span className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
          Saving recomputes your profile completeness. Run a rematch to rescore your jobs.
        </span>
        <Button variant="primary" loading={save.isPending} onClick={submit}>
          Save profile
        </Button>
      </div>
    </div>
  );
}

function PreferencesTab({ profile }: { profile: CandidateProfile }) {
  const save = useSavePreferences();
  const toast = useToast();
  const existing = profile.preferences;

  const [targetRoles, setTargetRoles] = useState<string[]>(existing?.targetRoles ?? []);
  const [locations, setLocations] = useState<string[]>(existing?.locations ?? []);
  const [industries, setIndustries] = useState<string[]>(existing?.industries ?? []);
  const [companies, setCompanies] = useState<string[]>(existing?.preferredCompanies ?? []);
  const [workModes, setWorkModes] = useState<string[]>(existing?.workModes ?? []);
  const [employmentTypes, setEmploymentTypes] = useState<string[]>(existing?.employmentTypes ?? []);
  const [openToRelocation, setOpenToRelocation] = useState(existing?.openToRelocation ?? false);
  const [immediateAlerts, setImmediateAlerts] = useState(existing?.immediateAlerts ?? true);
  const [dailyDigest, setDailyDigest] = useState(existing?.dailyDigest ?? true);
  const [salaryMin, setSalaryMin] = useState(
    existing?.salaryMin === null || existing?.salaryMin === undefined ? '' : String(existing.salaryMin),
  );
  const [currency, setCurrency] = useState(existing?.salaryCurrency ?? 'INR');

  const submit = () => {
    const payload: PreferencesPayload = {
      targetRoles,
      industries,
      locations,
      workModes,
      employmentTypes,
      preferredCompanies: companies,
      salaryMin: salaryMin === '' ? null : Number(salaryMin),
      salaryMax: null,
      salaryCurrency: currency,
      salaryPeriod: 'ANNUAL',
      openToRelocation,
      minExperienceYears: null,
      maxExperienceYears: null,
      immediateAlerts,
      dailyDigest,
    };
    save.mutate(payload, {
      onSuccess: () => toast.show('Preferences saved', 'success'),
      onError: () => toast.show('Preferences could not be saved.', 'error'),
    });
  };

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      <Panel title="What you are looking for">
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <Field label="Target roles" hint="Carries 20% of the match score.">
            {() => <TokenInput values={targetRoles} onChange={setTargetRoles} label="Target roles" />}
          </Field>
          <Field label="Preferred locations" hint="Carries 10%.">
            {() => <TokenInput values={locations} onChange={setLocations} label="Locations" />}
          </Field>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={openToRelocation}
              onChange={(event) => setOpenToRelocation(event.target.checked)}
            />
            <span>I am open to relocating</span>
          </label>
        </div>
      </Panel>

      <Panel title="Work mode and employment type">
        <OptionGrid
          options={WORK_MODE_OPTIONS}
          selected={workModes}
          onChange={setWorkModes}
          labelFor={workModeLabel}
        />
        <div className="divider" style={{ margin: 'var(--space-4) 0' }} />
        <OptionGrid
          options={EMPLOYMENT_OPTIONS}
          selected={employmentTypes}
          onChange={setEmploymentTypes}
          labelFor={employmentLabel}
        />
      </Panel>

      <Panel title="Industries, companies and pay">
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <Field label="Industries">
            {() => <TokenInput values={industries} onChange={setIndustries} label="Industries" />}
          </Field>
          <Field label="Companies you would like to work for">
            {() => <TokenInput values={companies} onChange={setCompanies} label="Companies" />}
          </Field>
          <Field label="Minimum salary" hint="Annual, before tax. Not used in scoring yet.">
            {({ id }) => (
              <div className="row gap-2">
                <Select
                  value={currency}
                  onChange={(event) => setCurrency(event.target.value)}
                  style={{ width: 90 }}
                  aria-label="Currency"
                >
                  {['INR', 'USD', 'EUR', 'GBP'].map((code) => (
                    <option key={code} value={code}>
                      {code}
                    </option>
                  ))}
                </Select>
                <TextInput
                  id={id}
                  type="number"
                  min={0}
                  value={salaryMin}
                  onChange={(event) => setSalaryMin(event.target.value)}
                />
              </div>
            )}
          </Field>
        </div>
      </Panel>

      <Panel title="Alerts">
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <label className="switch">
            <input
              type="checkbox"
              role="switch"
              checked={immediateAlerts}
              onChange={(event) => setImmediateAlerts(event.target.checked)}
            />
            <span>
              <span style={{ display: 'block', fontSize: 'var(--text-sm)' }}>Immediate alerts</span>
              <span className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                {IMMEDIATE_ALERTS_HINT}
              </span>
            </span>
          </label>
          {/* Hidden while nothing delivers a digest (PD-2). The stored value is saved unchanged. */}
          {SHOW_DIGEST_PREFERENCE && (
            <label className="switch">
              <input
                type="checkbox"
                role="switch"
                checked={dailyDigest}
                onChange={(event) => setDailyDigest(event.target.checked)}
              />
              <span>
                <span style={{ display: 'block', fontSize: 'var(--text-sm)' }}>Daily digest</span>
                <span className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                  Everything from 70% to 94%, once a day.
                </span>
              </span>
            </label>
          )}
        </div>
      </Panel>

      <div className="row between" style={{ paddingTop: 'var(--space-2)' }}>
        <span />
        <Button variant="primary" loading={save.isPending} onClick={submit}>
          Save preferences
        </Button>
      </div>
    </div>
  );
}

function ResumeTab({ profile }: { profile: CandidateProfile }) {
  const upload = useUploadResume();
  const pendingProposal = usePendingProposal();
  const toast = useToast();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  const resume = profile.resume;

  const handleFile = (file: File | undefined) => {
    if (!file) {
      return;
    }
    upload.mutate(file, {
      onSuccess: (result) =>
        toast.show(
          result.proposalId
            ? 'Resume read. Review the suggestions below before anything is saved.'
            : 'Resume stored. Nothing new was found to suggest.',
          'success',
        ),
      onError: (caught) =>
        toast.show(
          caught instanceof ApiError ? caught.message : 'That file could not be uploaded.',
          'error',
        ),
    });
  };

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      {/* The review comes first: it is the one thing on this screen waiting on
          the student, and nothing it describes has been saved yet. */}
      {pendingProposal.data && <ProposalReviewPanel proposal={pendingProposal.data} />}

      {resume && (
        <Panel title="Current resume">
          <div className="row between wrap gap-4">
            <div className="row gap-3">
              <span className="state__icon" style={{ width: 36, height: 36 }}>
                <Icon.Document size={16} />
              </span>
              <div>
                <p style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>
                  {resume.originalFilename}
                </p>
                <p className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                  Uploaded {relativeTime(resume.uploadedAt)}
                  {resume.sizeBytes ? ` · ${Math.round(resume.sizeBytes / 1024)} KB` : ''}
                </p>
              </div>
            </div>
            <div className="row gap-2">
              <Badge
                tone={
                  resume.parseStatus === 'PARSED'
                    ? 'positive'
                    : resume.parseStatus === 'FAILED'
                      ? 'negative'
                      : 'caution'
                }
                square
              >
                {resume.parseStatus.replace(/_/g, ' ')}
              </Badge>
              {resume.parseEngine && <Badge square>{resume.parseEngine}</Badge>}
            </div>
          </div>

          {resume.parseError && (
            <p
              className="text-muted"
              style={{
                fontSize: 'var(--text-sm)',
                marginTop: 'var(--space-4)',
                paddingTop: 'var(--space-4)',
                borderTop: '1px solid var(--line-faint)',
              }}
            >
              {resume.parseError}
            </p>
          )}

          <p className="text-faint" style={{ fontSize: 'var(--text-2xs)', marginTop: 'var(--space-3)' }}>
            Parsed {formatDateTime(resume.parsedAt) ?? 'not yet'}.
          </p>
        </Panel>
      )}

      <div
        className={cn('dropzone', dragging && 'dropzone--active', upload.isPending && 'dropzone--busy')}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          handleFile(event.dataTransfer.files?.[0]);
        }}
        onClick={() => !upload.isPending && inputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            inputRef.current?.click();
          }
        }}
        aria-label="Upload a new resume"
      >
        <span className="dropzone__icon">
          {upload.isPending ? <span className="spinner" /> : <Icon.Upload size={20} />}
        </span>
        <p className="dropzone__title">
          {resume ? 'Replace your resume' : 'Upload your resume'}
        </p>
        <p className="dropzone__hint">
          PDF, DOCX or plain text, up to 8 MB. Your previous resumes stay on record but stop being
          the active one.
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.docx,.txt,.md,application/pdf,text/plain"
          className="sr-only"
          onChange={(event) => handleFile(event.target.files?.[0])}
        />
      </div>

      <Panel title="What happens to your resume">
        <ul className="grid" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
          {RESUME_HANDLING.map((line) => (
            <li key={line} className="row gap-3 items-start">
              <Icon.Check size={13} style={{ color: 'var(--positive)', marginTop: 3, flex: 'none' }} />
              <span className="text-secondary">{line}</span>
            </li>
          ))}
        </ul>
      </Panel>
    </div>
  );
}

export default function Profile() {
  const profile = useProfile();
  const rematch = useRematch();
  const toast = useToast();
  const [tab, setTab] = useState<(typeof TABS)[number]>('Profile');

  // Remount the editors when the server sends a new profile, so the fields
  // reflect a resume upload that happened on another tab.
  const [version, setVersion] = useState(0);
  useEffect(() => {
    setVersion((current) => current + 1);
  }, [profile.dataUpdatedAt]);

  if (profile.isLoading) {
    return (
      <div className="page">
        <Skeleton width={220} height={30} />
        <div style={{ height: 'var(--space-8)' }} />
        <Skeleton height={320} radius={12} />
      </div>
    );
  }

  if (profile.isError || !profile.data) {
    return (
      <div className="page">
        <ErrorState
          title="Your profile could not be loaded"
          body={(profile.error as Error | undefined)?.message}
          onRetry={() => profile.refetch()}
        />
      </div>
    );
  }

  const data = profile.data;

  return (
    <div className="page">
      <PageHeader
        title="Career profile"
        subtitle="This is what CareerFlux matches against. Everything here is editable, and your edits always beat the parser."
        actions={
          <Button
            variant="secondary"
            size="sm"
            loading={rematch.isPending}
            onClick={() =>
              rematch.mutate(undefined, {
                onSuccess: (result) =>
                  toast.show(
                    `Rescored ${pluralize(result.jobsScored, 'job')}. ${result.visibleMatches} above threshold.`,
                    'success',
                  ),
              })
            }
          >
            <Icon.Refresh size={14} />
            Rematch
          </Button>
        }
      />

      <Panel tight>
        <div className="row between wrap gap-4">
          <div>
            <p className="eyebrow" style={{ marginBottom: 'var(--space-2)' }}>
              Profile completeness
            </p>
            <div style={{ minWidth: 220 }}>
              <Completeness value={data.profileCompleteness} />
            </div>
          </div>
          <div className="row gap-2">
            <Badge>{pluralize(data.skills.length, 'skill')}</Badge>
            <Badge>{pluralize(data.preferences?.targetRoles.length ?? 0, 'target role')}</Badge>
            <Badge tone={data.onboardingStage === 'COMPLETE' ? 'positive' : 'caution'}>
              {data.onboardingStage === 'COMPLETE' ? 'Setup complete' : 'Setup unfinished'}
            </Badge>
          </div>
        </div>
      </Panel>

      <div className="tabs" role="tablist" style={{ margin: 'var(--space-6) 0 var(--space-5)' }}>
        {TABS.map((entry) => (
          <button
            key={entry}
            type="button"
            role="tab"
            className="tab"
            aria-selected={tab === entry}
            onClick={() => setTab(entry)}
          >
            {entry}
          </button>
        ))}
      </div>

      {tab === 'Profile' && <ProfileTab key={`profile-${version}`} profile={data} />}
      {tab === 'Preferences' && <PreferencesTab key={`prefs-${version}`} profile={data} />}
      {tab === 'Resume' && <ResumeTab key={`resume-${version}`} profile={data} />}

      {/*
        Below the tabs rather than inside one. Changing a password is an account
        action, not a part of the career profile the tabs describe, and burying
        it in a tab is how people end up unable to find it.
      */}
      <div style={{ marginTop: 'var(--space-6)' }}>
        <PasswordPanel />
      </div>
    </div>
  );
}
