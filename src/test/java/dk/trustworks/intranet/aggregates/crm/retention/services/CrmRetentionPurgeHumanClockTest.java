package dk.trustworks.intranet.aggregates.crm.retention.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention clock has to be able to see the two tables a colleague writes BY HAND.
 *
 * <p>This is a regression suite for a defect that erased data every night and left no trace of
 * why. {@code AccountActivityService.lastActivityForAll()} unions ten sources, and neither
 * {@code account_relation_claim} nor {@code client_plan_stakeholder} is one of them — the claim
 * is logged under a field name of its own precisely so the accounts list does not render
 * "Became a customer" for it, and there is no plan source at all. Both tables used to reach the
 * purge's eligibility rule only as a <b>null-only fallback</b>, consulted when an account had
 * no activity of any kind. That is never true of the accounts where it matters. So on an
 * account whose last source activity was more than 24 months ago, a colleague could file a
 * claim or star a stakeholder this afternoon and the 03:30 sweep would delete it that night,
 * and again the next night, for ever.
 *
 * <p>The fix is one word in the wiring: the human clock is folded in with {@code max()}, not
 * behind a null check. These tests pin that word, because "fallback" and "fold" read almost
 * identically at a call site and behave completely differently.
 */
class CrmRetentionPurgeHumanClockTest {

