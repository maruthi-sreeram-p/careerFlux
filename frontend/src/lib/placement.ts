import type { PlacementStage } from './types';

/**
 * What a screen needs to know about the placement workflow.
 *
 * <p>Two audiences read the same six stages very differently. A placement
 * officer wants to know what they can do next; a student wants to know whether
 * anything is waiting on them and what the college has said. Both readings live
 * here so neither is worked out inline in a component, where it would drift.
 *
 * <p><b>This mirrors the server's rules; it does not enforce them.</b> The
 * transition matrix is decided in {@code PlacementStage.java} and checked in
 * {@code PlacementWorkflowService} against the stage as stored. Everything below
 * exists so the interface offers moves that will succeed rather than buttons
 * that produce an error — hiding a control is a courtesy, never a permission
 * check. If the two ever disagree the server wins and the user sees a refusal,
 * which is the correct failure: the wrong direction would be a screen that
 * permits something the server would have refused.
 */

export const PLACEMENT_STAGES: PlacementStage[] = [
  'SHORTLISTED',
  'INVITED',
  'INTERESTED',
  'SELECTED',
  'NOT_PROCEEDING',
  'DECLINED',
];

const TERMINAL: PlacementStage[] = ['SELECTED', 'NOT_PROCEEDING', 'DECLINED'];

const LABELS: Record<PlacementStage, string> = {
  SHORTLISTED: 'Shortlisted',
  INVITED: 'Invited',
  INTERESTED: 'Interested',
  SELECTED: 'Selected',
  NOT_PROCEEDING: 'Not proceeding',
  DECLINED: 'Declined',
};

/**
 * What each stage means to the student it is about.
 *
 * <p>Deliberately not the staff label. "Not proceeding" is an accurate word for
 * a spreadsheet and a cold one to read about yourself, and a student should be
 * told plainly what happened without having to decode placement vocabulary.
 */
const STUDENT_LABELS: Record<PlacementStage, string> = {
  SHORTLISTED: 'Put forward',
  INVITED: 'Invited to apply',
  INTERESTED: 'You said yes',
  SELECTED: 'Selected',
  NOT_PROCEEDING: 'Not moving ahead',
  DECLINED: 'You declined',
};

const DESCRIPTIONS: Record<PlacementStage, string> = {
  SHORTLISTED: 'Your college has put you forward for this role.',
  INVITED: 'Your college is asking whether you want to go ahead.',
  INTERESTED: 'You told your college you want to go ahead.',
  SELECTED: 'You have been selected for this role.',
  NOT_PROCEEDING: 'Your college is not taking this one further.',
  DECLINED: 'You told your college you did not want to go ahead.',
};

export type StageTone = 'neutral' | 'accent' | 'positive' | 'caution' | 'negative' | 'info';

const TONES: Record<PlacementStage, StageTone> = {
  SHORTLISTED: 'neutral',
  INVITED: 'caution',
  INTERESTED: 'info',
  SELECTED: 'positive',
  NOT_PROCEEDING: 'negative',
  DECLINED: 'negative',
};

/** The moves the college may make, mirroring the server's staff matrix. */
const STAFF_MOVES: Record<PlacementStage, PlacementStage[]> = {
  SHORTLISTED: ['INVITED', 'NOT_PROCEEDING'],
  INVITED: ['NOT_PROCEEDING'],
  INTERESTED: ['SELECTED', 'NOT_PROCEEDING'],
  SELECTED: [],
  NOT_PROCEEDING: [],
  DECLINED: [],
};

/** What each staff move is called on a button, phrased as the act itself. */
const ACTION_LABELS: Partial<Record<PlacementStage, string>> = {
  INVITED: 'Invite to apply',
  SELECTED: 'Mark selected',
  NOT_PROCEEDING: 'Not proceeding',
};

export interface StageAction {
  stage: PlacementStage;
  label: string;
  /** Whether this action ends the candidate's involvement, so it can be confirmed. */
  terminal: boolean;
}

export function stageLabel(stage: PlacementStage): string {
  return LABELS[stage] ?? stage;
}

export function studentStageLabel(stage: PlacementStage): string {
  return STUDENT_LABELS[stage] ?? LABELS[stage] ?? stage;
}

export function stageDescription(stage: PlacementStage): string {
  return DESCRIPTIONS[stage] ?? '';
}

export function stageTone(stage: PlacementStage): StageTone {
  return TONES[stage] ?? 'neutral';
}

