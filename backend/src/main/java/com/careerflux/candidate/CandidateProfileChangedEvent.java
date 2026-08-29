package com.careerflux.candidate;

import java.util.UUID;

/**
 * Published whenever something that feeds matching changes on a candidate:
 * skills, seniority, experience, target roles, locations.
 *
 * <p>Matching listens for this rather than being called directly from the
 * profile service, which keeps the candidate module unaware that scoring exists
 * and means a future consumer can react to the same change without another
 * edit here.
 *
 * @param reason short, human-readable cause, recorded in the log so a rematch
 *               can always be traced back to what triggered it
 */
public record CandidateProfileChangedEvent(UUID candidateId, String reason) {
}
