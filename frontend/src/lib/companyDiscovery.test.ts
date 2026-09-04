import { describe, expect, it } from 'vitest';

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
} from './companyDiscovery';
import type { CompanyDiscoveryResult, ResolvedCompany } from './types';

const company = (
  companyName: string,
  slug: string,
  outcome: ResolvedCompany['outcome'],
  domains: string[] = [],
): ResolvedCompany => ({
  companyName,
  slug,
  outcome,
  detail: 'stub',
  candidates: domains.map((domain) => ({
    domain,
    evidence: 'VERIFIED_SITE',
    detail: 'stub',
  })),
});

const result = (over: Partial<CompanyDiscoveryResult> = {}): CompanyDiscoveryResult => ({
  resolved: [],
  ambiguous: [],
  notFound: [],
  registered: [],
  alreadyKnown: [],
  withoutBoard: [],
  ...over,
});

describe('confirmation is a real decision, not a default', () => {
  it('nothing is selected before the operator chooses', () => {
    // The rule the whole screen exists for. A pre-ticked box is not a
    // confirmation — it is a default somebody clicked past — and the thing being
    // confirmed is an outbound request to a third party.
    expect(canConfirm({})).toBe(false);
    expect(confirmedDomains({})).toEqual([]);
  });

  it('choosing a domain enables the confirm step', () => {
    const selection = toggle({}, 'meesho', 'meesho.com');
    expect(canConfirm(selection)).toBe(true);
    expect(confirmedDomains(selection)).toEqual(['meesho.com']);
  });

  it('a second domain for the same company replaces the first', () => {
    // An ambiguous result asks which single site is the employer's, so picking
    // again is a change of mind, not an addition.
    let selection = toggle({}, 'acme', 'acme.com');
    selection = toggle(selection, 'acme', 'acme.in');
    expect(confirmedDomains(selection)).toEqual(['acme.in']);
    expect(isSelected(selection, 'acme', 'acme.com')).toBe(false);
    expect(isSelected(selection, 'acme', 'acme.in')).toBe(true);
  });

  it('clicking the selected domain again clears it', () => {
    let selection = toggle({}, 'acme', 'acme.com');
    selection = toggle(selection, 'acme', 'acme.com');
    expect(canConfirm(selection)).toBe(false);
  });

  it('different companies each keep their own choice', () => {
    let selection = toggle({}, 'acme', 'acme.com');
    selection = toggle(selection, 'meesho', 'meesho.com');
    expect(confirmedDomains(selection)).toEqual(['acme.com', 'meesho.com']);
  });

  it('does not mutate the selection it was given', () => {
    const original = { acme: 'acme.com' };
    toggle(original, 'meesho', 'meesho.com');
    expect(original).toEqual({ acme: 'acme.com' });
  });

  it('names line up positionally with the domains sent', () => {
    // The API matches the two lists by position, so a mismatch would register
    // a source under another company's name.
    const companies = [
      company('Meesho', 'meesho', 'RESOLVED', ['meesho.com']),
      company('Acme Corp', 'acme', 'AMBIGUOUS', ['acme.com', 'acme.in']),
    ];
    let selection = toggle({}, 'meesho', 'meesho.com');
    selection = toggle(selection, 'acme', 'acme.in');

    expect(confirmedDomains(selection)).toEqual(['acme.in', 'meesho.com']);
    expect(confirmedNames(selection, companies)).toEqual(['Acme Corp', 'Meesho']);
  });

  it('falls back to the slug when a company is not in the list', () => {
    expect(confirmedNames({ ghost: 'ghost.com' }, [])).toEqual(['ghost']);
  });
});

