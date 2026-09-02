package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * The four rules an inbound {@code triggerKey} has to satisfy, applied
 * identically on create and update because — unlike the key — this field is
 * meant to move.
 * <ol>
 *   <li>null/blank is "unassigned", and always allowed</li>
 *   <li>anything else must be one of the reserved pipeline triggers</li>
 *   <li>it must not already be answered by another letter</li>
 *   <li>a job-owned letter neither claims a moment nor may be claimed</li>
 * </ol>
 * Rules 1, 2 and 4 never reach the database; only the collision check does,
 * and its query is mocked.
 */
class RecruitmentEmailTriggerAssignmentTest {

    /** The one query the collision check is allowed to issue. */
    private static final String COLLISION_QUERY =
            "triggerKey = ?1 or (templateKey = ?1 and triggerKey is null)";

    private static final String OWN_UUID = "letter-uuid";
    private static final String OWN_KEY = "SOME_LETTER";

    // ---- Rule 1: unassigned ------------------------------------------------

    @Test
    void rule1_nullOrBlank_isUnassigned_andAlwaysAllowed() {
        // This is the state every row predating V562 is in, and it is also
        // how a letter is disconnected from a moment again — so it must be
        // reachable without so much as a query.
        assertNull(RecruitmentEmailService.resolveTriggerAssignment(OWN_UUID, OWN_KEY, null));
        assertNull(RecruitmentEmailService.resolveTriggerAssignment(OWN_UUID, OWN_KEY, ""));
        assertNull(RecruitmentEmailService.resolveTriggerAssignment(OWN_UUID, OWN_KEY, "   "));
    }

    // ---- Rule 2: a closed list --------------------------------------------

    @Test
    void rule2_anUnknownKey_isRejected() {
        BusinessRuleViolation ex = assertThrows(BusinessRuleViolation.class,
                () -> RecruitmentEmailService.resolveTriggerAssignment(
                        OWN_UUID, OWN_KEY, "NEWSLETTER"));
        assertEquals("triggerKey must be one of the known pipeline triggers", ex.getMessage());
    }

    @Test
    void rule2_aReasonOrBucketNoEventCanEverProduce_isRejected() {
        // The rejection and pool families are derived from their enums, not
        // from a prefix test — a reason code nothing can emit is not a
        // moment, and a letter assigned to one would wait forever.
        assertThrows(BusinessRuleViolation.class,
                () -> RecruitmentEmailService.resolveTriggerAssignment(
                        OWN_UUID, OWN_KEY, "REJECTION_BECAUSE_I_SAID_SO"));
        assertThrows(BusinessRuleViolation.class,
                () -> RecruitmentEmailService.resolveTriggerAssignment(
                        OWN_UUID, OWN_KEY, "POOLED_MAYBE"));
    }

    @Test
    void rule2_theStageFamilyIsStillPrefixMatched() {
        // Documenting the one loose edge rather than pretending otherwise:
        // isTriggerKey accepts any STAGE_* string, so STAGE_BANANA passes
        // this guard. The closed list that keeps it unreachable is the
        // frontend picker, not this rule.
        try (MockedStatic<PanacheEntityBase> panache = noOtherClaimant("STAGE_BANANA")) {
            assertEquals("STAGE_BANANA", RecruitmentEmailService.resolveTriggerAssignment(
                    OWN_UUID, OWN_KEY, "STAGE_BANANA"));
        }
    }

    @Test
    void rule2_aKnownTrigger_isAcceptedAndNormalised() {
        try (MockedStatic<PanacheEntityBase> panache = noOtherClaimant("STAGE_OFFER")) {
            assertEquals("STAGE_OFFER", RecruitmentEmailService.resolveTriggerAssignment(
                    OWN_UUID, OWN_KEY, "  stage_offer  "));
        }
    }

    // ---- Rule 3: one letter per moment ------------------------------------

    @Test
    void rule3_aMomentAnotherLetterAlreadyAnswers_isRejected_byName() {
        RecruitmentEmailTemplate incumbent = template("other-uuid", "STAGE_OFFER", null,
                "Tilbud – vi vil gerne have dig med");

        try (MockedStatic<PanacheEntityBase> panache = claimants("STAGE_OFFER", incumbent)) {
            BusinessRuleViolation ex = assertThrows(BusinessRuleViolation.class,
                    () -> RecruitmentEmailService.resolveTriggerAssignment(
                            OWN_UUID, OWN_KEY, "STAGE_OFFER"));
            // The name, not the uuid: the recruiter has to be able to go and
            // find the letter that is already in the way.
            assertEquals("'STAGE_OFFER' is already answered by template "
                    + "'Tilbud – vi vil gerne have dig med'", ex.getMessage());
        }
    }

