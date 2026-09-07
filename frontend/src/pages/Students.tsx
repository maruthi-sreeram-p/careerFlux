import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import {
  Button,
  Chip,
  EmptyState,
  Panel,
  SearchInput,
  Select,
  Skeleton,
  TextInput,
} from '../components/ui/primitives';
import {
  useBatches,
  useDepartments,
  useInstitutionOverview,
  useInstitutionStudents,
} from '../lib/queries';
import type { StudentQueryParams } from '../lib/types';

const PAGE_SIZE = 25;

const COMPLETION_FLOOR = [
  { value: 0, label: 'Any profile' },
  { value: 50, label: '50% and above' },
  { value: 75, label: '75% and above' },
  { value: 100, label: 'Complete only' },
];

const RESUME_OPTIONS = [
  { value: '', label: 'Any resume' },
  { value: 'true', label: 'Uploaded' },
  { value: 'false', label: 'Not uploaded' },
];

const SORTS = [
  { value: 'name', label: 'Name A–Z' },
  { value: 'cgpa', label: 'CGPA, highest first' },
  { value: 'profile', label: 'Profile completion' },
];

/** A labelled group in the filter bar. */
function FilterGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="grid" style={{ gap: 'var(--space-2)', minWidth: 160 }}>
      <p className="eyebrow">{title}</p>
      {children}
    </div>
  );
}

/**
 * The students a member of staff is responsible for, and a way to find one.
 *
 * <p>This was an alphabetical list with Previous and Next, which is workable at
 * fourteen students and useless at four hundred. A placement officer's real
 * question is a cohort — "2027 CSE with Java and Spring Boot, CGPA at least 7.5,
 * resume in" — or a single person by roll number, and neither is reachable by
 * paging.
 *
 * <p>Nothing is filtered in the browser. Every control below becomes a query
 * parameter and the server answers with one page, because filtering here would
 * mean the rest of the college had already been sent to it. That also keeps the
 * count in the footer honest: it describes the filtered set, not the directory.
 */
