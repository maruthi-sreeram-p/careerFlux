import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import { BrandMark } from '../../components/layout/AppShell';
import { Icon } from '../../components/ui/Icon';
import { OptionGrid, TokenInput } from '../../components/ui/TokenInput';
import {
  Badge,
  Button,
  Chip,
  Field,
  Select,
  TextArea,
  TextInput,
  cn,
  useToast,
} from '../../components/ui/primitives';
import { ApiError } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import {
  useProfile,
  useRematch,
  useSavePreferences,
  useSaveProfile,
  useUploadResume,
} from '../../lib/queries';
import {
  EMPLOYMENT_OPTIONS,
  SENIORITY_OPTIONS,
  WORK_MODE_OPTIONS,
  employmentLabel,
  seniorityLabel,
  workModeLabel,
} from '../../lib/format';
import type { CandidateProfile, PreferencesPayload } from '../../lib/types';

const STEPS = ['Resume', 'Profile', 'Preferences'] as const;

/* --------------------------------------------------------------- Step 1 */

function ResumeStep({
  onParsed,
  onSkip,
}: {
  onParsed: (profile: CandidateProfile, engine: string, aiAssisted: boolean, notice: string | null) => void;
  onSkip: () => void;
}) {
  const upload = useUploadResume();
  const toast = useToast();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stage, setStage] = useState(0);

  // The parse steps advance on a timer while the request is in flight. They
  // describe what the server genuinely does in that order; the timing is
  // cosmetic, the sequence is not.
  useEffect(() => {
    if (!upload.isPending) {
      setStage(0);
      return;
    }
    const timer = window.setInterval(() => setStage((current) => Math.min(current + 1, 2)), 900);
    return () => window.clearInterval(timer);
  }, [upload.isPending]);

  const handleFile = (file: File | undefined) => {
    if (!file) {
      return;
    }
    setError(null);
    upload.mutate(file, {
      onSuccess: (result) =>
        onParsed(result.profile, result.engine, result.aiAssisted, result.notice),
      onError: (caught) => {
        const message =
          caught instanceof ApiError ? caught.message : 'That file could not be uploaded.';
        setError(message);
        toast.show(message, 'error');
      },
    });
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    handleFile(event.dataTransfer.files?.[0]);
  };

  if (upload.isPending) {
    return (
      <div className="parsing" aria-live="polite">
        {['Reading the document', 'Extracting your details', 'Building your profile'].map(
          (label, index) => (
            <div
              key={label}
              className={cn(
                'parsing__step',
                index < stage && 'parsing__step--done',
                index === stage && 'parsing__step--active',
              )}
            >
              <span className="parsing__mark">
                {index < stage ? (
                  <Icon.Check size={11} />
                ) : index === stage ? (
                  <span className="spinner" style={{ width: 11, height: 11, borderWidth: 1.5 }} />
                ) : null}
              </span>
              {label}
            </div>
          ),
        )}
      </div>
    );
  }

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      <div
        className={cn('dropzone', dragging && 'dropzone--active')}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            inputRef.current?.click();
          }
        }}
        role="button"
        tabIndex={0}
        aria-label="Upload your resume"
      >
        <span className="dropzone__icon">
          <Icon.Upload size={20} />
        </span>
        <p className="dropzone__title">Drop your resume here</p>
        <p className="dropzone__hint">
          PDF, DOCX or plain text, up to 8 MB. Or <span style={{ color: 'var(--accent)' }}>browse for a file</span>.
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.docx,.txt,.md,application/pdf,text/plain"
          className="sr-only"
          onChange={(event) => handleFile(event.target.files?.[0])}
        />
      </div>

      {error && (
        <div className="auth__alert" role="alert">
          <Icon.Warning size={15} style={{ color: 'var(--negative)', flex: 'none', marginTop: 1 }} />
          <span>{error}</span>
        </div>
      )}

      <button
        type="button"
        className="btn btn--ghost btn--sm"
        style={{ justifySelf: 'center' }}
        onClick={onSkip}
      >
        I would rather fill it in myself
      </button>
    </div>
  );
}

/* --------------------------------------------------------------- Step 2 */

