package dk.trustworks.intranet.aggregates.crm.trustlink.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkConnectionDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's plausibility check, driven off a real response captured from TrustLink on
 * 2026-09-13 ({@code src/test/resources/trustlink/search-response-page1.json}: page 1 of 41,
 * five of Novo Nordisk's 203 tier-5 people).
 *
 * <p>This test exists because of one property of the upstream API that no amount of careful
 * coding survives on its own: <strong>TrustLink ignores request fields it does not
 * recognise instead of rejecting them.</strong> Misspell {@code companyNames} and the
 * answer is not an error — it is a beautifully well-formed page of every one of the 47,436
 * people in the database, which the sync would then attach to arbitrary client accounts.
 * The only thing standing between that typo and a production incident is
 * {@link TrustLinkClient#assertAnswersRequest}, so its rejections are pinned here one
 * clause at a time rather than left to be inferred from a happy path.
 *
 * <p>Plain JUnit against the static entry points, no socket and no Quarkus: the check has
 * to run in the DB-free fast tier that gates every deploy, because a check that only runs
 * when somebody remembers to point it at staging is not a check.
 */
class TrustLinkClientParseTest {

    /** Quarkus registers the time module on the injected mapper; the client cannot parse dates without it. */
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String FIXTURE = "trustlink/search-response-page1.json";
    private static final Set<String> NOVO = Set.of("Novo Nordisk");

    // ------------------------------------------------------------------------
    // The captured payload, as it actually came off the wire
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("the real payload parses into the shape the sync consumes")
    void theCapturedPageParses() throws Exception {
        TrustLinkSearchResponse response = parse(fixture());

        assertEquals(1, response.page());
        assertEquals(5, response.pageSize());
        assertEquals(203, response.totalCount(), "Novo Nordisk's tier-5 count on the day this was captured");
        assertEquals(41, response.totalPages());
        assertEquals(5, response.items().size());
        assertTrue(response.hasMorePages(), "page 1 of 41 is not the last page");

        TrustLinkConnectionDTO first = response.items().get(0);
        assertEquals("9c1199023424a4b0bfdb8de982815255", first.personId());
        assertEquals("Africa S. Prats", first.fullName());
        assertEquals("Senior Product Partner", first.position());
        assertEquals("Novo Nordisk", first.companyName());
        assertEquals(5, first.tier());
        assertEquals("https://www.linkedin.com/in/africa-prats", first.linkedInUrl());
    }

    /**
     * {@code connectedOn} arrives as a zoneless local datetime with a zero time. Persisting
     * it as a DATE is deliberate — see {@code ConnectedTrustworker} — and this pins that the
     * day does not shift when a machine in another timezone runs the sync.
     */
    @Test
    @DisplayName("a connection date survives as the day TrustLink wrote, with no timezone applied")
    void theConnectionDateIsADay() throws Exception {
        TrustLinkConnectionDTO first = parse(fixture()).items().get(0);

        TrustLinkConnectionDTO.ConnectedTrustworker edge = first.connectedTrustworkers().get(0);
        assertEquals("Simon Brandt Sørensen", edge.name(), "Danish letters survive the UTF-8 round trip");
        assertEquals(LocalDate.of(2024, 3, 10), edge.connectedOnDate());
    }

    @Test
    @DisplayName("an undated connection is still a connection")
    void anUndatedEdgeParsesToANullDay() throws Exception {
        TrustLinkSearchResponse response = parse(
                fixture().replace("\"connectedOn\": \"2024-03-10T00:00:00\"", "\"connectedOn\": null"));

        assertNull(response.items().get(0).connectedTrustworkers().get(0).connectedOnDate());
    }

    /**
     * Unknown fields are tolerated on purpose: TrustLink adding a column must not stop the
     * nightly sync. This is the flip side of the silence that makes the checks below
     * necessary, so both halves are stated here together.
     */
    @Test
    @DisplayName("a field TrustLink adds tomorrow does not break the parse")
    void unknownFieldsAreIgnored() throws Exception {
        TrustLinkSearchResponse response = parse(
                fixture().replace("\"coffeeCount\": 0", "\"someFieldInventedLater\": {\"a\": 1}, \"coffeeCount\": 0"));

        assertEquals(5, response.items().size());
    }

    @Test
    @DisplayName("the captured page answers the request that was actually sent")
    void theCapturedPageIsAccepted() throws Exception {
        TrustLinkClient.assertAnswersRequest(parse(fixture()), 1, 5, NOVO);
    }

    // ------------------------------------------------------------------------
    // ...and every way it could fail to answer it
    // ------------------------------------------------------------------------

    /**
     * The headline case. A person at a company nobody asked about can only mean the company
     * filter did not apply, which means this page is a slice of the whole database rather
     * than of one client's connections.
     */
    @Test
    @DisplayName("a company that was not asked for is rejected, not ingested")
    void rejectsACompanyThatWasNotAskedFor() throws Exception {
        TrustLinkSearchResponse response = parse(fixture());

        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 5, Set.of("Roche")));
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 5, Set.of()));
    }

    /**
     * TrustLink matches {@code companyNames} exactly and case-sensitively — {@code "novo
     * nordisk"} returns zero rows — so the check has to compare the same way. Folding case
     * here would accept a page the request could not have produced.
     */
    @Test
    @DisplayName("a differently-cased company name is a different company")
    void rejectsADifferentlyCasedCompany() throws Exception {
        TrustLinkSearchResponse response = parse(fixture());

        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 5, Set.of("novo nordisk")));
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 5, Set.of("NOVO NORDISK A/S")));
    }

    /**
     * Omitting {@code tiers} returns all 47,436 rows rather than an error. One non-tier-5
     * person on the page is therefore evidence the tier filter was dropped, and the page is
     * not data.
     */
    @Test
    @DisplayName("a tier other than 5 means the tier filter was ignored")
    void rejectsATierOtherThanFive() throws Exception {
        TrustLinkSearchResponse response = parse(fixture().replaceFirst("\"tier\": 5", "\"tier\": 3"));

        assertEquals(3, response.items().get(0).tier(), "the mutation has to reach the parsed item");
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 5, NOVO));
    }

    /**
     * The echo checks. Paging over 41 pages on an answer that is always page 1 would ingest
     * the first five people 41 times and silently lose the other 198.
     */
    @Test
    @DisplayName("a page number that is not the one asked for is rejected")
    void rejectsAPageThatIsNotTheOneAskedFor() throws Exception {
        TrustLinkSearchResponse response = parse(fixture());

        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 2, 5, NOVO));
    }

    @Test
    @DisplayName("a page size that is not the one asked for is rejected")
    void rejectsAPageSizeThatIsNotTheOneAskedFor() throws Exception {
        TrustLinkSearchResponse response = parse(fixture());

        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 500, NOVO));
    }

    @Test
    @DisplayName("more items than the page size means the page size was ignored")
    void rejectsAnOversizedPage() throws Exception {
        TrustLinkSearchResponse response = parse(fixture().replace("\"pageSize\": 5", "\"pageSize\": 2"));

        assertRejected(() -> TrustLinkClient.assertAnswersRequest(response, 1, 2, NOVO));
    }

    @Test
    @DisplayName("an envelope that contradicts itself is rejected")
    void rejectsAnIncoherentEnvelope() throws Exception {
        TrustLinkSearchResponse fewerThanShown = parse(fixture().replace("\"totalCount\": 203", "\"totalCount\": 2"));
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(fewerThanShown, 1, 5, NOVO));

        TrustLinkSearchResponse pastTheEnd = parse(fixture().replace("\"totalPages\": 41", "\"totalPages\": 0"));
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(pastTheEnd, 1, 5, NOVO));
    }

    @Test
    @DisplayName("a person with no id cannot be keyed and is not data")
    void rejectsAnItemWithoutAnIdentity() throws Exception {
        TrustLinkSearchResponse noId = parse(
                fixture().replace("\"personId\": \"9c1199023424a4b0bfdb8de982815255\"", "\"personId\": \"  \""));
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(noId, 1, 5, NOVO));

        TrustLinkSearchResponse noName = parse(
                fixture().replace("\"fullName\": \"Africa S. Prats\"", "\"fullName\": null"));
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(noName, 1, 5, NOVO));
    }

    @Test
    @DisplayName("no response at all is a failure, not an empty result")
    void rejectsANullResponse() {
        assertRejected(() -> TrustLinkClient.assertAnswersRequest(null, 1, 5, NOVO));
    }

    /** An empty page is legitimate: a batch of companies simply had no tier-5 connections. */
    @Test
    @DisplayName("an empty page is accepted")
    void acceptsAnEmptyPage() {
        TrustLinkClient.assertAnswersRequest(new TrustLinkSearchResponse(List.of(), 0, 1, 500, 0), 1, 500, NOVO);
    }

    // ------------------------------------------------------------------------
    // The request side of the same distrust
    // ------------------------------------------------------------------------

    /**
     * Case is preserved and blanks are dropped before a name is ever sent. Lower-casing the
     * names here would turn every company into zero results, and letting a blank through
     * would ask upstream for "no company filter", which it answers with everybody.
     */
    @Test
    @DisplayName("the names sent are de-duplicated and blank-free, with their case intact")
    void distinctNamesKeepsCaseAndDropsBlanks() {
        Set<String> names = TrustLinkClient.distinctNames(
                Arrays.asList("Novo Nordisk", "  Novo Nordisk A/S  ", "Novo Nordisk", null, "   ", ""));

        assertEquals(new LinkedHashSet<>(List.of("Novo Nordisk", "Novo Nordisk A/S")), names);
        assertFalse(names.contains("novo nordisk"), "folding case would turn every company into zero results");
        assertEquals(Set.of(), TrustLinkClient.distinctNames(null));
        assertEquals(Set.of(), TrustLinkClient.distinctNames(List.of("   ")));
    }

    /** A malformed or absent Retry-After must not become a zero-second hot retry loop. */
    @Test
    @DisplayName("Retry-After is honoured when it is a number and defaulted when it is not")
    void retryAfterIsAlwaysAUsableDelay() {
        assertEquals(3, TrustLinkClient.retryAfter("3"));
        assertEquals(7, TrustLinkClient.retryAfter("  7 "));
        assertEquals(1, TrustLinkClient.retryAfter("0"), "never a zero-second retry");
        assertEquals(1, TrustLinkClient.retryAfter("-5"));
        assertEquals(60, TrustLinkClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(60, TrustLinkClient.retryAfter(""));
        assertEquals(60, TrustLinkClient.retryAfter(null));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static String fixture() throws Exception {
        try (InputStream in = TrustLinkClientParseTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, FIXTURE + " must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static TrustLinkSearchResponse parse(String json) throws Exception {
        return MAPPER.readValue(json, TrustLinkSearchResponse.class);
    }

    private static void assertRejected(Runnable call) {
        TrustLinkClient.TrustLinkFailure failure = assertThrows(TrustLinkClient.TrustLinkFailure.class, call::run);
        assertEquals(TrustLinkClient.Failure.INVALID_RESPONSE, failure.code(),
                "a page that does not answer the request is a failure, never data");
    }
}
