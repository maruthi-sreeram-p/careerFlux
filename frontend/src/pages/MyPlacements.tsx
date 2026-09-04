import { useState } from 'react';

import { PageHeader } from '../components/layout/AppShell';
import {
  Badge,
  Button,
  Dialog,
  EmptyState,
  Field,
  Panel,
  TextArea,
  useToast,
} from '../components/ui/primitives';
import { formatDateTime, relativeTime, workModeLabel } from '../lib/format';
import {
  historyLine,
  placementSummary,
  sortPlacements,
  stageDescription,
  stageTone,
  studentActions,
  studentResponseFor,
  studentStageLabel,
  type StageAction,
} from '../lib/placement';
import { useMyPlacementHistory, useMyPlacements, useRespondToPlacement } from '../lib/queries';
import type { MyPlacement } from '../lib/types';

/**
 * What the college has put this student forward for.
 *
 * <p>Until now this was invisible to the person it was about: a placement team
 * could shortlist a student, and the student had no way to know. This is the
 * other side of that — and the only screen in CareerFlux where a student makes
 * a placement decision rather than reading one.
 *
 * <p>Deliberately absent: match scores, eligibility verdicts, skill gaps and
 * the reasons the college saw. Those are the college's working notes, the
 * server does not send them here, and turning them into a verdict a student
 * reads about themselves would be a different product decision than anyone has
 * made. What a student sees is what was decided and what is being asked.
 */

function PlacementCard({ placement }: { placement: MyPlacement }) {
  const toast = useToast();
  const respond = useRespondToPlacement();
  const [confirming, setConfirming] = useState<StageAction | null>(null);
  const [note, setNote] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);

  const actions = studentActions(placement.stage, placement.requirementStatus);

  const answer = async (action: StageAction, withNote: string) => {
    const response = studentResponseFor(action.stage);
    if (!response) return;
    try {
      await respond.mutateAsync({
        requirementId: placement.requirementId,
        response,
        note: withNote.trim() || undefined,
      });
      toast.show(
        response === 'interested'
          ? `Your college knows you want to go ahead with ${placement.companyName}.`
          : `Your college knows you are not going ahead with ${placement.companyName}.`,
        'success',
      );
      setConfirming(null);
      setNote('');
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'That answer could not be recorded.',
        'error',
      );
    }
  };

  return (
    <article className={`placement${placement.awaitingYou ? ' placement--awaiting' : ''}`}>
      <header className="placement__head">
        <div>
          <h3 className="placement__role">{placement.roleTitle}</h3>
          <p className="text-muted">
            {[
              placement.companyName,
              placement.location,
              placement.workMode ? workModeLabel(placement.workMode) : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
        </div>
        <Badge tone={stageTone(placement.stage)}>{studentStageLabel(placement.stage)}</Badge>
      </header>

      <p className="placement__meaning">{stageDescription(placement.stage)}</p>

      {placement.awaitingYou && placement.requirementStatus === 'OPEN' && (
        <div className="placement__ask">
          <p>
            <strong>Your college is waiting on your answer.</strong> Saying yes tells them to put
            you forward to {placement.companyName}. Saying no closes this one for you, and cannot be
            taken back here.
          </p>
          <div className="row wrap gap-2">
            {actions.map((action) => (
              <Button
                key={action.stage}
                variant={action.stage === 'INTERESTED' ? 'primary' : 'secondary'}
                disabled={respond.isPending}
                onClick={() => {
                  // Declining cannot be undone, so it is confirmed. Accepting
                  // still leaves the college a decision, so it is not.
                  if (action.terminal) {
                    setConfirming(action);
                  } else {
                    void answer(action, '');
                  }
                }}
              >
                {action.label}
              </Button>
            ))}
          </div>
        </div>
      )}

      {placement.awaitingYou && placement.requirementStatus !== 'OPEN' && (
        <p className="text-muted">
          This drive has closed, so your answer is no longer being collected. Speak to your
          placement office if you think that is wrong.
        </p>
      )}

      <footer className="placement__foot">
        <span className="text-faint">
          {placement.stageChangedAt
            ? `Last change ${relativeTime(placement.stageChangedAt)}`
            : placement.shortlistedAt
              ? `Put forward ${relativeTime(placement.shortlistedAt)}`
              : ''}
        </span>
        <button
          type="button"
          className="stage__history-toggle"
          onClick={() => setHistoryOpen((open) => !open)}
          aria-expanded={historyOpen}
        >
          {historyOpen ? 'Hide history' : 'History'}
        </button>
      </footer>

      {historyOpen && <MyHistory requirementId={placement.requirementId} />}

      <Dialog
        open={confirming !== null}
        onClose={() => {
          setConfirming(null);
          setNote('');
        }}
        title={`Decline ${placement.roleTitle}?`}
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => {
                setConfirming(null);
                setNote('');
              }}
            >
              Keep it open
            </Button>
            <Button
              variant="primary"
              disabled={respond.isPending}
              onClick={() => confirming && void answer(confirming, note)}
            >
              {respond.isPending ? 'Sending…' : 'Yes, decline'}
            </Button>
          </>
        }
      >
        <p className="text-muted">
          Your college will see that you decided not to go ahead with {placement.companyName}. This
          cannot be undone here.
        </p>
        <Field label="Anything you want them to know?" hint="Optional.">
          {({ id }) => (
            <TextArea
              id={id}
              value={note}
              maxLength={1000}
              rows={3}
              onChange={(event) => setNote(event.target.value)}
              placeholder="A reason, if you would like to give one"
            />
          )}
        </Field>
      </Dialog>
    </article>
  );
}