function ProfileStep({
  profile,
  extractionNotice,
  engine,
  onSaved,
}: {
  profile: CandidateProfile;
  extractionNotice: string | null;
  engine: string | null;
  onSaved: () => void;
}) {
  const save = useSaveProfile();
  const toast = useToast();
  const [headline, setHeadline] = useState(profile.headline ?? '');
  const [summary, setSummary] = useState(profile.summary ?? '');
  const [location, setLocation] = useState(profile.location ?? '');
  const [primaryRole, setPrimaryRole] = useState(profile.primaryRole ?? '');
  const [seniority, setSeniority] = useState(profile.seniority ?? 'UNSPECIFIED');
  const [years, setYears] = useState(
    profile.yearsExperience === null ? '' : String(profile.yearsExperience),
  );
  const [skills, setSkills] = useState<string[]>(profile.skills.map((skill) => skill.name));

  const submit = () => {
    save.mutate(
      {
        headline: headline || null,
        summary: summary || null,
        location: location || null,
        primaryRole: primaryRole || null,
        seniority,
        yearsExperience: years === '' ? null : Number(years),
        skills: skills.map((name) => ({ name, origin: 'MANUAL' })),
        experiences: profile.experiences.map((item) => ({ ...item })),
        education: profile.education.map((item) => ({ ...item })),
      },
      {
        onSuccess: onSaved,
        onError: (caught) =>
          toast.show(
            caught instanceof ApiError ? caught.message : 'Your profile could not be saved.',
            'error',
          ),
      },
    );
  };

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      {extractionNotice && (
        <div className="auth__alert auth__alert--info">
          <Icon.Info size={15} style={{ color: 'var(--info)', flex: 'none', marginTop: 1 }} />
          <span>{extractionNotice}</span>
        </div>
      )}

      {engine && !extractionNotice && (
        <p className="text-muted row gap-2" style={{ fontSize: 'var(--text-xs)' }}>
          <Icon.Sparkle size={13} style={{ color: 'var(--accent)' }} />
          Read from your resume by <span className="mono">{engine}</span>. Correct anything it got
          wrong — your edits always win.
        </p>
      )}

      <div className="review-group">
        <div className="review-group__head">
          <h2 className="review-group__title">The basics</h2>
        </div>
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <Field label="Headline" hint="One line describing what you do.">
            {({ id }) => (
              <TextInput
                id={id}
                value={headline}
                onChange={(event) => setHeadline(event.target.value)}
                placeholder="Java Backend Developer"
              />
            )}
          </Field>

          <div className="grid grid--two" style={{ gap: 'var(--space-4)' }}>
            <Field label="Current or target role">
              {({ id }) => (
                <TextInput
                  id={id}
                  value={primaryRole}
                  onChange={(event) => setPrimaryRole(event.target.value)}
                  placeholder="Backend Developer"
                />
              )}
            </Field>
            <Field label="Where you are based">
              {({ id }) => (
                <TextInput
                  id={id}
                  value={location}
                  onChange={(event) => setLocation(event.target.value)}
                  placeholder="Hyderabad, India"
                />
              )}
            </Field>
          </div>

          <div className="grid grid--two" style={{ gap: 'var(--space-4)' }}>
            <Field label="Seniority" hint="Used to weigh roles at the right level.">
              {({ id }) => (
                <Select
                  id={id}
                  value={seniority}
                  onChange={(event) => setSeniority(event.target.value)}
                >
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
                  placeholder="2"
                />
              )}
            </Field>
          </div>

          <Field label="Professional summary">
            {({ id }) => (
              <TextArea
                id={id}
                value={summary}
                onChange={(event) => setSummary(event.target.value)}
                placeholder="A couple of sentences about what you build and what you are good at."
              />
            )}
          </Field>
        </div>
      </div>

      <div className="review-group">
        <div className="review-group__head">
          <h2 className="review-group__title">Skills</h2>
          <span className="review-group__note">{skills.length} added</span>
        </div>
        <p className="review-group__note">
          These carry 40% of every match score, so it is worth getting them right.
        </p>
        <TokenInput
          values={skills}
          onChange={setSkills}
          maxValues={40}
          label="Your skills"
          placeholder="Java, Spring Boot, PostgreSQL…"
        />
      </div>

      <div className="onboarding__actions">
        <span />
        <Button variant="primary" loading={save.isPending} onClick={submit}>
          Continue
          <Icon.ArrowRight size={14} />
        </Button>
      </div>
    </div>
  );
}

/* --------------------------------------------------------------- Step 3 */

