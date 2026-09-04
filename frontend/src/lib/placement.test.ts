import { describe, expect, it } from 'vitest';

import {
  PLACEMENT_STAGES,
  awaitsStudent,
  historyLine,
  isTerminal,
  placementSummary,
  sortPlacements,
  staffActions,
  staffIsWaiting,
  stageDescription,
  stageLabel,
  stageTone,
  studentActions,
  studentResponseFor,
  studentStageLabel,
} from './placement';
import type { PlacementStage } from './types';

/**
 * The workflow as the screens read it.
 *
 * <p>The important test here is the first one. The frontend keeps its own copy
 * of the transition matrix so it can decide which buttons to draw, and a copy
 * is a thing that drifts. This pins it to the server's matrix as written in
 * PlacementStage.java, so a change on one side that is not made on the other
 * fails here rather than becoming a button that produces an error.
 */
describe('the offered moves match the server', () => {
  /** Transcribed from PlacementStage.java. Update both together or not at all. */
  const SERVER_STAFF_MOVES: Record<PlacementStage, PlacementStage[]> = {
    SHORTLISTED: ['INVITED', 'NOT_PROCEEDING'],
    INVITED: ['NOT_PROCEEDING'],
    INTERESTED: ['SELECTED', 'NOT_PROCEEDING'],
    SELECTED: [],
    NOT_PROCEEDING: [],
    DECLINED: [],
  };

  const SERVER_STUDENT_MOVES: Record<PlacementStage, PlacementStage[]> = {
    SHORTLISTED: [],
    INVITED: ['INTERESTED', 'DECLINED'],
    INTERESTED: [],
    SELECTED: [],
    NOT_PROCEEDING: [],
    DECLINED: [],
  };

  it.each(PLACEMENT_STAGES)('offers staff exactly the server moves from %s', (stage) => {
    expect(staffActions(stage, 'OPEN').map((a) => a.stage).sort()).toEqual(
      [...SERVER_STAFF_MOVES[stage]].sort(),
    );
  });

  it.each(PLACEMENT_STAGES)('offers a student exactly the server moves from %s', (stage) => {
    expect(studentActions(stage, 'OPEN').map((a) => a.stage).sort()).toEqual(
      [...SERVER_STUDENT_MOVES[stage]].sort(),
    );
  });

  it('never offers staff the student\'s answer, or the reverse', () => {
    // The two moves the whole design exists to keep apart.
    for (const stage of PLACEMENT_STAGES) {
      const staff = staffActions(stage, 'OPEN').map((a) => a.stage);
      expect(staff).not.toContain('INTERESTED');
      expect(staff).not.toContain('DECLINED');
      const student = studentActions(stage, 'OPEN').map((a) => a.stage);
      expect(student).not.toContain('SELECTED');
      expect(student).not.toContain('INVITED');
      expect(student).not.toContain('NOT_PROCEEDING');
    }
  });

  it('offers nothing at all once a stage is terminal', () => {
    for (const stage of ['SELECTED', 'NOT_PROCEEDING', 'DECLINED'] as PlacementStage[]) {
      expect(isTerminal(stage)).toBe(true);
      expect(staffActions(stage, 'OPEN')).toEqual([]);
      expect(studentActions(stage, 'OPEN')).toEqual([]);
    }
  });

  it('offers nothing once the drive is no longer open', () => {
    // The server freezes a closed requirement; a button here would only earn a
    // refusal.
    for (const status of ['DRAFT', 'CLOSED', 'ARCHIVED']) {
      expect(staffActions('SHORTLISTED', status)).toEqual([]);
      expect(studentActions('INVITED', status)).toEqual([]);
    }
  });

  it('never offers a move back to where the candidate already is', () => {
    for (const stage of PLACEMENT_STAGES) {
      expect(staffActions(stage, 'OPEN').map((a) => a.stage)).not.toContain(stage);
      expect(studentActions(stage, 'OPEN').map((a) => a.stage)).not.toContain(stage);
    }
  });
});

describe('what the stages are called', () => {
  it('gives every stage a label, a student label, a description and a tone', () => {
    for (const stage of PLACEMENT_STAGES) {
      expect(stageLabel(stage)).toBeTruthy();
      expect(stageLabel(stage)).not.toContain('_');
      expect(studentStageLabel(stage)).toBeTruthy();
      expect(stageDescription(stage)).toMatch(/\.$/);
      expect(stageTone(stage)).toBeTruthy();
    }
  });

  it('speaks to a student about themselves, not about a record', () => {
    expect(studentStageLabel('DECLINED')).toBe('You declined');
    expect(studentStageLabel('INTERESTED')).toBe('You said yes');
    // The staff word for it is accurate and cold; the student sees softer.
    expect(studentStageLabel('NOT_PROCEEDING')).not.toBe(stageLabel('NOT_PROCEEDING'));
  });

  it('distinguishes the college declining from the student declining', () => {
    expect(stageLabel('NOT_PROCEEDING')).not.toBe(stageLabel('DECLINED'));
    expect(stageTone('SELECTED')).toBe('positive');
  });

  it('marks a terminal action so it can be confirmed before it is taken', () => {
    const actions = staffActions('INTERESTED', 'OPEN');
    expect(actions.find((a) => a.stage === 'SELECTED')?.terminal).toBe(true);
    expect(staffActions('SHORTLISTED', 'OPEN').find((a) => a.stage === 'INVITED')?.terminal).toBe(
      false,
    );
  });
});

