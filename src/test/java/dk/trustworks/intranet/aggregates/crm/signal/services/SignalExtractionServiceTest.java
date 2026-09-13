package dk.trustworks.intranet.aggregates.crm.signal.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalExtractionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the backend does to a model answer before it is allowed to matter.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}, no database, no network — so it runs in the
 * DB-free fast tier that gates deploys. It exercises
 * {@link SignalExtractionService#parse} directly, which is why that method is
 * package-private and free of injected state.
 *
 * <p>The load-bearing cases are {@link #hallucinatedClientUuidIsRejected()} and
 * {@link #hallucinatedColleagueUuidIsRejected()}: the model is given two allowlists and
 * its answer is re-checked against both, so a hallucinated or forged uuid can never
 * attach a colleague's signal to someone else's client, nor assert that an employee who
 * was never mentioned knows a named third party.
 */
class SignalExtractionServiceTest {

    private static final String ORSTED = "11111111-1111-1111-1111-111111111111";
    private static final String DANSKE = "22222222-2222-2222-2222-222222222222";
    private static final List<String[]> CLIENTS = List.of(
            new String[]{ORSTED, "Ørsted"},
            new String[]{DANSKE, "Danske Bank"});

    private static final String TOBIAS = "33333333-3333-3333-3333-333333333333";
    private static final String METTE = "44444444-4444-4444-4444-444444444444";
    private static final List<String[]> COLLEAGUES = List.of(
            new String[]{TOBIAS, "Tobias Kjølsen"},
            new String[]{METTE, "Mette Hansen"});

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
                {"clientUuids":["%s"],"clientText":null,"personName":"Benny Hoffmann",
                 "personRole":"Head of AI (new)",
                 "relationText":"Hans knows Benny Hoffmann from school",
                 "colleagueUuids":[],
                 "signalType":"ORG_CHANGE","confidence":0.9}
                """.formatted(ORSTED), CLIENTS, COLLEAGUES, List.of());

        assertEquals(List.of(ORSTED), result.clientUuids());
        assertEquals("Benny Hoffmann", result.personName());
        assertEquals("Head of AI (new)", result.personRole());
        assertEquals("Hans knows Benny Hoffmann from school", result.relationText());
        assertEquals("ORG_CHANGE", result.signalType());
        assertEquals(0.9d, result.confidence());
        assertEquals(List.of(), result.colleagueUuids());
        assertNull(result.clientText(), "clientText is only for the unmatched case");
    }

    // ------------------------------------------------------------------
    // V593: the defect this release exists for
    // ------------------------------------------------------------------

    /**
     * The production line that started this: <i>"@Rigspolitiet … som jeg har mødt i
     * @KOMBIT sammen Tobias Kjølsen"</i>. Two accounts and one colleague in one sentence.
     *
     * <p>It used to store one row, on the account the author happened to pick LAST, and
     * discard the colleague entirely. Both halves must survive the parse.
     */
    @Test
    void aLineNamingTwoAccountsAndAColleagueKeepsAllOfThem() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":["%s","%s"],"clientText":null,"personName":"Dorte",
                 "personRole":null,
                 "relationText":"Hans and Tobias met Dorte at Danske Bank",
                 "colleagueUuids":["%s"],
                 "signalType":"COMING_PROJECT","confidence":0.7}
                """.formatted(ORSTED, DANSKE, TOBIAS), CLIENTS, COLLEAGUES, List.of());

        assertEquals(List.of(ORSTED, DANSKE), result.clientUuids(),
                "both accounts must survive, in the order the line named them");
        assertEquals(List.of(TOBIAS), result.colleagueUuids());
        assertEquals("Dorte", result.personName());
    }

    /**
     * A pick no longer SUPPRESSES the model's reading — it leads it.
     *
     * <p>This is the exact regression that lost Rigspolitiet: the picked client used to
     * replace everything the model found, so a second account it had correctly read was
     * unreachable. The picks come first because they are the author's own order, and what
     * the model found beyond them is the tail the panel offers as "you also mentioned X".
     */
    @Test
    void picksComeFirstAndDoNotHideWhatTheModelAlsoFound() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":["%s"],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":[],"signalType":"OTHER","confidence":0.2}
                """.formatted(ORSTED), CLIENTS, COLLEAGUES, List.of(DANSKE));

        assertEquals(List.of(DANSKE, ORSTED), result.clientUuids(),
                "the author's pick leads; the model's extra reading follows it");
    }

    /** A client the author picked AND the model read is one client, not two. */
    @Test
    void aClientBothPickedAndReadAppearsOnce() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":["%s","%s"],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":[],"signalType":"OTHER","confidence":0.2}
                """.formatted(ORSTED, DANSKE), CLIENTS, COLLEAGUES, List.of(ORSTED));

        assertEquals(List.of(ORSTED, DANSKE), result.clientUuids());
    }

    /**
     * The allowlist re-check, colleague side. Accepting an unverified uuid here would
     * assert in the relationship graph that a named employee knows a named third party —
     * worse than a wrong account, not better.
     */
    @Test
    void hallucinatedColleagueUuidIsRejected() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":["%s"],"clientText":null,"personName":"Dorte","personRole":null,
                 "relationText":null,
                 "colleagueUuids":["99999999-9999-9999-9999-999999999999","%s"],
                 "signalType":"OTHER","confidence":0.4}
                """.formatted(ORSTED, METTE), CLIENTS, COLLEAGUES, List.of());

        assertEquals(List.of(METTE), result.colleagueUuids(),
                "only uuids that were in the colleague allowlist may come back");
    }

    /** A colleague list the model answered as something other than an array is dropped. */
    @Test
    void malformedColleagueListDegradesToEmpty() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":["%s"],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":"Tobias","signalType":"OTHER","confidence":0.1}
                """.formatted(ORSTED), CLIENTS, COLLEAGUES, List.of());

        assertEquals(List.of(), result.colleagueUuids());
        assertEquals(List.of(ORSTED), result.clientUuids(), "the rest of the reading survives");
    }

    /** No colleague allowlist means no colleague can come back, whatever the model says. */
    @Test
    void noColleagueAllowlistMeansNoColleagues() {
        String answer = """
                {"clientUuids":[],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":["%s"],"signalType":"OTHER","confidence":0.1}
                """.formatted(TOBIAS);

        assertEquals(List.of(), service.parse(answer, CLIENTS, List.of(), List.of()).colleagueUuids());
        assertEquals(List.of(), service.parse(answer, CLIENTS, null, List.of()).colleagueUuids());
    }

    // ------------------------------------------------------------------
    // Validation that predates V593 and must keep holding
    // ------------------------------------------------------------------

    /**
     * The allowlist re-check. A uuid the model invented is not in the list we sent, so it
     * is dropped rather than resolving to a real client that happens to exist.
     */
    @Test
    void hallucinatedClientUuidIsRejected() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":["99999999-9999-9999-9999-999999999999"],"clientText":"Nowhere A/S",
                 "personName":null,"personRole":null,"relationText":null,"colleagueUuids":[],
                 "signalType":"OTHER","confidence":0.4}
                """, CLIENTS, COLLEAGUES, List.of());

        assertTrue(result.clientUuids().isEmpty(), "a uuid outside the allowlist must never be accepted");
        assertEquals("Nowhere A/S", result.clientText(),
                "the unmatched fragment is surfaced so the panel can say what it could not match");
    }

    /** A picked uuid is itself re-checked — the BFF forwards it, so it is caller input too. */
    @Test
    void pickedClientOutsideTheAllowlistIsAlsoRejected() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":[],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":[],"signalType":"OTHER","confidence":0.0}
                """, CLIENTS, COLLEAGUES, List.of("99999999-9999-9999-9999-999999999999"));

        assertTrue(result.clientUuids().isEmpty());
    }

    /**
     * OpenAIService never throws — it reports every failure as the literal "{}" or blank.
     * A caller that only try/catches silently accepts nothing, so this is tested explicitly.
     * The capture must still be savable, which is why the picked clients survive.
     */
    @Test
    void emptyObjectFromAFailedCallDegradesToAnEmptyReading() {
        for (String answer : new String[]{"{}", "  {}  ", "", "   ", null}) {
            SignalExtractionDTO result = service.parse(answer, CLIENTS, COLLEAGUES, List.of(ORSTED));
            assertEquals(List.of(ORSTED), result.clientUuids(), "the picked client survives a failed call");
            assertEquals(List.of(), result.colleagueUuids());
            assertNull(result.personName());
            assertEquals("OTHER", result.signalType());
            assertEquals(0.0d, result.confidence());
        }
    }

    @Test
    void unparseableJsonDegradesInsteadOfThrowing() {
        SignalExtractionDTO result = service.parse("not json at all", CLIENTS, COLLEAGUES, List.of(ORSTED));
        assertEquals(List.of(ORSTED), result.clientUuids());
        assertEquals("OTHER", result.signalType());
    }

    /** An unknown type must not throw — a capture is never refused over a classification. */
    @Test
    void unknownSignalTypeFallsBackToOther() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":[],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":[],"signalType":"SOMETHING_ELSE","confidence":0.5}
                """, CLIENTS, COLLEAGUES, List.of(ORSTED));

        assertEquals("OTHER", result.signalType());
    }

    @Test
    void signalTypeIsCaseInsensitive() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":[],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":[],"signalType":"coming_project","confidence":0.5}
                """, CLIENTS, COLLEAGUES, List.of(ORSTED));

        assertEquals("COMING_PROJECT", result.signalType());
    }

    /** Structured Outputs expresses "absent" as JSON null; blanks mean the same thing. */
    @Test
    void nullsAndBlanksBothBecomeNull() {
        SignalExtractionDTO result = service.parse("""
                {"clientUuids":[],"clientText":null,"personName":"   ","personRole":null,
                 "relationText":"","colleagueUuids":[],"signalType":"OTHER","confidence":0.1}
                """, CLIENTS, COLLEAGUES, List.of(ORSTED));

        assertNull(result.personName());
        assertNull(result.personRole());
        assertNull(result.relationText());
    }

    @Test
    void confidenceIsClampedAndDefaulted() {
        assertEquals(1.0d, service.parse(answerWithConfidence("5.0"), CLIENTS, COLLEAGUES, List.of()).confidence());
        assertEquals(0.0d, service.parse(answerWithConfidence("-2.0"), CLIENTS, COLLEAGUES, List.of()).confidence());
        assertEquals(0.0d, service.parse(answerWithConfidence("\"high\""), CLIENTS, COLLEAGUES, List.of()).confidence());
    }

    /** A null or empty allowlist must not let anything through. */
    @Test
    void noAllowlistMeansNoClient() {
        assertTrue(service.parse(answerWithConfidence("0.5"), List.of(), COLLEAGUES, List.of(ORSTED))
                .clientUuids().isEmpty());
        assertTrue(service.parse(answerWithConfidence("0.5"), null, COLLEAGUES, List.of(ORSTED))
                .clientUuids().isEmpty());
    }

    private static String answerWithConfidence(String confidence) {
        return """
                {"clientUuids":[],"clientText":null,"personName":null,"personRole":null,
                 "relationText":null,"colleagueUuids":[],"signalType":"OTHER","confidence":%s}
                """.formatted(confidence);
    }
}