export default function Students() {
  const [query, setQuery] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [batchId, setBatchId] = useState('');
  const [minCgpa, setMinCgpa] = useState('');
  const [skills, setSkills] = useState<string[]>([]);
  const [skillDraft, setSkillDraft] = useState('');
  const [minCompletion, setMinCompletion] = useState(0);
  const [resume, setResume] = useState('');
  const [sort, setSort] = useState('name');
  const [page, setPage] = useState(0);

  const overview = useInstitutionOverview();
  const departments = useDepartments();
  const batches = useBatches();

  // Every filter change returns to the first page. Staying on page 4 while the
  // result set shrinks to six students shows an empty table and looks like the
  // filter found nothing.
  const onFilterChange = <T,>(setter: (value: T) => void) => (value: T) => {
    setter(value);
    setPage(0);
  };

  const params: StudentQueryParams = useMemo(
    () => ({
      q: query || undefined,
      departmentId: departmentId || undefined,
      batchId: batchId || undefined,
      minCgpa: minCgpa ? Number(minCgpa) : undefined,
      skills: skills.length ? skills : undefined,
      minProfileCompleteness: minCompletion || undefined,
      resumeUploaded: resume === '' ? undefined : resume === 'true',
      sort,
      page,
      size: PAGE_SIZE,
    }),
    [query, departmentId, batchId, minCgpa, skills, minCompletion, resume, sort, page],
  );

  const students = useInstitutionStudents(params);
  const data = students.data;

  const activeFilters =
    (query ? 1 : 0) +
    (departmentId ? 1 : 0) +
    (batchId ? 1 : 0) +
    (minCgpa ? 1 : 0) +
    skills.length +
    (minCompletion ? 1 : 0) +
    (resume ? 1 : 0);

  const clearAll = () => {
    setQuery('');
    setDepartmentId('');
    setBatchId('');
    setMinCgpa('');
    setSkills([]);
    setSkillDraft('');
    setMinCompletion(0);
    setResume('');
    setSort('name');
    setPage(0);
  };

  const addSkill = () => {
    const value = skillDraft.trim();
    if (value && !skills.some((existing) => existing.toLowerCase() === value.toLowerCase())) {
      setSkills([...skills, value]);
      setPage(0);
    }
    setSkillDraft('');
  };

  const scopeLabel = overview.data?.scopeLabel;

  return (
    <>
      <PageHeader
        title="Students"
        subtitle={
          scopeLabel
            ? scopeLabel === 'Whole institution'
              ? 'Every student in your college.'
              : `Students in ${scopeLabel}.`
            : undefined
        }
      />

      <Panel>
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <SearchInput
            value={query}
            placeholder="Search students by name, roll number, email, or skill..."
            aria-label="Search students"
            onChange={(event) => onFilterChange(setQuery)(event.target.value)}
          />

          <div className="row wrap gap-4">
            <FilterGroup title="Department">
              <Select
                value={departmentId}
                aria-label="Filter by department"
                onChange={(event) => onFilterChange(setDepartmentId)(event.target.value)}
              >
                <option value="">All departments</option>
                {(departments.data ?? []).map((department) => (
                  <option key={department.id} value={department.id}>
                    {department.name}
                  </option>
                ))}
              </Select>
            </FilterGroup>

            <FilterGroup title="Batch">
              <Select
                value={batchId}
                aria-label="Filter by batch"
                onChange={(event) => onFilterChange(setBatchId)(event.target.value)}
              >
                <option value="">All batches</option>
                {(batches.data ?? []).map((batch) => (
                  <option key={batch.id} value={batch.id}>
                    {batch.name}
                  </option>
                ))}
              </Select>
            </FilterGroup>

            <FilterGroup title="Minimum CGPA">
              <TextInput
                type="number"
                min={0}
                max={10}
                step={0.1}
                value={minCgpa}
                placeholder="e.g. 7.5"
                aria-label="Minimum CGPA, on a ten point scale"
                onChange={(event) => onFilterChange(setMinCgpa)(event.target.value)}
              />
            </FilterGroup>

            <FilterGroup title="Profile completion">
              <Select
                value={minCompletion}
                aria-label="Filter by profile completion"
                onChange={(event) => onFilterChange(setMinCompletion)(Number(event.target.value))}
              >
                {COMPLETION_FLOOR.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            </FilterGroup>

            <FilterGroup title="Resume">
              <Select
                value={resume}
                aria-label="Filter by resume status"
                onChange={(event) => onFilterChange(setResume)(event.target.value)}
              >
                {RESUME_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            </FilterGroup>

            <FilterGroup title="Sort by">
              <Select
                value={sort}
                aria-label="Sort students"
                onChange={(event) => onFilterChange(setSort)(event.target.value)}
              >
                {SORTS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            </FilterGroup>
          </div>

          <FilterGroup title="Skills — a student must have every one">
            <div className="row wrap gap-2">
              {skills.map((skill) => (
                <Chip
                  key={skill}
                  selected
                  title="Remove this skill"
                  onClick={() => {
                    setSkills(skills.filter((entry) => entry !== skill));
                    setPage(0);
                  }}
                >
                  {skill} ✕
                </Chip>
              ))}
              <TextInput
                value={skillDraft}
                placeholder="Add a skill, then press Enter"
                aria-label="Add a skill to filter by"
                style={{ maxWidth: 240 }}
                onChange={(event) => setSkillDraft(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    addSkill();
                  }
                }}
              />
            </div>
          </FilterGroup>

          {activeFilters > 0 && (
            <div className="row gap-3">
              <Button variant="secondary" onClick={clearAll}>
                Clear filters
              </Button>
              <span className="text-faint" style={{ alignSelf: 'center' }}>
                {activeFilters} active
              </span>
            </div>
          )}
        </div>
      </Panel>

      {students.isLoading && (
        <Panel title="Loading students">
          <Skeleton height={180} radius={12} />
        </Panel>
      )}

      {students.isError && (
        <Panel title="Students could not be loaded">
          <p className="text-muted">Try again shortly. Nothing has changed.</p>
        </Panel>
      )}

      {data && data.content.length === 0 && (
        <Panel>
          <EmptyState
            title={
              activeFilters > 0
                ? 'No students match the current search and filters.'
                : 'No students in your scope'
            }
            body={
              activeFilters > 0
                ? 'Try removing a filter, or widening the CGPA or profile thresholds.'
                : scopeLabel === 'No scope granted'
                  ? 'You have not been granted a department or batch yet. A college administrator assigns this.'
                  : 'No students have been enrolled into your scope yet.'
            }
          />
        </Panel>
      )}

      {data && data.content.length > 0 && (
        <>
          <div className="data-table__scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Roll number</th>
                  <th>Department</th>
                  <th>Batch</th>
                  <th className="numeric">CGPA</th>
                  <th className="numeric">Profile</th>
                  <th>Resume</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((student) => (
                  <tr key={student.userId}>
                    <td>
                      <Link to={`/app/students/${student.userId}`} className="data-table__primary">
                        {student.fullName}
                      </Link>
                      <span className="text-faint">{student.email}</span>
                    </td>
                    <td className="mono">
                      {student.rollNumber ?? <span className="text-muted">—</span>}
                    </td>
                    <td>{student.departmentName ?? <span className="text-muted">Unassigned</span>}</td>
                    <td>{student.batchName ?? <span className="text-muted">Unassigned</span>}</td>
                    <td className="numeric">
                      {student.cgpa ? (
                        <span title={`${student.cgpa} on a ${student.cgpaScale} scale`}>
                          {student.normalisedCgpa}
                        </span>
                      ) : (
                        <span className="text-muted">Not recorded</span>
                      )}
                    </td>
                    <td className="numeric">{student.profileCompleteness}%</td>
                    <td>
                      {student.resumeUploaded ? 'Uploaded' : <span className="text-muted">None</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="row gap-2" style={{ marginTop: 'var(--space-4)' }}>
            <Button
              variant="secondary"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </Button>
            <span className="text-muted" style={{ alignSelf: 'center' }}>
              Page {data.page + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} students
            </span>
            <Button
              variant="secondary"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </Button>
          </div>
        </>
      )}
    </>
  );
}
