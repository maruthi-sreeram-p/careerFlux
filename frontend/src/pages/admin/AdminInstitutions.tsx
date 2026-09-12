import { useState } from 'react';
import type { FormEvent } from 'react';

import { PageHeader } from '../../components/layout/AppShell';
import {
  Badge,
  Button,
  ErrorState,
  Field,
  Panel,
  Skeleton,
  TextInput,
  useToast,
} from '../../components/ui/primitives';
import { ApiError } from '../../lib/api';
import { useCreateInstitution, useInstitutions } from '../../lib/queries';
import {
  EMPTY_ONBOARDING_FORM,
  buildProvisionRequest,
  canSubmit as formCanSubmit,
  isReachable,
} from '../../lib/onboarding';
import type { OnboardingFormState } from '../../lib/onboarding';
import { relativeTime } from '../../lib/format';
import type { ProvisionedInstitution } from '../../lib/types';

/**
 * Onboarding a college onto CareerFlux.
 *
 * <p>The one screen in the product that creates a tenant, which is why it is
 * short. What a college <em>is</em> — its departments, its batches, its students
 * — belongs to the college and is theirs to describe. This makes the boundary
 * and, if asked, the single account that can start filling it in.
 *
 * <p>Deliberately not a wizard. An operator onboarding a college has the name
 * and the email domain in front of them; spreading two fields across four
 * screens would not make it safer.
 */

/** Shown after creation, rather than dropping the operator back onto an empty form. */
function CreatedPanel({
  institution,
  onDone,
}: {
  institution: ProvisionedInstitution;
  onDone: () => void;
}) {
  return (
    <Panel title="Institution created successfully">
      <dl className="summary-list">
        <div>
          <dt className="text-muted">Institution</dt>
          <dd>
            <strong>{institution.name}</strong>
          </dd>
        </div>
        <div>
          <dt className="text-muted">Domain</dt>
          <dd>{institution.emailDomains ?? 'None — students join with the registration code'}</dd>
        </div>
        {institution.registrationCode && (
          <div>
            <dt className="text-muted">Registration code</dt>
            <dd>{institution.registrationCode}</dd>
          </div>
        )}
        <div>
          <dt className="text-muted">Identifier</dt>
          <dd>{institution.slug}</dd>
        </div>
        {institution.collegeAdminEmail && (
          <div>
            <dt className="text-muted">First administrator</dt>
            <dd>{institution.collegeAdminEmail}</dd>
          </div>
        )}
      </dl>

      <p className="text-muted">
        {institution.collegeAdminEmail ? (
          <>
            Give <strong>{institution.collegeAdminEmail}</strong> the password you set, over some
            channel that is not this screen. They sign in and configure the college from here on —
            you are not signed in as them.
          </>
        ) : (
          <>This college has no administrator yet, so nobody can configure it until one exists.</>
        )}
      </p>

      <div className="row gap-2">
        <Button onClick={onDone}>Onboard another college</Button>
      </div>
    </Panel>
  );
}

