package dk.trustworks.intranet.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 7 hard block for anonymous flows: page_registry rows inside these trees
 * can never be permission-gated from the admin console. Over-gating any of them
 * breaks candidate apply, consent links, the guest kiosk, onboarding upload or the
 * mobile expenses PWA outright for external users.
 */
class AnonymousRouteTreesTest {

    @Test
    void everyAnonymousFlowRootIsBlocked() {
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/apply"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/consent"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/guest"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/onboarding"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/login"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/expenses/mobile"));
    }

    @Test
    void subRoutesOfAnonymousTreesAreBlocked() {
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/apply/senior-consultant"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/consent/abc-123"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/onboarding/upload"));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/expenses/mobile/capture"));
    }

    @Test
    void prefixMatchingIsSegmentAware() {
        // "/applying" must not match "/apply"; "/expenses" is a protected page, not the PWA.
        assertFalse(AnonymousRouteTrees.isAnonymousTree("/applying"));
        assertFalse(AnonymousRouteTrees.isAnonymousTree("/expenses"));
        assertFalse(AnonymousRouteTrees.isAnonymousTree("/expenses/review"));
        assertFalse(AnonymousRouteTrees.isAnonymousTree("/guestbook"));
    }

    @Test
    void ordinaryProtectedRoutesAreNotBlocked() {
        assertFalse(AnonymousRouteTrees.isAnonymousTree("/dashboard"));
        assertFalse(AnonymousRouteTrees.isAnonymousTree("/invoice"));
        assertFalse(AnonymousRouteTrees.isAnonymousTree(null));
        assertFalse(AnonymousRouteTrees.isAnonymousTree(""));
    }

    @Test
    void caseAndTrailingWhitespaceDoNotBypassTheBlock() {
        assertTrue(AnonymousRouteTrees.isAnonymousTree(" /Apply "));
        assertTrue(AnonymousRouteTrees.isAnonymousTree("/EXPENSES/MOBILE/capture"));
    }
}
