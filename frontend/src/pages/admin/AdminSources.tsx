import { useState } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '../../components/layout/AppShell';
import { HealthBadge, StateBadge } from '../../components/source/SourceBits';
import { Icon } from '../../components/ui/Icon';
import {
  Badge,
  Button,
  Dialog,
  ErrorState,
  Field,
  Panel,
  Select,
  Skeleton,
  TextInput,
  useToast,
} from '../../components/ui/primitives';
import { ApiError, api } from '../../lib/api';
import { useAdapters, useAdminSourceAction, useSources } from '../../lib/queries';
import { relativeTime } from '../../lib/format';
import type { SourceSummary } from '../../lib/types';

/**
 * The registration and review console.
 *
 * Registration deliberately does not let an operator type a state. A source
 * starts at DISCOVERED and walks the machine: classify, check robots, record the
 * terms review, then transition. Nothing here can skip the policy gate — the
 * server refuses, and this UI is built to make that obvious rather than to hide
 * it behind a form.
 */
function RegisterDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const adapters = useAdapters();
  const toast = useToast();
  const [name, setName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [adapterKey, setAdapterKey] = useState('');
  const [externalIdentifier, setExternalIdentifier] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [tosUrl, setTosUrl] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    setSubmitting(true);
    try {
      await api.post('/api/admin/sources', {
        name,
        baseUrl,
        adapterKey: adapterKey || null,
        externalIdentifier: externalIdentifier || null,
        companyName: companyName || null,
        tosUrl: tosUrl || null,
        discoveryDetail: 'Registered through the admin console.',
        rateLimitPerMinute: 20,
      });
      toast.show('Source registered at DISCOVERED. Classify it next.', 'success');
      onClose();
      setName('');
      setBaseUrl('');
      setExternalIdentifier('');
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'That source could not be registered.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Register a source"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={submitting}
            disabled={!name || !baseUrl}
            onClick={submit}
          >
            Register
          </Button>
        </>
      }
    >
      <div className="grid" style={{ gap: 'var(--space-4)' }}>
        {error && (
          <div className="auth__alert" role="alert">
            <Icon.Warning size={15} style={{ color: 'var(--negative)', flex: 'none', marginTop: 1 }} />
            <span>{error}</span>
          </div>
        )}

        <Field label="Display name">
          {({ id }) => (
            <TextInput
              id={id}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Acme Corp careers"
            />
          )}
        </Field>

        <Field label="Base URL" hint="The public URL of the board or endpoint.">
          {({ id }) => (
            <TextInput
              id={id}
              value={baseUrl}
              onChange={(event) => setBaseUrl(event.target.value)}
              placeholder="https://boards.greenhouse.io/acme"
            />
          )}
        </Field>

        <Field
          label="Adapter"
          hint="Leave blank to let classification pick one from the URL."
        >
          {({ id }) => (
            <Select id={id} value={adapterKey} onChange={(event) => setAdapterKey(event.target.value)}>
              <option value="">Detect automatically</option>
              {adapters.data?.map((adapter) => (
                <option key={adapter.key} value={adapter.key}>
                  {adapter.displayName}
                </option>
              ))}
            </Select>
          )}
        </Field>

        <Field
          label="External identifier"
          hint="The board token or company slug the adapter needs, e.g. the Greenhouse board name."
        >
          {({ id }) => (
            <TextInput
              id={id}
              value={externalIdentifier}
              onChange={(event) => setExternalIdentifier(event.target.value)}
              placeholder="acme"
            />
          )}
        </Field>

        <Field label="Company name">
          {({ id }) => (
            <TextInput
              id={id}
              value={companyName}
              onChange={(event) => setCompanyName(event.target.value)}
              placeholder="Acme Corp"
            />
          )}
        </Field>

        <Field label="Terms of service URL">
          {({ id }) => (
            <TextInput
              id={id}
              value={tosUrl}
              onChange={(event) => setTosUrl(event.target.value)}
              placeholder="https://acme.example/terms"
            />
          )}
        </Field>

        <p className="text-faint" style={{ fontSize: 'var(--text-xs)' }}>
          The source is created in the DISCOVERED state with an empty policy record. It cannot be
          activated until robots.txt has been checked, a named reviewer has recorded the terms
          decision, and no access control would need to be bypassed.
        </p>
      </div>
    </Dialog>
  );
}

