import type {
  CandidateEvidence,
  CompanyDiscoveryResult,
  ResolutionOutcome,
  ResolvedCompany,
} from './types';

/**
 * What the "add company" screen needs to know.
 *
 * <p>The screen's whole job is to put a person between a guess and an outbound
 * request. That makes two rules load-bearing, and both live here rather than
 * inline in the component where they would drift:
 *
 * <ol>
 *   <li><b>Nothing is selected by default.</b> Not even a single confidently
 *       resolved domain. An operator confirming a destination has to actually
 *       choose it, because a pre-ticked box is not a confirmation — it is a
 *       default the person clicked past.
 *   <li><b>One domain per company, at most.</b> Choosing a second domain for a
 *       company replaces the first rather than adding to it, since an ambiguous
 *       result is a question about which single site is the employer's.
 * </ol>
 */

export type Selection = Record<string, string>;

/** How much weight a proposed domain carries, in the operator's words. */
const EVIDENCE_LABELS: Record<CandidateEvidence, string> = {
  REGISTRY: 'Already known',
  SEED_LIST: 'In the market list',
  VERIFIED_SITE: 'Site confirmed the name',
};

const EVIDENCE_TONES: Record<CandidateEvidence, 'positive' | 'info' | 'caution'> = {
  REGISTRY: 'positive',
  SEED_LIST: 'info',
  VERIFIED_SITE: 'caution',
};

const OUTCOME_LABELS: Record<ResolutionOutcome, string> = {
  RESOLVED: 'One domain found',
  AMBIGUOUS: 'Several possible domains',
  NOT_FOUND: 'No domain found',
};

export function evidenceLabel(evidence: CandidateEvidence): string {
  return EVIDENCE_LABELS[evidence] ?? evidence;
}

export function evidenceTone(evidence: CandidateEvidence) {
  return EVIDENCE_TONES[evidence] ?? 'info';
}

export function outcomeLabel(outcome: ResolutionOutcome): string {
  return OUTCOME_LABELS[outcome] ?? outcome;
}

/** Company names typed as free text, cleaned into a list worth sending. */
export function parseNames(raw: string): string[] {
  const seen = new Set<string>();
  return raw
    .split(/[\n,]/)
    .map((name) => name.trim())
    .filter((name) => name.length > 0)
    .filter((name) => {
      const key = name.toLowerCase();
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

/** Every company that produced at least one domain to choose from. */
export function choosable(result: CompanyDiscoveryResult | null): ResolvedCompany[] {
  if (!result) return [];
  return [...result.resolved, ...result.ambiguous].filter((c) => c.candidates.length > 0);
}

/**
 * Records a choice, replacing any previous one for that company.
 *
 * <p>Clicking the already-selected domain clears it, so a confirmation can be
 * taken back without reloading.
 */
export function toggle(selection: Selection, slug: string, domain: string): Selection {
  const next = { ...selection };
  if (next[slug] === domain) {
    delete next[slug];
  } else {
    next[slug] = domain;
  }
  return next;
}

export function isSelected(selection: Selection, slug: string, domain: string): boolean {
  return selection[slug] === domain;
}

/** The domains to probe, in a stable order. */
export function confirmedDomains(selection: Selection): string[] {
  return Object.keys(selection)
    .sort()
    .map((slug) => selection[slug]);
}

/** The company names matching those domains, positionally, as the API expects. */
export function confirmedNames(selection: Selection, companies: ResolvedCompany[]): string[] {
  return Object.keys(selection)
    .sort()
    .map((slug) => companies.find((c) => c.slug === slug)?.companyName ?? slug);
}

/** Nothing chosen means nothing to probe: the confirm step stays disabled. */
export function canConfirm(selection: Selection): boolean {
  return Object.keys(selection).length > 0;
}

/**
 * A one-line summary of a resolve, said in terms of what to do next.
 *
 * <p>Counting outcomes is more useful to an operator than counting names: what
 * they need to know is how many still need a decision.
 */
export function resolutionSummary(result: CompanyDiscoveryResult | null): string {
  if (!result) return '';
  const total = result.resolved.length + result.ambiguous.length + result.notFound.length;
  if (total === 0) return 'Nothing to resolve.';
  const parts: string[] = [];
  if (result.resolved.length > 0) parts.push(`${result.resolved.length} resolved`);
  if (result.ambiguous.length > 0) parts.push(`${result.ambiguous.length} need a choice`);
  if (result.notFound.length > 0) parts.push(`${result.notFound.length} not found`);
  return parts.join(' · ');
}

/** What a completed discovery run did, said plainly. */
export function discoverySummary(result: CompanyDiscoveryResult | null): string {
  if (!result) return '';
  const parts: string[] = [];
  if (result.registered.length > 0) parts.push(`${result.registered.length} registered`);
  if (result.alreadyKnown.length > 0) parts.push(`${result.alreadyKnown.length} already known`);
  if (result.withoutBoard.length > 0) {
    parts.push(`${result.withoutBoard.length} with no readable board`);
  }
  return parts.length === 0 ? 'Nothing was registered.' : parts.join(' · ');
}

/** True once a call has actually registered or rejected something. */
export function isDiscoveryResult(result: CompanyDiscoveryResult | null): boolean {
  if (!result) return false;
  return (
    result.registered.length > 0 ||
    result.alreadyKnown.length > 0 ||
    result.withoutBoard.length > 0
  );
}
