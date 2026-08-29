package com.careerflux.source.adapter;

import com.careerflux.source.domain.AccessPolicyType;
import com.careerflux.source.domain.AtsProvider;
import com.careerflux.source.domain.SourceType;

/**
 * Static description of an adapter. The access policy stated here is the one the
 * adapter is designed for; it is a claim the policy engine still verifies rather
 * than a permission the adapter grants itself.
 */
public record SourceMetadata(
        String key,
        String displayName,
        SourceType sourceType,
        AtsProvider atsProvider,
        AccessPolicyType intendedAccessPolicy,
        String documentationUrl,
        String description) {
}
