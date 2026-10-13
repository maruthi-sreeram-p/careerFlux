import { useState } from 'react';

import { PageHeader } from '../../components/layout/AppShell';
import { Icon } from '../../components/ui/Icon';
import {
  Badge,
  Button,
  Dialog,
  EmptyState,
  ErrorState,
  Field,
  Panel,
  Skeleton,
  TextInput,
  useToast,
} from '../../components/ui/primitives';
import { ApiError, api } from '../../lib/api';
import { relativeTime } from '../../lib/format';
import { useAdapterDefinitions, useAdapters, useSaveAdapterDefinition } from '../../lib/queries';
import type { AdapterDefinition, AdapterDefinitionInput, AdapterPreview } from '../../lib/types';

/**
 * Adapters an admin defines without code: a public JSON feed and where each
 * field sits in it.
 *
 * A definition is a capability, not a permission. Sources registered against it
 * still go through classify, robots.txt and a named terms review, and trying a
 * definition obeys robots.txt before it makes its one request.
 */

const EMPTY: AdapterDefinitionInput = {
  adapterKey: '',
  displayName: '',
  description: '',
  documentationUrl: '',
  endpointTemplate: '',
  itemsPath: '',
  idPath: 'id',
  titlePath: 'title',
  locationPath: '',
  descriptionPath: '',
  applyUrlPath: '',
  applyUrlTemplate: '',
  postedAtPath: '',
  employmentTypePath: '',
  departmentPath: '',
};

function toInput(definition: AdapterDefinition): AdapterDefinitionInput {
  const input = { ...EMPTY };
  (Object.keys(EMPTY) as (keyof AdapterDefinitionInput)[]).forEach((field) => {
    input[field] = (definition[field] as string | null) ?? '';
  });
  return input;
}

/** Blank fields go to the server as null, so "not mapped" is never an empty path. */
function toBody(input: AdapterDefinitionInput) {
  return Object.fromEntries(
    Object.entries(input).map(([field, value]) => [field, value.trim() === '' ? null : value.trim()]),
  );
}

const PATH_FIELDS: { field: keyof AdapterDefinitionInput; label: string; hint?: string; required?: boolean }[] = [
  {
    field: 'itemsPath',
    label: 'Postings list',
    hint: 'Where the array of postings is, e.g. data.jobs. Leave blank when the response is the array.',
  },
  { field: 'idPath', label: 'Id', required: true, hint: 'Stable per posting, e.g. id or requisition.id.' },
  { field: 'titlePath', label: 'Title', required: true },
  { field: 'locationPath', label: 'Location', hint: 'A list of names is joined, e.g. locations or location.city.' },
  { field: 'descriptionPath', label: 'Description' },
  { field: 'applyUrlPath', label: 'Apply link' },
  { field: 'postedAtPath', label: 'Posted at', hint: 'ISO date, or epoch seconds or milliseconds.' },
  { field: 'employmentTypePath', label: 'Employment type' },
  { field: 'departmentPath', label: 'Department' },
];

