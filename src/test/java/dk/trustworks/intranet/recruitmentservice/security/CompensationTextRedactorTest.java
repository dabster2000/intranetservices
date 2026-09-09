package dk.trustworks.intranet.recruitmentservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The free-text compensation backstop.
 * <p>
 * The "must mask" cases are <b>real production strings</b>, taken on
 * 2026-09-09 from candidates a live {@code RECRUITMENT_ASSISTANT} could
 * open. They are the regression suite: each one was readable in the clear
 * while the structured {@code SALARY_EXPECTATION} note beside it was
 * correctly redacted. Do not soften them without a product decision.
 * <p>
 * The "must keep" cases guard the other direction — the detector runs over
 * every note in the module, so a pattern that eats dates, Airtable record
 * ids or headcounts makes the timeline useless.
 */
@DisplayName("CompensationTextRedactor")
class CompensationTextRedactorTest {

    private static final String MASK = CompensationTextRedactor.MASK;

    @Nested
    @DisplayName("masks production leaks")
    class MasksProductionLeaks {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                // Untagged notes sitting beside a correctly-gated fact.
                "hendes lønforventning var 70.000 + pension. men hun var ydmyg i hendes udlæg",
                "Tilbudt stilling dd, takket ja til opstart per 1.10. Løn: 70.000",
                "Fantastisk samtale med Ulrich. Jeg er meget klar til at lave en aftale. Løn omkring 85.000.",
                // Airtable migration backlog.
                "Kan du lave en kontrakt til Tobias på 63k plus garanteret bonus 1 år fra startdato",
                "Hans lønpakke lige nu er 85000 + 10% pension. Han er dialog et andet sted",
                "Ja, 2. samtale. Løn forventninger ligger på 85.000 kr plus bonus og pension.",
                // AI_SUGGESTIONS_GENERATED pii — spells the field out, is not a NOTE_ADDED.
                "SALARY_EXPECTATION=70.000kr om måneden (\"Hun vil gerne tjene 70.000kr om måneden\")",
                // Interview-room note line with no fact tag at all.
                "Hun kan ikke lide at sælge. Hun vil gerne tjene 70.000kr om måneden.",
                // Dossier placeholder shapes — underscore keys defeat \b anchoring.
                "{\"EMPLOYEE_NAME\": \"Morten Hartmann\", \"BASE_SALARY\": \"85000.00\"}",
                "{\"GUARANEE_YEAR_BONUS\": \"162000\", \"AP_SALES_TARGET\": \"8000000\"}",
                // Structured comp facts, in case they ever reach a free-text surface.
                "80000 I grundløn\n5% pension\n5-10% I bonus.",
                "60.000/md. INKL. Bonus, men er åben for forhandling",
                "70.000 + 2% pension + 2x Din del af Trustworks med garanti på den første del",
        })
        void redactsEveryFigure(String leaked) {
            String redacted = CompensationTextRedactor.redact(leaked);
            assertTrue(CompensationTextRedactor.carriesCompensationAmount(leaked),
                    () -> "not detected as carrying an amount: " + leaked);
            assertTrue(redacted.contains(MASK), () -> "nothing masked in: " + redacted);
            assertFalse(redacted.matches("(?s).*\\d{5,}.*"),
                    () -> "a bare 5+ digit figure survived: " + redacted);
        }

        @Test
        @DisplayName("keeps the prose so the reader still sees THAT comp was discussed")
        void keepsProse() {
            String redacted = CompensationTextRedactor.redact(
                    "hendes lønforventning var 70.000 + pension. men hun var ydmyg");
            assertEquals("hendes lønforventning var " + MASK
                    + " + pension. men hun var ydmyg", redacted);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                // Danish compounds the salary word onto the END as often as the front.
                "Hendes månedsløn er 62.000",
                "grundløn 80000",
                "årsløn på 1,1 mio",
                // A figure with only a currency unit — no compensation noun at all.
                "Hun vil gerne tjene 70.000kr om måneden.",
                "Vi lander nok på 72.000 kr",
                "DKK 95.000 forventet",
        })
        void catchesCompoundsAndBareCurrency(String leaked) {
            assertTrue(CompensationTextRedactor.redact(leaked).contains(MASK),
                    () -> "missed: " + leaked);
        }
    }

    @Nested
    @DisplayName("leaves ordinary recruitment text alone")
    class LeavesOrdinaryTextAlone {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "17.6. sendt en mail om hvornår en samtale vil passe hende bedst. 25. juni invitation",
                "Airtable-migrering (ALL DATA, status 'Backlog', record recJrgqmrqN4WaGPC).",
                "Airtable-kommentar fra Niels (2026-07-01 13:37 UTC): Thomas og jeg har haft en snak.",
                "Hun vil først starte til oktober, gerne midt oktober.",
                "NNIT / Aeven: hardcore infra. finans - stor kunde. Sundhedssektoren 2 store kunder.",
                "Ring til ham på 20304050 i morgen",
                // løn- and kr-lookalikes: the vocabulary must not fire on these.
                "Data står i kolonne 3 med 120000 rækker",
                "Mødet var i Salon 2 med 25000 deltagere",
                "Kravene er beskrevet i 120000 ord",
                "Kristian har 150000 followers",
        })
        void returnsTheSameInstance(String ordinary) {
            assertFalse(CompensationTextRedactor.mentionsCompensation(ordinary)
                            && CompensationTextRedactor.redact(ordinary).contains(MASK),
                    () -> "wrongly masked: " + CompensationTextRedactor.redact(ordinary));
            assertSame(ordinary, CompensationTextRedactor.redact(ordinary));
        }

        @Test
        @DisplayName("a comp word with no figure is left intact")
        void compWordWithoutFigure() {
            String text = "Vi talte om løn, men hun ville ikke sætte tal på endnu.";
            assertSame(text, CompensationTextRedactor.redact(text));
            assertFalse(CompensationTextRedactor.carriesCompensationAmount(text));
        }

        @Test
        @DisplayName("ISO timestamps survive inside a masked note — the Airtable dumps are full of them")
        void keepsIsoTimestamps() {
            // Real shape from the migrated Airtable "ALL DATA" notes. Without the
            // (?<!\d:) guard the seconds read as the money shape 47.000 and the
            // note rendered "12:50:●●●Z".
            String text = "Airtable-kommentar: pension drøftet. "
                    + "\"Sidst ændret status\" : \"2026-07-01T12:50:47.000Z\", løn 70.000";
            String redacted = CompensationTextRedactor.redact(text);
            assertTrue(redacted.contains("2026-07-01T12:50:47.000Z"),
                    () -> "timestamp was mangled: " + redacted);
            assertTrue(redacted.contains("løn " + MASK), () -> "salary not masked: " + redacted);
        }

        @Test
        @DisplayName("a colon after a letter is not a timestamp — Løn:70.000 still masks")
        void colonAfterLetterStillMasks() {
            assertEquals("Løn:" + MASK, CompensationTextRedactor.redact("Løn:70.000"));
            assertEquals("Løn: " + MASK, CompensationTextRedactor.redact("Løn: 70.000"));
        }

        @Test
        @DisplayName("dates and percentages survive inside a masked note")
        void keepsDatesAndPercentages() {
            String redacted = CompensationTextRedactor.redact(
                    "Løn drøftet. Starter 2026-10-01, 5% pension, 3 børn, 61 år");
            assertTrue(redacted.contains("2026-10-01"), redacted);
            assertTrue(redacted.contains("5%"), redacted);
            assertTrue(redacted.contains("61 år"), redacted);
        }
    }

    @Nested
    @DisplayName("document walking")
    class DocumentWalking {

        @Test
        @DisplayName("masks strings at any depth and leaves keys alone")
        void masksNested() {
            Map<String, Object> pii = Map.of(
                    "text", "lønforventning 70.000",
                    "nested", Map.of("bullets", List.of("Nuværende pakke er 85000", "Bor i Aarhus")));

            Map<String, Object> masked = CompensationTextRedactor.redactDocument(pii);

            assertEquals("lønforventning " + MASK, masked.get("text"));
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) masked.get("nested");
            @SuppressWarnings("unchecked")
            List<String> bullets = (List<String>) nested.get("bullets");
            assertEquals("Nuværende pakke er " + MASK, bullets.get(0));
            assertEquals("Bor i Aarhus", bullets.get(1));
        }

        @Test
        @DisplayName("returns the identical instance when nothing matches — the caller's masked flag depends on it")
        void identityWhenClean() {
            Map<String, Object> pii = Map.of("text", "Hun starter 1. oktober");
            assertSame(pii, CompensationTextRedactor.redactDocument(pii));
            assertFalse(CompensationTextRedactor.documentCarriesCompensationAmount(pii));
        }

        @Test
        @DisplayName("non-string leaves are untouched")
        void leavesNonStrings() {
            Map<String, Object> pii = Map.of("count", 85000, "flag", true);
            assertSame(pii, CompensationTextRedactor.redactDocument(pii));
        }
    }
}
