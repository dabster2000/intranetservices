package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher;
import dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher.DirectoryUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the matching half of {@link PublicApplyReferrerService}
 * — the decision that turns an anonymous applicant's free text into either
 * a link to a real employee or a preserved external name.
 * <p>
 * The AI tier is switched off here on purpose: these cases pin the
 * deterministic behaviour the public endpoint depends on being SAFE, and
 * the AI tier's own contract (names only, directory never exposed) is
 * already covered by {@code AirtableReferrerMatcherTest}.
 */
class PublicApplyReferrerServiceTest {

    private static final String ANNE = "11111111-1111-1111-1111-111111111111";
    private static final String LARS = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_LARS = "33333333-3333-3333-3333-333333333333";

    private static final List<DirectoryUser> DIRECTORY = List.of(
            new DirectoryUser(ANNE, "Anne", "Rohde Jensen"),
            new DirectoryUser(LARS, "Lars", "Holm"),
            new DirectoryUser(OTHER_LARS, "Lars", "Holm"));

    private PublicApplyReferrerService service;

    @BeforeEach
    void setUp() {
        service = new PublicApplyReferrerService();
        service.referrerMatcher = new AirtableReferrerMatcher();
        // Deterministic tiers only — no OpenAI in a fast-tier test.
        service.aiExtractionEnabled = false;
    }

    @Test
    void exactName_linksTheEmployee() {
        PublicApplyReferrerService.ReferrerClaim claim = service.match("Anne Rohde Jensen", DIRECTORY);

        assertTrue(claim.isMatched());
        assertEquals(ANNE, claim.matchedUserUuid());
        assertEquals("name", claim.matchMethod());
        assertEquals("Anne Rohde Jensen", claim.claimedName(),
                "the applicant's own words are kept on the claim for the audit event");
    }

    @Test
    void middleNameOmitted_stillLinks() {
        // The token tier: "Anne Jensen" → "Anne Rohde Jensen". Applicants
        // write the name they know, not the one in the HR system.
        PublicApplyReferrerService.ReferrerClaim claim = service.match("anne  jensen", DIRECTORY);

        assertTrue(claim.isMatched());
        assertEquals(ANNE, claim.matchedUserUuid());
    }

    @Test
    void ambiguousName_neverGuesses_andIsKeptAsAnExternalName() {
        // Two Lars Holms. Naming the wrong colleague on an unverified claim
        // is worse than not linking at all.
        PublicApplyReferrerService.ReferrerClaim claim = service.match("Lars Holm", DIRECTORY);

        assertFalse(claim.isMatched());
        assertNull(claim.matchedUserUuid());
        assertNull(claim.matchMethod());
        assertTrue(claim.isPresent());
        assertEquals("Lars Holm", claim.claimedName(),
                "an unmatched claim is preserved verbatim as the external referrer name");
    }

    @Test
    void nonEmployee_isKeptAsAnExternalName() {
        // Real data: the person an applicant knows is often not an employee.
        PublicApplyReferrerService.ReferrerClaim claim =
                service.match("Mette fra mit netværk", DIRECTORY);

        assertFalse(claim.isMatched());
        assertEquals("Mette fra mit netværk", claim.claimedName());
    }

    @Test
    void singleToken_isTooWeakASignal() {
        // "Lars" must not resolve to a person on a public, unauthenticated
        // endpoint — this is the shape an attacker would probe with.
        assertFalse(service.match("Lars", DIRECTORY).isMatched());
    }

    @Test
    void none_meansNothingWasClaimed() {
        assertFalse(PublicApplyReferrerService.ReferrerClaim.NONE.isPresent());
        assertFalse(PublicApplyReferrerService.ReferrerClaim.NONE.isMatched());
    }
}
