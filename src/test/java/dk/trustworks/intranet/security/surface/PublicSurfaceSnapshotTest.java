package dk.trustworks.intranet.security.surface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The public-surface response-shape ratchet — task 3.11 of the
 * authorization-model-unification plan.
 *
 * <p>Freezes what every externally reachable endpoint returns and accepts, field by field,
 * and fails when that widens. It is the data-side twin of the frontend's
 * {@code access-manifest.json}: one gates who may call, this gates what comes back.
 *
 * <h2>Why a plain JUnit test and not a {@code @QuarkusTest}</h2>
 *
 * Two independent reasons, both from the plan's findings:
 *
 * <ul>
 *   <li>The CI fast tier runs {@code -DexcludedGroups=io.quarkus.test.junit.QuarkusTest}, so a
 *       {@code @QuarkusTest} here would never execute in the gate it is meant to be.</li>
 *   <li>A {@code @QuarkusTest} that aborts during boot is reported as a <em>green skip</em>, so
 *       the check would pass while asserting nothing — the worst possible failure mode for a
 *       ratchet.</li>
 * </ul>
 *
 * <h2>What fails, and how hard</h2>
 *
 * <ol>
 *   <li><b>Any difference from the committed snapshot</b> — a diff, fixed by regenerating and
 *       committing, which puts the change in front of a reviewer.</li>
 *   <li><b>A new entity return type</b> — hard failure. Existing ones are grandfathered
 *       (a check that is red on day one gets disabled on day two), but the surface may not
 *       gain another one. This is Phase 13's burn-down number moving the wrong way.</li>
 *   <li><b>A new sensitive-named field</b> — hard failure. {@code cpr}, {@code password},
 *       {@code bankAccount}, {@code salary}, {@code pension}, {@code birthday},
 *       {@code privatePhone}. The ones already there are listed in the snapshot so the debt
 *       is visible rather than implied.</li>
 * </ol>
 *
 * <p>To accept an intended change:
 * {@code ./mvnw -Dtest=PublicSurfaceSnapshotTest -Dpublic.surface.update=true test}
 * then commit the regenerated {@code public-surface-snapshot.json} and
 * {@code docs/access/backend-public-surface.md} in the same pull request.
 */
class PublicSurfaceSnapshotTest {

    private static final String UPDATE_PROPERTY = "public.surface.update";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static boolean updating() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the snapshot is byte-identical across two runs (V3.7)")
    void deterministic() {
        String first = PublicSurfaceSnapshotter.toJson(PublicSurfaceSnapshotter.build());
        String second = PublicSurfaceSnapshotter.toJson(PublicSurfaceSnapshotter.build());
        assertEquals(first, second,
                "The snapshotter is not deterministic. getDeclaredFields() has no specified order, so "
                        + "every collection must be sorted explicitly — otherwise this check produces "
                        + "spurious failures and gets disabled.");
    }

    @Test
    @DisplayName("the externally reachable surface matches the committed snapshot")
    void matchesCommittedSnapshot() {
        PublicSurfaceSnapshotter.Snapshot snapshot = PublicSurfaceSnapshotter.build();
        String generated = PublicSurfaceSnapshotter.toJson(snapshot);
        Path file = PublicSurfaceSnapshotter.snapshotFile();

        if (updating()) {
            PublicSurfaceSnapshotter.write(file, generated);
            PublicSurfaceSnapshotter.write(
                    PublicSurfaceSnapshotter.markdownFile(), PublicSurfaceSnapshotter.toMarkdown(snapshot));
            return;
        }

        String committed = PublicSurfaceSnapshotter.read(file);
        assertNotNull(committed, file + " is missing. Regenerate with -D" + UPDATE_PROPERTY + "=true.");

        if (!committed.equals(generated)) {
            fail(describeDifference(committed, generated));
        }
    }

