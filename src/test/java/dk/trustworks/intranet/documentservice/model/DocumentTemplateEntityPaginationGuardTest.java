package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.testsupport.CollectionFetchPaginationGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for Hibernate warning HHH90003004 in {@code DocumentTemplateEntity}.
 *
 * <p>{@code findByUuidWithPlaceholders} used to run three {@code LEFT JOIN FETCH} queries —
 * placeholders, defaultSigners, signingSchemas — each terminated with Panache
 * {@code firstResult()}. All three collections are {@code @OneToMany}, so a single successful
 * {@code GET /templates/{uuid}} logged the warning three times (a 404 logged it once, the
 * other two being behind a null check).
 *
 * <p>The detector and its controls live in {@link CollectionFetchPaginationGuard}; this class
 * only applies them to {@code DocumentTemplateEntity}. The limit was never load-bearing:
 * all three queries filter on {@code t.uuid}, the primary key, so at most one root entity
 * can match.
 */
@DisplayName("DocumentTemplateEntity must not combine a collection JOIN FETCH with a limit")
class DocumentTemplateEntityPaginationGuardTest {

    private static final Path ENTITY_SOURCE = Path.of(
            "src/main/java/dk/trustworks/intranet/documentservice/model/DocumentTemplateEntity.java");

    @Test
    @DisplayName("DocumentTemplateEntity has no collection fetch combined with a limit")
    void entity_hasNoCollectionFetchWithLimit() throws IOException {
        String source = CollectionFetchPaginationGuard.readSource(ENTITY_SOURCE, "findByUuidWithPlaceholders");

        var violations = CollectionFetchPaginationGuard.findCollectionFetchWithLimit(source);

        assertTrue(violations.isEmpty(), () -> """
                DocumentTemplateEntity combines a collection JOIN FETCH with a limit.
                Hibernate will discard the SQL limit, load the full result set and paginate \
                in memory, logging HHH90003004 on every call.

                Terminate the query with list() instead, and take the first element. \
                Do NOT "fix" this by pushing the limit into SQL: with LIMIT 1 on a joined \
                collection the root entity comes back with exactly ONE element in that \
                collection, which for a template means one placeholder, one default signer \
                or one signing schema.

                Offending chain(s):
                %s""".formatted(String.join("\n\n", violations)));
    }
}