function CreateForm({ onCreated }: { onCreated: (created: ProvisionedInstitution) => void }) {
  const create = useCreateInstitution();
  const toast = useToast();

  // One state object rather than eight, so the rules in lib/onboarding can be
  // tested against exactly what this form holds.
  const [form, setForm] = useState<OnboardingFormState>(EMPTY_ONBOARDING_FORM);
  const [error, setError] = useState<ApiError | null>(null);

  const set = <K extends keyof OnboardingFormState>(key: K, value: OnboardingFormState[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const reachable = isReachable(form);
  const canSubmit = formCanSubmit(form) && !create.isPending;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      const created = await create.mutateAsync(buildProvisionRequest(form));
      // Cleared straight away. The password has done its job and has no reason
      // to sit in this component's state afterwards.
      set('adminPassword', '');
      toast.show(`${created.name} is ready.`, 'success');
      onCreated(created);
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(caught);
      } else {
        toast.show('That institution could not be created.', 'error');
      }
    }
  };

  return (
    <Panel title="Create institution">
      <form onSubmit={submit} noValidate>
        <Field label="Institution name" hint="As the college writes it themselves.">
          {({ id }) => (
            <TextInput
              id={id}
              value={form.name}
              onChange={(event) => set('name', event.target.value)}
              placeholder="Northgate Institute of Technology"
              autoComplete="off"
            />
          )}
        </Field>

        <Field
          label="Email domain"
          hint="Students with an address here are recognised automatically. Separate several with commas."
          error={error?.fieldError('emailDomains')}
        >
          {({ id }) => (
            <TextInput
              id={id}
              value={form.emailDomains}
              onChange={(event) => set('emailDomains', event.target.value)}
              placeholder="northgate.edu"
              autoComplete="off"
            />
          )}
        </Field>

        <Field
          label="Registration code"
          hint="Optional. For students whose address is not on the college domain."
        >
          {({ id }) => (
            <TextInput
              id={id}
              value={form.registrationCode}
              onChange={(event) => set('registrationCode', event.target.value)}
              placeholder="NORTHGATE-2026"
              autoComplete="off"
            />
          )}
        </Field>

        <Field label="City" hint="Optional.">
          {({ id }) => (
            <TextInput
              id={id}
              value={form.city}
              onChange={(event) => set('city', event.target.value)}
              placeholder="Hyderabad"
              autoComplete="off"
            />
          )}
        </Field>

        {!reachable && form.name.trim().length > 0 && (
          <p className="text-muted">
            Give the college an email domain, a registration code, or both — otherwise nobody can
            register against it.
          </p>
        )}

        <label className="row gap-2">
          <input
            type="checkbox"
            checked={form.withAdmin}
            onChange={(event) => set('withAdmin', event.target.checked)}
          />
          <span>Create the college's first placement coordinator now</span>
        </label>

        {form.withAdmin && (
          <>
            <Field label="Name">
              {({ id }) => (
                <TextInput
                  id={id}
                  value={form.adminName}
                  onChange={(event) => set('adminName', event.target.value)}
                  placeholder="Anita Rao"
                  autoComplete="off"
                />
              )}
            </Field>
            <Field label="Email">
              {({ id }) => (
                <TextInput
                  id={id}
                  type="email"
                  value={form.adminEmail}
                  onChange={(event) => set('adminEmail', event.target.value)}
                  placeholder="anita@northgate.edu"
                  autoComplete="off"
                />
              )}
            </Field>
            <Field
              label="Initial password"
              hint="At least 10 characters. Pass it to them separately, and ask them to change it."
            >
              {({ id }) => (
                <TextInput
                  id={id}
                  type="password"
                  value={form.adminPassword}
                  onChange={(event) => set('adminPassword', event.target.value)}
                  autoComplete="new-password"
                />
              )}
            </Field>
          </>
        )}

        {error && <ErrorState title="That institution could not be created" body={error.message} />}

        <div className="row gap-2">
          <Button type="submit" disabled={!canSubmit}>
            {create.isPending
              ? 'Creating…'
              : form.withAdmin
                ? 'Create institution & admin'
                : 'Create institution'}
          </Button>
        </div>
      </form>
    </Panel>
  );
}

export default function AdminInstitutions() {
  const institutions = useInstitutions();
  const [created, setCreated] = useState<ProvisionedInstitution | null>(null);

  return (
    <>
      <PageHeader
        title="Institutions"
        subtitle="Colleges on this deployment, and how to onboard another."
      />

      {created ? (
        <CreatedPanel institution={created} onDone={() => setCreated(null)} />
      ) : (
        <CreateForm onCreated={setCreated} />
      )}

      <Panel title="Existing institutions">
        {institutions.isLoading && <Skeleton />}
        {institutions.isError && (
          <ErrorState title="The list of institutions could not be loaded" />
        )}
        {institutions.data && institutions.data.length === 0 && (
          <p className="text-muted">
            No colleges yet. The first one you create is the first tenant on this deployment.
          </p>
        )}
        {institutions.data && institutions.data.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>Institution</th>
                <th>Email domain</th>
                <th>Status</th>
                <th>Onboarded</th>
              </tr>
            </thead>
            <tbody>
              {institutions.data.map((institution) => (
                <tr key={institution.id}>
                  <td>
                    <strong>{institution.name}</strong>
                    <div className="text-muted">{institution.slug}</div>
                  </td>
                  <td>
                    {institution.emailDomains ?? <span className="text-muted">Code only</span>}
                  </td>
                  <td>
                    <Badge tone={institution.status === 'ACTIVE' ? 'positive' : 'neutral'}>
                      {institution.status}
                    </Badge>
                  </td>
                  <td>{relativeTime(institution.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </>
  );
}