    @Test
    @DisplayName("no new endpoint returns a persistence entity")
    void noNewEntityReturnTypes() {
        if (updating()) return;
        JsonNode committed = committedJson();
        if (committed == null) return;

        PublicSurfaceSnapshotter.Snapshot snapshot = PublicSurfaceSnapshotter.build();
        JsonNode endpoints = committed.path("endpoints");
        List<String> offenders = new ArrayList<>();

        for (PublicSurfaceSnapshotter.Endpoint e : snapshot.endpoints().values()) {
            JsonNode before = endpoints.path(e.key());
            boolean wasEntity = before.path("returnsEntity").asBoolean(false);
            boolean wasBodyEntity = before.path("requestBodyIsEntity").asBoolean(false);
            if (e.returnsEntity() && !wasEntity) {
                offenders.add("✗ " + e.key() + " returns " + e.returns() + ", a persistence entity."
                        + "\n  " + (before.isMissingNode()
                        ? "This is a NEW endpoint on the external surface."
                        : "It previously returned " + before.path("returns").asText() + ".")
                        + "\n  An entity publishes every column it maps, including ones added later by an"
                        + "\n  unrelated migration. Return a DTO that names the fields it exposes."
                        + "\n  (findings F-21; the facade is Phase 13.3.)");
            }
            if (e.requestBodyIsEntity() && !wasBodyEntity) {
                offenders.add("✗ " + e.key() + " accepts " + e.requestBody() + " as its request body."
                        + "\n  Binding an entity straight from the request is a mass-assignment surface:"
                        + "\n  any column the caller names is written. Take a DTO instead.");
            }
        }

        if (!offenders.isEmpty()) fail(String.join("\n\n", offenders));
    }

