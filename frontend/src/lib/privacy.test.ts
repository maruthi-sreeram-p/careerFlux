import { describe, expect, it } from 'vitest';

import { aiProcessingStatus, erasureSummary, exportFileName, purposeState } from './privacy';
import type { ConsentOverview, ErasureView, PurposeState } from './types';

const notice = {
  id: 'n1',
  purpose: 'AI_PROCESSING' as const,
  version: 'engineering-placeholder-1',
  effectiveFrom: '2026-09-01T00:00:00Z',
  checksum: 'a'.repeat(64),
  placeholder: true,
  body: 'text',
};

function state(overrides: Partial<PurposeState>): PurposeState {
  return { purpose: 'AI_PROCESSING', currentNotice: notice, active: false, reconsentRequired: false, latest: null, ...overrides };
}

function request(overrides: Partial<ErasureView>): ErasureView {
  return {
    id: 'e1',
    status: 'GRACE_PERIOD',
    requestedByRole: 'STUDENT',
    requestedAt: '2026-09-01T00:00:00Z',
    graceEndsAt: '2026-10-01T00:00:00Z',
    cancellable: true,
    cancelledAt: null,
    completedAt: null,
    ...overrides,
  };
}

describe('AI processing status', () => {
  it('is off when nothing has been agreed', () => {
    expect(aiProcessingStatus(undefined).on).toBe(false);
    expect(aiProcessingStatus(state({})).detail).toMatch(/nothing is sent to an AI provider/i);
  });

  it('is on only when the server says consent is in force', () => {
    const status = aiProcessingStatus(state({ active: true }));
    expect(status.on).toBe(true);
    expect(status.detail).toMatch(/name, contact details and links removed/i);
  });

  it('asks for the new notice when the old one was agreed to', () => {
    const status = aiProcessingStatus(state({ reconsentRequired: true }));
    expect(status.on).toBe(false);
    expect(status.detail).toMatch(/notice has changed/i);
  });

  it('finds a purpose in the overview', () => {
    const overview: ConsentOverview = { purposes: [state({ active: true })], history: [] };
    expect(purposeState(overview, 'AI_PROCESSING')?.active).toBe(true);
    expect(purposeState(overview, 'PRIVACY_NOTICE')).toBeUndefined();
  });
});

describe('erasure summary', () => {
  const during = new Date('2026-09-10T00:00:00Z');
  const after = new Date('2026-10-05T00:00:00Z');

  it('offers a request when there is none, or the last was cancelled', () => {
    expect(erasureSummary(undefined).canRequest).toBe(true);
    expect(erasureSummary(request({ status: 'CANCELLED', cancellable: false })).canRequest).toBe(true);
  });

  it('can be cancelled during the grace period, and says nothing has been removed', () => {
    const summary = erasureSummary(request({}), during);
    expect(summary.canCancel).toBe(true);
    expect(summary.canRequest).toBe(false);
    expect(summary.detail).toMatch(/nothing has been removed/i);
  });

  it('never claims removal just because the grace date has passed', () => {
    const summary = erasureSummary(request({}), after);
    expect(summary.detail).toMatch(/nothing has been removed yet/i);
    expect(summary.canCancel).toBe(true);
  });

  it('follows the server on whether a request can still be cancelled', () => {
    expect(erasureSummary(request({ cancellable: false }), during).canCancel).toBe(false);
    expect(erasureSummary(request({ status: 'PROCESSING', cancellable: false }), during).canCancel).toBe(false);
    expect(erasureSummary(request({ status: 'FAILED', cancellable: false }), during).canCancel).toBe(false);
  });

  it('reports a completed erasure and offers nothing further', () => {
    const summary = erasureSummary(request({ status: 'COMPLETED', cancellable: false, completedAt: '2026-10-02T00:00:00Z' }));
    expect(summary.title).toBe('Erased');
    expect(summary.canRequest).toBe(false);
    expect(summary.canCancel).toBe(false);
  });
});

describe('export file name', () => {
  it('is dated and JSON', () => {
    expect(exportFileName(new Date('2026-09-16T10:00:00Z'))).toBe('careerflux-my-data-2026-09-16.json');
  });
});
