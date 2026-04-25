package dk.trustworks.intranet.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the YAML duplicate-key bug discovered on 2026-04-25.
 *
 * <p>Context (see docs/superpowers/plans/2026-04-24-expenses-journal15-recovery.md):
 * the kill switch ({@code dk.trustworks.expense.economics-upload.enabled})
 * and environment-id ({@code dk.trustworks.environment.id}) properties were
 * added under a fresh top-level {@code dk:} block in application.yml, while
 * an existing {@code dk:} block (for {@code dk.trustworks.intranet.*})
 * already lived later in the file. SnakeYAML accepts duplicate keys at the
 * same mapping level by default and the LAST one wins, so the entire
 * earlier block was silently dropped at parse time. Both code-side
 * prevention layers (Layer 2 kill switch, Layer 3 idempotency-key
 * namespace) were therefore inactive in production despite their env vars
 * being set on the ECS task definitions.
 *
 * <p>This test pins two invariants:
 * <ol>
 *   <li>application.yml parses cleanly under SnakeYAML strict mode (no
 *       duplicate keys at any mapping level — this catches the issue at the
 *       structural level for any future block, not just {@code dk:}).
 *   <li>The two prevention-layer properties are reachable via the parsed
 *       structure (catches the case where a future edit inadvertently
 *       moves them under a key that does not match
 *       {@code dk.trustworks.expense.economics-upload.enabled} /
 *       {@code dk.trustworks.environment.id}).
 * </ol>
 */
class ApplicationYamlNoDuplicateKeysTest {

    @Test
    void applicationYamlHasNoDuplicateKeysAnywhere() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml not found on test classpath");
            yaml.load(in);
        } catch (Exception e) {
            fail("application.yml has duplicate keys (SnakeYAML silently lets the "
                    + "later one win, dropping the earlier block at parse time). "
                    + "This caused the 2026-04-25 kill-switch outage. Original error: "
                    + e.getMessage());
        }
    }

    @Test
    void killSwitchAndEnvironmentIdPropertiesAreReachable() throws Exception {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> root;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml not found on test classpath");
            root = yaml.load(in);
        }

        Object expense = path(root, "dk", "trustworks", "expense", "economics-upload", "enabled");
        assertNotNull(expense,
                "dk.trustworks.expense.economics-upload.enabled is null. The kill "
                        + "switch (Layer 2) reads this property; if it is unreachable, "
                        + "@ConfigProperty falls back to defaultValue=true and the "
                        + "switch never fires.");

        Object envId = path(root, "dk", "trustworks", "environment", "id");
        assertNotNull(envId,
                "dk.trustworks.environment.id is null. The env-namespaced "
                        + "idempotency-key (Layer 3) reads this property; if it is "
                        + "unreachable, both prod and staging default to "
                        + "'production' and idempotency keys collide across "
                        + "environments — re-creating the original journal-15 bug.");
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> root, String... keys) {
        Object cursor = root;
        for (String k : keys) {
            if (!(cursor instanceof Map)) return null;
            cursor = ((Map<String, Object>) cursor).get(k);
            if (cursor == null) return null;
        }
        return cursor;
    }
}
