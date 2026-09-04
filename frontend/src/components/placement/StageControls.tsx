import { useState } from 'react';

import { Badge, Button, Dialog, Field, TextArea, useToast } from '../ui/primitives';
import { formatDateTime, relativeTime } from '../../lib/format';
import {
  awaitsStudent,
  historyLine,
  isTerminal,
  staffActions,
  stageLabel,
  stageTone,
  type StageAction,
} from '../../lib/placement';
import { useChangeStage, useStageHistory } from '../../lib/queries';
import type { PlacementStage } from '../../lib/types';

/**
 * Where a shortlisted candidate has got to, and what the college can do next.
 *
 * <p>The screen offers only moves the server would accept, which means it
 * offers no way to record a student's answer for them. That is not a styling
 * decision: if a placement officer can mark somebody interested, the invitation
 * step becomes paperwork and the record stops meaning what it says. The buttons
 * that are missing here are missing on purpose.
 *
 * <p>Terminal moves are confirmed, because they cannot be undone — there is no
 * transition out of SELECTED, NOT_PROCEEDING or DECLINED, by design.
 */

export function StageBadge({ stage }: { stage: PlacementStage }) {
  return <Badge tone={stageTone(stage)}>{stageLabel(stage)}</Badge>;
}

export function StageControls({
  requirementId,
  candidateId,
  candidateName,
  stage,
  requirementStatus,
  editable,
}: {
  requirementId: string;
  candidateId: string;
  candidateName: string;
  stage: PlacementStage;
  requirementStatus: string;
  editable: boolean;
}) {
  const toast = useToast();
  const change = useChangeStage();
  const [confirming, setConfirming] = useState<StageAction | null>(null);
  const [note, setNote] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);

  const actions = editable ? staffActions(stage, requirementStatus) : [];

  const move = async (action: StageAction, withNote: string) => {
    try {
      await change.mutateAsync({
        requirementId,
        candidateId,
        stage: action.stage,
        note: withNote.trim() || undefined,
      });
      toast.show(`${candidateName} — ${stageLabel(action.stage).toLowerCase()}.`, 'success');
      setConfirming(null);
      setNote('');
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'That change could not be recorded.',
        'error',
      );
    }
  };

  return (
    <div className="stage">
      <div className="stage__row">
        <StageBadge stage={stage} />
        {awaitsStudent(stage) && (
          <span className="stage__waiting">Waiting on the student to answer</span>
        )}
        {isTerminal(stage) && <span className="text-faint">Closed</span>}
        <button
          type="button"
          className="stage__history-toggle"
          onClick={() => setHistoryOpen((open) => !open)}
          aria-expanded={historyOpen}
        >
          {historyOpen ? 'Hide history' : 'History'}
        </button>
      </div>

      {actions.length > 0 && (
        <div className="row wrap gap-2">
          {actions.map((action) => (
            <Button
              key={action.stage}
              variant={action.stage === 'SELECTED' ? 'primary' : 'secondary'}
              disabled={change.isPending}
              onClick={() => {
                // Anything that ends the candidate's involvement is confirmed;
                // an invitation is not, since it can still be stopped later.
                if (action.terminal) {
                  setConfirming(action);
                } else {
                  void move(action, '');
                }
              }}
            >
              {action.label}
            </Button>
          ))}
        </div>
      )}

      {historyOpen && <StageHistory requirementId={requirementId} candidateId={candidateId} />}

      <Dialog
        open={confirming !== null}
        onClose={() => {
          setConfirming(null);
          setNote('');
        }}
        title={confirming ? `${confirming.label}: ${candidateName}` : ''}
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => {
                setConfirming(null);
                setNote('');
              }}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              disabled={change.isPending}
              onClick={() => confirming && void move(confirming, note)}
            >
              {change.isPending ? 'Recording…' : 'Record it'}
            </Button>
          </>
        }
      >
        <p className="text-muted">
          {confirming?.stage === 'SELECTED'
            ? `This records that ${candidateName} has been selected. It is the end of the workflow for them and cannot be undone here.`
            : `This closes ${candidateName}'s involvement in this drive. It cannot be undone here.`}
        </p>
        <Field label="Note" hint="Optional. Kept with the record so the reason is not lost.">
          {({ id }) => (
            <TextArea
              id={id}
              value={note}
              maxLength={1000}
              rows={3}
              onChange={(event) => setNote(event.target.value)}
              placeholder="Why, in your own words"
            />
          )}
        </Field>
      </Dialog>
    </div>
  );
}

/** The append-only trail: who moved this candidate, when, and why. */
export function StageHistory({
  requirementId,
  candidateId,
}: {
  requirementId: string;
  candidateId: string;
}) {
  const history = useStageHistory(requirementId, candidateId);

  if (history.isLoading) {
    return <span className="skeleton skeleton--line" />;
  }
  if (history.isError) {
    return <p className="text-muted">That history could not be loaded.</p>;
  }
  const changes = history.data ?? [];
  if (changes.length === 0) {
    return (
      <p className="text-faint stage__empty">
        Shortlisted, and nothing has happened since.
      </p>
    );
  }

  return (
    <ol className="stage__history">
      {changes.map((change) => (
        <li key={change.id} className={`stage__event stage__event--${change.actorKind.toLowerCase()}`}>
          <span className="stage__event-line">{historyLine(change)}</span>
          <time className="text-faint" dateTime={change.occurredAt} title={formatDateTime(change.occurredAt) ?? ''}>
            {relativeTime(change.occurredAt)}
          </time>
          {change.note && <p className="stage__event-note">“{change.note}”</p>}
        </li>
      ))}
    </ol>
  );
}
