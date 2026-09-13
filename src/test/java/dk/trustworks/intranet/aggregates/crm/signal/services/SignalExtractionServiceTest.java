package dk.trustworks.intranet.aggregates.crm.signal.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalExtractionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Everything the backend does to a model answer before it is allowed to matter.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}, no database, no network — so it runs in the
 * DB-free fast tier that gates deploys. It exercises
 * {@link SignalExtractionService#parse} directly, which is why that method is
 * package-private and free of injected state.
 *
 * <p>The load-bearing case is {@link #hallucinatedClientUuidIsRejected()}: the model is
 * given an allowlist and its answer is re-checked against it, so a hallucinated or forged
 * uuid can never attach a colleague's signal to someone else's client.
 */
class SignalExtractionServiceTest {

    private static final String ORSTED = "11111111-1111-1111-1111-111111111111";
    private static final String DANSKE = "22222222-2222-2222-2222-222222222222";
    private static final List<String[]> CLIENTS = List.of(
            new String[]{ORSTED, "Ørsted"},
            new String[]{DANSKE, "Danske Bank"});

    private SignalExtractionService service;

    @BeforeEach
    void setUp() {
        service = new SignalExtractionService();
        service.objectMapper = new ObjectMapper();
        service.extractionModel = "test-model";
    }

    @Test
    void readsAFullLine() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":"%s","clientText":null,"personName":"Benny Hoffmann",
                 "personRole":"Head of AI (new)",
                 "relationText":"Hans knows Benny Hoffmann from school",
                 "signalType":"ORG_CHANGE","confidence":0.9}
                """.formatted(ORSTED), CLIENTS, null);

        assertEquals(ORSTED, result.clientUuid());
        assertEquals("Benny Hoffmann", result.personName());
        assertEquals("Head of AI (new)", result.personRole());
        assertEquals("Hans knows Benny Hoffmann from school", result.relationText());
        assertEquals("ORG_CHANGE", result.signalType());
        assertEquals(0.9d, result.confidence());
        assertNull(result.clientText(), "clientText is only for the unmatched case");
    }

    /**
     * The allowlist re-check. A uuid the model invented is not in the list we sent, so it
     * resolves to null rather than to a real client that happens to exist.
     */
    @Test
    void hallucinatedClientUuidIsRejected() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":"99999999-9999-9999-9999-999999999999","clientText":"Nowhere A/S",
                 "personName":null,"personRole":null,"relationText":null,
                 "signalType":"OTHER","confidence":0.4}
                """, CLIENTS, null);

        assertNull(result.clientUuid(), "a uuid outside the allowlist must never be accepted");
        assertEquals("Nowhere A/S", result.clientText(),
                "the unmatched fragment is surfaced so the panel can say what it could not match");
    }

    /** The author's explicit @ pick is deterministic; the model's guess is not. */
    @Test
    void pickedClientWinsOverTheModel() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":"%s","clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"signalType":"OTHER","confidence":0.2}
                """.formatted(DANSKE), CLIENTS, ORSTED);

        assertEquals(ORSTED, result.clientUuid());
    }

    /** A picked uuid is itself re-checked — the BFF forwards it, so it is caller input too. */
    @Test
    void pickedClientOutsideTheAllowlistIsAlsoRejected() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":null,"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"signalType":"OTHER","confidence":0.0}
                """, CLIENTS, "99999999-9999-9999-9999-999999999999");

        assertNull(result.clientUuid());
    }

    /**
     * OpenAIService never throws — it reports every failure as the literal "{}" or blank.
     * A caller that only try/catches silently accepts nothing, so this is tested explicitly.
     * The capture must still be savable, which is why a picked client survives.
     */
    @Test
    void emptyObjectFromAFailedCallDegradesToAnEmptyReading() {
        for (String answer : new String[]{"{}", "  {}  ", "", "   ", null}) {
            SignalExtractionDTO result = service.parse(answer, CLIENTS, ORSTED);
            assertEquals(ORSTED, result.clientUuid(), "the picked client survives a failed call");
            assertNull(result.personName());
            assertEquals("OTHER", result.signalType());
            assertEquals(0.0d, result.confidence());
        }
    }

    @Test
    void unparseableJsonDegradesInsteadOfThrowing() {
        SignalExtractionDTO result = service.parse("not json at all", CLIENTS, ORSTED);
        assertEquals(ORSTED, result.clientUuid());
        assertEquals("OTHER", result.signalType());
    }

    /** An unknown type must not throw — a capture is never refused over a classification. */
    @Test
    void unknownSignalTypeFallsBackToOther() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":null,"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"signalType":"SOMETHING_ELSE","confidence":0.5}
                """, CLIENTS, ORSTED);

        assertEquals("OTHER", result.signalType());
    }

    @Test
    void signalTypeIsCaseInsensitive() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":null,"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"signalType":"coming_project","confidence":0.5}
                """, CLIENTS, ORSTED);

        assertEquals("COMING_PROJECT", result.signalType());
    }

    /** Structured Outputs expresses "absent" as JSON null; blanks mean the same thing. */
    @Test
    void nullsAndBlanksBothBecomeNull() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuid":null,"clientText":null,"personName":"   ","personRole":null,
                 "relationText":"","signalType":"OTHER","confidence":0.1}
                """, CLIENTS, ORSTED);

        assertNull(result.personName());
        assertNull(result.personRole());
        assertNull(result.relationText());
    }

    @Test
    void confidenceIsClampedAndDefaulted() {
        assertEquals(1.0d, service.parse(answerWithConfidence("5.0"), CLIENTS, null).confidence());
        assertEquals(0.0d, service.parse(answerWithConfidence("-2.0"), CLIENTS, null).confidence());
        assertEquals(0.0d, service.parse(answerWithConfidence("\"high\""), CLIENTS, null).confidence());
    }

    /** A null or empty allowlist must not let anything through. */
    @Test
    void noAllowlistMeansNoClient() {
        assertNull(service.parse(answerWithConfidence("0.5"), List.of(), ORSTED).clientUuid());
        assertNull(service.parse(answerWithConfidence("0.5"), null, ORSTED).clientUuid());
    }

    private static String answerWithConfidence(String confidence) {
        return """
                {"clientUuid":null,"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"signalType":"OTHER","confidence":%s}
                """.formatted(confidence);
    }
}