function MyHistory({ requirementId }: { requirementId: string }) {
  const history = useMyPlacementHistory(requirementId);

  if (history.isLoading) return <span className="skeleton skeleton--line" />;
  if (history.isError) return <p className="text-muted">That history could not be loaded.</p>;

  const changes = history.data ?? [];
  if (changes.length === 0) {
    return <p className="text-faint stage__empty">Nothing has happened since you were put forward.</p>;
  }

  return (
    <ol className="stage__history">
      {changes.map((change) => (
        <li key={change.id} className={`stage__event stage__event--${change.actorKind.toLowerCase()}`}>
          <span className="stage__event-line">{historyLine(change)}</span>
          <time
            className="text-faint"
            dateTime={change.occurredAt}
            title={formatDateTime(change.occurredAt) ?? ''}
          >
            {relativeTime(change.occurredAt)}
          </time>
          {change.note && <p className="stage__event-note">“{change.note}”</p>}
        </li>
      ))}
    </ol>
  );
}

export default function MyPlacements() {
  const placements = useMyPlacements();
  const data = placements.data ?? [];
  const ordered = sortPlacements(data);

  return (
    <>
      <PageHeader
        title="Placements"
        subtitle={placements.data ? placementSummary(data) : 'Loading…'}
      />

      {placements.isLoading && (
        <Panel title="Loading your placements">
          <span className="skeleton skeleton--line" />
        </Panel>
      )}

      {placements.isError && (
        <Panel title="Placements unavailable">
          <p className="text-muted">Your placements could not be loaded just now.</p>
        </Panel>
      )}

      {placements.data && ordered.length === 0 && (
        <EmptyState
          title="Nothing yet"
          body="When your placement office puts you forward for a role, it appears here — along with anything they need you to answer."
        />
      )}

      {ordered.length > 0 && (
        <div className="placement__list">
          {ordered.map((placement) => (
            <PlacementCard key={placement.requirementId} placement={placement} />
          ))}
        </div>
      )}

      {ordered.length > 0 && (
        <p className="text-faint discovery__footnote">
          Your placement office decides who is put forward and who is selected. CareerFlux does not
          make either decision, and does not answer on your behalf — only you can say whether you
          want to go ahead.
        </p>
      )}
    </>
  );
}
