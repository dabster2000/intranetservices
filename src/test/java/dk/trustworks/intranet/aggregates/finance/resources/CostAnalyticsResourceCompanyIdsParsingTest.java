package dk.trustworks.intranet.aggregates.finance.resources;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the multi-company filter bug (TASK F1).
 *
 * <p>The frontend joins the selected company UUIDs with a comma
 * (<code>companyIds=uuidA,uuidB</code>). JAX-RS / RESTEasy does NOT split a
 * comma-joined query value into a {@code Set<String>}, so binding the param as
 * {@code Set<String>} produced a SINGLE-element set <code>{"uuidA,uuidB"}</code>
 * → SQL <code>company_id IN ('uuidA,uuidB')</code> → 0 rows. Single-company and
 * all-companies (empty) worked; only 2+ explicit companies silently zeroed.
 *
 * <p>The fix binds the param as a raw {@code String} and parses it with
 * {@code parseCommaSeparated(...)}, which must yield a 2-element {@code Set} so
 * the IN clause receives both UUIDs.
 *
 * <p>This test invokes the private helper reflectively. Pre-fix the helper does
 * not exist on {@link CostAnalyticsResource} ({@code NoSuchMethodException} →
 * FAIL); post-fix it splits the comma-joined value into 2 elements (PASS).
 */
class CostAnalyticsResourceCompanyIdsParsingTest {

    private static final String AS = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final String TECH = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";

    @SuppressWarnings("unchecked")
    private Set<String> parse(String input) throws Exception {
        Method m = CostAnalyticsResource.class.getDeclaredMethod("parseCommaSeparated", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(new CostAnalyticsResource(), input);
    }

    @Test
    void commaJoinedTwoCompanies_yieldsTwoElementSet() throws Exception {
        Set<String> result = parse(AS + "," + TECH);
        assertNotNull(result, "parseCommaSeparated must not return null for two companies");
        assertEquals(2, result.size(),
                "comma-joined companyIds must split into 2 elements (the bug produced 1)");
        assertTrue(result.contains(AS) && result.contains(TECH),
                "both company UUIDs must be present in the parsed set");
    }

    @Test
    void singleCompany_yieldsSingleElementSet() throws Exception {
        Set<String> result = parse(AS);
        assertEquals(Set.of(AS), result);
    }

    @Test
    void blankOrNull_yieldsNull() throws Exception {
        assertNull(parse(null), "null input must map to null (all-companies)");
        assertNull(parse(""), "blank input must map to null (all-companies)");
        assertNull(parse("   "), "whitespace input must map to null (all-companies)");
    }

    @Test
    void tooManyCompanies_isRejectedWith400() {
        // Security hardening: a pathologically large companyIds list must be rejected
        // with HTTP 400 (BadRequestException) rather than forwarded as an unbounded IN().
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("00000000-0000-4000-8000-%012d", i));
        }
        InvocationTargetException ex =
                assertThrows(InvocationTargetException.class, () -> parse(sb.toString()),
                        "an oversized companyIds list must throw");
        assertInstanceOf(BadRequestException.class, ex.getCause(),
                "oversized companyIds must be rejected with BadRequestException (HTTP 400)");
    }

    @Test
    void documentsTheBug_joinedValueAsSingleSetElementHasSizeOne() {
        // What JAX-RS produced when the param was Set<String>: the whole
        // comma-joined value became ONE element → IN ('uuidA,uuidB') → 0 rows.
        Set<String> buggyBinding = Set.of(AS + "," + TECH);
        assertEquals(1, buggyBinding.size(),
                "documents the root cause: comma-joined value bound as a single Set element");
    }
}
