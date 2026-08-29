import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import {
  Button,
  Field,
  Panel,
  Select,
  TextArea,
  TextInput,
  useToast,
} from '../components/ui/primitives';
import { useCreateRequirement, useDepartments } from '../lib/queries';
import type { CreateRequirementPayload } from '../lib/types';

/**
 * Recording what a company asked for.
 *
 * <p>The form is shaped like the conversation the placement officer just had:
 * who is hiring, for what, what candidates must have, what they would like, and
 * who may be put forward. Required and preferred skills are separate boxes
 * because that is how a company states them — "must have Java, Spring Boot and
 * SQL; Kafka would be nice" — and keeping the two apart here is what lets a
 * missing requirement be reported differently from a missing preference when
 * students are ranked against this later.
 *
 * <p>A requirement is always saved as a draft. Publishing is a separate act on
 * the detail screen, because an open requirement is one the college will search
 * real students against.
 */
function parseSkills(
  raw: string,
  tier: 'REQUIRED' | 'PREFERRED',
): { skill: string; tier: string }[] {
  return raw
    .split(/[,\n]/)
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((skill) => ({ skill, tier }));
}

function optionalNumber(raw: string): number | null {
  if (!raw.trim()) {
    return null;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export default function RequirementNew() {
  const navigate = useNavigate();
  const toast = useToast();
  const create = useCreateRequirement();
  const departments = useDepartments();

  const [companyName, setCompanyName] = useState('');
  const [roleTitle, setRoleTitle] = useState('');
  const [description, setDescription] = useState('');
  const [required, setRequired] = useState('');
  const [preferred, setPreferred] = useState('');
  const [minExp, setMinExp] = useState('');
  const [maxExp, setMaxExp] = useState('');
  const [graduationYear, setGraduationYear] = useState('');
  const [minCgpa, setMinCgpa] = useState('');
  const [workMode, setWorkMode] = useState('UNSPECIFIED');
  const [location, setLocation] = useState('');
  const [driveDate, setDriveDate] = useState('');
  const [departmentIds, setDepartmentIds] = useState<string[]>([]);

  const canSubmit = companyName.trim().length > 0 && roleTitle.trim().length > 0;

  const skills = useMemo(
    () => [...parseSkills(required, 'REQUIRED'), ...parseSkills(preferred, 'PREFERRED')],
    [required, preferred],
  );

  const toggleDepartment = (id: string) => {
    setDepartmentIds((current) =>
      current.includes(id) ? current.filter((entry) => entry !== id) : [...current, id],
    );
  };

  const submit = async () => {
    const payload: CreateRequirementPayload = {
      companyName: companyName.trim(),
      roleTitle: roleTitle.trim(),
      description: description.trim() || null,
      minExperienceYears: optionalNumber(minExp),
      maxExperienceYears: optionalNumber(maxExp),
      graduationYear: optionalNumber(graduationYear),
      minCgpa: optionalNumber(minCgpa),
      workMode,
      location: location.trim() || null,
      driveDate: driveDate || null,
      departmentIds,
      skills,
    };
    try {
      const saved = await create.mutateAsync(payload);
      if (saved.skillsUnresolved.length > 0) {
        // Said plainly rather than swallowed. An unrecognised skill is not
        // stored and will never be matched on, and the officer who typed it is
        // the only person who can correct it.
        toast.show(
          `Saved as a draft. CareerFlux did not recognise ${saved.skillsUnresolved.join(
            ', ',
          )} — those will not be matched on.`,
        );
      } else {
        toast.show('Saved as a draft.', 'success');
      }
      navigate(`/app/requirements/${saved.id}`);
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'The requirement could not be saved.',
        'error',
      );
    }
  };

  return (
    <>
      <PageHeader
        title="Add a company requirement"
        subtitle="Record the brief as the company gave it. You can publish it once it reads correctly."
      />

      <div className="grid grid--two">
        <Panel title="The role">
          <Field label="Company">
            {({ id }) => (
              <TextInput
                id={id}
                value={companyName}
                onChange={(event) => setCompanyName(event.target.value)}
                placeholder="XYZ Technologies"
              />
            )}
          </Field>
          <Field label="Role title">
            {({ id }) => (
              <TextInput
                id={id}
                value={roleTitle}
                onChange={(event) => setRoleTitle(event.target.value)}
                placeholder="Java Backend Developer"
              />
            )}
          </Field>
          <Field label="Description" hint="Anything the company said that does not fit a field.">
            {({ id }) => (
              <TextArea
                id={id}
                rows={5}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            )}
          </Field>
        </Panel>

        <Panel title="Skills">
          <Field label="Required" hint="Conditions, not wishes. One per line or comma separated.">
            {({ id }) => (
              <TextArea
                id={id}
                rows={4}
                value={required}
                onChange={(event) => setRequired(event.target.value)}
                placeholder={'Java\nSpring Boot\nSQL'}
              />
            )}
          </Field>
          <Field label="Preferred" hint="Nice to have. A gap here is not a disqualification.">
            {({ id }) => (
              <TextArea
                id={id}
                rows={3}
                value={preferred}
                onChange={(event) => setPreferred(event.target.value)}
                placeholder={'Kafka\nAWS\nDocker'}
              />
            )}
          </Field>
          <p className="text-muted">
            {skills.length === 0
              ? 'No skills entered yet. Without them a candidate search has nothing to compare.'
              : `${skills.length} skill${skills.length === 1 ? '' : 's'} will be recorded.`}
          </p>
        </Panel>

        <Panel title="Who may be put forward">
          <Field label="Minimum experience (years)">
            {({ id }) => (
              <TextInput
                id={id}
                value={minExp}
                onChange={(event) => setMinExp(event.target.value)}
                placeholder="0"
                inputMode="decimal"
              />
            )}
          </Field>
          <Field label="Maximum experience (years)">
            {({ id }) => (
              <TextInput
                id={id}
                value={maxExp}
                onChange={(event) => setMaxExp(event.target.value)}
                placeholder="2"
                inputMode="decimal"
              />
            )}
          </Field>
          <Field label="Batch" hint="Graduation year. Leave empty for any batch.">
            {({ id }) => (
              <TextInput
                id={id}
                value={graduationYear}
                onChange={(event) => setGraduationYear(event.target.value)}
                placeholder="2027"
                inputMode="numeric"
              />
            )}
          </Field>
          <Field
            label="CGPA the company asks for"
            hint="Recorded as the company's stated rule. CareerFlux does not hold student CGPA yet, so nothing is filtered on it."
          >
            {({ id }) => (
              <TextInput
                id={id}
                value={minCgpa}
                onChange={(event) => setMinCgpa(event.target.value)}
                placeholder="7.0"
                inputMode="decimal"
              />
            )}
          </Field>
          <Field label="Departments" hint="Leave all unticked to open it to the whole college.">
            {() => (
              <div className="row wrap gap-2">
                {(departments.data ?? []).map((department) => (
                  <label key={department.id} className="row gap-2">
                    <input
                      type="checkbox"
                      checked={departmentIds.includes(department.id)}
                      onChange={() => toggleDepartment(department.id)}
                    />
                    <span>{department.name}</span>
                  </label>
                ))}
                {(departments.data ?? []).length === 0 && (
                  <span className="text-muted">No departments configured.</span>
                )}
              </div>
            )}
          </Field>
        </Panel>

        <Panel title="Where and when">
          <Field label="Work mode">
            {({ id }) => (
              <Select id={id} value={workMode} onChange={(event) => setWorkMode(event.target.value)}>
                <option value="UNSPECIFIED">Not stated</option>
                <option value="ONSITE">On-site</option>
                <option value="HYBRID">Hybrid</option>
                <option value="REMOTE">Remote</option>
              </Select>
            )}
          </Field>
          <Field label="Location">
            {({ id }) => (
              <TextInput
                id={id}
                value={location}
                onChange={(event) => setLocation(event.target.value)}
                placeholder="Hyderabad, India"
              />
            )}
          </Field>
          <Field label="Drive date" hint="Optional. When the company is coming to campus.">
            {({ id }) => (
              <TextInput
                id={id}
                type="date"
                value={driveDate}
                onChange={(event) => setDriveDate(event.target.value)}
              />
            )}
          </Field>
        </Panel>
      </div>

      <div className="row gap-2 section">
        <Button onClick={submit} disabled={!canSubmit || create.isPending}>
          {create.isPending ? 'Saving…' : 'Save as draft'}
        </Button>
        <Button variant="secondary" onClick={() => navigate('/app/requirements')}>
          Cancel
        </Button>
        {!canSubmit && (
          <span className="text-muted">A company and a role title are the minimum.</span>
        )}
      </div>
    </>
  );
}
