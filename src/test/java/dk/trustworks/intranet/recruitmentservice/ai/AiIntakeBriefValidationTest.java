package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P9 §5.1 unit contract for the brief section: the model's answer is
 * untrusted text that lands in {@code recruitment_events.pii} and is
 * rendered on the candidate profile, so the validator must separate a
 * <em>thin</em> brief (drop it silently, contract §4.3) from a
 * <em>contaminated</em> one (reject, so the reactor retries and nothing raw
 * is persisted).
 * <p>
 * The regression this pins down: a 2026-08 production generation
 * ({@code gpt-5.6-terra}, {@code brief-v1}) wrote the model's own
 * deliberation about schema conformance — harmony channel markers
 * ("assistant to=system" / "assistant to=final") and an unterminated JSON
 * fragment — into the pii block behind the real bullets, because the old
 * validator truncated every bullet at the length cap instead of rejecting
 * it and because {@code readTree} silently ignored trailing tokens.
 * <p>
 * Deliberately a plain JUnit test, not a {@code @QuarkusTest}: only this
 * DB-free tier runs in the CI deploy gate.
 */
class AiIntakeBriefValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiIntakeGenerationService service = new AiIntakeGenerationService();

    private static JsonNode brief(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("test fixture is not valid JSON", e);
        }
    }

    // ---- The happy path stays happy -------------------------------------------

    @Test
    void validBullets_survive_cleanedAndOrdered() {
        List<String> bullets = service.validateBullets(brief("""
                ["Kandidaten er uddannet cand.merc. fra CBS.",
                 "Har otte \\u00e5rs erfaring som forretningsanalytiker.",
                 "Angiver dansk og engelsk som arbejdssprog."]"""));

        assertEquals(3, bullets.size());
        assertEquals("Kandidaten er uddannet cand.merc. fra CBS.", bullets.get(0));
        assertEquals("Angiver dansk og engelsk som arbejdssprog.", bullets.get(2));
    }

    @Test
    void controlCharactersInsideABullet_areStripped_notRejected() {
        List<String> bullets = service.validateBullets(brief("""
                ["Punkt\\u0007et om baggrund","Punkt\\u200bto om uddannelse","Punkt tre om ans\\u00f8gning"]"""));

        assertEquals(List.of("Punkt et om baggrund", "Punkt to om uddannelse", "Punkt tre om ansøgning"),
                bullets);
    }

    @Test
    void moreThanFiveBullets_areCappedNotRejected() {
        List<String> bullets = service.validateBullets(brief("""
                ["Et","To","Tre","Fire","Fem","Seks","Syv"]"""));

        assertEquals(5, bullets.size(), "over-generation is benign — take the first five");
        assertEquals("Fem", bullets.get(4));
    }

    // ---- Thin ⇒ silently absent (contract §4.3, no retry) ----------------------

    @Test
    void explicitNullBrief_isAbsent_notAFailure() {
        assertTrue(service.validateBullets(brief("null")).isEmpty(),
                "the schema declares brief nullable — nothing to say is not a failure");
    }

    @Test
    void fewerThanThreeNonBlankBullets_isAbsent_notAFailure() {
        assertTrue(service.validateBullets(brief("""
                ["Punkt et om baggrund","   ","Punkt to om uddannelse"]""")).isEmpty(),
                "under MIN_BULLETS after filtering => brief treated as absent");
    }

    // ---- Contaminated ⇒ reject, so the caller retries --------------------------

    @Test
    void scratchpadLadenResponse_theProductionShape_isRejected() {
        // Three real bullets followed by the model's own deliberation, exactly
        // as production event seq 613 recorded it.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.validateBullets(brief("""
                        ["Kandidaten er uddannet cand.merc. fra CBS.",
                         "Har otte \\u00e5rs erfaring som forretningsanalytiker.",
                         "Angiver dansk og engelsk som arbejdssprog.",
                         "assistant to=system I need to make sure the output conforms to the schema.",
                         "assistant to=final {\\"brief\\": [\\"Kandidaten er uddannet"]""")));

        assertTrue(failure.getMessage().contains("contaminated"), failure.getMessage());
        assertFalse(failure.getMessage().contains("cand.merc."),
                "the failure message must never carry candidate PII");
    }

    @Test
    void aBulletOverTheLengthCap_isRejected_notTruncated() {
        // The old validator truncated at MAX_BULLET_CHARS, which is precisely how
        // a scratchpad dump became a 400-character "bullet" with unbalanced JSON.
        String dump = "Vi skal overholde skemaet og returnere praecis 3-5 punkter. ".repeat(20);
        assertTrue(dump.length() > 400, "fixture must exceed the cap");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.validateBullets(MAPPER.createArrayNode()
                        .add("Kandidaten er uddannet cand.merc. fra CBS.")
                        .add("Har otte aars erfaring som forretningsanalytiker.")
                        .add(dump)));

        assertTrue(failure.getMessage().contains("cap is"), failure.getMessage());
    }

    @Test
    void aBulletThatReEmitsTheJsonEnvelope_isRejected() {
        assertThrows(IllegalStateException.class, () -> service.validateBullets(brief("""
                ["{\\"brief\\": [\\"Punkt et\\"]}","Punkt to om uddannelse","Punkt tre om ans\\u00f8gning"]""")));
    }

    @Test
    void aBulletWrappedInACodeFence_isRejected() {
        assertThrows(IllegalStateException.class, () -> service.validateBullets(brief("""
                ["Punkt et om baggrund","```json","Punkt tre om ans\\u00f8gning"]""")));
    }

    @Test
    void harmonyControlTokens_areRejected() {
        assertTrue(AiIntakeGenerationService.looksLikeModelScratchpad("<|channel|>analysis"));
        assertTrue(AiIntakeGenerationService.looksLikeModelScratchpad("assistant to=final"));
        assertTrue(AiIntakeGenerationService.looksLikeModelScratchpad("[\"Punkt et\"]"));
    }

    @Test
    void danishProseAboutTechnology_isNotMistakenForScratchpad() {
        // The marker list is structural on purpose: a real bullet about an IT
        // consultant may well name JSON, REST or a database schema.
        assertFalse(AiIntakeGenerationService.looksLikeModelScratchpad(
                "Har erfaring med JSON, REST-API'er og databaseskema-design."));
        assertFalse(AiIntakeGenerationService.looksLikeModelScratchpad(
                "Beskriver sig selv som analytisk og struktureret."));
    }

    @Test
    void aBriefThatIsNotAnArray_isRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.validateBullets(brief("\"Kandidaten er uddannet cand.merc.\"")));

        assertTrue(failure.getMessage().contains("expected an array"), failure.getMessage());
    }

    @Test
    void aNonTextBulletInsideTheArray_isRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.validateBullets(brief("""
                        ["Punkt et om baggrund",{"text":"Punkt to"},"Punkt tre"]""")));

        assertTrue(failure.getMessage().contains("expected a string"), failure.getMessage());
    }

    @Test
    void aMissingBriefSection_isRejected() {
        // MissingNode — the strict schema declares brief required, so its
        // absence means the answer is not the document we asked for.
        assertThrows(IllegalStateException.class,
                () -> service.validateBullets(brief("{\"suggestions\":null}").path("brief")));
    }

    // ---- The envelope: exactly one JSON document -------------------------------

    @Test
    void trailingScratchpadAfterTheJsonObject_failsTheParse() {
        // Jackson's default readTree parses the leading object and silently
        // drops the rest; that silence is what let contaminated answers through.
        assertThrows(Exception.class, () -> AiIntakeGenerationService.parseSingleJsonDocument(
                "{\"brief\":[\"Punkt et\",\"Punkt to\",\"Punkt tre\"]}\n"
                        + "assistant to=final The schema requires 3-5 bullets, so"));
    }

    @Test
    void anUnterminatedJsonDocument_failsTheParse() {
        assertThrows(Exception.class, () -> AiIntakeGenerationService.parseSingleJsonDocument(
                "{\"brief\":[\"Punkt et\",\"Punkt to\""));
    }

    @Test
    void oneCleanJsonDocument_parses() throws Exception {
        JsonNode root = AiIntakeGenerationService.parseSingleJsonDocument(
                "{\"brief\":[\"Punkt et\",\"Punkt to\",\"Punkt tre\"]}");

        assertEquals(3, root.path("brief").size());
    }
}
