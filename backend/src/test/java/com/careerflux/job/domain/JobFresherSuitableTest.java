package com.careerflux.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.careerflux.common.taxonomy.Seniority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobFresherSuitableTest {

    private static Job job(Seniority seniority, String minYears) {
        Job job = new Job();
        job.setSeniority(seniority);
        job.setMinExperienceYears(minYears == null ? null : new BigDecimal(minYears));
        return job;
    }

    @Test
    @DisplayName("entry-level and internship roles suit a fresher unless they demand experience")
    void entryLevel() {
        assertThat(job(Seniority.ENTRY, null).isFresherSuitable()).isTrue();
        assertThat(job(Seniority.INTERN, "0").isFresherSuitable()).isTrue();
        assertThat(job(Seniority.ENTRY, "3").isFresherSuitable()).isFalse();
    }

    @Test
    @DisplayName("junior or unlabelled roles need an explicit low experience requirement")
    void needsEvidence() {
        assertThat(job(Seniority.UNSPECIFIED, "0").isFresherSuitable()).isTrue();
        assertThat(job(Seniority.JUNIOR, "1").isFresherSuitable()).isTrue();
        assertThat(job(Seniority.UNSPECIFIED, null).isFresherSuitable()).isFalse();
        assertThat(job(Seniority.JUNIOR, "2").isFresherSuitable()).isFalse();
    }

    @Test
    @DisplayName("mid level and above never suit a fresher")
    void senior() {
        assertThat(job(Seniority.MID, "0").isFresherSuitable()).isFalse();
        assertThat(job(Seniority.SENIOR, null).isFresherSuitable()).isFalse();
    }
}
