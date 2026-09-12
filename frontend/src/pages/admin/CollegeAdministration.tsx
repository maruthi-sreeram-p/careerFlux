import { useState } from 'react';
import type { FormEvent } from 'react';

import { PageHeader } from '../../components/layout/AppShell';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  Field,
  Panel,
  Select,
  Skeleton,
  TextInput,
  useToast,
} from '../../components/ui/primitives';
import { ApiError } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import {
  useBatches,
  useCreateBatch,
  useCreateDepartment,
  useCreateStaff,
  useDepartments,
  useStaff,
} from '../../lib/queries';
import {
  APPOINTABLE_ROLES,
  EMPTY_BATCH_FORM,
  EMPTY_DEPARTMENT_FORM,
  EMPTY_STAFF_FORM,
  MIN_PASSWORD_LENGTH,
  buildBatchRequest,
  buildDepartmentRequest,
  buildStaffRequest,
  canManageBatches,
  canManageDepartments,
  canManageStaff,
  canSubmitBatch,
  canSubmitDepartment,
  canSubmitStaff,
  isValidGraduationYear,
  requiresDepartment,
  scopeLabel,
} from '../../lib/administration';
import type { BatchFormState, DepartmentFormState, StaffFormState } from '../../lib/administration';

/**
 * A college describing itself: its departments, its graduating batches, and the
 * people who run placement.
 *
 * <p>Everything here already existed as a permission long before it existed as a
 * screen. The college's administrator could sign in and read and change nothing,
 * so a freshly onboarded college had no departments — which meant no department
 * coordinator could be scoped to one and no company requirement could target
 * anybody.
 *
 * <p>One page rather than three, because these are three answers to one question
 * and an administrator setting a college up does them in a sitting. Each section
 * is independent: whichever permissions somebody holds are the sections they
 * see, and the server refuses the rest regardless of what is rendered.
 */

/** Turns a failed mutation into something worth reading. */
function messageFor(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return fallback;
}

// ---------------------------------------------------------------- departments