function DefinitionDialog({
  open,
  editing,
  onClose,
}: {
  open: boolean;
  editing: AdapterDefinition | null;
  onClose: () => void;
}) {
  const toast = useToast();
  const save = useSaveAdapterDefinition();
  const [input, setInput] = useState<AdapterDefinitionInput>(editing ? toInput(editing) : EMPTY);
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<AdapterPreview | null>(null);
  const [trying, setTrying] = useState(false);

  const set = (field: keyof AdapterDefinitionInput) => (event: { target: { value: string } }) =>
    setInput((current) => ({ ...current, [field]: event.target.value }));

  const needsToken = input.endpointTemplate.includes('{token}');

  const tryIt = async () => {
    setError(null);
    setPreview(null);
    setTrying(true);
    try {
      setPreview(
        await api.post<AdapterPreview>('/api/admin/adapters/preview', {
          definition: toBody(input),
          token: token.trim() || null,
        }),
      );
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'The definition could not be tried.');
    } finally {
      setTrying(false);
    }
  };

  const submit = () => {
    setError(null);
    save.mutate(
      { key: editing?.adapterKey ?? null, body: toBody(input) },
      {
        onSuccess: (saved) => {
          toast.show(
            editing
              ? `${saved.displayName} saved.`
              : `${saved.displayName} saved as ${saved.adapterKey}. Register a source with it next.`,
            'success',
          );
          onClose();
        },
        onError: (caught) =>
          setError(caught instanceof ApiError ? caught.message : 'The adapter could not be saved.'),
      },
    );
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={editing ? `Edit ${editing.displayName}` : 'Define an adapter'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="secondary"
            loading={trying}
            disabled={!input.endpointTemplate || !input.idPath || !input.titlePath || (needsToken && !token)}
            onClick={tryIt}
          >
            Try it
          </Button>
          <Button
            variant="primary"
            loading={save.isPending}
            disabled={!input.displayName || !input.endpointTemplate || !input.idPath || !input.titlePath}
            onClick={submit}
          >
            Save
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

        <Field label="Name">
          {({ id }) => (
            <TextInput id={id} value={input.displayName} onChange={set('displayName')} placeholder="Acme jobs API" />
          )}
        </Field>

        {!editing && (
          <Field label="Key" hint="Optional. Made from the name when blank; always starts with custom-.">
            {({ id }) => (
              <TextInput id={id} value={input.adapterKey} onChange={set('adapterKey')} placeholder="custom-acme" />
            )}
          </Field>
        )}

        <Field
          label="Endpoint"
          hint="An https address returning JSON. Put {token} where each source's identifier goes, e.g. https://api.acme.example/boards/{token}/jobs."
        >
          {({ id }) => (
            <TextInput
              id={id}
              value={input.endpointTemplate}
              onChange={set('endpointTemplate')}
              placeholder="https://api.acme.example/boards/{token}/jobs"
            />
          )}
        </Field>

        <div className="adapter-form__paths">
          {PATH_FIELDS.map(({ field, label, hint, required }) => (
            <Field key={field} label={required ? `${label} *` : label} hint={hint}>
              {({ id }) => <TextInput id={id} value={input[field]} onChange={set(field)} />}
            </Field>
          ))}
        </div>

        <Field
          label="Apply link template"
          hint="Used when a posting has no link of its own. Must contain {id}, e.g. https://acme.example/careers/{id}."
        >
          {({ id }) => (
            <TextInput id={id} value={input.applyUrlTemplate} onChange={set('applyUrlTemplate')} />
          )}
        </Field>

        <Field label="Description">
          {({ id }) => <TextInput id={id} value={input.description} onChange={set('description')} />}
        </Field>

        <Field label="Documentation URL">
          {({ id }) => (
            <TextInput id={id} value={input.documentationUrl} onChange={set('documentationUrl')} />
          )}
        </Field>

        {needsToken && (
          <Field label="Sample identifier for Try it" hint="Fills {token} for the one trial request.">
            {({ id }) => (
              <TextInput id={id} value={token} onChange={(event) => setToken(event.target.value)} />
            )}
          </Field>
        )}

        {preview && <PreviewResult preview={preview} />}

        <p className="text-faint" style={{ fontSize: 'var(--text-xs)' }}>
          Try it makes one request, only if the endpoint's robots.txt permits it. Saving activates
          nothing: a source using this adapter is still classified, checked against robots.txt and
          reviewed for terms before it syncs.
        </p>
      </div>
    </Dialog>
  );
}