    @Test
    void rule3_theIncumbentCanBeAnUnassignedRowSittingOnItsOwnKey() {
        // Judged on the EFFECTIVE trigger. A row that has claimed nothing but
        // whose key is STAGE_OFFER is still the letter the mailer would pick,
        // so it is still in the way.
        RecruitmentEmailTemplate legacyIncumbent = template("legacy-uuid", "STAGE_OFFER", null,
                "Tilbud");

        try (MockedStatic<PanacheEntityBase> panache = claimants("STAGE_OFFER", legacyIncumbent)) {
            assertThrows(BusinessRuleViolation.class,
                    () -> RecruitmentEmailService.resolveTriggerAssignment(
                            OWN_UUID, OWN_KEY, "STAGE_OFFER"));
        }
    }

    @Test
    void rule3_theRowBeingEditedIsNotItsOwnRival() {
        // Re-saving a letter without touching its assignment must not read as
        // a collision with itself.
        RecruitmentEmailTemplate self = template(OWN_UUID, OWN_KEY, "STAGE_OFFER", "Tilbud");

        try (MockedStatic<PanacheEntityBase> panache = claimants("STAGE_OFFER", self)) {
            assertEquals("STAGE_OFFER", RecruitmentEmailService.resolveTriggerAssignment(
                    OWN_UUID, OWN_KEY, "STAGE_OFFER"));
        }
    }

    // ---- Rule 4: job-owned letters ----------------------------------------

    @Test
    void rule4_aJobOwnedKey_cannotBeAssignedAsATrigger() {
        // CONSENT_RENEWAL's {{consent_link}} is minted by the GDPR sweep and
        // by nothing else; ART14_NOTICE is a legal obligation its own screen
        // fires. Neither is a moment the pipeline can point a letter at.
        for (String key : new String[]{
                RecruitmentGdprService.KEY_CONSENT_RENEWAL,
                RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION,
                RecruitmentEmailService.KEY_INTERVIEW_CANDIDATE_INVITATION,
                RecruitmentEmailService.KEY_ART14_NOTICE}) {
            BusinessRuleViolation ex = assertThrows(BusinessRuleViolation.class,
                    () -> RecruitmentEmailService.resolveTriggerAssignment(OWN_UUID, OWN_KEY, key),
                    key + " must not be assignable as a trigger");
            assertTrue(ex.getMessage().contains("owned end to end by its job"),
                    "message must say why, not just no: " + ex.getMessage());
        }
    }

    @Test
    void rule4_aJobOwnedLetter_cannotBeGivenATrigger() {
        // The other direction. Its job looks the row up by the literal key,
        // so an assignment would be read by nothing and would only lie to
        // whoever opened the screen next.
        for (String ownKey : new String[]{
                RecruitmentGdprService.KEY_CONSENT_RENEWAL,
                RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION,
                RecruitmentEmailService.KEY_INTERVIEW_CANDIDATE_INVITATION,
                RecruitmentEmailService.KEY_ART14_NOTICE}) {
            assertThrows(BusinessRuleViolation.class,
                    () -> RecruitmentEmailService.resolveTriggerAssignment(
                            OWN_UUID, ownKey, "STAGE_OFFER"),
                    ownKey + " must not be re-pointed at a pipeline moment");
        }
    }

    @Test
    void rule4_aJobOwnedLetterMayStillBeSavedUnassigned() {
        // Editing the consent-renewal wording must not become impossible just
        // because it can never carry a trigger.
        assertNull(RecruitmentEmailService.resolveTriggerAssignment(
                OWN_UUID, RecruitmentGdprService.KEY_CONSENT_RENEWAL, null));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static MockedStatic<PanacheEntityBase> noOtherClaimant(String triggerKey) {
        return claimants(triggerKey);
    }

    private static MockedStatic<PanacheEntityBase> claimants(String triggerKey,
                                                             RecruitmentEmailTemplate... rows) {
        MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
        panache.when(() -> PanacheEntityBase.list(COLLISION_QUERY, triggerKey))
                .thenReturn(List.of(rows));
        return panache;
    }

    private static RecruitmentEmailTemplate template(String uuid, String templateKey,
                                                     String triggerKey, String name) {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setUuid(uuid);
        template.setTemplateKey(templateKey);
        template.setTriggerKey(triggerKey);
        template.setName(name);
        template.setActive(true);
        return template;
    }
}
