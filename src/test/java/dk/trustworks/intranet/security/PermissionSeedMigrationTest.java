package dk.trustworks.intranet.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 4 seed-drift gate (task 4.5).
 *
 * <p>The {@code permission} table is seeded by a committed, generated Flyway migration —
 * never reconciled at boot (finding F-14). This test regenerates the seed from
 * {@link Permissions#CATALOGUE} and asserts the committed migration matches byte for
 * byte. It runs in the fast tier, which gates every deploy (Phase 2), so a permission
 * added or renamed in code without regenerating the seed cannot reach an environment.
 */
class PermissionSeedMigrationTest {

    @Test
    void committedSeedMatchesCatalogue() throws IOException {
        Path migration = Path.of(PermissionSeedSql.MIGRATION_FILE);
        assertTrue(Files.exists(migration),
                "Missing " + migration + " — generate it: ./mvnw -q compile && "
                        + "java -cp target/classes dk.trustworks.intranet.security.PermissionSeedSql");
        assertEquals(PermissionSeedSql.render(), Files.readString(migration, StandardCharsets.UTF_8),
                "V464 has drifted from Permissions.java. Regenerate it (do NOT edit by hand): "
                        + "./mvnw -q compile && java -cp target/classes dk.trustworks.intranet.security.PermissionSeedSql");
    }
}
