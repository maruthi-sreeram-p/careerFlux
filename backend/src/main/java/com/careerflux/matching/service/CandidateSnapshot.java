package com.careerflux.matching.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.careerflux.common.taxonomy.EmploymentType;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.common.taxonomy.WorkMode;

/**
 * Everything the scorer needs about a candidate, read once and passed around as
 * an immutable value.
 *
 * <p>Scoring a candidate against several hundred jobs must not issue a query per
 * job, and it must not touch lazily-loaded JPA associations from outside a
 * transaction. Materialising the candidate once solves both.
 */
public record CandidateSnapshot(
        UUID candidateId,
        UUID userId,
        String fullName,
        String primaryRole,
        Seniority seniority,
        BigDecimal yearsExperience,
        String location,
        Set<String> skillSlugs,
        List<String> targetRoles,
        Set<String> preferredLocations,
        Set<WorkMode> workModes,
        Set<EmploymentType> employmentTypes,
        boolean openToRelocation,
        boolean immediateAlerts,
        boolean dailyDigest) {

    public CandidateSnapshot {
        skillSlugs = Set.copyOf(skillSlugs);
        targetRoles = List.copyOf(targetRoles);
        preferredLocations = normalizeAll(preferredLocations);
        workModes = Set.copyOf(workModes);
        employmentTypes = Set.copyOf(employmentTypes);
    }

    /** Shared normalization so location comparison behaves the same on both sides. */
    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return stripped.replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
    }

    private static Set<String> normalizeAll(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.toLowerCase(Locale.ROOT).strip());
            }
        }
        return Set.copyOf(result);
    }

    /** True when there is enough on the profile for a score to mean anything. */
    public boolean isScorable() {
        return !skillSlugs.isEmpty() || !targetRoles.isEmpty() || primaryRole != null;
    }
}
