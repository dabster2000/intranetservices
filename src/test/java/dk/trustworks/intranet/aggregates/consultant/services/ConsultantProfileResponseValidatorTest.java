package dk.trustworks.intranet.aggregates.consultant.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.consultant.services.ConsultantProfileResponseValidator.Accepted;
import dk.trustworks.intranet.aggregates.consultant.services.ConsultantProfileResponseValidator.Rejected;
import dk.trustworks.intranet.aggregates.consultant.services.ConsultantProfileResponseValidator.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit — deliberately NOT {@code @QuarkusTest} (a boot abort would be reported as a green
 * SKIP and this file would silently stop testing anything).
 *
 * <p>This validator is the single control that stops the defect the whole change exists to fix:
 * {@code OpenAIService} returns the literal {@code "{}"} on HTTP error, timeout and empty output,
 * the old call site parsed that <em>successfully</em>, and {@code MAPPER.writeValueAsString} of the
 * resulting {@code MissingNode} produced the 4-character string {@code "null"} — valid JSON, valid
 * for a MariaDB JSON column, written back with {@code generated_at} stamped, so an empty card was
 * served as fresh for seven days with nothing logged. Every rejection case below is a regression
 * guard for that; the sanitisation cases guard the chip labels that reach the DOM.
 */
class ConsultantProfileResponseValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Result validate(String json) {
        return ConsultantProfileResponseValidator.validate(json, MAPPER);
    }

    private static String rejectionCode(String json) {
        return assertInstanceOf(Rejected.class, validate(json),
                "expected rejection for: " + json).code();
    }

    // ---- 10-13. rejection ------------------------------------------------------

    @Test
    void emptyObject_isRejected() {
        // THE regression case: "{}" is what OpenAIService returns for an error, a timeout,
        // an empty output text and (with refusalFallbackJson=null) a refusal.
        assertEquals(ConsultantProfileResponseValidator.CODE_EMPTY_OUTPUT, rejectionCode("{}"));
        assertEquals(ConsultantProfileResponseValidator.CODE_EMPTY_OUTPUT, rejectionCode("  {}  "));
    }

    @Test
    void nullBlankAndWhitespace_areRejected() {
        assertEquals(ConsultantProfileResponseValidator.CODE_EMPTY_OUTPUT, rejectionCode(null));
        assertEquals(ConsultantProfileResponseValidator.CODE_EMPTY_OUTPUT, rejectionCode(""));
        assertEquals(ConsultantProfileResponseValidator.CODE_EMPTY_OUTPUT, rejectionCode("   "));
    }

    @Test
    void theOldRefusalFallbackPayload_isRejected() {
        // This exact literal used to be passed as refusalFallbackJson. It parses cleanly, so it
        // cached an empty profile for a full staleness window.
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_PITCH,
                rejectionCode("{\"pitch\":\"\",\"industries\":[],\"topSkills\":[]}"));
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_PITCH,
                rejectionCode("{\"pitch\":\"   \",\"industries\":[],\"topSkills\":[]}"));
    }

    @Test
    void nullArrays_areRejectedByShapeNotByStringComparison() {
        // A JSON null and a missing key both have to fail. Comparing the serialised value to the
        // string "null" is exactly the check that let the original defect through.
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_INDUSTRIES,
                rejectionCode("{\"pitch\":\"x\",\"industries\":null,\"topSkills\":[]}"));
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_INDUSTRIES,
                rejectionCode("{\"pitch\":\"x\",\"topSkills\":[]}"));
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_SKILLS,
                rejectionCode("{\"pitch\":\"x\",\"industries\":[],\"topSkills\":null}"));
        // A non-array of the right "looks populated" kind must fail too.
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_INDUSTRIES,
                rejectionCode("{\"pitch\":\"x\",\"industries\":\"Finance\",\"topSkills\":[]}"));
    }

    @Test
    void unparseableOrNonObjectPayloads_areRejected() {
        assertEquals(ConsultantProfileResponseValidator.CODE_UNPARSEABLE, rejectionCode("not json"));
        assertEquals(ConsultantProfileResponseValidator.CODE_UNPARSEABLE, rejectionCode("[1,2,3]"));
        assertEquals(ConsultantProfileResponseValidator.CODE_UNPARSEABLE, rejectionCode("\"a string\""));
    }

    @Test
    void aRunawayPitch_isRejected() {
        String json = "{\"pitch\":\"" + "x".repeat(401) + "\",\"industries\":[],\"topSkills\":[]}";
        assertEquals(ConsultantProfileResponseValidator.CODE_BAD_PITCH, rejectionCode(json));
    }

    // ---- 14. acceptance + sanitisation -----------------------------------------

    @Test
    void validPayload_isAcceptedAndSanitised() {
        Accepted accepted = assertInstanceOf(Accepted.class, validate("""
                {"pitch":"  Leads complex public-sector programmes.  ",
                 "industries":["Public Sector","public sector","Energy","Transportation","Health"],
                 "topSkills":["Agile Methods","AGILE METHODS","Energy","Change Management",
                              "This skill label is far too long to ever render on a 240px card",
                              "Project Management","Extra"]}"""));

        assertEquals("Leads complex public-sector programmes.", accepted.pitch());

        // <= 3 industries, deduped case-insensitively, order preserved.
        assertEquals(List.of("Public Sector", "Energy", "Transportation"), accepted.industries());

        // <= 4 skills, deduped case-insensitively, each <= 32 chars, and a skill that duplicates
        // an industry ("Energy") is dropped so the same word cannot appear in two chip colours.
        assertTrue(accepted.topSkills().size() <= ConsultantProfileResponseValidator.MAX_SKILLS);
        assertFalse(accepted.topSkills().contains("Energy"));
        assertTrue(accepted.topSkills().contains("Agile Methods"));
        assertEquals(1, accepted.topSkills().stream().filter(s -> s.equalsIgnoreCase("agile methods")).count());
        accepted.topSkills().forEach(skill -> assertTrue(
                skill.length() <= ConsultantProfileResponseValidator.MAX_LABEL_CHARS,
                "label longer than the cap reached the DTO: " + skill));
    }

    @Test
    void nonTextualArrayEntries_areIgnoredRatherThanStringified() {
        Accepted accepted = assertInstanceOf(Accepted.class, validate(
                "{\"pitch\":\"ok\",\"industries\":[1,null,{\"a\":1},\"Finance\"],\"topSkills\":[true,\"Java\"]}"));

        assertEquals(List.of("Finance"), accepted.industries());
        assertEquals(List.of("Java"), accepted.topSkills());
    }

    @Test
    void controlAndBidiCharacters_areStrippedFromEveryUserVisibleField() {
        // A CV author controls the model input, so a bidi override reaching a chip label is a
        // reachable spoofing vector. The expected-absent characters are built from code points
        // rather than pasted into the source, because they are invisible in an editor.
        String bidiOverride = String.valueOf((char) 0x202E);   // RIGHT-TO-LEFT OVERRIDE (Cf)
        String bell = String.valueOf((char) 0x0007);           // BELL (Cc)

        Accepted accepted = assertInstanceOf(Accepted.class, validate(
                "{\"pitch\":\"Safe\\u202Epitch\",\"industries\":[\"Fin\\u0007ance\"],\"topSkills\":[\"Ja\\u202Eva\"]}"));

        assertFalse(accepted.pitch().contains(bidiOverride), "bidi override survived into the pitch");
        assertFalse(accepted.industries().get(0).contains(bell), "control char survived into an industry label");
        assertFalse(accepted.topSkills().get(0).contains(bidiOverride), "bidi override survived into a skill label");
    }

    @Test
    void emptyArrays_areAcceptedWhenThePitchIsReal() {
        // A thin CV legitimately yields no labels; that must not be a failure, because the
        // deterministic half of the card supplies chips independently.
        Accepted accepted = assertInstanceOf(Accepted.class,
                validate("{\"pitch\":\"Real pitch.\",\"industries\":[],\"topSkills\":[]}"));

        assertTrue(accepted.industries().isEmpty());
        assertTrue(accepted.topSkills().isEmpty());
    }

    @Test
    void sanitizeLabels_capsAndDedupesIndependentlyOfTheParser() {
        assertEquals(List.of(), ConsultantProfileResponseValidator.sanitizeLabels(null, 3));
        assertEquals(List.of(), ConsultantProfileResponseValidator.sanitizeLabels(List.of("a"), 0));
        assertEquals(List.of("Java"),
                ConsultantProfileResponseValidator.sanitizeLabels(List.of("  Java ", "java", "JAVA"), 3));
        assertEquals(ConsultantProfileResponseValidator.MAX_LABEL_CHARS,
                ConsultantProfileResponseValidator.sanitizeLabels(List.of("y".repeat(80)), 1).get(0).length());
    }
}
