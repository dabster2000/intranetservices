package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.TemplateCoverageResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TemplateCoverageResponse.MomentCoverage;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyMode;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.Counts;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.CoverageContext;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.QueueCounts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.GROUP_APPLICATION;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.GROUP_GDPR;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.GROUP_POOL;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.GROUP_REJECTION;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.GROUP_STAGE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.OUTCOME_COVERED;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.OUTCOME_FALLS_BACK;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.OUTCOME_INACTIVE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.OUTCOME_NONE;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.build;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The coverage core (fast tier, no DB): what the Journey tab is allowed to
 * claim about a moment.
 * <p>
 * The rule the whole screen rests on is that a moment counts as covered
 * only when the mailer would actually resolve a letter for it. The core is
 * handed one rung at a time — exactly what
 * {@link RecruitmentEmailService#findFirstActiveByTrigger} answers for a
 * single key in production — and the chain it walks comes from
 * {@link RecruitmentEmailService#rejectionKeyChain} /
 * {@link RecruitmentEmailService#pooledKeyChain}, so a change to either
 * chain lands here without this file naming a single key suffix itself.
 */
class RecruitmentCommsCoverageServiceTest {

    /** Rejected, no reason: the two rungs the reject dialog can produce. */
    private static final String REJECTION_SCREENING =
            RecruitmentEmailService.KEY_REJECTION_SCREENING;
    private static final String REJECTION_POST_INTERVIEW =
            RecruitmentEmailService.KEY_REJECTION_POST_INTERVIEW;

    /**
     * The two stages a rejection chain splits on. Only SCREENING-vs-later
     * matters to {@link RecruitmentEmailService#rejectionKeyChain}, so the
     * second is any interview stage.
     */
    private static final String SCREENING_STAGE = RecruitmentStage.SCREENING.name();
    private static final String POST_INTERVIEW_STAGE = RecruitmentStage.INTERVIEW_1.name();

    /** "Too junior, and we found out at screening" — the narrowest rung there is. */
    private static final List<String> EXPERIENCE_AT_SCREENING =
            RecruitmentEmailService.rejectionKeyChain(
                    RecruitmentRejectionReason.EXPERIENCE_LEVEL.name(), SCREENING_STAGE);

    private static final List<String> SILVER_MEDALIST =
            RecruitmentEmailService.pooledKeyChain(CandidatePoolStatus.SILVER_MEDALIST.name());

    // ---- Outcomes ---------------------------------------------------------

    @Test
    void aLetterOnTheMomentsOwnKey_switchedOn_isCovered() {
        RecruitmentEmailTemplate letter = template("ACKNOWLEDGEMENT");
        TemplateCoverageResponse coverage =
                build(contextOf(Map.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT, letter)));

        MomentCoverage moment = moment(coverage, RecruitmentEmailService.KEY_ACKNOWLEDGEMENT);
        assertEquals(OUTCOME_COVERED, moment.outcome());
        assertEquals(GROUP_APPLICATION, moment.group());
        assertEquals(letter.getUuid(), moment.templateUuid());
        assertEquals("ACKNOWLEDGEMENT", moment.templateKey());
        assertEquals(Boolean.TRUE, moment.autoSend());
        assertNull(moment.fallsBackTo());
    }

    @Test
    void aLetterReassignedToAMoment_coversIt_underItsOwnUnchangedKey() {
        // The point of the trigger/identity split: the letter answering the
        // offer moment is the one whose key still says POOLED_NOT_NOW,
        // because renaming a key would break every EMAIL_SENT join ever
        // recorded. The screen has to report the identity it really has.
        RecruitmentEmailTemplate reassigned = template("POOLED_NOT_NOW");
        TemplateCoverageResponse coverage = build(contextOf(Map.of("STAGE_OFFER", reassigned)));

        MomentCoverage moment = moment(coverage, "STAGE_OFFER");
        assertEquals(OUTCOME_COVERED, moment.outcome());
        assertEquals(GROUP_STAGE, moment.group());
        assertEquals("POOLED_NOT_NOW", moment.templateKey());
        assertNull(moment.fallsBackTo());
    }

    @Test
    void aSpecificRejectionWithNoLetterOfItsOwn_fallsBackToTheRungThatAnswers() {
        // Nothing narrow is configured, so the reject dialog's chain runs all
        // the way down to the generic screening letter — and that is what the
        // candidate will actually receive, so it is what the row must name.
        RecruitmentEmailTemplate generic = template(REJECTION_SCREENING);
        TemplateCoverageResponse coverage =
                build(contextOf(Map.of(REJECTION_SCREENING, generic)));

        MomentCoverage specific = specific(moment(coverage, REJECTION_SCREENING),
                EXPERIENCE_AT_SCREENING.get(0));
        assertEquals(OUTCOME_FALLS_BACK, specific.outcome());
        assertEquals(REJECTION_SCREENING, specific.fallsBackTo());
        assertEquals(REJECTION_SCREENING, specific.templateKey());
        assertEquals(generic.getUuid(), specific.templateUuid());
    }

    @Test
    void aSpecificRejectionWithItsOwnLetter_isCovered_notFallenBackTo() {
        RecruitmentEmailTemplate own = template(EXPERIENCE_AT_SCREENING.get(0));
        RecruitmentEmailTemplate generic = template(REJECTION_SCREENING);
        TemplateCoverageResponse coverage = build(contextOf(Map.of(
                EXPERIENCE_AT_SCREENING.get(0), own,
                REJECTION_SCREENING, generic)));

        MomentCoverage specific = specific(moment(coverage, REJECTION_SCREENING),
                EXPERIENCE_AT_SCREENING.get(0));
        assertEquals(OUTCOME_COVERED, specific.outcome());
        assertEquals(own.getUuid(), specific.templateUuid());
        assertNull(specific.fallsBackTo());
    }

    @Test
    void aLetterSwitchedOff_readsAsInactive_andIsNamed() {
        // Nothing is sent either way, but "you switched this one off" is the
        // actionable half of that — a blank row would hide the one fact that
        // explains the silence.
        RecruitmentEmailTemplate off = template("ACKNOWLEDGEMENT");
        TemplateCoverageResponse coverage = build(context(
                Map.of(),
                Map.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT, off),
                Map.of(), Map.of()));

        MomentCoverage moment = moment(coverage, RecruitmentEmailService.KEY_ACKNOWLEDGEMENT);
        assertEquals(OUTCOME_INACTIVE, moment.outcome());
        assertEquals(off.getUuid(), moment.templateUuid());
        assertEquals("ACKNOWLEDGEMENT", moment.templateKey());
        assertNull(moment.fallsBackTo());
    }

    @Test
    void aMomentNothingHasEverAnswered_isNone_andCarriesNoLetter() {
        TemplateCoverageResponse coverage = build(contextOf(Map.of()));

        MomentCoverage moment = moment(coverage, RecruitmentEmailService.KEY_ACKNOWLEDGEMENT);
        assertEquals(OUTCOME_NONE, moment.outcome());
        assertNull(moment.templateUuid());
        assertNull(moment.templateKey());
        assertNull(moment.templateName());
        assertNull(moment.autoSend());
        assertNull(moment.fallsBackTo());
    }

    @Test
    void aSwitchedOffGenericLetter_makesTheSpecificRungsInactiveToo_notMerelyEmpty() {
        // The chain resolves nothing, so nothing is sent for "too junior at
        // screening" either — and the reason is the same switched-off generic
        // letter, which is what the recruiter has to go and turn back on.
        RecruitmentEmailTemplate off = template(REJECTION_SCREENING);
        TemplateCoverageResponse coverage = build(context(
                Map.of(), Map.of(REJECTION_SCREENING, off), Map.of(), Map.of()));

        MomentCoverage specific = specific(moment(coverage, REJECTION_SCREENING),
                EXPERIENCE_AT_SCREENING.get(0));
        assertEquals(OUTCOME_INACTIVE, specific.outcome());
        assertEquals(REJECTION_SCREENING, specific.fallsBackTo());
    }

    // ---- The curated shape ------------------------------------------------

    @Test
    void tenCuratedMoments_inTheOrderTheJourneyReadsThem() {
        TemplateCoverageResponse coverage = build(contextOf(Map.of()));

        assertEquals(List.of(
                        RecruitmentEmailService.KEY_ACKNOWLEDGEMENT,
                        RecruitmentEmailService.KEY_UNSOLICITED_ACKNOWLEDGEMENT,
                        RecruitmentEmailService.KEY_DUPLICATE_APPLICATION_NOTICE,
                        "STAGE_INTERVIEW_1",
                        "STAGE_INTERVIEW_2",
                        "STAGE_INTERVIEW_3",
                        "STAGE_OFFER",
                        REJECTION_SCREENING,
                        RecruitmentEmailService.KEY_POOLED,
                        RecruitmentGdprService.KEY_CONSENT_RENEWAL),
                coverage.moments().stream().map(MomentCoverage::triggerKey).toList());
        assertEquals(GROUP_GDPR,
                moment(coverage, RecruitmentGdprService.KEY_CONSENT_RENEWAL).group());
    }

    @Test
    void theRejectedMoment_nestsBothGenericRungs_andEveryReasonInBothStageBuckets() {
        TemplateCoverageResponse coverage = build(contextOf(Map.of()));
        MomentCoverage rejected = moment(coverage, REJECTION_SCREENING);

        // The after-interviews letter is a sibling rung, not a narrower one;
        // it is nested because the moment is one row and a row has one key.
        assertEquals(REJECTION_POST_INTERVIEW, rejected.specifics().get(0).triggerKey());
        assertEquals(1 + RecruitmentRejectionReason.values().length * 2,
                rejected.specifics().size());
        for (RecruitmentRejectionReason reason : RecruitmentRejectionReason.values()) {
            for (String stage : List.of(SCREENING_STAGE, POST_INTERVIEW_STAGE)) {
                MomentCoverage specific = specific(rejected,
                        RecruitmentEmailService.rejectionKeyChain(reason.name(), stage).get(0));
                assertEquals(GROUP_REJECTION, specific.group());
                assertTrue(specific.specifics().isEmpty(),
                        "a reason-coded rung is the bottom of the tree");
            }
        }
    }

    @Test
    void thePooledMoment_nestsEveryTalentPoolBucket() {
        TemplateCoverageResponse coverage = build(contextOf(Map.of()));
        MomentCoverage pooled = moment(coverage, RecruitmentEmailService.KEY_POOLED);

        assertEquals(GROUP_POOL, pooled.group());
        assertEquals(CandidatePoolStatus.values().length, pooled.specifics().size());
        for (CandidatePoolStatus status : CandidatePoolStatus.values()) {
            MomentCoverage bucket = specific(pooled,
                    RecruitmentEmailService.pooledKeyChain(status.name()).get(0));
            assertEquals(GROUP_POOL, bucket.group());
        }
    }

    @Test
    void aBucketedPoolLetter_coversItsBucket_whileTheOthersFallBackToTheGenericOne() {
        RecruitmentEmailTemplate silver = template(SILVER_MEDALIST.get(0));
        RecruitmentEmailTemplate generic = template(RecruitmentEmailService.KEY_POOLED);
        TemplateCoverageResponse coverage = build(contextOf(Map.of(
                SILVER_MEDALIST.get(0), silver,
                RecruitmentEmailService.KEY_POOLED, generic)));

        MomentCoverage pooled = moment(coverage, RecruitmentEmailService.KEY_POOLED);
        assertEquals(OUTCOME_COVERED, pooled.outcome());
        assertEquals(OUTCOME_COVERED, specific(pooled, SILVER_MEDALIST.get(0)).outcome());
        MomentCoverage prospect = specific(pooled,
                RecruitmentEmailService.pooledKeyChain(CandidatePoolStatus.PROSPECT.name()).get(0));
        assertEquals(OUTCOME_FALLS_BACK, prospect.outcome());
        assertEquals(RecruitmentEmailService.KEY_POOLED, prospect.fallsBackTo());
    }

    // ---- Counts -----------------------------------------------------------

    @Test
    void aColdRollup_reportsZeroesEverywhere_ratherThanFailing() {
        // The page ships before the nightly job does, and must still render:
        // no rows counted is a true statement about a database nothing has
        // counted yet.
        RecruitmentEmailTemplate letter = template("ACKNOWLEDGEMENT");
        TemplateCoverageResponse coverage =
                build(contextOf(Map.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT, letter)));

        assertEquals(10, coverage.moments().size());
        coverage.moments().forEach(RecruitmentCommsCoverageServiceTest::assertZeroCounts);
    }

    @Test
    void rollupCountsAreReadPerMoment_andQueueCountsPerAnsweringLetter() {
        // The two count sources are keyed differently on purpose: a moment
        // fired (or did not) regardless of which letter answered it, while
        // the review queue snapshots the letter's own identity.
        RecruitmentEmailTemplate reassigned = template("POOLED_NOT_NOW");
        TemplateCoverageResponse coverage = build(context(
                Map.of("STAGE_OFFER", reassigned),
                Map.of("STAGE_OFFER", reassigned),
                Map.of("STAGE_OFFER", new Counts(12, 9)),
                Map.of("POOLED_NOT_NOW", new QueueCounts(5, 1, 3))));

        MomentCoverage moment = moment(coverage, "STAGE_OFFER");
        assertEquals(12, moment.occurredCount());
        assertEquals(9, moment.emailedCount());
        assertEquals(5, moment.queuedCount());
        assertEquals(1, moment.dismissedCount());
        assertEquals(3, moment.approvedCount());
    }

    @Test
    void aMomentNothingAnswers_carriesNoQueueHistory_evenWhenTheKeyHasOne() {
        // A queue row keyed ACKNOWLEDGEMENT belongs to the letter, not the
        // moment; with no letter answering, there is nothing to attribute it
        // to and the row must not borrow another's history.
        TemplateCoverageResponse coverage = build(context(Map.of(), Map.of(), Map.of(),
                Map.of("ACKNOWLEDGEMENT", new QueueCounts(4, 2, 2))));

        MomentCoverage moment = moment(coverage, RecruitmentEmailService.KEY_ACKNOWLEDGEMENT);
        assertEquals(OUTCOME_NONE, moment.outcome());
        assertEquals(0, moment.queuedCount());
        assertEquals(0, moment.dismissedCount());
        assertEquals(0, moment.approvedCount());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Every letter switched on, no counts anywhere — the common case. */
    private static CoverageContext contextOf(Map<String, RecruitmentEmailTemplate> answering) {
        return context(answering, answering, Map.of(), Map.of());
    }

    /**
     * @param answering  rung → the letter the mailer would resolve for it,
     *                   which is what {@code findFirstActiveByTrigger}
     *                   answers for a single key in production
     * @param anyState   rung → the letter claiming it whatever its state
     * @param rollup     moment key → windowed counts
     * @param queue      template key → review-queue history
     */
    private static CoverageContext context(Map<String, RecruitmentEmailTemplate> answering,
                                           Map<String, RecruitmentEmailTemplate> anyState,
                                           Map<String, Counts> rollup,
                                           Map<String, QueueCounts> queue) {
        return new CoverageContext(answering::get, anyState::get,
                key -> rollup.getOrDefault(key, Counts.ZERO),
                key -> queue.getOrDefault(key, QueueCounts.ZERO));
    }

    private static RecruitmentEmailTemplate template(String templateKey) {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setUuid("uuid-" + templateKey);
        template.setTemplateKey(templateKey);
        template.setName("Skabelon " + templateKey);
        template.setSubject("Emne for " + templateKey);
        template.setBody("Kære {{candidate_first_name}}");
        template.setActive(true);
        template.setAutoSend(true);
        template.setCopyRoles("");
        template.setCopyMode(RecruitmentEmailCopyMode.BCC);
        return template;
    }

    private static MomentCoverage moment(TemplateCoverageResponse coverage, String triggerKey) {
        return coverage.moments().stream()
                .filter(moment -> triggerKey.equals(moment.triggerKey()))
                .findFirst()
                .orElseGet(() -> fail("No curated moment for " + triggerKey));
    }

    private static MomentCoverage specific(MomentCoverage parent, String triggerKey) {
        return parent.specifics().stream()
                .filter(specific -> triggerKey.equals(specific.triggerKey()))
                .findFirst()
                .orElseGet(() -> fail(parent.triggerKey() + " has no specific " + triggerKey));
    }

    private static void assertZeroCounts(MomentCoverage moment) {
        assertEquals(0, moment.occurredCount(), moment.triggerKey() + " occurred");
        assertEquals(0, moment.emailedCount(), moment.triggerKey() + " emailed");
        assertEquals(0, moment.queuedCount(), moment.triggerKey() + " queued");
        assertEquals(0, moment.dismissedCount(), moment.triggerKey() + " dismissed");
        assertEquals(0, moment.approvedCount(), moment.triggerKey() + " approved");
        moment.specifics().forEach(RecruitmentCommsCoverageServiceTest::assertZeroCounts);
    }
}