    @Test
    @DisplayName("no new sensitive-named field reaches the external surface")
    void noNewSensitiveFields() {
        if (updating()) return;
        JsonNode committed = committedJson();
        if (committed == null) return;

        TreeSet<String> known = new TreeSet<>();
        committed.path("sensitiveFields").forEach(n -> known.add(n.asText()));

        PublicSurfaceSnapshotter.Snapshot snapshot = PublicSurfaceSnapshotter.build();
        List<String> added = snapshot.sensitiveFields().stream().filter(s -> !known.contains(s)).toList();

        if (!added.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "A field whose name marks it as regulated or confidential is now returned to every\n"
                            + "holder of a public:read key — or, on a @PermitAll endpoint, to anyone at all:\n\n");
            for (String s : added) sb.append("  ✗ ").append(s).append('\n');
            sb.append("\nIf this is genuinely intended, regenerate the snapshot with -D")
                    .append(UPDATE_PROPERTY)
                    .append("=true\nin the same pull request, so a reviewer sees the field appear in the diff.");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("the generated enumeration is current")
    void markdownEnumerationIsCurrent() {
        PublicSurfaceSnapshotter.Snapshot snapshot = PublicSurfaceSnapshotter.build();
        String generated = PublicSurfaceSnapshotter.toMarkdown(snapshot);
        Path file = PublicSurfaceSnapshotter.markdownFile();

        if (updating()) {
            PublicSurfaceSnapshotter.write(file, generated);
            return;
        }
        String committed = PublicSurfaceSnapshotter.read(file);
        assertNotNull(committed, file + " is missing. Regenerate with -D" + UPDATE_PROPERTY + "=true.");
        assertEquals(committed, generated,
                "docs/access/backend-public-surface.md is stale. Regenerate with -D" + UPDATE_PROPERTY + "=true.");
    }

    @Test
    @DisplayName("the snapshot records its own gaps rather than hiding them")
    void gapsAreRecorded() {
        PublicSurfaceSnapshotter.Snapshot snapshot = PublicSurfaceSnapshotter.build();
        assertTrue(snapshot.endpoints().size() > 0, "No externally reachable endpoint was found at all — "
                + "the class scan is broken, and a green run here would mean nothing.");
        // Every unresolved entry must name the endpoint it belongs to, or it is not actionable.
        for (String u : snapshot.unresolved()) {
            assertTrue(u.contains(" ") && u.contains("—"), "Unactionable unresolved entry: " + u);
        }
    }

    // -----------------------------------------------------------------------

    private JsonNode committedJson() {
        String committed = PublicSurfaceSnapshotter.read(PublicSurfaceSnapshotter.snapshotFile());
        if (committed == null) return null;
        try {
            return MAPPER.readTree(committed);
        } catch (Exception e) {
            throw new IllegalStateException("Committed snapshot is not valid JSON", e);
        }
    }

    /**
     * Name the endpoint, the type and the field — a diff of 4 000 lines of JSON is not a
     * failure message anyone acts on.
     */
    private String describeDifference(String committedJson, String generatedJson) {
        StringBuilder sb = new StringBuilder("The externally reachable surface changed.\n\n");
        try {
            JsonNode before = MAPPER.readTree(committedJson).path("endpoints");
            JsonNode after = MAPPER.readTree(generatedJson).path("endpoints");

            List<String> lines = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = after.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode prev = before.path(entry.getKey());
                if (prev.isMissingNode()) {
                    lines.add("+ new endpoint " + entry.getKey() + " → " + entry.getValue().path("returns").asText());
                    continue;
                }
                if (!prev.path("returns").asText().equals(entry.getValue().path("returns").asText())) {
                    lines.add("~ " + entry.getKey() + " now returns "
                            + entry.getValue().path("returns").asText()
                            + " (was " + prev.path("returns").asText() + ")");
                }
                lines.addAll(fieldDifferences(entry.getKey(), prev.path("fields"), entry.getValue().path("fields")));
            }
            for (Iterator<String> it = before.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (after.path(key).isMissingNode()) lines.add("- endpoint removed: " + key);
            }
            if (lines.isEmpty()) lines.add("(totals or gap lists changed only)");
            for (String l : lines.stream().limit(60).toList()) sb.append("  ").append(l).append('\n');
        } catch (Exception e) {
            sb.append("  (could not diff structurally: ").append(e.getMessage()).append(")\n");
        }
        sb.append("\nEvery field listed above is returned to every holder of a public:read key — and, on a\n");
        sb.append("@PermitAll endpoint, to any caller at all. If the change is intended, regenerate with\n");
        sb.append("  ./mvnw -Dtest=PublicSurfaceSnapshotTest -D").append(UPDATE_PROPERTY).append("=true test\n");
        sb.append("and commit the snapshot in this pull request, so a reviewer approves it explicitly.");
        return sb.toString();
    }

    private List<String> fieldDifferences(String endpoint, JsonNode before, JsonNode after) {
        List<String> lines = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = after.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> type = it.next();
            JsonNode prevType = before.path(type.getKey());
            String simple = type.getKey().substring(type.getKey().lastIndexOf('.') + 1);
            if (prevType.isMissingNode()) {
                lines.add("+ " + endpoint + " — new type in the response tree: " + simple);
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> f = type.getValue().fields(); f.hasNext(); ) {
                Map.Entry<String, JsonNode> field = f.next();
                JsonNode prevField = prevType.path(field.getKey());
                if (prevField.isMissingNode()) {
                    lines.add("+ " + endpoint + " — " + simple + " gained field `" + field.getKey()
                            + "` (" + field.getValue().asText() + ")");
                } else if (!prevField.asText().equals(field.getValue().asText())) {
                    lines.add("~ " + endpoint + " — " + simple + "." + field.getKey() + ": "
                            + prevField.asText() + " → " + field.getValue().asText());
                }
            }
            for (Iterator<String> f = prevType.fieldNames(); f.hasNext(); ) {
                String name = f.next();
                if (type.getValue().path(name).isMissingNode()) {
                    lines.add("- " + endpoint + " — " + simple + " lost field `" + name + "`");
                }
            }
        }
        return lines;
    }
}