function Departments() {
  const departments = useDepartments();
  const create = useCreateDepartment();
  const toast = useToast();
  const [form, setForm] = useState<DepartmentFormState>(EMPTY_DEPARTMENT_FORM);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      const created = await create.mutateAsync(buildDepartmentRequest(form));
      toast.show(`${created.code} added.`, 'success');
      setForm(EMPTY_DEPARTMENT_FORM);
    } catch (error) {
      toast.show(messageFor(error, 'That department could not be added.'), 'error');
    }
  };

  return (
    <Panel title="Departments">
      <p className="text-muted">
        What a department coordinator can be responsible for, and what a company requirement
        can target.
      </p>
      {departments.isLoading && <Skeleton className="skeleton--line" />}
      {departments.isError && (
        <ErrorState
          title="Departments could not be loaded"
          body="Try again, or reload the page."
        />
      )}

      {departments.data && departments.data.length === 0 && (
        <EmptyState
          title="No departments yet"
          body="Add the first one below. Until a department exists, a department coordinator cannot be scoped to anything and a requirement targeting a department will match nobody."
        />
      )}

      {departments.data && departments.data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th className="numeric">Students</th>
              </tr>
            </thead>
            <tbody>
              {departments.data.map((department) => (
                <tr key={department.id}>
                  <td>
                    <Badge tone="info">{department.code}</Badge>
                  </td>
                  <td>{department.name}</td>
                  <td className="numeric">{department.studentCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <form className="stack gap-3" onSubmit={submit} style={{ marginTop: 'var(--space-4)' }}>
        <div className="grid grid--two">
          <Field label="Department name">
            {({ id }) => (
              <TextInput
                id={id}
                value={form.name}
                placeholder="Computer Science and Engineering"
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            )}
          </Field>
          <Field label="Code" hint="How staff refer to it, and how a department coordinator's scope is written down.">
            {({ id }) => (
              <TextInput
                id={id}
                value={form.code}
                placeholder="CSE"
                onChange={(event) => setForm({ ...form, code: event.target.value })}
              />
            )}
          </Field>
        </div>
        <div className="row">
          <Button type="submit" disabled={!canSubmitDepartment(form) || create.isPending}>
            {create.isPending ? 'Adding…' : 'Add department'}
          </Button>
        </div>
      </form>
    </Panel>
  );
}

// -------------------------------------------------------------------- batches

function Batches() {
  const batches = useBatches();
  const create = useCreateBatch();
  const toast = useToast();
  const [form, setForm] = useState<BatchFormState>(EMPTY_BATCH_FORM);

  const yearLooksWrong = form.graduationYear.length > 0 && !isValidGraduationYear(form.graduationYear);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      const created = await create.mutateAsync(buildBatchRequest(form));
      toast.show(`${created.name} added.`, 'success');
      setForm(EMPTY_BATCH_FORM);
    } catch (error) {
      toast.show(messageFor(error, 'That batch could not be added.'), 'error');
    }
  };

  return (
    <Panel title="Batches">
      <p className="text-muted">
        Graduating years, so a requirement can ask for the class it is hiring from.
      </p>
      {batches.isLoading && <Skeleton className="skeleton--line" />}
      {batches.isError && (
        <ErrorState title="Batches could not be loaded" body="Try again, or reload the page." />
      )}

      {batches.data && batches.data.length === 0 && (
        <EmptyState
          title="No batches yet"
          body="Add a graduating year below. A requirement that names a year no batch covers will match nobody."
        />
      )}

      {batches.data && batches.data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Batch</th>
                <th>Graduating</th>
                <th className="numeric">Students</th>
              </tr>
            </thead>
            <tbody>
              {batches.data.map((batch) => (
                <tr key={batch.id}>
                  <td>{batch.name}</td>
                  <td>
                    <Badge tone="neutral">{batch.graduationYear}</Badge>
                  </td>
                  <td className="numeric">{batch.studentCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <form className="stack gap-3" onSubmit={submit} style={{ marginTop: 'var(--space-4)' }}>
        <div className="grid grid--two">
          <Field label="Batch name">
            {({ id }) => (
              <TextInput
                id={id}
                value={form.name}
                placeholder="Class of 2027"
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            )}
          </Field>
          <Field
            label="Graduation year"
            error={yearLooksWrong ? 'Enter a four-digit year, for example 2027.' : undefined}
          >
            {({ id }) => (
              <TextInput
                id={id}
                value={form.graduationYear}
                placeholder="2027"
                inputMode="numeric"
                onChange={(event) => setForm({ ...form, graduationYear: event.target.value })}
              />
            )}
          </Field>
        </div>
        <div className="row">
          <Button type="submit" disabled={!canSubmitBatch(form) || create.isPending}>
            {create.isPending ? 'Adding…' : 'Add batch'}
          </Button>
        </div>
      </form>
    </Panel>
  );
}

// ---------------------------------------------------------------------- staff

function Staff() {
  const staff = useStaff();
  const departments = useDepartments();
  const create = useCreateStaff();
  const toast = useToast();
  const [form, setForm] = useState<StaffFormState>(EMPTY_STAFF_FORM);

  const needsDepartment = requiresDepartment(form.role);
  const noDepartmentsYet = (departments.data?.length ?? 0) === 0;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      const created = await create.mutateAsync(buildStaffRequest(form));
      toast.show(`${created.fullName} added as ${created.role.replace(/_/g, ' ').toLowerCase()}.`, 'success');
      setForm(EMPTY_STAFF_FORM);
    } catch (error) {
      toast.show(messageFor(error, 'That account could not be created.'), 'error');
    }
  };

  return (
    <Panel title="Placement staff">
      <p className="text-muted">
        Who runs placement here, and how much of the college each of them covers.
      </p>
      {staff.isLoading && <Skeleton className="skeleton--line" />}
      {staff.isError && (
        <ErrorState title="Staff could not be loaded" body="Try again, or reload the page." />
      )}

      {staff.data && staff.data.length === 0 && (
        <EmptyState
          title="No staff yet"
          body="Appoint a department coordinator for each department below. A placement coordinator can also appoint another placement coordinator to share running the college."
        />
      )}

      {staff.data && staff.data.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Covers</th>
              </tr>
            </thead>
            <tbody>
              {staff.data.map((member) => (
                <tr key={member.userId}>
                  <td>{member.fullName}</td>
                  <td className="text-muted">{member.email}</td>
                  <td>
                    <Badge tone={member.role === 'PLACEMENT_COORDINATOR' ? 'accent' : 'info'}>
                      {member.role.replace(/_/g, ' ').toLowerCase()}
                    </Badge>
                  </td>
                  <td>{scopeLabel(member)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <form className="stack gap-3" onSubmit={submit} style={{ marginTop: 'var(--space-4)' }}>
        <div className="grid grid--two">
          <Field label="Full name">
            {({ id }) => (
              <TextInput
                id={id}
                value={form.fullName}
                placeholder="Priya Raman"
                onChange={(event) => setForm({ ...form, fullName: event.target.value })}
              />
            )}
          </Field>
          <Field label="Email">
            {({ id }) => (
              <TextInput
                id={id}
                type="email"
                value={form.email}
                placeholder="priya@northgate.edu"
                onChange={(event) => setForm({ ...form, email: event.target.value })}
              />
            )}
          </Field>
        </div>
        <div className="grid grid--two">
          <Field label="Role">
            {({ id }) => (
              <Select
                id={id}
                value={form.role}
                onChange={(event) =>
                  setForm({ ...form, role: event.target.value, departmentCode: '' })
                }
              >
                {APPOINTABLE_ROLES.map((role) => (
                  <option key={role.value} value={role.value}>
                    {role.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field
            label="Initial password"
            hint={`At least ${MIN_PASSWORD_LENGTH} characters. Give it to them directly and ask them to change it.`}
          >
            {({ id }) => (
              <TextInput
                id={id}
                type="password"
                value={form.password}
                onChange={(event) => setForm({ ...form, password: event.target.value })}
              />
            )}
          </Field>
        </div>

        {needsDepartment && (
          <Field
            label="Department"
            hint="A department coordinator sees only their own department's students. This is what decides that."
            error={noDepartmentsYet ? 'Add a department first — a department coordinator needs one.' : undefined}
          >
            {({ id }) => (
              <Select
                id={id}
                value={form.departmentCode}
                onChange={(event) => setForm({ ...form, departmentCode: event.target.value })}
              >
                <option value="">Choose a department…</option>
                {(departments.data ?? []).map((department) => (
                  <option key={department.id} value={department.code}>
                    {department.code} · {department.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}

        <div className="row">
          <Button type="submit" disabled={!canSubmitStaff(form) || create.isPending}>
            {create.isPending ? 'Creating…' : 'Add staff member'}
          </Button>
        </div>
      </form>
    </Panel>
  );
}

// ----------------------------------------------------------------------- page

export default function CollegeAdministration() {
  const { user } = useAuth();

  return (
    <>
      <PageHeader
        title="Institution"
        subtitle={user?.institutionName ?? 'Your college'}
      />

      <div className="stack gap-5">
        {canManageDepartments(user) && <Departments />}
        {canManageBatches(user) && <Batches />}
        {canManageStaff(user) && <Staff />}
      </div>
    </>
  );
}
