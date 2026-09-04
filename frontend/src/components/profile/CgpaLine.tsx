import type { DiscoveredCandidate } from '../../lib/types';

/**
 * A student's CGPA beside the figure the company asked for.
 *
 * <p>Deliberately a quiet line rather than a headline. Technical compatibility
 * is the technical signal and eligibility is the formal one; CGPA is a fact
 * feeding the second, and giving it visual weight would invite exactly the
 * reading the product exists to prevent — that a number below the bar settles
 * the question.
 *
 * <p>"Not verified" and a value are different states and are worded
 * differently. Nothing renders a zero for an absent CGPA.
 */
export function CgpaLine({
  candidate,
  minCgpa,
}: {
  candidate: DiscoveredCandidate;
  minCgpa: number | null;
}) {
  if (candidate.cgpa === null && minCgpa === null) {
    return null;
  }
  return (
    <div className="candidate__skills">
      <span className="candidate__skills-label">CGPA</span>
      <span className="text-secondary">
        {candidate.cgpa === null ? (
          <span className="text-muted">Not verified by the college</span>
        ) : (
          candidate.cgpa
        )}
        {minCgpa !== null && (
          <span className="text-muted"> · {minCgpa} required</span>
        )}
      </span>
    </div>
  );
}
