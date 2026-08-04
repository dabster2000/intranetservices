package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.testsupport.CollectionFetchPaginationGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for Hibernate warning HHH90003004
 * ("firstResult/maxResults specified with collection fetch; applying in memory").
 *
 * <p>Production emitted this warning 165 times in 24h, every occurrence attributed to
 * {@code POST /auth/token} calling {@link ApiClientRepository#findByClientIdWithScopes(String)}.
 *
 * <p>The detector and its controls live in {@link CollectionFetchPaginationGuard}; this class
 * only applies them to {@code ApiClientRepository}. The limit was never load-bearing on these
 * lookups — {@code clientId} is unique and {@code uuid} is the primary key, so at most one
 * root entity can match.
 */
@DisplayName("ApiClientRepository must not combine a collection JOIN FETCH with a limit")
class ApiClientRepositoryPaginationGuardTest {

    private static final Path REPOSITORY_SOURCE = Path.of(
            "src/main/java/dk/trustworks/intranet/security/apiclient/ApiClientRepository.java");

    @Test
    @DisplayName("ApiClientRepository has no collection fetch combined with a limit")
    void repository_hasNoCollectionFetchWithLimit() throws IOException {
        String source = readRepositorySource();

        var violations = CollectionFetchPaginationGuard.findCollectionFetchWithLimit(source);

        assertTrue(violations.isEmpty(), () -> """
                ApiClientRepository combines a collection JOIN FETCH with a limit.
                Hibernate will discard the SQL limit, load the full result set and paginate \
                in memory, logging HHH90003004 on every call.

                Terminate the query with list()/stream() instead, and take the first element. \
                Do NOT "fix" this by pushing the limit into SQL: with LIMIT 1 on a joined \
                collection the root entity comes back with exactly ONE element in that \
                collection, which for api_clients means tokens issued with one scope.

                Offending chain(s):
                %s""".formatted(String.join("\n\n", violations)));
    }

    @Test
    @DisplayName("ApiClientRepository applies no EntityManager pagination to a collection fetch")
    void repository_hasNoEntityManagerPaginationOnCollectionFetch() throws IOException {
        String source = readRepositorySource();

        // The audit-log query legitimately paginates, but it projects scalars via a
        // plain LEFT JOIN and fetches no collection, so it must not trip this guard.
        if (CollectionFetchPaginationGuard.hasJpaLimit(source)) {
            assertFalse(CollectionFetchPaginationGuard.hasCollectionFetch(source)
                            && CollectionFetchPaginationGuard.sharesMethodWithLimit(source),
                    "An EntityManager query in ApiClientRepository paginates over a collection fetch.");
        }
    }

    private static String readRepositorySource() throws IOException {
        return CollectionFetchPaginationGuard.readSource(REPOSITORY_SOURCE, "findByClientIdWithScopes");
    }
}
