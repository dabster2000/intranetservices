package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.slack.ai.SlackMentionPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything a general channel is allowed to put in the database. The model chooses the
 * account here, so {@link SlackMentionExtractionService#parse} re-checks every choice it
 * made, and this class is what stands between the two: hand-written model answers in,
 * validated readings out, no Quarkus boot, no OpenAI, no database.
 *
 * <p>The rule worth reading twice is D13 — a mention that cannot point at a line of the
 * day is discarded whole. A reading nobody can point at is indistinguishable from an
 * invented one, and the only honest thing to do with an invented reading about a client
 * is to not have it.
 *
 * <p>The second rule worth reading twice is the one about emptiness. Nearly every test here
 * asserts that something was NOT filed, and the lane has two ways of filing nothing that
 * look identical from the outside and mean opposite things: a day that held nothing about
 * any account, which closes and never comes back, and a model call that never happened,
 * which must leave the day open. Every assertion about emptiness below therefore also says
 * which of the two it is.
 *
 * <p>Fast tier — the DB-free tier the deploy gate runs.
 */
class SlackMentionExtractionServiceTest {

    private static final String ACME = "11111111-1111-1111-1111-111111111111";
    private static final String BETA = "22222222-2222-2222-2222-222222222222";

    /** An id the run never showed the model, so the only way it could appear is invention. */
    private static final String INVENTED = "99999999-9999-9999-9999-999999999999";

    private static final Set<String> ALLOWLIST = Set.of(ACME, BETA);

    /**
     * What {@code colleagueKeys} builds in production: the own-company deny-list, every
     * colleague's name, and the first name the prompt actually renders.
     */
    private static final Set<String> COLLEAGUES =
            Set.of("trustworks", "tw", "intra", "nicolas", "nicolas vestergaard", "marta");

    private SlackMentionExtractionService service;

    @BeforeEach
    void setUp() {
        service = new SlackMentionExtractionService();
        service.objectMapper = new ObjectMapper();
        service.mentionModel = "test-model";
    }

    // ------------------------------------------------------------------------
    // Degradation: OpenAIService never throws, it answers "{}"
    // ------------------------------------------------------------------------

    @Test
    void noOutputIsAFailedCallAndNotAnEmptyDay() {
        // OpenAIService never throws: a rate limit, a timeout and an exhausted token budget
        // all come back as the literal empty object. Reading that as "this day held nothing
        // about any account" is how a day is lost for ever — the cursor advances past it in
        // the same transaction and the window is ts > cursor.
        assertFailed(read(null, 10));
        assertFailed(read("", 10));
        assertFailed(read("   ", 10));
        assertFailed(read("{}", 10));
        assertFailed(read(" {} ", 10));
    }

    @Test
    void anAnswerThatCannotCarryMentionsIsAFailedCallToo() {
        // Strict Structured Outputs cannot produce any of these, so they are the transport
        // failing rather than the model answering.
        assertFailed(read("not json", 10));
        assertFailed(read("[1,2]", 10));
        assertFailed(read("{\"mentions\":{}}", 10));
    }

    @Test
    void aRefusalIsAnEmptyDayNotAFailure() {
        // The refusal fallback is schema-conformant and says something true: nothing about
        // anybody. The day closes on it like any other quiet day.
        assertNothing(read(SlackMentionPrompts.REFUSAL_FALLBACK_JSON, 10));
    }

    @Test
    void anAnswerTheValidationEmptiesIsStillAnAnswer() {
        // The commonest shape of all: a well-formed reading that every check throws away. It
        // is not a failed call and must not hold the channel's cursor back.
        assertNothing(read(call(mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[99]")), 10));
        assertNothing(read("{\"mentions\":[]}", 10));
    }

    @Test
    void anythingThatIsNotAMentionObjectIsSkipped() {
        var reading = read("{\"mentions\":[\"x\",1,null,[],"
                + mention(byId(ACME), "Kundemøde hos Acme torsdag", "LOW", "[1]") + "]}", 10);
        assertEquals(1, reading.mentions().size());
    }

    // ------------------------------------------------------------------------
    // 1. Attribution — an id is worth having only if this run showed it to the model
    // ------------------------------------------------------------------------

    @Test
    void anIdTheRunShowedTheModelIsTheAccountItIsFiledAgainst() {
        var reading = read(call(mention(byId(ACME), "Kundemøde hos Acme torsdag", "LOW", "[2]")), 10);
        assertEquals(1, reading.mentions().size());
        assertEquals(ACME, reading.mentions().get(0).clientUuid());
        assertTrue(reading.unmatched().isEmpty());
    }

    @Test
    void anIdOffTheAllowlistIsASightingNotAMention() {
        var reading = read(call(mention("\"clientId\":\"" + INVENTED + "\",\"companyName\":\"NN Markets\"",
                "Kundemøde hos NN torsdag", "LOW", "[1]")), 10);
        assertTrue(reading.mentions().isEmpty(), "an invented uuid is never attributed to an account");
        assertEquals(1, reading.unmatched().size());
        assertEquals("NN Markets", reading.unmatched().get(0).displayName());
        assertEquals("nn markets", reading.unmatched().get(0).nameKey());
    }

    @Test
    void neitherAnIdNorANameIsNotAMentionAtAll() {
        assertNothing(read(call(mention(byId(INVENTED), "h", "LOW", "[1]")), 10));
        assertNothing(read(call(mention(byName("   "), "h", "LOW", "[1]")), 10));
        assertNothing(read(call(mention(byName(null), "h", "LOW", "[1]")), 10));
    }

    @Test
    void aLinkedNameIsPromotedToTheAccountSomebodyLinkedItTo() {
        var reading = service.parse(call(mention(byName("  NN   Markets "), "Kundemøde hos NN", "LOW", "[1]")),
                ALLOWLIST, 10, Map.of("nn markets", BETA), COLLEAGUES);
        assertEquals(1, reading.mentions().size());
        assertEquals(BETA, reading.mentions().get(0).clientUuid(),
                "the LINKED map answers by nameKey, so spelling and spacing do not matter");
        assertTrue(reading.unmatched().isEmpty(), "a linked name has stopped being a hint");
    }

    @Test
    void aNameThatNobodyHasLinkedStaysAHint() {
        var reading = service.parse(call(mention(byName("NN Markets"), "Kundemøde hos NN", "LOW", "[1]")),
                ALLOWLIST, 10, Map.of("anden virksomhed", BETA), COLLEAGUES);
        assertTrue(reading.mentions().isEmpty());
        assertEquals(1, reading.unmatched().size());
    }

    @Test
    void theNameKeyIsWhatOneCompanyIsCountedBy() {
        assertEquals("nn markets", SlackMentionExtractionService.nameKey("  NN   Markets  "));
        assertEquals("nn markets", SlackMentionExtractionService.nameKey("nn markets"));
        assertEquals("", SlackMentionExtractionService.nameKey(null));
        assertEquals("", SlackMentionExtractionService.nameKey("   "));
    }

    /**
     * {@code slack_unmatched_company.name_key} is the PRIMARY KEY under
     * {@code utf8mb4_general_ci}, which reads an accented Latin letter as its base one. Two
     * keys here that the collation reads as one are not two hints — they are ERROR 1062 at
     * flush, and with them the whole day's transaction and the cursor that moved inside it.
     */
    @Test
    void anAccentedNameFoldsHereBecauseTheCollationFoldsItThere() {
        assertEquals("nestle", SlackMentionExtractionService.nameKey("Nestlé"));
        assertEquals(SlackMentionExtractionService.nameKey("Nestle"),
                SlackMentionExtractionService.nameKey("Nestlé"),
                "one colleague writes it with the accent and the next one without, on the same day");
        assertEquals(SlackMentionExtractionService.nameKey("Nestle\u0301"),
                SlackMentionExtractionService.nameKey("Nestlé"),
                "a pasted name can arrive decomposed; NFD is what makes both spellings one key");
        assertEquals("saint gobain", SlackMentionExtractionService.nameKey("Saint-Göbain"));
        assertEquals("arhus kommune", SlackMentionExtractionService.nameKey("Århus Kommune"),
                "å decomposes, and general_ci folds it to a as well");
    }

    /**
     * The fold goes exactly as far as the collation's and no further. æ and ø have no
     * canonical decomposition and {@code general_ci} leaves them alone, so folding them here
     * would file two real companies as one hint — the one direction of this rule that is not
     * safe to get wrong in the generous direction.
     */
    @Test
    void theDanishLettersAreTheirOwnLettersAtBothEnds() {
        assertEquals("ørsted", SlackMentionExtractionService.nameKey("Ørsted"));
        assertNotEquals(SlackMentionExtractionService.nameKey("Orsted"),
                SlackMentionExtractionService.nameKey("Ørsted"));
        assertEquals("ærø færgen", SlackMentionExtractionService.nameKey("Ærø Færgen"));
    }

    // ------------------------------------------------------------------------
    // 2. Evidence (D13) — no valid evidence means no row
    // ------------------------------------------------------------------------

    @Test
    void aMentionThatCitesNothingIsNotARow() {
        assertNothing(read(call(mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[]")), 10));
        assertNothing(read(call(mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "null")), 10));
        assertNothing(read(call(mention(byName("NN Markets"), "Kundemøde hos NN", "HIGH", "[]")), 10));
    }

    @Test
    void aMentionThatCitesOnlyLinesTheDayNeverHadIsNotARow() {
        assertNothing(read(call(mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[0,-3,11,99]")), 10));
        assertNothing(read(call(mention(byName("NN Markets"), "Kundemøde hos NN", "HIGH", "[11]")), 10));
    }

    @Test
    void evidenceOutOfRangeIsFilteredAndTheRestSurvives() {
        var reading = read(call(mention(byId(ACME), "Acme har mistet deres deadline", "LOW",
                "[0,7,3,11,3,\"4\",true]")), 10);
        assertEquals(List.of(3, 7), reading.mentions().get(0).evidence(),
                "in range only, lowest first, one entry per cited message");
    }

    @Test
    void evidenceIsCappedAtTwentyLinesLowestFirst() {
        StringBuilder lines = new StringBuilder();
        for (int line = 30; line >= 1; line--) {
            if (line < 30) {
                lines.append(',');
            }
            lines.append(line);
        }
        var reading = read(call(mention(byId(ACME), "Acme har mistet deres deadline", "LOW",
                "[" + lines + "]")), 30);
        List<Integer> evidence = reading.mentions().get(0).evidence();
        assertEquals(SlackMentionExtractionService.MAX_EVIDENCE_PER_MENTION, evidence.size());
        assertEquals(1, evidence.get(0).intValue(), "the first cited line is the row's permalink, so it must be the earliest");
        assertEquals(20, evidence.get(evidence.size() - 1).intValue());
    }

    // ------------------------------------------------------------------------
    // 3. A colleague, or ourselves, is not a prospect
    // ------------------------------------------------------------------------

    @Test
    void aColleagueOrOurselvesIsDroppedAndCounted() {
        var reading = read(call(
                mention(byName("Nicolas"), "Nicolas er tilbage på mandag", "LOW", "[1]"),
                mention(byName("Nicolas Vestergaard"), "Nicolas er tilbage på mandag", "LOW", "[2]"),
                mention(byName("Trustworks"), "Trustworks er blevet inviteret til udbuddet", "HIGH", "[3]"),
                mention(byName("TW"), "TW skal levere en arkitekt", "LOW", "[4]"),
                mention(byName("intra"), "intra er nede", "LOW", "[5]"),
                mention(byName("NN Markets"), "Kundemøde hos NN torsdag", "LOW", "[6]")), 10);
        assertEquals(5, reading.droppedAsColleague());
        assertTrue(reading.mentions().isEmpty());
        assertEquals(1, reading.unmatched().size(), "only the company that is really a company survives");
        assertEquals("NN Markets", reading.unmatched().get(0).displayName());
    }

    @Test
    void anInventedMentionOfAColleagueDoesNotInflateTheCount() {
        // The order in parse is load-bearing: evidence is checked before the colleague test,
        // so an admin reading droppedAsColleague sees mistakes, not hallucinations.
        assertNothing(read(call(mention(byName("Nicolas"), "Nicolas er tilbage på mandag", "LOW", "[99]")), 10));
    }

    @Test
    void aColleagueNameBesideAKnownIdIsNotADrop() {
        var reading = read(call(mention("\"clientId\":\"" + ACME + "\",\"companyName\":\"Nicolas\"",
                "Kundemøde hos Acme torsdag", "LOW", "[1]")), 10);
        assertEquals(1, reading.mentions().size(), "the id decided it; the name was never read as a company");
        assertEquals(0, reading.droppedAsColleague());
    }

    // ------------------------------------------------------------------------
    // 4. Without a headline there is no row
    // ------------------------------------------------------------------------

    @Test
    void withoutAHeadlineThereIsNoRow() {
        assertNothing(read(call(mention(byId(ACME), null, "HIGH", "[1]")), 10));
        assertNothing(read(call(mention(byId(ACME), "   ", "HIGH", "[1]")), 10));
        assertNothing(read(call(mention(byName("NN Markets"), null, "HIGH", "[1]")), 10));
    }

    // ------------------------------------------------------------------------
    // 5. The shared fields through the digest lane's own helpers and caps
    // ------------------------------------------------------------------------

    @Test
    void theDigestLanesOwnCapsApplyToAMention() {
        String json = "{" + byId(ACME)
                + ",\"headline\":\"" + "h".repeat(400) + "\",\"relevance\":\"HIGH\","
                + "\"decisions\":" + itemsJson("d", 20) + ","
                + "\"nextSteps\":[{\"text\":\"" + "n".repeat(400) + "\",\"who\":\"" + "w".repeat(100)
                + "\",\"when\":\"torsdag\"}],"
                + "\"risks\":[],\"clientAsks\":[],"
                + "\"clientPeople\":" + peopleJson("P", 14) + ","
                + "\"topics\":" + topicsJson("t", 9) + ","
                + "\"confidence\":7,\"evidence\":[1]}";
        SlackDigestContent content = read(call(json), 10).mentions().get(0).content();

        assertEquals(AccountSlackDigestService.MAX_HEADLINE_CHARS, content.headline().length());
        assertEquals(AccountSlackDigestService.MAX_ITEMS_PER_LIST, content.decisions().size());
        assertEquals(AccountSlackDigestService.MAX_TEXT_CHARS, content.nextSteps().get(0).text().length());
        assertEquals(AccountSlackDigestService.MAX_WHO_CHARS, content.nextSteps().get(0).who().length());
        assertNull(content.nextSteps().get(0).when(), "a date that is not ISO is dropped, not guessed");
        assertEquals(AccountSlackDigestService.MAX_PEOPLE, content.clientPeople().size());
        assertEquals(AccountSlackDigestService.MAX_TOPICS, content.topics().size());
        assertEquals(1.0d, content.confidence(), 0.0001, "clamped");
    }

    @Test
    void aCompanyNameIsCutToWhatTheHintsColumnHolds() {
        var reading = read(call(mention(byName("N".repeat(200)), "Kundemøde hos NN", "LOW", "[1]")), 10);
        String displayName = reading.unmatched().get(0).displayName();
        assertEquals(SlackMentionExtractionService.MAX_COMPANY_NAME_CHARS, displayName.length());
        assertEquals(displayName.toLowerCase(Locale.ROOT), reading.unmatched().get(0).nameKey(),
                "the key is derived from the cut name, so it can never outgrow the column either");
    }

    @Test
    void aStoredRowIsNeverNone() {
        assertEquals(SlackDigestContent.RELEVANCE_LOW, relevanceOf("NONE"));
        assertEquals(SlackDigestContent.RELEVANCE_LOW, relevanceOf("banana"));
        assertEquals(SlackDigestContent.RELEVANCE_LOW, relevanceOf(null));
        assertEquals(SlackDigestContent.RELEVANCE_LOW, relevanceOf("LOW"));
        assertEquals(SlackDigestContent.RELEVANCE_HIGH, relevanceOf("HIGH"));
    }

    @Test
    void aDecisionMakesTheRowHighWhateverTheModelLabelledIt() {
        String json = "{" + byId(ACME) + ",\"headline\":\"Acme kører videre uden reelle ejere\","
                + "\"relevance\":\"NONE\","
                + "\"decisions\":[{\"text\":\"Systemet bygges uden reelle ejere\",\"who\":\"Lars\",\"when\":null}],"
                + "\"nextSteps\":[],\"risks\":[],\"clientAsks\":[],\"clientPeople\":[],\"topics\":[],"
                + "\"confidence\":0.9,\"evidence\":[1]}";
        assertEquals(SlackDigestContent.RELEVANCE_HIGH,
                read(call(json), 10).mentions().get(0).content().relevance());
    }

    // ------------------------------------------------------------------------
    // 6. The flood guard
    // ------------------------------------------------------------------------

    @Test
    void twentyMentionsPerCallWithTheHighOnesKeptFirst() {
        List<String> ids = new ArrayList<>();
        List<String> mentions = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String uuid = String.format(Locale.ROOT, "%08d-0000-0000-0000-000000000000", i);
            ids.add(uuid);
            mentions.add(mention(byId(uuid), "mention " + i, i % 5 == 0 ? "HIGH" : "LOW", "[1]"));
        }
        var reading = service.parse(call(mentions.toArray(String[]::new)),
                Set.copyOf(ids), 10, Map.of(), COLLEAGUES);

        assertEquals(SlackMentionExtractionService.MAX_MENTIONS_PER_CALL, reading.mentions().size());
        for (int i = 0; i < 25; i += 5) {
            assertTrue(holds(reading, ids.get(i)), "every HIGH one survives the cut: " + i);
        }
        assertTrue(holds(reading, ids.get(18)), "the LOW ones are kept in the model's own order");
        assertFalse(holds(reading, ids.get(19)), "the cut falls on the tail of the LOW ones");
        assertFalse(holds(reading, ids.get(24)));
    }

    // ------------------------------------------------------------------------
    // 7. Merge — one client, one row, however many times the call named it
    // ------------------------------------------------------------------------

    @Test
    void twoMentionsOfOneClientAreOneRow() {
        String morning = "{" + byId(ACME) + ",\"headline\":\"Kundemøde hos Acme torsdag\",\"relevance\":\"HIGH\","
                + "\"decisions\":[{\"text\":\"Alfa\",\"who\":\"Lars\",\"when\":null}],"
                + "\"nextSteps\":[],\"risks\":[{\"text\":\"Deadline i fare\"}],\"clientAsks\":[],"
                + "\"clientPeople\":[{\"name\":\"Lars\",\"role\":\"CIO\"}],"
                + "\"topics\":[\"møde\"],\"confidence\":0.9,\"evidence\":[3,1]}";
        String afternoon = "{" + byId(ACME) + ",\"headline\":\"Acme har mistet deres deadline\",\"relevance\":\"HIGH\","
                + "\"decisions\":[{\"text\":\"alfa\",\"who\":null,\"when\":null},"
                + "{\"text\":\"Beta\",\"who\":null,\"when\":null}],"
                + "\"nextSteps\":[],\"risks\":[],\"clientAsks\":[],"
                + "\"clientPeople\":[{\"name\":\"lars\",\"role\":null},{\"name\":\"Laura\",\"role\":null}],"
                + "\"topics\":[\"MØDE\",\"deadline\"],\"confidence\":0.4,\"evidence\":[9]}";
        var reading = read(call(morning, afternoon), 10);

        assertEquals(1, reading.mentions().size());
        SlackDigestContent content = reading.mentions().get(0).content();
        assertEquals("Kundemøde hos Acme torsdag; Acme har mistet deres deadline", content.headline());
        assertEquals(SlackDigestContent.RELEVANCE_HIGH, content.relevance());
        assertEquals(List.of("Alfa", "Beta"),
                content.decisions().stream().map(SlackDigestContent.Item::text).toList(),
                "the union in the order the day said it, deduped without regard to case");
        assertEquals(1, content.risks().size());
        assertEquals(List.of("Lars", "Laura"),
                content.clientPeople().stream().map(SlackDigestContent.Person::name).toList());
        assertEquals(List.of("møde", "deadline"), content.topics());
        assertEquals(0.4d, content.confidence(), 0.0001,
                "a row is only as trustworthy as its weakest reading");
        assertEquals(List.of(1, 3, 9), reading.mentions().get(0).evidence());
    }

    @Test
    void aLowHeadlineIsDroppedTheMomentAHighOneExists() {
        var reading = read(call(
                mention(byId(ACME), "Møde med Claims i den kommende uge", "LOW", "[1]"),
                mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[2]")), 10);
        assertEquals(1, reading.mentions().size());
        assertEquals("Acme har mistet deres deadline", reading.mentions().get(0).content().headline());
        assertEquals(SlackDigestContent.RELEVANCE_HIGH, reading.mentions().get(0).content().relevance());
    }

    @Test
    void twoHighHeadlinesAreJoinedAndCutAtTheColumn() {
        var reading = read(call(
                mention(byId(ACME), "a".repeat(150), "HIGH", "[1]"),
                mention(byId(ACME), "b".repeat(150), "HIGH", "[2]")), 10);
        String headline = reading.mentions().get(0).content().headline();
        assertEquals(AccountSlackDigestService.MAX_HEADLINE_CHARS, headline.length());
        assertTrue(headline.startsWith("a".repeat(150) + "; b"));
    }

    @Test
    void theSameHeadlineTwiceIsSaidOnce() {
        var reading = read(call(
                mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[1]"),
                mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[2]")), 10);
        assertEquals("Acme har mistet deres deadline", reading.mentions().get(0).content().headline());
    }

    @Test
    void theMergedListsAreReCapped() {
        String first = "{" + byId(ACME) + ",\"headline\":\"Acme, formiddag\",\"relevance\":\"HIGH\","
                + "\"decisions\":" + itemsJson("a", 8) + ","
                + "\"nextSteps\":[],\"risks\":" + notesJson("ra", 8) + ",\"clientAsks\":[],"
                + "\"clientPeople\":" + peopleJson("PA", 10) + ","
                + "\"topics\":" + topicsJson("ta", 6) + ",\"confidence\":0.9,\"evidence\":[1]}";
        String second = "{" + byId(ACME) + ",\"headline\":\"Acme, eftermiddag\",\"relevance\":\"HIGH\","
                + "\"decisions\":" + itemsJson("b", 8) + ","
                + "\"nextSteps\":[],\"risks\":" + notesJson("rb", 8) + ",\"clientAsks\":[],"
                + "\"clientPeople\":" + peopleJson("PB", 10) + ","
                + "\"topics\":" + topicsJson("tb", 6) + ",\"confidence\":0.9,\"evidence\":[2]}";
        SlackDigestContent content = read(call(first, second), 10).mentions().get(0).content();

        assertEquals(AccountSlackDigestService.MAX_ITEMS_PER_LIST, content.decisions().size());
        assertEquals("a0", content.decisions().get(0).text());
        assertEquals("a7", content.decisions().get(7).text(), "the cap holds and the earlier reading wins it");
        assertEquals(AccountSlackDigestService.MAX_ITEMS_PER_LIST, content.risks().size());
        assertEquals(AccountSlackDigestService.MAX_PEOPLE, content.clientPeople().size());
        assertEquals("PA0", content.clientPeople().get(0).name());
        assertEquals(AccountSlackDigestService.MAX_TOPICS, content.topics().size());
        assertEquals("ta0", content.topics().get(0));
    }

    @Test
    void theMergedEvidenceIsDeliberatelyNotReCapped() {
        // MAX_EVIDENCE_PER_MENTION is per model answer. The union is what the row's
        // participants and message count rest on, so cutting it would under-report the
        // colleagues who genuinely wrote about the client.
        var reading = read(call(
                mention(byId(ACME), "Acme, formiddag", "HIGH", range(1, 20)),
                mention(byId(ACME), "Acme, eftermiddag", "HIGH", range(21, 40))), 40);
        assertEquals(40, reading.mentions().get(0).evidence().size());
        assertEquals(1, reading.mentions().get(0).evidence().get(0).intValue());
        assertEquals(40, reading.mentions().get(0).evidence().get(39).intValue());
    }

    @Test
    void oneCompanyPerDayHoweverOftenTheCallNamedIt() {
        var reading = read(call(
                mention(byName("NN Markets"), "Kundemøde hos NN torsdag", "LOW", "[5]"),
                mention(byName("nn   markets"), "NN spørger til prisen", "LOW", "[2]"),
                mention(byName("  NN MARKETS  "), "NN har sagt ja", "HIGH", "[2,7]")), 10);
        assertEquals(1, reading.unmatched().size());
        SlackMentionExtractionService.UnmatchedSighting sighting = reading.unmatched().get(0);
        assertEquals("NN Markets", sighting.displayName(), "the spelling first seen wins");
        assertEquals("nn markets", sighting.nameKey());
        assertEquals(List.of(2, 5, 7), sighting.evidence());
    }

    @Test
    void twoDifferentClientsStayTwoRows() {
        var reading = read(call(
                mention(byId(ACME), "Acme har mistet deres deadline", "HIGH", "[1]"),
                mention(byId(BETA), "Beta vil gerne mødes", "LOW", "[2]")), 10);
        assertEquals(2, reading.mentions().size());
        assertTrue(holds(reading, ACME));
        assertTrue(holds(reading, BETA));
    }

    // ------------------------------------------------------------------------
    // Privacy — what the backend guarantees structurally, not what the prompt asks for
    // ------------------------------------------------------------------------

    @Test
    void personalDetailAboutAColleagueCannotReachAStoredField() {
        // A day of a general channel is mostly none of the CRM's business. The prompt says
        // so; this is what holds when the model ignores it — every string the row keeps has
        // been through the digest lane's own cleaner, which is where addresses, links and
        // numbers stop.
        String json = "{" + byId(ACME)
                + ",\"headline\":\"Nicolas er på skadestuen, ring 12 34 56 78 eller nicolas@trustworks.dk\","
                + "\"relevance\":\"HIGH\","
                + "\"decisions\":[{\"text\":\"Se journalen på https://intra.trustworks.dk/hr/nicolas\","
                + "\"who\":\"Nicolas <nicolas@trustworks.dk>\",\"when\":null}],"
                + "\"nextSteps\":[],\"risks\":[],\"clientAsks\":[],"
                + "\"clientPeople\":[{\"name\":\"Nicolas (nicolas@trustworks.dk)\",\"role\":null}],"
                + "\"topics\":[\"https://intra.trustworks.dk/hr\"],\"confidence\":1,\"evidence\":[1]}";
        SlackDigestContent content = read(call(json), 10).mentions().get(0).content();

        assertEquals("Nicolas er på skadestuen, ring [phone] eller [e-mail]", content.headline());
        assertEquals("Se journalen på [link]", content.decisions().get(0).text());
        assertEquals("Nicolas", content.decisions().get(0).who(), "the tag is stripped by the sanitizer");
        assertEquals("Nicolas ([e-mail])", content.clientPeople().get(0).name());
        assertEquals("[link]", content.topics().get(0));
    }

    @Test
    void aSightingsNameIsCleanedLikeEveryOtherStoredString() {
        var reading = read(call(mention(byName("NN Markets <mail@nn.dk>"), "Kundemøde hos NN", "LOW", "[1]")), 10);
        assertEquals("NN Markets", reading.unmatched().get(0).displayName());
        assertEquals("nn markets", reading.unmatched().get(0).nameKey());
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private SlackMentionExtractionService.Reading read(String json, int lineCount) {
        return service.parse(json, ALLOWLIST, lineCount, Map.of(), COLLEAGUES);
    }

    /** Nothing was filed, and the day may be closed: the model answered, it just said nothing. */
    private static void assertNothing(SlackMentionExtractionService.Reading reading) {
        assertTrue(reading.mentions().isEmpty(), "nothing is filed against an account");
        assertTrue(reading.unmatched().isEmpty(), "and nothing is filed as a hint either");
        assertEquals(0, reading.droppedAsColleague());
        assertFalse(reading.failed(), "an answer the validation emptied is still an answer");
    }

    /** Nothing was filed because there was no answer at all: the day must stay open. */
    private static void assertFailed(SlackMentionExtractionService.Reading reading) {
        assertTrue(reading.mentions().isEmpty());
        assertTrue(reading.unmatched().isEmpty());
        assertEquals(0, reading.droppedAsColleague());
        assertTrue(reading.failed(), "the cursor may not advance past a day the model never answered about");
    }

    private static boolean holds(SlackMentionExtractionService.Reading reading, String clientUuid) {
        return reading.mentions().stream().anyMatch(row -> clientUuid.equals(row.clientUuid()));
    }

    private String relevanceOf(String raw) {
        return read(call(mention(byId(ACME), "Kundemøde hos Acme torsdag", raw, "[1]")), 10)
                .mentions().get(0).content().relevance();
    }

    /** The envelope the strict schema produces. */
    private static String call(String... mentions) {
        return "{\"mentions\":[" + String.join(",", mentions) + "]}";
    }

    /** A mention carrying only what validation requires, so one test varies one thing. */
    private static String mention(String attribution, String headline, String relevance, String evidence) {
        return "{" + attribution
                + ",\"headline\":" + quoted(headline)
                + ",\"relevance\":" + quoted(relevance)
                + ",\"decisions\":[],\"nextSteps\":[],\"risks\":[],\"clientAsks\":[],"
                + "\"clientPeople\":[],\"topics\":[],\"confidence\":0.5,"
                + "\"evidence\":" + evidence + "}";
    }

    private static String byId(String uuid) {
        return "\"clientId\":" + quoted(uuid) + ",\"companyName\":null";
    }

    private static String byName(String name) {
        return "\"clientId\":null,\"companyName\":" + quoted(name);
    }

    private static String quoted(String raw) {
        return raw == null ? "null" : "\"" + raw + "\"";
    }

    private static String range(int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int line = from; line <= to; line++) {
            if (line > from) {
                sb.append(',');
            }
            sb.append(line);
        }
        return sb.append(']').toString();
    }

    private static String itemsJson(String prefix, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"").append(prefix).append(i).append("\",\"who\":null,\"when\":null}");
        }
        return sb.append(']').toString();
    }

    private static String notesJson(String prefix, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"").append(prefix).append(i).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String peopleJson(String prefix, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(prefix).append(i).append("\",\"role\":null}");
        }
        return sb.append(']').toString();
    }

    private static String topicsJson(String prefix, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(prefix).append(i).append('"');
        }
        return sb.append(']').toString();
    }
}
