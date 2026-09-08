import { useMemo, useState } from 'react';

import { Badge, Button, Panel, useToast } from '../ui/primitives';
import { ApiError } from '../../lib/api';
import { useApproveProposal, useRejectProposal } from '../../lib/queries';
import type {
  Decision,
  DecisionAction,
  ProposalItemState,
  ProposalSection,
  ProposalView,
  ProposedItem,
} from '../../lib/types';

/**
 * The review screen: what a resume reading would change, and the student's answer.
 *
 * <p>Nothing here is pre-selected. A student who presses Apply without touching
 * anything gets a profile that is exactly as they left it, which is the correct
 * outcome for somebody who did not read the list — the alternative, defaulting
 * to accept, would make the whole workflow decorative.
 */

const SECTION_TITLES: Record<ProposalSection, string> = {
  PERSONAL: 'Personal information',
  PROFESSIONAL: 'Professional details',
  LINKS: 'Links',
  SKILLS: 'Skills',
  EXPERIENCE: 'Experience',
  EDUCATION: 'Education',
  ACADEMIC: 'Academic record',
};

const SECTION_ORDER: ProposalSection[] = [
  'PERSONAL',
  'PROFESSIONAL',
  'LINKS',
  'SKILLS',
  'EXPERIENCE',
  'EDUCATION',
  'ACADEMIC',
];

const STATE_LABELS: Record<ProposalItemState, string> = {
  NEW: '+ New',
  UNCHANGED: '✓ Already matches',
  CONFLICT: 'Differs from your profile',
  MISSING: 'Not found in resume',
  UNCERTAIN: '⚠ Needs review',
  INFORMATION_FOUND: 'For information only',
};

const STATE_TONES: Record<ProposalItemState, 'neutral' | 'positive' | 'caution' | 'accent'> = {
  NEW: 'positive',
  UNCHANGED: 'neutral',
  CONFLICT: 'caution',
  MISSING: 'neutral',
  UNCERTAIN: 'caution',
  INFORMATION_FOUND: 'accent',
};

/** What the student has said about one item so far. Absent means "not accepted". */
type Answer = { action: Exclude<DecisionAction, 'REJECT'>; value?: string };

export function ProposalReviewPanel({ proposal }: { proposal: ProposalView }) {
  const toast = useToast();
  const approve = useApproveProposal(proposal.id);
  const reject = useRejectProposal(proposal.id);
  const [answers, setAnswers] = useState<Record<string, Answer>>({});
  const [editing, setEditing] = useState<Record<string, string>>({});

  const decidable = useMemo(
    () => proposal.items.filter((item) => item.decidable),
    [proposal.items],
  );
  const acceptedCount = Object.keys(answers).length;

  const grouped = useMemo(() => {
    const map = new Map<ProposalSection, ProposedItem[]>();
    for (const item of proposal.items) {
      const bucket = map.get(item.section);
      if (bucket) {
        bucket.push(item);
      } else {
        map.set(item.section, [item]);
      }
    }
    return SECTION_ORDER.filter((section) => map.has(section)).map(
      (section) => [section, map.get(section) as ProposedItem[]] as const,
    );
  }, [proposal.items]);

  const setAnswer = (key: string, answer: Answer | null) => {
    setAnswers((current) => {
      const next = { ...current };
      if (answer) {
        next[key] = answer;
      } else {
        delete next[key];
      }
      return next;
    });
  };

  const apply = () => {
    const decisions: Decision[] = Object.entries(answers).map(([key, answer]) => ({
      key,
      action: answer.action,
      value: answer.action === 'EDIT' ? (answer.value ?? '') : null,
    }));
    approve.mutate(decisions, {
      onSuccess: (result) =>
        toast.show(
          result.applied.length > 0
            ? `Profile updated successfully. ${result.applied.length} ${
                result.applied.length === 1 ? 'change was' : 'changes were'
              } saved.`
            : 'Nothing was accepted, so your profile is unchanged.',
          'success',
        ),
      onError: (caught) =>
        toast.show(
          caught instanceof ApiError ? caught.message : 'Those changes could not be saved.',
          'error',
        ),
    });
  };

  const discard = () => {
    reject.mutate(undefined, {
      onSuccess: () =>
        toast.show('Proposal rejected. Your existing profile was not changed.', 'success'),
      onError: (caught) =>
        toast.show(
          caught instanceof ApiError ? caught.message : 'That could not be completed.',
          'error',
        ),
    });
  };

  return (
    <Panel
      title="CareerFlux found information that may improve your profile"
      action={
        <Badge tone={proposal.aiAssisted ? 'accent' : 'neutral'} title={proposal.engine}>
          {proposal.aiAssisted ? `Read by ${proposal.engine}` : 'Read by the built-in parser'}
        </Badge>
      }
    >
      <div className="grid" style={{ gap: 'var(--space-4)' }}>
        <p className="text-muted">
          Nothing below has been saved. Choose what you want to keep — anything you leave alone
          stays exactly as it is now.
        </p>

        {grouped.map(([section, items]) => (
          <section key={section} className="grid" style={{ gap: 'var(--space-2)' }}>
            <p className="eyebrow">{SECTION_TITLES[section]}</p>
            {items.map((item) => (
              <ProposalRow
                key={item.key}
                item={item}
                answer={answers[item.key]}
                draft={editing[item.key]}
                onAccept={() => setAnswer(item.key, { action: 'ACCEPT' })}
                onClear={() => setAnswer(item.key, null)}
                onEditStart={() =>
                  setEditing((current) => ({
                    ...current,
                    [item.key]: item.proposedValue ?? '',
                  }))
                }
                onEditChange={(value) =>
                  setEditing((current) => ({ ...current, [item.key]: value }))
                }
                onEditSave={(value) => setAnswer(item.key, { action: 'EDIT', value })}
                onEditCancel={() =>
                  setEditing((current) => {
                    const next = { ...current };
                    delete next[item.key];
                    return next;
                  })
                }
              />
            ))}
          </section>
        ))}

        <div className="row between wrap gap-3" style={{ paddingTop: 'var(--space-2)' }}>
          <span className="text-muted">
            {acceptedCount === 0
              ? `Nothing selected of ${decidable.length} suggested ${
                  decidable.length === 1 ? 'change' : 'changes'
                }.`
              : `${acceptedCount} of ${decidable.length} selected.`}
          </span>
          <div className="row gap-2">
            <Button variant="ghost" loading={reject.isPending} onClick={discard}>
              Reject all
            </Button>
            <Button variant="primary" loading={approve.isPending} onClick={apply}>
              {acceptedCount === 0 ? 'Keep my profile as it is' : `Apply ${acceptedCount} change${
                acceptedCount === 1 ? '' : 's'
              }`}
            </Button>
          </div>
        </div>
      </div>
    </Panel>
  );
}

