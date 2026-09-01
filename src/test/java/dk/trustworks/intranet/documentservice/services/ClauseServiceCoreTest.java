package dk.trustworks.intranet.documentservice.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the clause library's two hard guarantees
 * (template-clauses spec §4.3, Phase 2 exit gate): the publish-time
 * tag↔parameter diff that blocks the poi-tl silent-discard trap, and the
 * cross-key collision detection behind template links.
 */
class ClauseServiceCoreTest {

    // ---- Tag ↔ parameter diff (publish gate) -----------------------------------

    @Test
    void diffTags_matchingSetsProduceEmptyDiff() {
        ClauseService.TagDiff diff = ClauseService.diffTags(
                Set.of("GB_AMOUNT", "GB_PERIOD_END"),
                Set.of("GB_AMOUNT", "GB_PERIOD_END"));
        assertTrue(diff.isEmpty());
    }

    @Test
    void diffTags_undeclaredTagBlocksPublish() {
        // A tag in the fragment with no declared parameter would be silently
        // deleted by poi-tl's DiscardHandler — the diff must name it.
        ClauseService.TagDiff diff = ClauseService.diffTags(
                Set.of("GB_AMOUNT", "GB_PERIOD_END"),
                Set.of("GB_AMOUNT"));
        assertEquals(Set.of("GB_PERIOD_END"), diff.tagsWithoutParameter());
        assertTrue(diff.parametersWithoutTag().isEmpty());
        assertTrue(diff.describe("GARANTIBONUS").contains("GB_PERIOD_END"));
    }

    @Test
    void diffTags_parameterMissingFromFragmentBlocksPublish() {
        ClauseService.TagDiff diff = ClauseService.diffTags(
                Set.of("GB_AMOUNT"),
                Set.of("GB_AMOUNT", "GB_PERIOD_END"));
        assertEquals(Set.of("GB_PERIOD_END"), diff.parametersWithoutTag());
        assertTrue(diff.tagsWithoutParameter().isEmpty());
    }

    @Test
    void diffTags_anchorAndCompanyFactTagsAreStructural() {
        // The CLAUSES anchor and COMPANY_* fact tags resolve server-side —
        // they are never clause parameters and must not block publishing.
        ClauseService.TagDiff diff = ClauseService.diffTags(
                Set.of("GB_AMOUNT", "CLAUSES", "COMPANY_SHORT_NAME", "COMPANY_NAME_GENITIVE"),
                Set.of("GB_AMOUNT"));
        assertTrue(diff.isEmpty());
    }

    // ---- Cross-key collision validation (link gate) ----------------------------

    @Test
    void findKeyCollisions_cleanPrefixedKeysPass() {
        List<String> collisions = ClauseService.findKeyCollisions(
                Set.of("EMPLOYEE_NAME", "START_DATE"),
                List.of(
                        new ClauseService.ClauseKeys("GARANTIBONUS", Set.of("GB_AMOUNT", "GB_PERIOD_END")),
                        new ClauseService.ClauseKeys("ANCIENNITET", Set.of("ANC_YEARS"))));
        assertTrue(collisions.isEmpty());
    }

    @Test
    void findKeyCollisions_clauseKeyCollidingWithTemplateIsNamed() {
        List<String> collisions = ClauseService.findKeyCollisions(
                Set.of("EMPLOYEE_NAME", "START_DATE"),
                List.of(new ClauseService.ClauseKeys("GARANTIBONUS", Set.of("START_DATE", "GB_AMOUNT"))));
        assertEquals(List.of("START_DATE"), collisions);
    }

    @Test
    void findKeyCollisions_twoClausesSharingAKeyCollide() {
        List<String> collisions = ClauseService.findKeyCollisions(
                Set.of(),
                List.of(
                        new ClauseService.ClauseKeys("A", Set.of("AMOUNT")),
                        new ClauseService.ClauseKeys("B", Set.of("AMOUNT"))));
        assertEquals(List.of("AMOUNT"), collisions);
    }

    // ---- Clause key normalization ----------------------------------------------

    @Test
    void normalizeClauseKey_uppercasesAndTrims() {
        assertEquals("GARANTIBONUS", ClauseService.normalizeClauseKey("  garantibonus "));
    }

    @Test
    void normalizeClauseKey_rejectsInvalidCharacters() {
        assertThrows(WebApplicationException.class, () -> ClauseService.normalizeClauseKey("garanti-bonus"));
        assertThrows(WebApplicationException.class, () -> ClauseService.normalizeClauseKey(""));
        assertThrows(WebApplicationException.class, () -> ClauseService.normalizeClauseKey(null));
    }
}
