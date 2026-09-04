import { useState } from 'react';

import { Badge, Button, Dialog, Field, TextArea, useToast } from '../ui/primitives';
import {
  canConfirm,
  choosable,
  confirmedDomains,
  confirmedNames,
  discoverySummary,
  evidenceLabel,
  evidenceTone,
  isDiscoveryResult,
  isSelected,
  outcomeLabel,
  parseNames,
  resolutionSummary,
  toggle,
  type Selection,
} from '../../lib/companyDiscovery';
import { useDiscoverConfirmedDomains, useResolveCompanies } from '../../lib/queries';
import type { CompanyDiscoveryResult } from '../../lib/types';

/**
 * Adding an employer by name.
 *
 * <p>Two steps, and the gap between them is the feature. CareerFlux resolves a
 * name to the domains it believes belong to that company and stops there; a
 * person then chooses which one is right before anything is probed. That is not
 * ceremony — the confirmation authorises an outbound request to somebody else's
 * servers, and registering the wrong domain puts another company's jobs in
 * front of students under this employer's name.
 *
 * <p>Nothing is pre-selected, including a single confidently resolved domain,
 * because a pre-ticked box is a default rather than a decision.
 *
 * <p>What this screen will not do is pretend. Most large employers run career
 * systems CareerFlux cannot read, and the honest result — no readable board —
 * is reported as plainly as a success.
 */
export function AddCompanyDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const toast = useToast();
  const resolve = useResolveCompanies();
  const discover = useDiscoverConfirmedDomains();

  const [names, setNames] = useState('');
  const [result, setResult] = useState<CompanyDiscoveryResult | null>(null);
  const [selection, setSelection] = useState<Selection>({});
  const [error, setError] = useState<string | null>(null);

  const companies = choosable(result);
  const finished = isDiscoveryResult(result);

  const reset = () => {
    setNames('');
    setResult(null);
    setSelection({});
    setError(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const runResolve = async () => {
    const parsed = parseNames(names);
    if (parsed.length === 0) {
      setError('Enter at least one company name.');
      return;
    }
    setError(null);
    setSelection({});
    try {
      setResult(await resolve.mutateAsync(parsed));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Those names could not be resolved.');
    }
  };

  const runDiscover = async () => {
    setError(null);
    try {
      const outcome = await discover.mutateAsync({
        confirmedDomains: confirmedDomains(selection),
        companyNames: confirmedNames(selection, companies),
      });
      setResult(outcome);
      setSelection({});
      toast.show(discoverySummary(outcome), 'success');
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Those domains could not be probed.');
    }
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title="Add company"
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            {finished ? 'Done' : 'Cancel'}
          </Button>
          {!result && (
            <Button variant="primary" onClick={runResolve} disabled={resolve.isPending}>
              {resolve.isPending ? 'Looking…' : 'Find domains'}
            </Button>
          )}
          {result && !finished && (
            <Button
              variant="primary"
              onClick={runDiscover}
              disabled={!canConfirm(selection) || discover.isPending}
            >
              {discover.isPending ? 'Probing…' : 'Confirm and probe'}
            </Button>
          )}
          {finished && (
            <Button variant="secondary" onClick={reset}>
              Add another
            </Button>
          )}
        </>
      }
    >
      {!result && (
        <>
          <p className="text-muted">
            Enter one company per line. CareerFlux works out which domain each name belongs to and
            shows you what it found — nothing is contacted for jobs until you confirm a domain.
          </p>
          <Field
            label="Company names"
            hint="One per line, or separated by commas."
            error={error ?? undefined}
          >
            {({ id }) => (
              <TextArea
                id={id}
                value={names}
                rows={5}
                onChange={(event) => setNames(event.target.value)}
                placeholder={'Meesho\nAutoRABIT\nWipro'}
              />
            )}
          </Field>
        </>
      )}

      {result && !finished && (
        <>
          <p className="text-muted">{resolutionSummary(result)}</p>

          {companies.length === 0 && (
            <p className="text-muted">
              No domain could be worked out for those names. If you know the careers domain, close
              this and register the source directly.
            </p>
          )}

          <div className="discovery-candidates">
            {companies.map((company) => (
              <section key={company.slug} className="candidate-group">
                <header className="candidate-group__head">
                  <h4 className="candidate-group__name">{company.companyName}</h4>
                  <Badge tone={company.outcome === 'RESOLVED' ? 'info' : 'caution'}>
                    {outcomeLabel(company.outcome)}
                  </Badge>
                </header>
                <p className="text-faint candidate-group__detail">{company.detail}</p>
                <div className="candidate-group__options">
                  {company.candidates.map((candidate) => {
                    const chosen = isSelected(selection, company.slug, candidate.domain);
                    return (
                      <button
                        type="button"
                        key={candidate.domain}
                        className={`candidate-option${chosen ? ' candidate-option--chosen' : ''}`}
                        aria-pressed={chosen}
                        onClick={() =>
                          setSelection((current) =>
                            toggle(current, company.slug, candidate.domain),
                          )
                        }
                      >
                        <span className="candidate-option__domain">{candidate.domain}</span>
                        <Badge tone={evidenceTone(candidate.evidence)}>
                          {evidenceLabel(candidate.evidence)}
                        </Badge>
                        <span className="candidate-option__detail text-faint">
                          {candidate.detail}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>

          {result.notFound.length > 0 && (
            <p className="text-faint">
              No domain found for {result.notFound.map((c) => c.companyName).join(', ')}.
            </p>
          )}

          {error && <p className="form-error">{error}</p>}
        </>
      )}

      {result && finished && (
        <>
          <p className="text-muted">{discoverySummary(result)}</p>

          {result.registered.length > 0 && (
            <ul className="discovery-outcome">
              {result.registered.map((source) => (
                <li key={source.sourceId}>
                  <strong>{source.name}</strong> — {source.provider.toLowerCase()} board at{' '}
                  {source.domain}
                  <span className="text-faint"> · registered at DISCOVERED</span>
                </li>
              ))}
            </ul>
          )}

          {result.alreadyKnown.length > 0 && (
            <p className="text-faint">Already in the registry: {result.alreadyKnown.join(', ')}.</p>
          )}

          {result.withoutBoard.length > 0 && (
            <p className="text-faint">
              No readable board at {result.withoutBoard.join(', ')}. That is a normal outcome —
              many employers run career systems CareerFlux cannot read, and none of these were
              registered.
            </p>
          )}

          <p className="text-faint">
            Nothing here is active yet. A registered source still has to be classified and pass
            robots, terms and access review before it will ever be synced.
          </p>
        </>
      )}
    </Dialog>
  );
}