function ProposalRow({
  item,
  answer,
  draft,
  onAccept,
  onClear,
  onEditStart,
  onEditChange,
  onEditSave,
  onEditCancel,
}: {
  item: ProposedItem;
  answer: Answer | undefined;
  draft: string | undefined;
  onAccept: () => void;
  onClear: () => void;
  onEditStart: () => void;
  onEditChange: (value: string) => void;
  onEditSave: (value: string) => void;
  onEditCancel: () => void;
}) {
  const chosen = answer !== undefined;
  const isEditing = draft !== undefined;

  return (
    <div
      className="panel panel--raised"
      style={{ padding: 'var(--space-3)', display: 'grid', gap: 'var(--space-2)' }}
    >
      <div className="row between wrap gap-2">
        <strong>{item.label}</strong>
        <Badge tone={STATE_TONES[item.state]}>{STATE_LABELS[item.state]}</Badge>
      </div>

      {/* Both values, side by side, with neither presented as the correct one. */}
      {(item.currentValue !== null || item.state === 'CONFLICT') && (
        <ValueLine caption="On your profile now" value={item.currentValue} />
      )}
      <ValueLine caption="On your resume" value={item.proposedValue} />

      {item.note && <p className="text-muted">{item.note}</p>}

      {item.decidable && (
        <div className="row gap-2 wrap">
          {isEditing ? (
            <>
              <input
                className="input"
                value={draft}
                aria-label={`New value for ${item.label}`}
                onChange={(event) => onEditChange(event.target.value)}
              />
              <Button
                size="sm"
                variant="primary"
                disabled={draft.trim().length === 0}
                onClick={() => {
                  onEditSave(draft.trim());
                  onEditCancel();
                }}
              >
                Use this
              </Button>
              <Button size="sm" variant="ghost" onClick={onEditCancel}>
                Cancel
              </Button>
            </>
          ) : (
            <>
              <Button
                size="sm"
                variant={answer?.action === 'ACCEPT' ? 'primary' : 'secondary'}
                onClick={answer?.action === 'ACCEPT' ? onClear : onAccept}
              >
                {answer?.action === 'ACCEPT' ? 'Accepted' : 'Use resume value'}
              </Button>
              {item.editable && (
                <Button size="sm" variant="ghost" onClick={onEditStart}>
                  {answer?.action === 'EDIT' ? `Edit (using "${answer.value}")` : 'Edit'}
                </Button>
              )}
              {chosen && (
                <Button size="sm" variant="ghost" onClick={onClear}>
                  {item.state === 'CONFLICT' ? 'Keep what I have' : 'Undo'}
                </Button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function ValueLine({ caption, value }: { caption: string; value: string | null }) {
  return (
    <div className="row gap-2 wrap">
      <span className="eyebrow" style={{ minWidth: '11rem' }}>
        {caption}
      </span>
      <span className={value ? undefined : 'text-muted'}>{value ?? 'Not provided'}</span>
    </div>
  );
}