export function isTerminal(stage: PlacementStage): boolean {
  return TERMINAL.includes(stage);
}

/** True only at INVITED: the one stage where the college is waiting on an answer. */
export function awaitsStudent(stage: PlacementStage): boolean {
  return stage === 'INVITED';
}

/**
 * The moves to offer the college for a candidate at this stage.
 *
 * <p>Empty once the drive is closed. A closed requirement is frozen on the
 * server, and offering a button that will be refused is worse than offering
 * none.
 */
export function staffActions(
  stage: PlacementStage,
  requirementStatus: string,
): StageAction[] {
  if (requirementStatus !== 'OPEN') return [];
  return (STAFF_MOVES[stage] ?? []).map((target) => ({
    stage: target,
    label: ACTION_LABELS[target] ?? stageLabel(target),
    terminal: isTerminal(target),
  }));
}

/**
 * Whether the college has anything left to decide.
 *
 * <p>Note that INVITED returns false even though NOT_PROCEEDING is technically
 * available: the college has asked and is waiting, and a screen that says
 * "waiting on the student" alongside "your move" would be lying about which.
 */
export function staffIsWaiting(stage: PlacementStage): boolean {
  return stage === 'INVITED';
}

/** The two answers a student may give, and only from an invitation. */
export function studentActions(
  stage: PlacementStage,
  requirementStatus: string,
): StageAction[] {
  if (requirementStatus !== 'OPEN' || stage !== 'INVITED') return [];
  return [
    { stage: 'INTERESTED', label: "Yes, I'm interested", terminal: false },
    { stage: 'DECLINED', label: 'No, thank you', terminal: true },
  ];
}

/** The wire value the student endpoint accepts, which is not the stage name. */
export function studentResponseFor(stage: PlacementStage): 'interested' | 'declined' | null {
  if (stage === 'INTERESTED') return 'interested';
  if (stage === 'DECLINED') return 'declined';
  return null;
}

/**
 * Sorts a student's placements so the ones needing them come first.
 *
 * <p>Invitations, then everything still live, then everything finished; ties
 * broken by most recent movement. A student opening this page should not have
 * to hunt for the one thing that is actually waiting on them.
 */
export function sortPlacements<T extends { stage: PlacementStage; stageChangedAt: string | null; shortlistedAt: string | null }>(
  placements: T[],
): T[] {
  const rank = (p: T) => (awaitsStudent(p.stage) ? 0 : isTerminal(p.stage) ? 2 : 1);
  const at = (p: T) => Date.parse(p.stageChangedAt ?? p.shortlistedAt ?? '') || 0;
  return [...placements].sort((a, b) => rank(a) - rank(b) || at(b) - at(a));
}

/**
 * A one-line count for the top of the student's page.
 *
 * <p>Says the number that matters rather than the total, because "3 placements"
 * tells a student nothing they need and "1 waiting on you" tells them what to
 * do next.
 */
export function placementSummary(placements: { stage: PlacementStage }[]): string {
  if (placements.length === 0) return 'You have not been put forward for any roles yet.';
  const waiting = placements.filter((p) => awaitsStudent(p.stage)).length;
  const selected = placements.filter((p) => p.stage === 'SELECTED').length;
  const parts: string[] = [];
  if (waiting > 0) parts.push(`${waiting} waiting on you`);
  if (selected > 0) parts.push(`${selected} selected`);
  const live = placements.filter((p) => !isTerminal(p.stage) && !awaitsStudent(p.stage)).length;
  if (live > 0) parts.push(`${live} in progress`);
  if (parts.length === 0) return `${placements.length} closed`;
  return parts.join(' · ');
}

/** How a history line reads, given who moved it. */
export function historyLine(change: {
  fromStage: string | null;
  toStage: PlacementStage;
  actorKind: 'STAFF' | 'STUDENT';
  actorLabel: string | null;
}): string {
  const who = change.actorLabel ?? (change.actorKind === 'STUDENT' ? 'The student' : 'A staff member');
  switch (change.toStage) {
    case 'INVITED':
      return `${who} invited them to apply`;
    case 'INTERESTED':
      return `${who} said they are interested`;
    case 'DECLINED':
      return `${who} declined`;
    case 'SELECTED':
      return `${who} marked them selected`;
    case 'NOT_PROCEEDING':
      return `${who} decided not to proceed`;
    default:
      return `${who} moved them to ${stageLabel(change.toStage).toLowerCase()}`;
  }
}