    /** A fixed day, so the test says the same thing in February as in July. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    private static final String CLIENT = "client-1";

    @Test
    @DisplayName("a claim filed today on a long-quiet account keeps it out of tonight's sweep")
    void aClaimFiledTodayIsActivity() {
        // The account's newest SOURCE activity is three years old: no meeting, no signal, no
        // note since 2023. Under the old null-only fallback the human clock was never even
        // consulted, because the account HAD activity — just old activity — and the claim
        // filed this morning was deleted at 03:30.
        Map<String, LocalDate> source = clock(LocalDate.of(2023, 9, 14));
        Map<String, LocalDate> human = clock(TODAY);

        LocalDate lastActivity =
                CrmRetentionPurgeService.lastActivityOf(CLIENT, source, human, empty());

        assertEquals(TODAY, lastActivity,
                "a colleague recording how they know somebody IS activity on the account");
        assertFalse(CrmRetentionPurgeService.isPastRetention(lastActivity, TODAY),
                "the claim was filed today; nothing about this account is 24 months stale");
    }

    @Test
    @DisplayName("a stakeholder validated today does the same — the plan is hand-written too")
    void aStakeholderValidatedTodayIsActivity() {
        Map<String, LocalDate> source = clock(LocalDate.of(2022, 1, 1));
        Map<String, LocalDate> human = clock(TODAY.minusDays(2));

        LocalDate lastActivity =
                CrmRetentionPurgeService.lastActivityOf(CLIENT, source, human, empty());

        assertEquals(TODAY.minusDays(2), lastActivity);
        assertFalse(CrmRetentionPurgeService.isPastRetention(lastActivity, TODAY));
    }

    @Test
    @DisplayName("the old null-only shape is what this pins: activity present must NOT hide the human clock")
    void anAccountWithActivityStillConsultsTheHumanClock() {
        // The exact shape of the bug, stated as an assertion rather than as prose: the source
        // clock is non-null, so a null-only fallback would return it and stop. It must not.
        Map<String, LocalDate> source = clock(LocalDate.of(2020, 5, 5));
        Map<String, LocalDate> human = clock(LocalDate.of(2026, 5, 5));

        assertEquals(LocalDate.of(2026, 5, 5),
                CrmRetentionPurgeService.lastActivityOf(CLIENT, source, human, empty()),
                "folded with max(), never consulted only when the source clock is absent");
    }

    @Test
    @DisplayName("a stale claim never drags a fresh account backwards — the fold is max, both ways")
    void theHumanClockNeverShortensTheWindow() {
        // The mirror of the bug, and the reason this is max() rather than "prefer the human
        // clock": a claim from 2021 on an account with a meeting last week must not make the
        // account look three years quiet and erase the meeting's attendees.
        Map<String, LocalDate> source = clock(TODAY.minusDays(7));
        Map<String, LocalDate> human = clock(LocalDate.of(2021, 3, 1));

        LocalDate lastActivity =
                CrmRetentionPurgeService.lastActivityOf(CLIENT, source, human, empty());

        assertEquals(TODAY.minusDays(7), lastActivity);
        assertFalse(CrmRetentionPurgeService.isPastRetention(lastActivity, TODAY));
    }

    @Test
    @DisplayName("an account known only to the human clock is dated by it, not treated as unknown")
    void theHumanClockAloneIsEnough() {
        assertEquals(LocalDate.of(2024, 4, 1),
                CrmRetentionPurgeService.lastActivityOf(
                        CLIENT, empty(), clock(LocalDate.of(2024, 4, 1)), empty()));
    }

    @Test
    @DisplayName("the registry fallback still answers when source and human clocks are both silent")
    void theFallbackStillApplies() {
        // An account_person row derived from a source that has since gone: no activity, no
        // claim, no stakeholder. The row's own last_seen_at is the only clock there is, and it
        // still has to be honoured or such an account could never be purged at all.
        LocalDate registryDate = LocalDate.of(2021, 6, 30);

        LocalDate lastActivity =
                CrmRetentionPurgeService.lastActivityOf(CLIENT, empty(), empty(), clock(registryDate));

        assertEquals(registryDate, lastActivity);
        assertTrue(CrmRetentionPurgeService.isPastRetention(lastActivity, TODAY));
    }

    @Test
    @DisplayName("the fallback is last, not first — a human date outranks the registry row")
    void theHumanClockOutranksTheFallback() {
        assertEquals(LocalDate.of(2026, 2, 2),
                CrmRetentionPurgeService.lastActivityOf(
                        CLIENT, empty(), clock(LocalDate.of(2026, 2, 2)),
                        clock(LocalDate.of(2019, 1, 1))));
    }

    @Test
    @DisplayName("no clock at all stays null — an unknown date is not an old one")
    void noClockAtAllIsStillUnknown() {
        LocalDate lastActivity =
                CrmRetentionPurgeService.lastActivityOf(CLIENT, empty(), empty(), empty());

        assertNull(lastActivity);
        assertFalse(CrmRetentionPurgeService.isPastRetention(lastActivity, TODAY),
                "erasure is irreversible; an account nothing can date is kept, not purged");
    }

    @Test
    @DisplayName("one account's claim does not date another's — the fold is per client")
    void theFoldIsPerClient() {
        Map<String, LocalDate> source = new HashMap<>();
        source.put("quiet", LocalDate.of(2020, 1, 1));
        source.put("busy", TODAY);
        Map<String, LocalDate> human = new HashMap<>();
        human.put("busy", TODAY);

        assertEquals(LocalDate.of(2020, 1, 1),
                CrmRetentionPurgeService.lastActivityOf("quiet", source, human, empty()),
                "the busy account's claim must not shelter the quiet one");
        assertTrue(CrmRetentionPurgeService.isPastRetention(
                CrmRetentionPurgeService.lastActivityOf("quiet", source, human, empty()), TODAY));
    }

    @Test
    @DisplayName("newest() takes the later date and treats absent as losing, never as newest")
    void newestPrefersTheLaterDate() {
        LocalDate earlier = LocalDate.of(2024, 1, 1);
        LocalDate later = LocalDate.of(2025, 1, 1);

        assertEquals(later, CrmRetentionPurgeService.newest(earlier, later));
        assertEquals(later, CrmRetentionPurgeService.newest(later, earlier));
        assertEquals(later, CrmRetentionPurgeService.newest(null, later));
        assertEquals(later, CrmRetentionPurgeService.newest(later, null));
        assertNull(CrmRetentionPurgeService.newest(null, null));
    }

    @Test
    @DisplayName("two equal dates collapse to that date rather than to null")
    void newestHandlesEquality() {
        LocalDate same = LocalDate.of(2025, 7, 1);
        assertEquals(same, CrmRetentionPurgeService.newest(same, same));
    }

    @Test
    @DisplayName("the claim clock reads account_relation_claim and both of its dates")
    void theClaimClockReadsTheRightTable() {
        String sql = normalised(CrmRetentionPurgeService.HUMAN_CLOCK_CLAIMS);

        assertTrue(sql.contains("from account_relation_claim"), sql);
        assertTrue(sql.contains("group by client_uuid"), sql);
        // updated_at as well as claimed_at: a claim moved from "met once" to "trusted" this
        // morning is today's work even though the row was first written in 2023.
        assertTrue(sql.contains("claimed_at"), sql);
        assertTrue(sql.contains("updated_at"), sql);
    }

    @Test
    @DisplayName("the stakeholder clock reads client_plan_stakeholder and validated_at, not only created_at")
    void theStakeholderClockReadsTheRightTable() {
        String sql = normalised(CrmRetentionPurgeService.HUMAN_CLOCK_STAKEHOLDERS);

        assertTrue(sql.contains("from client_plan_stakeholder"), sql);
        assertTrue(sql.contains("group by client_uuid"), sql);
        assertTrue(sql.contains("created_at"), sql);
        assertTrue(sql.contains("validated_at"),
                "the plan's whole point is that a seat is re-confirmed; created_at alone calls a "
                        + "stakeholder validated last week two years stale");
    }

    @Test
    @DisplayName("every greatest() is wrapped in coalesce — in MariaDB a NULL argument makes the whole call NULL")
    void greatestIsNullSafe() {
        // MariaDB's GREATEST returns NULL if ANY argument is NULL, so a bare
        // greatest(a, b) over a nullable pair would report "this account has no human clock"
        // for a row that has one — and an account with no human clock is one this job deletes.
        for (String sql : new String[]{
                CrmRetentionPurgeService.HUMAN_CLOCK_CLAIMS,
                CrmRetentionPurgeService.HUMAN_CLOCK_STAKEHOLDERS}) {
            String normalised = normalised(sql);
            assertTrue(normalised.contains("greatest(coalesce("),
                    "greatest() must take coalesce() pairs, never bare columns: " + normalised);
            assertEquals(2, countOccurrences(normalised, "coalesce("),
                    "both sides of the greatest() need their own coalesce, or one NULL still "
                            + "swallows the other date: " + normalised);
        }
    }

    private static String normalised(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = haystack.indexOf(needle);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(needle, from + needle.length());
        }
        return count;
    }

    private static Map<String, LocalDate> clock(LocalDate date) {
        Map<String, LocalDate> map = new HashMap<>();
        map.put(CLIENT, date);
        return map;
    }

    /** A HashMap, not {@code Map.of()}: these maps are read with keys that are often absent. */
    private static Map<String, LocalDate> empty() {
        return new HashMap<>();
    }
}
