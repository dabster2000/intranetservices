package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts against <strong>what actually ships</strong> — the payload literals inside
 * V496 — rather than against the source export the generator read. A generator bug that
 * dropped a screen would leave the source file perfectly intact.
 *
 * <p>Spec §12.1 asks the placeholder scan to "find all 24 refs". Three numbers are in
 * play and the test pins all three, because they are easy to conflate:
 * <ul>
 *   <li><strong>24</strong> callouts carry a {@code TW-nn} reference;</li>
 *   <li><strong>22</strong> distinct references — TW-01 and TW-06 each appear in two
 *       topics;</li>
 *   <li><strong>28</strong> blocks contain the literal {@code [Udfyldes}, the extra four
 *       being the video slots, which carry no TW ref and matter just as much.</li>
 * </ul>
 */
class CompetenceSeedContentTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V496__Competence_seed_content_2026_08.sql");

    private static List<CompetenceContent.CoursePayload> courses;
    private static List<CompetenceContent.TestPayload> tests;

    @BeforeAll
    static void loadShippedPayloads() throws IOException {
        assertTrue(Files.exists(MIGRATION), "Missing " + MIGRATION);
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        courses = new ArrayList<>();
        tests = new ArrayList<>();
        for (String literal : extractLiterals(sql, "{\"screens\":")) {
            courses.add(CompetencePayloadCodec.readCourse(literal));
        }
        for (String literal : extractLiterals(sql, "{\"questions\":")) {
            tests.add(CompetencePayloadCodec.readTest(literal));
        }
    }

    /**
     * Pull SQL string literals that start with {@code prefix}, undoing exactly the
     * escaping the generator applied. A naive split on quotes would cut the payloads in
     * half — they contain apostrophes and semicolons.
     *
     * <p>Both unescapes matter and one is easy to get wrong. The generator escapes every
     * backslash to {@code \\} and every quote to {@code ''}; MariaDB collapses both when
     * it reads the literal, so this has to as well. Leaving {@code \\} doubled turns a
     * JSON {@code \"} into {@code \\"} — an escaped backslash followed by a live quote,
     * which ends the JSON string early and makes the parser fail several words later, a
     * long way from the actual cause.
     */
    private static List<String> extractLiterals(String sql, String prefix) {
        List<String> out = new ArrayList<>();
        String needle = "'" + prefix;
        int at = sql.indexOf(needle);
        while (at >= 0) {
            StringBuilder sb = new StringBuilder();
            int i = at + 1;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (c == '\\' && i + 1 < sql.length() && sql.charAt(i + 1) == '\\') {
                    sb.append('\\');
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        sb.append('\'');
                        i += 2;
                        continue;
                    }
                    break;
                }
                sb.append(c);
                i++;
            }
            out.add(sb.toString());
            at = sql.indexOf(needle, i);
        }
        return out;
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the four topics ship with the inventory the spec promises")
    void inventoryMatches() {
        assertEquals(4, courses.size(), "expected four course payloads");
        assertEquals(4, tests.size(), "expected four test payloads");
        assertEquals(46, courses.stream().mapToInt(c -> c.screens().size()).sum(),
                "13 + 11 + 10 + 12 screens");
        assertEquals(40, tests.stream().mapToInt(t -> t.questions().size()).sum(),
                "10 questions per topic");
    }

    @Test
    @DisplayName("every question has exactly 4 options and exactly 1 correct")
    void questionShapeIsUniform() {
        int options = 0;
        for (CompetenceContent.TestPayload test : tests) {
            for (CompetenceContent.Question q : test.questions()) {
                assertEquals(4, q.options().size(), q.id() + " should have 4 options");
                assertEquals(1, q.options().stream().filter(CompetenceContent.Option::correct).count(),
                        q.id() + " should have exactly one correct option");
                assertEquals(q.options().size(),
                        q.options().stream().map(CompetenceContent.Option::id).distinct().count(),
                        q.id() + " has duplicate option ids");
                options += q.options().size();
            }
        }
        assertEquals(160, options);
    }

    @Test
    @DisplayName("the seeded content passes the same validator that gates publish")
    void seededContentIsValid() {
        for (CompetenceContent.CoursePayload course : courses) {
            assertEquals(List.of(), CompetenceContentValidator.validateCourse(course));
        }
        for (CompetenceContent.TestPayload test : tests) {
            assertEquals(List.of(), CompetenceContentValidator.validateTest(test));
        }
    }

    @Test
    @DisplayName("placeholder scan: 24 TW callouts, 22 distinct refs, 28 [Udfyldes blocks")
    void placeholderScanFindsEverything() {
        List<CompetencePlaceholderScanner.Finding> all = new ArrayList<>();
        for (CompetenceContent.CoursePayload course : courses) {
            all.addAll(CompetencePlaceholderScanner.scan(course));
        }
        assertEquals(28, all.size(), "blocks containing the literal [Udfyldes");

        long withRef = all.stream().filter(f -> f.ref() != null).count();
        assertEquals(24, withRef, "callouts carrying a TW-nn reference");

        Set<String> refs = CompetencePlaceholderScanner.distinctRefs(all);
        assertEquals(22, refs.size(), "distinct TW refs — TW-01 and TW-06 each appear twice");
        assertTrue(refs.contains("TW-01"));
        assertTrue(refs.contains("TW-22"));

        long videoSlots = all.stream()
                .filter(f -> "video".equals(f.blockType()))
                .count();
        assertEquals(4, videoSlots, "the four unfilled video slots");
    }

    @Test
    @DisplayName("the seed ships no video provider or id, so nothing is embedded yet")
    void videoSlotsAreInert() {
        for (CompetenceContent.CoursePayload course : courses) {
            for (CompetenceContent.Screen screen : course.screens()) {
                for (CompetenceContent.Block block : screen.blocks()) {
                    if ("video".equals(block.type())) {
                        assertTrue(block.provider() == null || block.provider().isBlank());
                        assertTrue(block.videoId() == null || block.videoId().isBlank());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("eyebrows number only the content screens")
    void eyebrowsDeriveCleanly() {
        for (CompetenceContent.CoursePayload course : courses) {
            List<String> eyebrows = CompetenceEyebrow.derive(course);
            assertEquals(course.screens().size(), eyebrows.size());
            assertEquals("Introduktion", eyebrows.get(0), "every topic opens on an intro screen");
            assertTrue(eyebrows.contains("Nøglepunkter"));
            assertTrue(eyebrows.contains("Supplerende materiale"));
            long numbered = eyebrows.stream().filter(e -> e.startsWith("Afsnit ")).count();
            long contentScreens = course.screens().stream()
                    .filter(s -> "content".equals(s.role())).count();
            assertEquals(contentScreens, numbered);
            assertTrue(eyebrows.contains("Afsnit 1 af " + contentScreens));
        }
    }

    @Test
    @DisplayName("no seeded text contains raw HTML — the renderer emits nodes, never markup")
    void seededContentCarriesNoMarkup() {
        for (CompetenceContent.CoursePayload course : courses) {
            for (CompetenceContent.Screen screen : course.screens()) {
                for (CompetenceContent.Block block : screen.blocks()) {
                    if ("code".equals(block.type())) {
                        continue; // code is literal by definition
                    }
                    String text = block.text();
                    if (text != null) {
                        assertFalse(text.contains("<script"), "script tag in authored text");
                        assertFalse(text.matches("(?s).*<[a-zA-Z]+[ >].*"),
                                "raw HTML element in authored text: " + text);
                    }
                }
            }
        }
    }
}