function PreviewResult({ preview }: { preview: AdapterPreview }) {
  return (
    <div className="adapter-preview" aria-live="polite">
      <div className="row wrap gap-2">
        <Badge tone={preview.fetched ? 'positive' : 'caution'}>{preview.fetched ? 'Fetched' : 'Not fetched'}</Badge>
        {preview.robotsStatus && <Badge square>robots: {preview.robotsStatus}</Badge>}
      </div>
      <p className="adapter-preview__message">{preview.message}</p>
      <p className="source-row__url truncate">{preview.endpoint}</p>
      {preview.postings.length > 0 && (
        <ul className="adapter-preview__list">
          {preview.postings.map((posting) => (
            <li key={posting.externalId}>
              <strong>{posting.title}</strong>
              <span className="text-faint">
                {' '}
                · {posting.location ?? 'no location'} · {posting.applyUrl ? 'apply link' : 'no apply link'}
                {posting.hasDescription ? ' · description' : ''}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function DefinitionRow({ definition, onEdit }: { definition: AdapterDefinition; onEdit: () => void }) {
  const toast = useToast();
  const save = useSaveAdapterDefinition();

  const toggle = () =>
    save.mutate(
      { key: definition.adapterKey, toggle: definition.enabled ? 'disable' : 'enable' },
      {
        onSuccess: () =>
          toast.show(
            definition.enabled
              ? `${definition.displayName} switched off. Its sources will not sync.`
              : `${definition.displayName} switched on.`,
            'success',
          ),
        onError: (caught) =>
          toast.show(caught instanceof ApiError ? caught.message : 'That change failed.', 'error'),
      },
    );

  return (
    <div className="source-row" style={{ gridTemplateColumns: '10px minmax(0,1fr) auto' }}>
      <span
        className={`source-row__pip source-row__pip--${definition.enabled ? 'active' : 'retired'}`}
      />
      <div className="grow">
        <p className="source-row__name truncate">{definition.displayName}</p>
        <p className="source-row__url truncate">{definition.endpointTemplate}</p>
        <div className="row wrap gap-2" style={{ marginTop: 'var(--space-2)' }}>
          <Badge square>{definition.adapterKey}</Badge>
          <Badge tone={definition.enabled ? 'positive' : 'neutral'}>
            {definition.enabled ? 'Enabled' : 'Off'}
          </Badge>
          <span className="text-faint" style={{ fontSize: 'var(--text-2xs)' }}>
            updated {relativeTime(definition.updatedAt)} by {definition.updatedBy}
          </span>
        </div>
      </div>
      <div className="row wrap gap-2">
        <Button size="sm" onClick={onEdit}>
          Edit
        </Button>
        <Button size="sm" loading={save.isPending} onClick={toggle}>
          {definition.enabled ? 'Switch off' : 'Switch on'}
        </Button>
      </div>
    </div>
  );
}

export default function AdminAdapters() {
  const definitions = useAdapterDefinitions();
  const builtIn = useAdapters();
  const [dialog, setDialog] = useState<{ editing: AdapterDefinition | null } | null>(null);

  const custom = new Set(definitions.data?.map((definition) => definition.adapterKey));

  return (
    <div className="page page--wide">
      <PageHeader
        title="Adapters"
        subtitle="Teach CareerFlux to read a public JSON job feed without a code change. Sources registered with an adapter still pass every policy check."
        actions={
          <Button variant="primary" size="sm" onClick={() => setDialog({ editing: null })}>
            <Icon.Plus size={14} />
            Define adapter
          </Button>
        }
      />

      <Panel title="Defined here" flush>
        {definitions.isLoading && (
          <div style={{ padding: 'var(--space-4)' }}>
            <Skeleton height={54} />
          </div>
        )}
        {definitions.isError && (
          <ErrorState
            title="The adapters could not be loaded"
            body={(definitions.error as Error).message}
            onRetry={() => definitions.refetch()}
          />
        )}
        {definitions.data?.length === 0 && (
          <EmptyState
            title="No adapters defined yet"
            body="Define one for any employer or board that publishes its jobs as JSON."
          />
        )}
        {definitions.data?.map((definition) => (
          <DefinitionRow
            key={definition.adapterKey}
            definition={definition}
            onEdit={() => setDialog({ editing: definition })}
          />
        ))}
      </Panel>

      <Panel title="Built in" flush className="adapter-builtins">
        {builtIn.data
          ?.filter((adapter) => !custom.has(adapter.key))
          .map((adapter) => (
            <div
              key={adapter.key}
              className="source-row"
              style={{ gridTemplateColumns: '10px minmax(0,1fr)' }}
            >
              <span className="source-row__pip source-row__pip--active" />
              <div className="grow">
                <p className="source-row__name truncate">{adapter.displayName}</p>
                <p className="source-row__url">{adapter.description}</p>
                <div className="row wrap gap-2" style={{ marginTop: 'var(--space-2)' }}>
                  <Badge square>{adapter.key}</Badge>
                </div>
              </div>
            </div>
          ))}
      </Panel>

      {dialog && (
        <DefinitionDialog
          open
          editing={dialog.editing}
          onClose={() => setDialog(null)}
        />
      )}
    </div>
  );
}
