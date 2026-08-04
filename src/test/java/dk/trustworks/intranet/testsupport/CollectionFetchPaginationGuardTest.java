package dk.trustworks.intranet.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.testsupport.CollectionFetchPaginationGuard.findCollectionFetchWithLimit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Controls for {@link CollectionFetchPaginationGuard}: proves the detector actually
 * detects, and does not fire on the shapes we deliberately keep. Without these, every
 * guard built on the detector could pass vacuously.
 */
@DisplayName("Collection-fetch-with-limit detector")
class CollectionFetchPaginationGuardTest {

    @Test
    @DisplayName("flags the text-block pattern that was live in production (POST /auth/token)")
    void flagsTheOriginalApiClientCode() {
        // Verbatim shape of the code that emitted HHH90003004 165x/day.
        String regressed = """
                public Optional<ApiClient> findByClientIdWithScopes(String clientId) {
                    return find(""\"
                            SELECT DISTINCT c FROM ApiClient c
                            LEFT JOIN FETCH c.scopes
                            WHERE c.clientId = ?1
                            ""\", clientId).firstResultOptional();
                }
                """;

        var violations = findCollectionFetchWithLimit(regressed);
        assertEquals(1, violations.size(),
                "Detector failed to flag the known-bad pattern; guards built on it would pass vacuously.");
        assertTrue(violations.get(0).contains("firstResultOptional"));
    }

    @Test
    @DisplayName("flags the concatenated-string pattern that was live in GET /templates/{uuid}")
    void flagsTheOriginalDocumentTemplateCode() {
        // Verbatim shape of the three chains in DocumentTemplateEntity.findByUuidWithPlaceholders:
        // JPQL assembled by string concatenation rather than a text block, one warning each.
        String regressed = """
                public static DocumentTemplateEntity findByUuidWithPlaceholders(String uuid) {
                    DocumentTemplateEntity template = find(
                        "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                        "LEFT JOIN FETCH t.placeholders " +
                        "WHERE t.uuid = ?1 " +
                        "ORDER BY t.uuid",
                        uuid
                    ).firstResult();
                    if (template != null) {
                        find(
                            "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                            "LEFT JOIN FETCH t.defaultSigners " +
                            "WHERE t.uuid = ?1",
                            uuid
                        ).firstResult();
                        find(
                            "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                            "LEFT JOIN FETCH t.signingSchemas " +
                            "WHERE t.uuid = ?1",
                            uuid
                        ).firstResult();
                    }
                    return template;
                }
                """;

        var violations = findCollectionFetchWithLimit(regressed);
        assertEquals(3, violations.size(),
                "Detector must flag all three chains — one HHH90003004 warning each per request.");
        assertTrue(violations.stream().allMatch(v -> v.contains("firstResult()")));
    }

    @Test
    @DisplayName("flags page() and range() as limiting terminals too")
    void flagsPageAndRange() {
        assertEquals(1, findCollectionFetchWithLimit(
                "find(\"SELECT c FROM C c LEFT JOIN FETCH c.kids\").page(0, 20).list();").size());
        assertEquals(1, findCollectionFetchWithLimit(
                "find(\"SELECT c FROM C c JOIN FETCH c.kids\").range(0, 9).list();").size());
    }

    /**
     * Negative control: the detector must not fire on the shapes we deliberately
     * kept, or the guards would be unsatisfiable and invite a bad "fix".
     */
    @Test
    @DisplayName("ignores collection fetch without a limit, and limits without a collection fetch")
    void doesNotFlagSafeShapes() {
        assertTrue(findCollectionFetchWithLimit(
                        "find(\"SELECT DISTINCT c FROM C c LEFT JOIN FETCH c.kids\").list();").isEmpty(),
                "A collection fetch with no limit does not trigger HHH90003004.");
        assertTrue(findCollectionFetchWithLimit(
                        "find(\"clientId\", clientId).firstResultOptional();").isEmpty(),
                "A limit with no collection fetch pushes LIMIT into SQL correctly.");
        assertTrue(findCollectionFetchWithLimit(
                        "find(\"SELECT c FROM C c LEFT JOIN FETCH c.kids\").singleResultOptional();").isEmpty(),
                "singleResult/singleResultOptional do not set maxResults.");
    }
}
