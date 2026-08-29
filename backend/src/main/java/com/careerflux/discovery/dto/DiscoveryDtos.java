package com.careerflux.discovery.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What candidate discovery hands back.
 *
 * <p>Two answers per student, never one. {@code compatibility} says how well
 * their skills, role, experience and circumstances line up with what the
 * company described; {@code eligibility} says whether the conditions the
 * company stated are satisfied. A high number beside NOT_ELIGIBLE is not a
 * contradiction — it is the whole reason this feature exists.
 *
 * <p>Deliberately absent: contact details, resume text, and any link to the
 * resume document. Discovery shows what is needed to judge relevance, and
 * nothing beyond it. Opening a student's record remains a separate request
 * through the existing directory endpoint, and their resume a separate
 * permission again.
 */
public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /** A dimension the scorer compared, so the number can be taken apart. */
    public record DimensionView(String dimension, Integer score, String unknownSide) {
    }

    /** One line of the scorer's own explanation. Never written by the client. */
    public record ReasonView(String kind, String dimension, String label, String detail) {
    }

    /**
     * @param compatibility null when the scorer could not form an opinion —
     *                      rendered as "not enough information", never as 0%
     * @param cgpa          null until CareerFlux holds a verified numeric CGPA;
     *                      never parsed from a free-text grade
     * @param eligibility   ELIGIBLE / ELIGIBLE_WITH_GAPS / NOT_ELIGIBLE / UNKNOWN
     */
    public record CandidateView(
            UUID candidateId,
            UUID userId,
            String fullName,
            String department,
            String batch,
            BigDecimal cgpa,
            Integer compatibility,
            String confidence,
            int confidenceCoverage,
            String eligibility,
            List<String> eligibilityReasons,
            List<String> matchedRequiredSkills,
            List<String> missingRequiredSkills,
            List<String> matchedPreferredSkills,
            List<String> missingPreferredSkills,
            BigDecimal yearsExperience,
            String primaryRole,
            List<DimensionView> dimensions,
            List<ReasonView> strengths,
            List<ReasonView> gaps,
            /* Whether the placement team has already chosen to put this student
               forward. A decision someone made, not a property of the score. */
            boolean shortlisted) {
    }

    /**
     * @param scopeLabel        what the caller is looking at, so a short list is
     *                          not mistaken for a small pool
     * @param consideredStudents how many students were in scope before filtering
     * @param cgpaAvailable     false while no verified CGPA exists anywhere, so
     *                          the screen can explain the UNKNOWNs once instead
     *                          of on every row
     */
    public record CandidatePage(
            UUID requirementId,
            String companyName,
            String roleTitle,
            String requirementStatus,
            List<String> requiredSkills,
            List<String> preferredSkills,
            List<String> targetDepartments,
            Integer targetGraduationYear,
            BigDecimal minCgpa,
            String scopeLabel,
            long consideredStudents,
            boolean cgpaAvailable,
            /** How many students are on this requirement's shortlist, from the table. */
            long shortlistedCount,
            List<CandidateView> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
