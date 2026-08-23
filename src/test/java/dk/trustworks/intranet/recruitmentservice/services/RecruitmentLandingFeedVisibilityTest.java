package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventFixtures;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentLandingService.dossierFeedRowVisible;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentLandingService.feedRowVisible;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast-tier pin of the landing feed's row-visibility rule
 * ({@code RecruitmentLandingService.feedRowVisible}) — added 2026-08-23
 * after Hans's first assistant sign-in on staging showed the ENTIRE
 * candidate intake in "Recent activity": candidate-level events carry no
 * position, so they slipped past the position filter for every viewer.
 * The landing API test is a {@code @QuarkusTest} outside the CI deploy
 * gate; this file is what actually blocks a regression.
 */
class RecruitmentLandingFeedVisibilityTest {

    private static final String IN_PRACTICE_POSITION = "pos-in";
    private static final String OTHER_POSITION = "pos-out";
    private static final String REACHABLE_CANDIDATE = "cand-reachable";
    private static final String FOREIGN_CANDIDATE = "cand-foreign";
    private static final String PARTNER_CANDIDATE = "cand-partner";

    private static final Set<String> VISIBLE_POSITIONS = Set.of(IN_PRACTICE_POSITION);
    private static final Set<String> REACHABLE = Set.of(REACHABLE_CANDIDATE);
    private static final Set<String> PARTNER_ONLY = Set.of(PARTNER_CANDIDATE);

    private static RecruitmentEvent event(RecruitmentEventType type,
                                          String candidateUuid, String positionUuid) {
        return RecruitmentEventFixtures.detachedEvent(
                type, RecruitmentEventVisibility.NORMAL, candidateUuid, positionUuid);
    }

    /** The leak Hans found: a scoped viewer and a position-less candidate event. */
    @Test
    void candidateOnlyEvents_hideFromScopedViewers_unlessTheCandidateIsReachable() {
        RecruitmentEvent foreignCreated = event(
                RecruitmentEventType.CANDIDATE_CREATED, FOREIGN_CANDIDATE, null);
        assertFalse(feedRowVisible(foreignCreated, false, false,
                        VISIBLE_POSITIONS, REACHABLE, PARTNER_ONLY),
                "an assistant/involved viewer must not see a foreign candidate appear");

        RecruitmentEvent reachableCreated = event(
                RecruitmentEventType.CANDIDATE_CREATED, REACHABLE_CANDIDATE, null);
        assertTrue(feedRowVisible(reachableCreated, false, false,
                        VISIBLE_POSITIONS, REACHABLE, PARTNER_ONLY),
                "…but their own positions' applicants still show");
    }

    @Test
    void candidateOnlyEvents_showWholesaleToTheProfileReadTier() {
        RecruitmentEvent foreignCreated = event(
                RecruitmentEventType.CANDIDATE_CREATED, FOREIGN_CANDIDATE, null);
        assertTrue(feedRowVisible(foreignCreated, true, true,
                        VISIBLE_POSITIONS, Set.of(), PARTNER_ONLY),
                "ADMIN/HR/RECRUITMENT/TEAMLEAD read the whole candidate population");
    }

    @Test
    void referralFacts_areInboxTierOnly() {
        RecruitmentEvent referral = event(
                RecruitmentEventType.REFERRAL_SUBMITTED, null, null);
        assertFalse(feedRowVisible(referral, false, false,
                        VISIBLE_POSITIONS, REACHABLE, PARTNER_ONLY),
                "raw referral facts never reach an assistant or involved-only viewer");
        assertTrue(feedRowVisible(referral, true, true,
                VISIBLE_POSITIONS, Set.of(), PARTNER_ONLY));
    }

    @Test
    void positionEvents_followTheVisibleSlice_forEveryViewer() {
        RecruitmentEvent outside = event(RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                FOREIGN_CANDIDATE, OTHER_POSITION);
        assertFalse(feedRowVisible(outside, true, true,
                        VISIBLE_POSITIONS, Set.of(), PARTNER_ONLY),
                "even a wholesale reader's feed follows the position slice passed in");

        RecruitmentEvent inside = event(RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                REACHABLE_CANDIDATE, IN_PRACTICE_POSITION);
        assertTrue(feedRowVisible(inside, false, false,
                VISIBLE_POSITIONS, REACHABLE, PARTNER_ONLY));
    }

    @Test
    void partnerHardFilter_dropsEveryEventOfAPartnerOnlyCandidate() {
        RecruitmentEvent pooled = event(
                RecruitmentEventType.CANDIDATE_POOLED, PARTNER_CANDIDATE, null);
        assertFalse(feedRowVisible(pooled, true, true,
                VISIBLE_POSITIONS, Set.of(), PARTNER_ONLY));
    }

    @Test
    void circleEvents_needAVisiblePosition_andFailClosedWithoutOne() {
        RecruitmentEvent positionless = RecruitmentEventFixtures.detachedEvent(
                RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                RecruitmentEventVisibility.CIRCLE, REACHABLE_CANDIDATE, null);
        assertFalse(feedRowVisible(positionless, true, true,
                VISIBLE_POSITIONS, Set.of(), PARTNER_ONLY));

        RecruitmentEvent inCircle = RecruitmentEventFixtures.detachedEvent(
                RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                RecruitmentEventVisibility.CIRCLE, REACHABLE_CANDIDATE,
                IN_PRACTICE_POSITION);
        assertTrue(feedRowVisible(inCircle, true, true,
                VISIBLE_POSITIONS, Set.of(), PARTNER_ONLY));
    }

    @Test
    void signingCompleted_requiresCandidateScopedDossierCapability() {
        RecruitmentEvent completed = event(
                RecruitmentEventType.SIGNING_COMPLETED,
                REACHABLE_CANDIDATE, IN_PRACTICE_POSITION);

        assertFalse(dossierFeedRowVisible(completed, Set.of()),
                "an assistant/profile reader must not learn that a contract was signed");
        assertTrue(dossierFeedRowVisible(completed, Set.of(REACHABLE_CANDIDATE)),
                "an eligible named TEAMLEAD or HR/admin keeps signing activity");

        RecruitmentEvent malformed = event(
                RecruitmentEventType.SIGNING_COMPLETED, null, IN_PRACTICE_POSITION);
        assertFalse(dossierFeedRowVisible(malformed, Set.of(REACHABLE_CANDIDATE)),
                "a signing event without a candidate must fail closed");
    }

    @Test
    void offerOpened_remainsOrdinaryProgressWithoutDossierCapability() {
        RecruitmentEvent offerOpened = event(
                RecruitmentEventType.OFFER_OPENED,
                REACHABLE_CANDIDATE, IN_PRACTICE_POSITION);
        assertTrue(dossierFeedRowVisible(offerOpened, Set.of()));
    }
}
