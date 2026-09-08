-- ===========================================================================
-- V15  A proposal may record that the reading failed
--
-- V14 deliberately allowed four statuses and said so in a comment: a failed
-- extraction produced no proposal at all, so a FAILED row could never be
-- written, and a status nothing can reach describes a state the system does
-- not have.
--
-- That reasoning was sound about V14's behaviour and wrong about what the
-- behaviour should be. When Gemini fails, the student is left with a stored
-- resume, a FAILED parse status on it, and no record anywhere that a reading
-- was attempted on their behalf. The proposals list — the one place they look
-- to see what CareerFlux made of their CV — simply shows nothing, which is
-- indistinguishable from never having uploaded anything.
--
-- So FAILED becomes reachable rather than decorative: the upload path now
-- writes a FAILED proposal when extraction throws. It carries the resume it
-- was reading and an empty item list, because there is nothing to review; it
-- exists to say "we tried, and could not". Like SUPERSEDED it is not a human
-- decision, so it records no reviewer and no review time, and like every
-- non-PENDING status it cannot be approved.
--
-- Only the CHECK constraint changes. No column is added, no row is touched,
-- and every existing row still satisfies the wider constraint.
-- ===========================================================================

ALTER TABLE ai_profile_proposals DROP CONSTRAINT ck_ai_proposals_status;

ALTER TABLE ai_profile_proposals ADD CONSTRAINT ck_ai_proposals_status CHECK (
    status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'FAILED'));
