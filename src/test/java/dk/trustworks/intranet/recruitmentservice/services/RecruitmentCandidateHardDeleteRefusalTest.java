package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCandidateHardDeleteService.REFUSAL_HIRED_OR_CONVERTED;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCandidateHardDeleteService.REFUSAL_SIGNED;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCandidateHardDeleteService.refusalCodeFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The hard delete's refusal rule (change C2), as a pure function of the four
 * facts it depends on.
 *
 * <p>Two of these tests assert what the rule <em>refuses</em>; the rest assert
 * what it deliberately <em>does not</em>, which is the harder half to keep
 * true. The owner's decision is that a hard delete stops on hired-or-signed
 * and on nothing else — a candidate with interviews, scheduling holds, record
 * checks, live consents, an open DSAR or posted Slack cards is deleted, and
 * the cascade plus the external cleanup are sized for that. Every extra
 * refusal someone "obviously" adds later turns the feature back into
 * anonymize, which already exists and which is not what this is for. So the
 * negative cases are pinned as hard as the positive ones.</p>
 */
class RecruitmentCandidateHardDeleteRefusalTest {

    // ---- HIRED_OR_CONVERTED -------------------------------------------------

    @Test
    void aHiredCandidate_isRefused() {
        assertEquals(REFUSAL_HIRED_OR_CONVERTED,
                refusalCodeFor(CandidateStatus.HIRED, null, 0, 0));
    }

    @Test
    void aConvertedCandidate_isRefusedEvenWhenTheStatusSaysOtherwise() {
        // converted_user_uuid welds the row to a live `user` row via
        // fk_recruitment_candidates_converted_user (V489), so the delete
        // would fail on the FK anyway — but it must fail as a stated refusal,
        // not as a 500 out of the cascade.
        assertEquals(REFUSAL_HIRED_OR_CONVERTED,
                refusalCodeFor(CandidateStatus.ACTIVE, "1c3a9b2e-user", 0, 0));
    }

    @Test
    void aBlankConvertedUserUuid_isNotAConversion() {
        assertNull(refusalCodeFor(CandidateStatus.ACTIVE, "   ", 0, 0));
    }

    // ---- SIGNED --------------------------------------------------------------

    @Test
    void aSignedDossierRevision_isRefused() {
        assertEquals(REFUSAL_SIGNED, refusalCodeFor(CandidateStatus.ACTIVE, null, 1, 0));
    }

    @Test
    void aNextSignCompletedCase_isRefused() {
        // recruitment_signing_completed_cases.candidate_uuid is NOT NULL
        // (V441:48), so unlike the Airtable row it cannot be unlinked — this
        // refusal is what keeps the cascade from having to solve that.
        assertEquals(REFUSAL_SIGNED, refusalCodeFor(CandidateStatus.ACTIVE, null, 0, 1));
    }

    @Test
    void hiredWins_whenBothWouldApply() {
        assertEquals(REFUSAL_HIRED_OR_CONVERTED,
                refusalCodeFor(CandidateStatus.HIRED, "some-user", 3, 2));
    }

    // ---- What is deliberately NOT refused -------------------------------------

    @Test
    void anOrdinaryCandidateWithNothingAttached_proceeds() {
        assertNull(refusalCodeFor(CandidateStatus.ACTIVE, null, 0, 0));
    }

    @Test
    void everyNonHiredStatus_proceeds() {
        Arrays.stream(CandidateStatus.values())
                .filter(status -> status != CandidateStatus.HIRED)
                .forEach(status -> assertNull(refusalCodeFor(status, null, 0, 0),
                        status + " must not block a hard delete — the owner's refusal list is "
                                + "hired-or-signed only. An ANONYMIZED or POOLED row created by "
                                + "mistake is exactly the case this endpoint exists for."));
    }

    @Test
    void unsignedDossierRevisions_doNotRefuse() {
        // The count passed in is already narrowed to kind = SIGNATURE with a
        // signing_case_key; a draft or a REVIEW_PDF revision never reaches it.
        assertNull(refusalCodeFor(CandidateStatus.ACTIVE, null, 0, 0));
    }

    @Test
    void theRefusalCodesAreDistinctAndMachineReadable() {
        assertNotEquals(REFUSAL_HIRED_OR_CONVERTED, REFUSAL_SIGNED);
        assertEquals("HIRED_OR_CONVERTED", REFUSAL_HIRED_OR_CONVERTED);
        assertEquals("SIGNED", REFUSAL_SIGNED);
    }

    @Test
    void everyRefusalCodeHasHumanText() {
        for (String code : new String[]{REFUSAL_HIRED_OR_CONVERTED, REFUSAL_SIGNED}) {
            String message = RecruitmentCandidateHardDeleteService.refusalMessageFor(code);
            assertNotEquals(code, message,
                    "a 409 body that repeats the code as its message tells the operator nothing");
        }
    }
}
