package dk.trustworks.intranet.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the staging-sync stored procedure.
 *
 * <p>Context (2026-04-24 incident): V258 defined {@code sp_sync_prod_to_staging}
 * with a typo — {@code twservices4-staging.expense} instead of
 * {@code expenses} — on the expense status-flip UPDATEs. The typo silently
 * aborted the procedure, VALIDATED production expenses leaked into staging, and
 * staging then POSTed them to e-conomics journal 15 for 49 nights before anyone
 * noticed. 117 expenses hit UP_FAILED/URLChanged.
 *
 * <p>This test scans every Flyway migration that creates
 * {@code sp_sync_prod_to_staging} and pins two invariants on the latest one
 * (the migration that will actually be in effect at runtime after all
 * migrations apply):
 *
 * <ol>
 *   <li>Never reference {@code `twservices4-staging`.`expense`} (singular).
 *   <li>Contain a {@code SIGNAL SQLSTATE '45000'} post-condition check so a
 *       future regression aborts loudly instead of silently.
 * </ol>
 *
 * <p>See docs/superpowers/plans/2026-04-24-expenses-journal15-recovery.md
 * (Prevention Layer 5).
 */
class SpSyncProdToStagingSafeguardTest {

    /** Order migrations by their V-number so we can assert on the latest one. */
    private static final Comparator<Path> BY_VERSION = (a, b) -> {
        Pattern p = Pattern.compile("^V(\\d+)__");
        Matcher ma = p.matcher(a.getFileName().toString());
        Matcher mb = p.matcher(b.getFileName().toString());
        if (!ma.find() || !mb.find()) return a.getFileName().compareTo(b.getFileName());
        return Integer.compare(Integer.parseInt(ma.group(1)), Integer.parseInt(mb.group(1)));
    };

    private static final Path MIGRATIONS_DIR =
            Path.of("src/main/resources/db/migration");

    /** Cached once per class so we don't re-scan every migration file per @Test. */
    private static Path latestMigration;

    @BeforeAll
    static void locateLatestMigration() throws IOException {
        try (Stream<Path> stream = Files.list(MIGRATIONS_DIR)) {
            List<Path> candidates = stream
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(SpSyncProdToStagingSafeguardTest::createsSpSyncProdToStaging)
                    .sorted(BY_VERSION)
                    .collect(Collectors.toList());
            latestMigration = candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
        }
    }

    @Test
    void latest_sp_sync_prod_to_staging_uses_expenses_plural_not_singular() throws IOException {
        assertNotNull(latestMigration, "no migration creates sp_sync_prod_to_staging");

        String body = Files.readString(latestMigration);

        boolean hasSingularTypo = body.contains("`twservices4-staging`.`expense` SET");
        assertFalse(hasSingularTypo,
                latestMigration.getFileName() + " references `twservices4-staging`.`expense` "
                        + "(singular). The table is `expenses` (plural). This is the exact typo "
                        + "that caused the 2026-04-24 staging-sync incident.");

        long pluralCount = Pattern.compile("`twservices4-staging`\\.`expenses` SET")
                .matcher(body).results().count();
        assertTrue(pluralCount >= 2,
                latestMigration.getFileName() + " should contain at least 2 UPDATE statements on "
                        + "`twservices4-staging`.`expenses`, found " + pluralCount);
    }

    @Test
    void latest_sp_sync_prod_to_staging_has_expense_status_post_condition() throws IOException {
        assertNotNull(latestMigration, "no migration creates sp_sync_prod_to_staging");

        String body = Files.readString(latestMigration);

        assertTrue(body.contains("SIGNAL SQLSTATE '45000'"),
                latestMigration.getFileName() + " must SIGNAL SQLSTATE '45000' as a post-condition "
                        + "for the expense status-flip safeguard.");

        boolean checksExpenses = Pattern.compile(
                "FROM\\s+`twservices4-staging`\\.`expenses`", Pattern.CASE_INSENSITIVE)
                .matcher(body).find();
        assertTrue(checksExpenses,
                latestMigration.getFileName() + " SIGNAL guard must query "
                        + "`twservices4-staging`.`expenses`.");
    }

    private static boolean createsSpSyncProdToStaging(Path path) {
        String body;
        try {
            body = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return body.contains("CREATE PROCEDURE sp_sync_prod_to_staging")
                || body.contains("CREATE PROCEDURE `sp_sync_prod_to_staging`")
                || body.contains("CREATE DEFINER=`admin`@`%` PROCEDURE sp_sync_prod_to_staging")
                || body.contains("CREATE DEFINER=`admin`@`%` PROCEDURE `sp_sync_prod_to_staging`");
    }
}