describe('what the operator is offered to choose from', () => {
  it('offers resolved and ambiguous companies, never not-found ones', () => {
    const r = result({
      resolved: [company('Meesho', 'meesho', 'RESOLVED', ['meesho.com'])],
      ambiguous: [company('Acme', 'acme', 'AMBIGUOUS', ['acme.com', 'acme.in'])],
      notFound: [company('Ghost', 'ghost', 'NOT_FOUND')],
    });
    expect(choosable(r).map((c) => c.slug)).toEqual(['meesho', 'acme']);
  });

  it('skips a company that produced no candidates', () => {
    const r = result({ resolved: [company('Empty', 'empty', 'RESOLVED', [])] });
    expect(choosable(r)).toEqual([]);
  });

  it('handles no result at all', () => {
    expect(choosable(null)).toEqual([]);
  });
});

describe('typed company names', () => {
  it('splits on commas and newlines', () => {
    expect(parseNames('Meesho, AutoRABIT\nWipro')).toEqual(['Meesho', 'AutoRABIT', 'Wipro']);
  });

  it('drops blanks and trims', () => {
    expect(parseNames('  Meesho  ,, \n  , Wipro ')).toEqual(['Meesho', 'Wipro']);
  });

  it('drops case-insensitive duplicates, keeping what was typed first', () => {
    expect(parseNames('Meesho, meesho, MEESHO')).toEqual(['Meesho']);
  });

  it('an empty box yields nothing to send', () => {
    expect(parseNames('')).toEqual([]);
    expect(parseNames('   \n  ')).toEqual([]);
  });
});

describe('summaries say what to do next', () => {
  it('a resolve counts outcomes, not names', () => {
    const r = result({
      resolved: [company('A', 'a', 'RESOLVED', ['a.com'])],
      ambiguous: [company('B', 'b', 'AMBIGUOUS', ['b.com', 'b.in'])],
      notFound: [company('C', 'c', 'NOT_FOUND')],
    });
    expect(resolutionSummary(r)).toBe('1 resolved · 1 need a choice · 1 not found');
  });

  it('an empty resolve says so', () => {
    expect(resolutionSummary(result())).toBe('Nothing to resolve.');
    expect(resolutionSummary(null)).toBe('');
  });

  it('a discovery run reports what it did, including what had no board', () => {
    const r = result({
      registered: [
        {
          sourceId: '1',
          name: 'Meesho',
          domain: 'meesho.com',
          provider: 'GREENHOUSE',
          boardToken: 'meesho',
          howFound: 'ATS_PROBE',
          detail: 'x',
        },
      ],
      alreadyKnown: ['razorpay.com'],
      withoutBoard: ['wipro.com'],
    });
    expect(discoverySummary(r)).toBe(
      '1 registered · 1 already known · 1 with no readable board',
    );
  });

  it('a run that registered nothing says that plainly', () => {
    // Honest reporting matters here: most large employers have no readable
    // board, and the screen must not imply otherwise.
    expect(discoverySummary(result())).toBe('Nothing was registered.');
  });

  it('distinguishes a resolve from a completed discovery', () => {
    expect(isDiscoveryResult(result({ resolved: [company('A', 'a', 'RESOLVED', ['a.com'])] })))
      .toBe(false);
    expect(isDiscoveryResult(result({ withoutBoard: ['a.com'] }))).toBe(true);
    expect(isDiscoveryResult(null)).toBe(false);
  });
});

describe('labels', () => {
  it('every evidence kind has a label and a tone', () => {
    for (const evidence of ['REGISTRY', 'SEED_LIST', 'VERIFIED_SITE'] as const) {
      expect(evidenceLabel(evidence)).toBeTruthy();
      expect(evidenceLabel(evidence)).not.toContain('_');
      expect(evidenceTone(evidence)).toBeTruthy();
    }
  });

  it('every outcome has a label written for a person', () => {
    for (const outcome of ['RESOLVED', 'AMBIGUOUS', 'NOT_FOUND'] as const) {
      expect(outcomeLabel(outcome)).toBeTruthy();
      expect(outcomeLabel(outcome)).not.toContain('_');
    }
  });

  it('a registry hit reads as stronger than a guess that happened to answer', () => {
    expect(evidenceTone('REGISTRY')).toBe('positive');
    expect(evidenceTone('VERIFIED_SITE')).toBe('caution');
  });
});
