package com.careerflux.ai.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.careerflux.ai.dto.ExtractedResume;
import com.careerflux.ai.proposal.dto.ProposalDtos.ProposedItem;
import com.careerflux.candidate.domain.CandidateEducation;
import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.domain.CandidateSkill;
import com.careerflux.candidate.domain.CgpaSource;
import com.careerflux.common.taxonomy.Seniority;
import com.careerflux.skill.Skill;
import com.careerflux.common.taxonomy.SkillCategory;
import com.careerflux.skill.SkillResolver;
import com.careerflux.user.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the student is shown, and what they are not allowed to be shown.
 *
 * <p>No Spring and no database: the diff is a comparison between two values and
 * is tested as one. That keeps these fast enough to cover every state on every
 * kind of field, which matters because the states are the contract — a mislabel
 * here is a student pressing Accept on something they misread.
 */
class ProfileProposalDiffTest {

    private SkillResolver skillResolver;
    private ProfileProposalBuilder builder;
    private CandidateProfile profile;

    @BeforeEach
    void setUp() {
        skillResolver = mock(SkillResolver.class);
        when(skillResolver.lookup(anyString())).thenReturn(Optional.empty());
        builder = new ProfileProposalBuilder(skillResolver);

        profile = new CandidateProfile();
        User user = new User();
        user.setFullName("Aarav Sharma");
        profile.setUser(user);
    }

    // ------------------------------------------------------------- helpers

    private static ExtractedResume extraction(String phone, String headline, String location) {
        return new ExtractedResume(null, null, phone, location, headline, null, null, null,
                null, null, null, null, List.of(), List.of(), List.of());
    }

    private ProposedItem item(ExtractedResume extracted, String key) {
        return builder.build(profile, extracted).stream()
                .filter(i -> i.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no item called " + key));
    }

    private static Skill skill(String name, String slug) {
        Skill skill = new Skill();
        skill.setCanonicalName(name);
        skill.setSlug(slug);
        skill.setCategory(SkillCategory.LANGUAGE);
        return skill;
    }

    // -------------------------------------------------------------- states

    @Nested
    @DisplayName("the verdict on a field")
    class FieldStates {

        @Test
        @DisplayName("a value the profile does not have is NEW")
        void absentFromProfileIsNew() {
            ProposedItem phone = item(extraction("+91 9876543210", null, null), "field:phone");

            assertThat(phone.state()).isEqualTo(ProposalItemState.NEW);
            assertThat(phone.currentValue()).isNull();
            assertThat(phone.proposedValue()).isEqualTo("+91 9876543210");
            assertThat(phone.decidable()).isTrue();
        }

        @Test
        @DisplayName("a value the profile already has is UNCHANGED and cannot be applied")
        void sameValueIsUnchanged() {
            profile.setPhone("+91 9876543210");

            ProposedItem phone = item(extraction("+91 9876543210", null, null), "field:phone");

            assertThat(phone.state()).isEqualTo(ProposalItemState.UNCHANGED);
            assertThat(phone.decidable())
                    .describedAs("accepting a value that is already there is a write with no effect")
                    .isFalse();
        }

        @Test
        @DisplayName("case and spacing alone are not a difference worth asking about")
        void equivalentValuesAreUnchanged() {
            profile.setLocation("Hyderabad");

            assertThat(item(extraction(null, null, "  hyderabad "), "field:location").state())
                    .isEqualTo(ProposalItemState.UNCHANGED);
        }

        @Test
        @DisplayName("two different values are a CONFLICT, and neither is called correct")
        void differentValuesConflict() {
            profile.setPhone("9000000000");

            ProposedItem phone = item(extraction("9111111111", null, null), "field:phone");

            assertThat(phone.state()).isEqualTo(ProposalItemState.CONFLICT);
            assertThat(phone.currentValue()).isEqualTo("9000000000");
            assertThat(phone.proposedValue()).isEqualTo("9111111111");
            assertThat(phone.decidable())
                    .describedAs("the student resolves it; nothing is applied on their behalf")
                    .isTrue();
            assertThat(phone.note()).contains("Nothing changes unless you choose");
        }

        @Test
        @DisplayName("a value the resume did not mention is MISSING and can never blank the profile")
        void absentFromResumeIsMissing() {
            profile.setPhone("9000000000");

            ProposedItem phone = item(extraction(null, null, null), "field:phone");

            assertThat(phone.state()).isEqualTo(ProposalItemState.MISSING);
            assertThat(phone.proposedValue()).isNull();
            assertThat(phone.decidable())
                    .describedAs("a CV that omits a phone number is not a claim that there isn't one")
                    .isFalse();
        }

        @Test
        @DisplayName("an empty string from the model counts as not found, not as a blank value")
        void blankIsMissingRatherThanAnErasure() {
            profile.setHeadline("Backend developer");

            assertThat(item(extraction(null, "   ", null), "field:headline").state())
                    .isEqualTo(ProposalItemState.MISSING);
        }
    }

    @Nested
    @DisplayName("values that were read but cannot be trusted as read")
    class Uncertainty {

        @Test
        @DisplayName("a seniority that is not one of our levels is UNCERTAIN")
        void unknownSeniorityIsUncertain() {
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    "Rockstar Ninja", null, null, null, null, List.of(), List.of(), List.of());

            ProposedItem item = item(extracted, "field:seniority");

            assertThat(item.state()).isEqualTo(ProposalItemState.UNCERTAIN);
            assertThat(item.note()).contains("not one of the levels");
        }

        @Test
        @DisplayName("a seniority that is one of our levels is not")
        void knownSeniorityIsNew() {
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    "JUNIOR", null, null, null, null, List.of(), List.of(), List.of());

            assertThat(item(extracted, "field:seniority").state()).isEqualTo(ProposalItemState.NEW);
        }