describe('who is waiting on whom', () => {
  it('says the student is being waited on only at INVITED', () => {
    for (const stage of PLACEMENT_STAGES) {
      expect(awaitsStudent(stage)).toBe(stage === 'INVITED');
      expect(staffIsWaiting(stage)).toBe(stage === 'INVITED');
    }
  });
});

describe('a student\'s list', () => {
  const placement = (
    stage: PlacementStage,
    stageChangedAt: string | null = null,
    shortlistedAt: string | null = '2026-01-01T00:00:00Z',
  ) => ({ stage, stageChangedAt, shortlistedAt });

  it('puts what is waiting on them first and what is finished last', () => {
    const sorted = sortPlacements([
      placement('SELECTED', '2026-03-01T00:00:00Z'),
      placement('SHORTLISTED'),
      placement('INVITED', '2026-02-01T00:00:00Z'),
    ]);
    expect(sorted.map((p) => p.stage)).toEqual(['INVITED', 'SHORTLISTED', 'SELECTED']);
  });

  it('breaks ties by most recent movement', () => {
    const sorted = sortPlacements([
      placement('INVITED', '2026-01-05T00:00:00Z'),
      placement('INVITED', '2026-06-05T00:00:00Z'),
    ]);
    expect(sorted[0].stageChangedAt).toBe('2026-06-05T00:00:00Z');
  });

  it('falls back to when they were shortlisted if nothing has moved', () => {
    const sorted = sortPlacements([
      placement('SHORTLISTED', null, '2026-01-01T00:00:00Z'),
      placement('SHORTLISTED', null, '2026-05-01T00:00:00Z'),
    ]);
    expect(sorted[0].shortlistedAt).toBe('2026-05-01T00:00:00Z');
  });

  it('does not modify the array it was given', () => {
    const original = [placement('SELECTED'), placement('INVITED')];
    sortPlacements(original);
    expect(original.map((p) => p.stage)).toEqual(['SELECTED', 'INVITED']);
  });

  it('survives a missing or unparseable timestamp', () => {
    expect(() => sortPlacements([placement('INVITED', 'not a date', null)])).not.toThrow();
  });

  it('summarises by what needs doing, not by how many there are', () => {
    expect(placementSummary([])).toContain('not been put forward');
    expect(placementSummary([{ stage: 'INVITED' }, { stage: 'SHORTLISTED' }])).toBe(
      '1 waiting on you · 1 in progress',
    );
    expect(placementSummary([{ stage: 'SELECTED' }])).toBe('1 selected');
    expect(placementSummary([{ stage: 'DECLINED' }, { stage: 'NOT_PROCEEDING' }])).toBe('2 closed');
  });
});

describe('the wire value a student sends', () => {
  it('translates only the two answers they may give', () => {
    expect(studentResponseFor('INTERESTED')).toBe('interested');
    expect(studentResponseFor('DECLINED')).toBe('declined');
    for (const stage of ['SHORTLISTED', 'INVITED', 'SELECTED', 'NOT_PROCEEDING'] as PlacementStage[]) {
      expect(studentResponseFor(stage)).toBeNull();
    }
  });
});

describe('the history, in words', () => {
  it('names who did it and what they did', () => {
    expect(
      historyLine({
        fromStage: 'SHORTLISTED',
        toStage: 'INVITED',
        actorKind: 'STAFF',
        actorLabel: 'Priya Nair',
      }),
    ).toBe('Priya Nair invited them to apply');
    expect(
      historyLine({
        fromStage: 'INVITED',
        toStage: 'DECLINED',
        actorKind: 'STUDENT',
        actorLabel: 'Arjun Rao',
      }),
    ).toBe('Arjun Rao declined');
  });

  it('still reads once the account behind it is gone', () => {
    // actorLabel and actorUserId both go null when a user is deleted; the line
    // must not become "null invited them to apply".
    expect(
      historyLine({ fromStage: 'SHORTLISTED', toStage: 'INVITED', actorKind: 'STAFF', actorLabel: null }),
    ).toBe('A staff member invited them to apply');
    expect(
      historyLine({ fromStage: 'INVITED', toStage: 'INTERESTED', actorKind: 'STUDENT', actorLabel: null }),
    ).toBe('The student said they are interested');
  });

  it('has a line for every stage that can be moved to', () => {
    for (const stage of PLACEMENT_STAGES) {
      const line = historyLine({
        fromStage: null,
        toStage: stage,
        actorKind: 'STAFF',
        actorLabel: 'Someone',
      });
      expect(line.startsWith('Someone ')).toBe(true);
      expect(line).not.toContain('_');
    }
  });
});
