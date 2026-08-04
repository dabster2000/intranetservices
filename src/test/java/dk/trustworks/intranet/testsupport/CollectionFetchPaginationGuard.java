package dk.trustworks.intranet.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level detector for the code shape behind Hibernate warning HHH90003004
 * ("firstResult/maxResults specified with collection fetch; applying in memory").
 *
 * <p>Mechanism, verified against the exact deployed dependency versions:
 * <ul>
 *   <li>Panache 3.36.3 {@code CommonPanacheQueryImpl.firstResult()} calls
 *       {@code createQuery(1)}, which calls {@code setMaxResults(1)} unconditionally.
 *       {@code list()} / {@code stream()} / {@code singleResult()} take the
 *       {@code createQuery()} path, which sets a limit only when {@code page} or
 *       {@code range} is non-null.</li>
 *   <li>Hibernate 7.3.7 {@code SqmQueryImpl.executionContextForDoList} emits the warning
 *       under exactly {@code if (hasLimit && containsCollectionFetches)}. {@code DISTINCT}
 *       is <em>not</em> part of that gate — it only drives {@code needsDistinct},
 *       which controls root de-duplication.</li>
 * </ul>
 *
 * <p>So the invariant these guards protect is narrow and precise: a query that
 * {@code JOIN FETCH}es a collection must not also apply a limit.
 *
 * <p>This is a source-level detector because the database is read-only in this
 * environment and a live {@code @QuarkusTest} cannot boot. Its own behaviour is
 * covered by {@link CollectionFetchPaginationGuardTest}, which carries positive and
 * negative controls so callers fail loudly rather than passing vacuously if the
 * detector ever breaks.
 */
public final class CollectionFetchPaginationGuard {

    /**
     * A Panache query chain: from {@code find(} up to the terminating semicolon.
     * These queries are single-statement chains, so this brackets each query with
     * its terminal operation.
     */
    private static final Pattern PANACHE_CHAIN = Pattern.compile("\\bfind\\s*\\(.*?;", Pattern.DOTALL);

    /** Fetching a collection. Case-insensitive: JPQL keywords are written both ways. */
    private static final Pattern COLLECTION_FETCH = Pattern.compile("JOIN\\s+FETCH", Pattern.CASE_INSENSITIVE);

    /**
     * Terminal operations that cause Hibernate to see a limit.
     * {@code firstResult}/{@code firstResultOptional} set maxResults(1);
     * {@code page}/{@code range} set both firstResult and maxResults.
     * Deliberately excludes {@code list}, {@code stream}, {@code singleResult},
     * {@code singleResultOptional} and {@code count} — none of those set a limit.
     */
    private static final Pattern LIMITING_TERMINAL = Pattern.compile(
            "\\.\\s*(firstResult|firstResultOptional|page|range)\\s*\\(");

    /** EntityManager-level pagination, the non-Panache route to the same warning. */
    private static final Pattern JPA_LIMIT = Pattern.compile("\\.\\s*(setMaxResults|setFirstResult)\\s*\\(");

    private CollectionFetchPaginationGuard() {
    }

    /**
     * Returns each Panache query chain that fetches a collection and also applies a limit.
     */
    public static List<String> findCollectionFetchWithLimit(String source) {
        var violations = new ArrayList<String>();
        Matcher m = PANACHE_CHAIN.matcher(source);
        while (m.find()) {
            String chain = m.group();
            if (COLLECTION_FETCH.matcher(chain).find() && LIMITING_TERMINAL.matcher(chain).find()) {
                violations.add(chain.strip());
            }
        }
        return violations;
    }

    /** True when the source applies EntityManager-level pagination anywhere. */
    public static boolean hasJpaLimit(String source) {
        return JPA_LIMIT.matcher(source).find();
    }

    /** True when the source fetches a collection anywhere. */
    public static boolean hasCollectionFetch(String source) {
        return COLLECTION_FETCH.matcher(source).find();
    }

    /**
     * True when a single method body contains both a collection fetch and JPA pagination.
     */
    public static boolean sharesMethodWithLimit(String source) {
        for (String body : source.split("\n    (public|private|protected) ")) {
            if (COLLECTION_FETCH.matcher(body).find() && JPA_LIMIT.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a source file the guard depends on, failing loudly if the file has moved
     * or the guarded symbol has been renamed — otherwise the guard would pass without
     * checking anything.
     *
     * @param source         path relative to the module root
     * @param requiredSymbol a declaration the guard is written against
     */
    public static String readSource(Path source, String requiredSymbol) throws IOException {
        if (!Files.exists(source)) {
            throw new IllegalStateException("Cannot locate " + source.toAbsolutePath()
                    + " — this guard would otherwise pass without checking anything. "
                    + "Working directory is " + Path.of("").toAbsolutePath());
        }
        String text = Files.readString(source);
        if (!text.contains(requiredSymbol)) {
            throw new IllegalStateException(source + " no longer declares " + requiredSymbol
                    + " — update the guard that depends on it.");
        }
        return text;
    }
}