function ActionRow({ source }: { source: SourceSummary }) {
  const action = useAdminSourceAction();
  const toast = useToast();

  const run = (name: string, label: string, body?: unknown) =>
    action.mutate(
      { sourceId: source.id, action: name, body },
      {
        onSuccess: () => toast.show(label, 'success'),
        onError: (caught) => {
          const message =
            caught instanceof ApiError
              ? caught.details?.blockers
                ? `${caught.message} ${(caught.details.blockers as string[]).join(' ')}`
                : caught.message
              : 'That action failed.';
          toast.show(message, 'error');
        },
      },
    );

  return (
    <div className="source-row" style={{ gridTemplateColumns: '10px minmax(0,1fr) auto' }}>
      <span className={`source-row__pip source-row__pip--${source.state.toLowerCase()}`} />
      <div className="grow">
        <Link to={`/app/sources/${source.id}`} className="source-row__name truncate">
          {source.name}
        </Link>
        <p className="source-row__url truncate">{source.baseUrl}</p>
        <div className="row wrap gap-2" style={{ marginTop: 'var(--space-2)' }}>
          <StateBadge state={source.state} />
          <HealthBadge health={source.healthStatus} lastCheckedAt={source.lastHealthCheckAt} />
          {source.adapterKey ? (
            <Badge square>{source.adapterKey}</Badge>
          ) : (
            <Badge tone="caution">No adapter</Badge>
          )}
          <span className="text-faint" style={{ fontSize: 'var(--text-2xs)' }}>
            {source.lastSuccessfulSyncAt
              ? `synced ${relativeTime(source.lastSuccessfulSyncAt)}`
              : 'never synced'}
          </span>
        </div>
      </div>
      <div className="row wrap gap-2">
        {source.state === 'DISCOVERED' && (
          <Button size="sm" onClick={() => run('classify', 'Classification attempted')}>
            Classify
          </Button>
        )}
        <Button size="sm" onClick={() => run('check-robots', 'robots.txt evaluated')}>
          Check robots
        </Button>
        {(source.state === 'CLASSIFIED' || source.state === 'POLICY_REVIEW') && (
          <Button
            size="sm"
            onClick={() =>
              run('terms-review', 'Terms review recorded', {
                tosStatus: 'PERMITTED',
                tosUrl: source.policy?.tosUrl ?? null,
                notes: 'Reviewed through the admin console.',
              })
            }
          >
            Record terms review
          </Button>
        )}
        {source.state === 'POLICY_REVIEW' && (
          <Button
            size="sm"
            onClick={() =>
              run('access', 'Access characteristics recorded', {
                accessPolicy: 'PUBLIC_API',
                requiresAuthentication: false,
                requiresCaptcha: false,
                hasAntiBot: false,
                paywalled: false,
                allowedFields: 'title, company, location, description, apply URL',
              })
            }
          >
            Record access
          </Button>
        )}
        {source.state === 'POLICY_REVIEW' && (
          <Button
            size="sm"
            onClick={() =>
              run('transition', 'Moved to APPROVED', {
                targetState: 'APPROVED',
                reason: 'Policy satisfied.',
              })
            }
          >
            Approve
          </Button>
        )}
        {source.state === 'APPROVED' && (
          <Button
            variant="primary"
            size="sm"
            onClick={() =>
              run('transition', 'Source activated', {
                targetState: 'ACTIVE',
                reason: 'Activated from the admin console.',
              })
            }
          >
            Activate
          </Button>
        )}
        {(source.state === 'ACTIVE' || source.state === 'DEGRADED') && (
          <>
            <Button size="sm" onClick={() => run('health-check', 'Health probe complete')}>
              Probe
            </Button>
            <Button variant="primary" size="sm" onClick={() => run('sync', 'Ingestion run complete')}>
              Sync now
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

export default function AdminSources() {
  const sources = useSources([], 0, 100);
  const [registerOpen, setRegisterOpen] = useState(false);

  return (
    <div className="page page--wide">
      <PageHeader
        title="Source registry"
        subtitle="Register, classify and review sources. Every action here is recorded against your account in the audit log."
        actions={
          <Button variant="primary" size="sm" onClick={() => setRegisterOpen(true)}>
            <Icon.Plus size={14} />
            Register source
          </Button>
        }
      />

      <Panel flush>
        {sources.isLoading &&
          [0, 1, 2].map((index) => (
            <div key={index} style={{ padding: 'var(--space-4)' }}>
              <Skeleton height={54} />
            </div>
          ))}

        {sources.isError && (
          <ErrorState
            title="The registry could not be loaded"
            body={(sources.error as Error).message}
            onRetry={() => sources.refetch()}
          />
        )}

        {sources.data?.content.map((source) => <ActionRow key={source.id} source={source} />)}
      </Panel>

      <p className="text-faint" style={{ fontSize: 'var(--text-xs)', marginTop: 'var(--space-4)' }}>
        The quick actions above record the common case. For anything unusual — a restricted terms
        outcome, an endpoint that turns out to need authentication — open the source and record the
        specifics, because those are the decisions that have to be defensible later.
      </p>

      <RegisterDialog open={registerOpen} onClose={() => setRegisterOpen(false)} />
    </div>
  );
}
