package com.careerflux.source.adapter;

import java.time.Instant;

/**
 * A posting exactly as the source presented it, before normalization.
 *
 * <p>Adapters do the least possible interpretation: they map the source's fields
 * onto these names and stop. Everything else — title normalization, seniority
 * inference, skill extraction, location parsing — happens later in the pipeline
 * where it can be applied consistently across every source.
 *
 * <p>{@code rawPayload} is retained verbatim so provenance is complete and a
 * later pipeline change can be replayed against what the source actually said.
 */
public record RawJobPosting(
        String externalId,
        String requisitionId,
        String title,
        String companyName,
        String locationText,
        String descriptionHtml,
        String employmentTypeText,
        String workModeText,
        String departmentText,
        String salaryText,
        String applyUrl,
        String sourceUrl,
        Instant postedAt,
        Instant updatedAt,
        String rawPayload) {

    public static Builder builder(String externalId) {
        return new Builder(externalId);
    }

    /** Adapters build these field by field; most sources supply only a subset. */
    public static final class Builder {

        private final String externalId;
        private String requisitionId;
        private String title;
        private String companyName;
        private String locationText;
        private String descriptionHtml;
        private String employmentTypeText;
        private String workModeText;
        private String departmentText;
        private String salaryText;
        private String applyUrl;
        private String sourceUrl;
        private Instant postedAt;
        private Instant updatedAt;
        private String rawPayload;

        private Builder(String externalId) {
            this.externalId = externalId;
        }

        public Builder requisitionId(String value) {
            this.requisitionId = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder companyName(String value) {
            this.companyName = value;
            return this;
        }

        public Builder locationText(String value) {
            this.locationText = value;
            return this;
        }

        public Builder descriptionHtml(String value) {
            this.descriptionHtml = value;
            return this;
        }

        public Builder employmentTypeText(String value) {
            this.employmentTypeText = value;
            return this;
        }

        public Builder workModeText(String value) {
            this.workModeText = value;
            return this;
        }

        public Builder departmentText(String value) {
            this.departmentText = value;
            return this;
        }

        public Builder salaryText(String value) {
            this.salaryText = value;
            return this;
        }

        public Builder applyUrl(String value) {
            this.applyUrl = value;
            return this;
        }

        public Builder sourceUrl(String value) {
            this.sourceUrl = value;
            return this;
        }

        public Builder postedAt(Instant value) {
            this.postedAt = value;
            return this;
        }

        public Builder updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        public Builder rawPayload(String value) {
            this.rawPayload = value;
            return this;
        }

        public RawJobPosting build() {
            return new RawJobPosting(externalId, requisitionId, title, companyName, locationText,
                    descriptionHtml, employmentTypeText, workModeText, departmentText, salaryText,
                    applyUrl, sourceUrl, postedAt, updatedAt, rawPayload);
        }
    }
}
