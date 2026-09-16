package com.careerflux.consent;

import java.util.UUID;

/**
 * Published inside the transaction that records a withdrawal, so anything that
 * holds work done under that consent can let go of it in the same transaction.
 */
public record ConsentWithdrawnEvent(UUID userId, ConsentPurpose purpose) {
}