function PreferencesStep({ profile, onSaved }: { profile: CandidateProfile; onSaved: () => void }) {
  const save = useSavePreferences();
  const rematch = useRematch();
  const toast = useToast();
  const existing = profile.preferences;

  const [targetRoles, setTargetRoles] = useState<string[]>(existing?.targetRoles ?? []);
  const [locations, setLocations] = useState<string[]>(existing?.locations ?? []);
  const [industries, setIndustries] = useState<string[]>(existing?.industries ?? []);
  const [companies, setCompanies] = useState<string[]>(existing?.preferredCompanies ?? []);
  const [workModes, setWorkModes] = useState<string[]>(existing?.workModes ?? []);
  const [employmentTypes, setEmploymentTypes] = useState<string[]>(
    existing?.employmentTypes ?? ['FULL_TIME'],
  );
  const [openToRelocation, setOpenToRelocation] = useState(existing?.openToRelocation ?? false);
  const [salaryMin, setSalaryMin] = useState(
    existing?.salaryMin === null || existing?.salaryMin === undefined ? '' : String(existing.salaryMin),
  );
  const [currency, setCurrency] = useState(existing?.salaryCurrency ?? 'INR');

  const canContinue = targetRoles.length > 0;

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
      immediateAlerts: true,
      dailyDigest: true,
    };
    save.mutate(payload, {
      // Score the corpus before handing over to the dashboard, so the first
      // thing a new candidate sees is their matches rather than an empty feed.
      onSuccess: () => rematch.mutate(undefined, { onSettled: onSaved }),
      onError: (caught) =>
        toast.show(
          caught instanceof ApiError ? caught.message : 'Preferences could not be saved.',
          'error',
        ),
    });
  };

  const working = save.isPending || rematch.isPending;

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      <div className="review-group">
        <div className="review-group__head">
          <h2 className="review-group__title">What are you aiming at?</h2>
          <Badge tone={canContinue ? 'positive' : 'caution'}>
            {canContinue ? 'Ready' : 'Add at least one'}
          </Badge>
        </div>
        <p className="review-group__note">
          The roles you want, not the one you have. CareerFlux uses these to decide which sources
          are worth watching.
        </p>
        <TokenInput
          values={targetRoles}
          onChange={setTargetRoles}
          label="Target roles"
          placeholder="Backend Developer, Java Developer…"
        />
      </div>

      <div className="review-group">
        <div className="review-group__head">
          <h2 className="review-group__title">Where</h2>
        </div>
        <TokenInput
          values={locations}
          onChange={setLocations}
          label="Preferred locations"
          placeholder="Hyderabad, Bengaluru, Remote…"
        />
        <label className="checkbox" style={{ marginTop: 'var(--space-1)' }}>
          <input
            type="checkbox"
            checked={openToRelocation}
            onChange={(event) => setOpenToRelocation(event.target.checked)}
          />
          <span>I am open to relocating</span>
        </label>
      </div>

      <div className="review-group">
        <div className="review-group__head">
          <h2 className="review-group__title">How you want to work</h2>
        </div>
        <OptionGrid
          options={WORK_MODE_OPTIONS}
          selected={workModes}
          onChange={setWorkModes}
          labelFor={workModeLabel}
        />
        <div className="divider" style={{ margin: 'var(--space-2) 0' }} />
        <OptionGrid
          options={EMPLOYMENT_OPTIONS}
          selected={employmentTypes}
          onChange={setEmploymentTypes}
          labelFor={employmentLabel}
        />
      </div>

      <div className="review-group">
        <div className="review-group__head">
          <h2 className="review-group__title">Optional</h2>
          <span className="review-group__note">Skip anything you are not sure about</span>
        </div>
        <div className="grid grid--two" style={{ gap: 'var(--space-4)' }}>
          <Field label="Minimum salary" hint="Annual, before tax.">
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
                  step={50000}
                  value={salaryMin}
                  onChange={(event) => setSalaryMin(event.target.value)}
                  placeholder="800000"
                />
              </div>
            )}
          </Field>
          <Field label="Industries">
            {() => (
              <TokenInput
                values={industries}
                onChange={setIndustries}
                label="Industries"
                placeholder="Software, Fintech…"
              />
            )}
          </Field>
        </div>
        <Field label="Companies you would like to work for">
          {() => (
            <TokenInput
              values={companies}
              onChange={setCompanies}
              label="Preferred companies"
              placeholder="Add a company…"
            />
          )}
        </Field>
      </div>

      <div className="onboarding__actions">
        <span className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
          You can change all of this later.
        </span>
        <Button variant="primary" loading={working} disabled={!canContinue} onClick={submit}>
          {rematch.isPending ? 'Finding your matches…' : 'Start discovering'}
          {!working && <Icon.ArrowRight size={14} />}
        </Button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------ Container */