        @Test
        @DisplayName("an implausible number of years is UNCERTAIN rather than refused")
        void implausibleYearsAreUncertain() {
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, 203d, null, null, null, List.of(), List.of(), List.of());

            ProposedItem item = item(extracted, "field:yearsExperience");

            assertThat(item.state()).isEqualTo(ProposalItemState.UNCERTAIN);
            assertThat(item.decidable())
                    .describedAs("still offered — the student can see it is a typo and we cannot")
                    .isTrue();
        }

        @Test
        @DisplayName("something that is plainly not a web address is UNCERTAIN")
        void nonUrlLinkIsUncertain() {
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, "see my LinkedIn profile", null, null,
                    List.of(), List.of(), List.of());

            assertThat(item(extracted, "field:linkedinUrl").state())
                    .isEqualTo(ProposalItemState.UNCERTAIN);
        }

        @Test
        @DisplayName("an actual address is not")
        void realUrlIsNew() {
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, "https://linkedin.com/in/aarav", null, null,
                    List.of(), List.of(), List.of());

            assertThat(item(extracted, "field:linkedinUrl").state()).isEqualTo(ProposalItemState.NEW);
        }
    }

    @Nested
    @DisplayName("the CGPA, which belongs to the college")
    class AcademicRecord {

        private ExtractedResume withGrade(String grade) {
            return new ExtractedResume(null, null, null, null, null, null, null, null, null,
                    null, null, null, List.of(), List.of(),
                    List.of(new ExtractedResume.ExtractedEducation(
                            "Example Institute of Technology", "B.Tech", "CSE", 2022, 2026, grade)));
        }

        @Test
        @DisplayName("a grade found on a resume is shown, and is not something the student can apply")
        void gradeIsInformationOnly() {
            ProposedItem academic = item(withGrade("9.1 CGPA"), "academic:0");

            assertThat(academic.state()).isEqualTo(ProposalItemState.INFORMATION_FOUND);
            assertThat(academic.decidable())
                    .describedAs("a CGPA is an institutional record, not a reading of a PDF")
                    .isFalse();
            assertThat(academic.note()).contains("recorded by your college");
        }

        @Test
        @DisplayName("a grade that disagrees with the recorded one is a CONFLICT that still changes nothing")
        void disagreeingGradeIsANonActionableConflict() {
            profile.recordVerifiedCgpa(new BigDecimal("8.50"), new User());

            ProposedItem academic = item(withGrade("9.1 CGPA"), "academic:0");

            assertThat(academic.state()).isEqualTo(ProposalItemState.CONFLICT);
            assertThat(academic.currentValue()).startsWith("8.5");
            assertThat(academic.proposedValue()).isEqualTo("9.1 CGPA");
            assertThat(academic.decidable()).isFalse();
            assertThat(academic.note()).contains("unchanged");
        }

        @Test
        @DisplayName("no proposal item anywhere can write a CGPA")
        void nothingProposesACgpaField() {
            profile.recordVerifiedCgpa(new BigDecimal("8.50"), new User());

            List<ProposedItem> items = builder.build(profile, withGrade("9.1 CGPA"));

            assertThat(items)
                    .filteredOn(ProposedItem::decidable)
                    .extracting(ProposedItem::key)
                    .describedAs("nothing actionable may mention the academic record")
                    .noneMatch(key -> key.toLowerCase().contains("cgpa")
                            || key.startsWith("academic:"));
        }

        @Test
        @DisplayName("a resume with no grade produces no academic line at all")
        void noGradeNoLine() {
            assertThat(builder.build(profile, withGrade(null)))
                    .extracting(ProposedItem::key)
                    .noneMatch(key -> key.startsWith("academic:"));
        }
    }

    @Nested
    @DisplayName("skills, experience and education")
    class Collections {

        @Test
        @DisplayName("a skill the candidate does not have is NEW and carries the slug to save")
        void newSkill() {
            Skill java = skill("Java", "java");
            when(skillResolver.lookup("Java")).thenReturn(Optional.of(java));

            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of("Java"), List.of(), List.of());

            ProposedItem item = item(extracted, "skill:java");
            assertThat(item.state()).isEqualTo(ProposalItemState.NEW);
            assertThat(item.data()).containsEntry("slug", "java");
            assertThat(item.editable())
                    .describedAs("a skill is accepted or not; it is not a text box")
                    .isFalse();
        }

        @Test
        @DisplayName("a skill the candidate already has is UNCHANGED, not a duplicate to accept")
        void heldSkill() {
            Skill java = skill("Java", "java");
            when(skillResolver.lookup("Java")).thenReturn(Optional.of(java));
            CandidateSkill held = new CandidateSkill();
            held.setSkill(java);
            profile.getSkills().add(held);

            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of("Java"), List.of(), List.of());

            assertThat(item(extracted, "skill:java").state()).isEqualTo(ProposalItemState.UNCHANGED);
        }

        @Test
        @DisplayName("a skill the dictionary does not know is offered as the student's own, and never added to it")
        void unknownSkillStaysPrivate() {
            // Phase 2A, F4. This used to be dropped here and minted into the
            // shared dictionary by the real resolver; it is now kept for the
            // student alone, and only if they accept it.
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of("Sprnig Boot"), List.of(), List.of());

            ProposedItem item = item(extracted, "skill:private:sprnig-boot");
            assertThat(item.state()).isEqualTo(ProposalItemState.NEW);
            assertThat(item.data())
                    .describedAs("no dictionary slug: approval must not look for one")
                    .containsEntry("name", "Sprnig Boot")
                    .doesNotContainKey("slug");
            verify(skillResolver, never()).resolve(anyString());
        }

        @Test
        @DisplayName("each job is its own decision")
        void experiencesAreSeparateItems() {
            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of(),
                    List.of(new ExtractedResume.ExtractedExperience(
                                    "Zoho", "Backend Intern", "Chennai", "2024-05", "2024-07", false, null),
                            new ExtractedResume.ExtractedExperience(
                                    "Freshworks", "SDE Intern", "Hyderabad", "2025-01", null, true, null)),
                    List.of());

            List<ProposedItem> items = builder.build(profile, extracted);

            assertThat(items).extracting(ProposedItem::key)
                    .contains("experience:0", "experience:1");
            assertThat(item(extracted, "experience:0").data())
                    .containsEntry("companyName", "Zoho")
                    .containsEntry("title", "Backend Intern");
        }

        @Test
        @DisplayName("a degree already on the profile is not offered again")
        void heldEducationIsUnchanged() {
            CandidateEducation held = new CandidateEducation();
            held.setInstitution("Example Institute of Technology");
            held.setDegree("B.Tech");
            profile.getEducation().add(held);

            ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of(), List.of(),
                    List.of(new ExtractedResume.ExtractedEducation(
                            "Example Institute of Technology", "B.Tech", "CSE", 2022, 2026, null)));

            assertThat(item(extracted, "education:0").state()).isEqualTo(ProposalItemState.UNCHANGED);
        }
    }

    @Test
    @DisplayName("an empty reading proposes nothing rather than a screen full of blanks")
    void emptyExtractionHasNothingDecidable() {
        ExtractedResume empty = new ExtractedResume(null, null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), List.of());

        assertThat(builder.build(profile, empty))
                .describedAs("every field should be MISSING, and none of them actionable")
                .noneMatch(ProposedItem::decidable);
    }

    @Test
    @DisplayName("the reader's email is never turned into a proposal")
    void emailIsNotProposed() {
        ExtractedResume extracted = new ExtractedResume("Aarav Sharma", "aarav@example.com", null,
                null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of());

        assertThat(builder.build(profile, extracted))
                .extracting(ProposedItem::key)
                .describedAs("CareerFlux already knows the student's email; storing another is collection for its own sake")
                .doesNotContain("field:email");
    }

    @Test
    @DisplayName("seniority is never presented as a free text box")
    void seniorityIsNotEditableAsText() {
        ExtractedResume extracted = new ExtractedResume(null, null, null, null, null, null, null,
                "SENIOR", null, null, null, null, List.of(), List.of(), List.of());

        assertThat(item(extracted, "field:seniority").editable()).isFalse();
    }

    @Test
    @DisplayName("the profile is not touched by building a diff")
    void buildingChangesNothing() {
        profile.setPhone("9000000000");
        profile.setSeniority(Seniority.JUNIOR);

        builder.build(profile, extraction("9111111111", "New headline", "Pune"));

        assertThat(profile.getPhone()).isEqualTo("9000000000");
        assertThat(profile.getHeadline()).isNull();
        assertThat(profile.getLocation()).isNull();
        assertThat(profile.getSeniority()).isEqualTo(Seniority.JUNIOR);
    }
}
