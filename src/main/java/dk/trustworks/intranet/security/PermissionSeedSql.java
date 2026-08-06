package dk.trustworks.intranet.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders the Flyway seed migration for the {@code permission} table from
 * {@link Permissions#CATALOGUE} — the build-time seed generation required by
 * Phase 4 task 4.5 (boot-time reconciliation is forbidden, finding F-14).
 *
 * <p>The committed migration {@code V464__Seed_permission_catalogue.sql} must be
 * byte-identical to {@link #render()}; {@code PermissionSeedMigrationTest} asserts
 * this in the fast test tier, which blocks every deploy (Phase 2 gate). To
 * regenerate after editing {@link Permissions}:
 *
 * <pre>
 *   ./mvnw -q compile
 *   java -cp target/classes dk.trustworks.intranet.security.PermissionSeedSql
 * </pre>
 *
 * <p>The rendered SQL is idempotent and non-resurrecting (F-13): re-running it
 * refreshes display metadata only — it never touches {@code state},
 * {@code revoked_at}, {@code origin} or {@code enforce_acting_user}, so a key
 * marked STALE or revoked stays that way across rollback/re-deploy cycles.
 */
public final class PermissionSeedSql {

    static final String MIGRATION_FILE = "src/main/resources/db/migration/V464__Seed_permission_catalogue.sql";

    public static String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                -- V464: Seed the permission catalogue (authorization-model-unification Phase 4, task 4.5)
                --
                -- GENERATED FILE — do not edit by hand.
                -- Generated from dk.trustworks.intranet.security.Permissions by PermissionSeedSql;
                -- PermissionSeedMigrationTest (fast tier, deploy-gating) fails if this file and the
                -- catalogue diverge. Regenerate with:
                --   ./mvnw -q compile && java -cp target/classes dk.trustworks.intranet.security.PermissionSeedSql
                --
                -- Idempotent and non-resurrecting (F-13): ON DUPLICATE KEY UPDATE refreshes display
                -- metadata only and never touches state, revoked_at, origin or enforce_acting_user.

                """);
        List<Permissions.Permission> sorted = new ArrayList<>(Permissions.CATALOGUE);
        sorted.sort(Comparator.comparing(Permissions.Permission::key));
        for (Permissions.Permission p : sorted) {
            sb.append("INSERT INTO permission (permission_key, display_name, description, category, origin, state)\n")
              .append("  VALUES (").append(sqlString(p.key())).append(", ")
              .append(sqlString(p.displayName())).append(", NULL, ")
              .append(sqlString(p.category())).append(", 'CODE', 'ACTIVE')\n")
              .append("  ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);\n");
        }
        return sb.toString();
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : MIGRATION_FILE);
        Files.writeString(target, render(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target + " (" + Permissions.CATALOGUE.size() + " permissions)");
    }

    private PermissionSeedSql() {
    }
}