export default function Onboarding() {
  const navigate = useNavigate();
  const { refreshUser } = useAuth();
  const profileQuery = useProfile();
  const [step, setStep] = useState(0);
  const [engine, setEngine] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Resume the flow where the candidate actually is, so a refresh does not send
  // someone who already uploaded a resume back to the drop zone.
  const startStep = useMemo(() => {
    const stage = profileQuery.data?.onboardingStage;
    if (stage === 'PROFILE_REVIEW') {
      return 1;
    }
    if (stage === 'PREFERENCES' || stage === 'COMPLETE') {
      return 2;
    }
    return 0;
  }, [profileQuery.data?.onboardingStage]);

  const [initialised, setInitialised] = useState(false);
  useEffect(() => {
    if (!initialised && profileQuery.data) {
      setStep(startStep);
      setInitialised(true);
    }
  }, [initialised, profileQuery.data, startStep]);

  const finish = async () => {
    await refreshUser();
    navigate('/app', { replace: true });
  };

  const headings = [
    {
      title: 'Start with your resume',
      lead: 'CareerFlux reads it once to build your profile. You get to correct everything on the next screen before anything is saved for matching.',
    },
    {
      title: 'Check what we read',
      lead: 'This is what came out of your resume. Fix anything wrong — your corrections always beat the parser.',
    },
    {
      title: 'What are you looking for?',
      lead: 'This is the part that decides which sources CareerFlux watches and which roles clear your threshold.',
    },
  ];

  return (
    <div className="onboarding">
      <div className="onboarding__bar">
        <div className="onboarding__bar-inner">
          <div className="row gap-3">
            <BrandMark />
            <span className="brand-word">CareerFlux</span>
          </div>
          <div className="steps">
            {STEPS.map((label, index) => (
              <div key={label} style={{ display: 'contents' }}>
                <div
                  className={cn(
                    'step',
                    index === step && 'step--active',
                    index < step && 'step--done',
                  )}
                >
                  <span className="step__dot">
                    {index < step ? <Icon.Check size={10} /> : index + 1}
                  </span>
                  <span className="step__label">{label}</span>
                </div>
                {index < STEPS.length - 1 && (
                  <span className={cn('step__rule', index < step && 'step__rule--done')} />
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="onboarding__body">
        <div className="onboarding__screen" key={step}>
          <h1 className="onboarding__title">{headings[step].title}</h1>
          <p className="onboarding__lead">{headings[step].lead}</p>

          <div style={{ marginTop: 'var(--space-8)' }}>
            {step === 0 && (
              <ResumeStep
                onParsed={(_profile, parsedEngine, aiAssisted, parseNotice) => {
                  setEngine(aiAssisted ? parsedEngine : null);
                  setNotice(parseNotice);
                  setStep(1);
                }}
                onSkip={() => setStep(1)}
              />
            )}

            {step === 1 && profileQuery.data && (
              <ProfileStep
                profile={profileQuery.data}
                extractionNotice={notice}
                engine={engine}
                onSaved={() => setStep(2)}
              />
            )}

            {step === 2 && profileQuery.data && (
              <PreferencesStep profile={profileQuery.data} onSaved={finish} />
            )}

            {step > 0 && !profileQuery.data && (
              <div className="row center" style={{ padding: 'var(--space-16)' }}>
                <span className="spinner" aria-label="Loading your profile" />
              </div>
            )}
          </div>

          {step > 0 && (
            <div className="row gap-2" style={{ marginTop: 'var(--space-6)' }}>
              <Button variant="ghost" size="sm" onClick={() => setStep(step - 1)}>
                <Icon.ChevronLeft size={14} />
                Back
              </Button>
              {step === 2 && (
                <Chip>
                  Profile {profileQuery.data?.profileCompleteness ?? 0}% complete
                </Chip>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
