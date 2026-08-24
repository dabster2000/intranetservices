package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.services.ExpenseAccountSuggestionService.PlanAccount;
import dk.trustworks.intranet.expenseservice.services.ExpenseAccountSuggestionService.PlanCategory;
import dk.trustworks.intranet.expenseservice.services.ExpenseAccountSuggestionService.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests (no Quarkus, no DB) for the pure top-N suggestion selection. */
class ExpenseAccountSuggestionServiceTest {

    private static Object[] row(String account, int uses) {
        return new Object[]{account, uses};
    }

    private static Object[] planRow(String category, int account, String name) {
        return new Object[]{category, account, name};
    }

    @Test
    void keeps_frequency_order_and_limits() {
        List<Object[]> freq = List.of(row("3585", 40), row("3560", 22), row("3580", 7), row("3590", 2));
        Map<Integer, String> assignable = Map.of(
                3585, "Travel", 3560, "Office supplies", 3580, "Meals", 3590, "Software");

        List<Suggestion> out = ExpenseAccountSuggestionService.topSuggestions(freq, assignable, 3);

        assertEquals(3, out.size());
        assertEquals(new Suggestion(3585, "Travel", 40), out.get(0));
        assertEquals(new Suggestion(3560, "Office supplies", 22), out.get(1));
        assertEquals(new Suggestion(3580, "Meals", 7), out.get(2));
    }

    @Test
    void drops_accounts_that_are_not_assignable() {
        // 9998 (fallback) and retired accounts are absent from the assignable map.
        List<Object[]> freq = List.of(row("9998", 90), row("1234", 10), row("3585", 5));
        Map<Integer, String> assignable = Map.of(3585, "Travel");

        List<Suggestion> out = ExpenseAccountSuggestionService.topSuggestions(freq, assignable, 3);

        assertEquals(List.of(new Suggestion(3585, "Travel", 5)), out);
    }

    @Test
    void skips_unparsable_account_strings() {
        List<Object[]> freq = List.of(row("n/a", 12), row("", 3), row("3585", 2));
        Map<Integer, String> assignable = Map.of(3585, "Travel");

        List<Suggestion> out = ExpenseAccountSuggestionService.topSuggestions(freq, assignable, 3);

        assertEquals(1, out.size());
        assertEquals(3585, out.get(0).account());
    }

    @Test
    void empty_inputs_yield_empty_list() {
        assertTrue(ExpenseAccountSuggestionService.topSuggestions(List.of(), Map.of(), 3).isEmpty());
    }

    // ------------------------------------------------------------------
    // The full picker plan (groupByCategory)
    // ------------------------------------------------------------------

    @Test
    void groups_accounts_under_their_category_preserving_query_order() {
        List<Object[]> rows = List.of(
                planRow("Rejser", 4030, "Rejser og ophold"),
                planRow("Rejser", 4050, "Taxa/tog/bus"),
                planRow("Rejser", 4055, "Parkering"),
                planRow("Viden", 3560, "Kursus/udd/konferencer"));

        List<PlanCategory> out = ExpenseAccountSuggestionService.groupByCategory(rows, 4050);

        assertEquals(2, out.size());
        assertEquals("Rejser", out.get(0).categoryName());
        assertEquals(List.of(4030, 4050, 4055),
                out.get(0).expenseAccounts().stream().map(PlanAccount::accountNumber).toList());
        assertEquals("Viden", out.get(1).categoryName());
        assertEquals(List.of(3560),
                out.get(1).expenseAccounts().stream().map(PlanAccount::accountNumber).toList());
    }

    @Test
    void flags_the_most_used_account_and_its_category() {
        List<Object[]> rows = List.of(
                planRow("Rejser", 4030, "Rejser og ophold"),
                planRow("Rejser", 4050, "Taxa/tog/bus"),
                planRow("Viden", 3560, "Kursus/udd/konferencer"));

        List<PlanCategory> out = ExpenseAccountSuggestionService.groupByCategory(rows, 4050);

        assertTrue(out.get(0).defaultCategory());
        assertFalse(out.get(0).expenseAccounts().get(0).defaultAccount());
        assertTrue(out.get(0).expenseAccounts().get(1).defaultAccount());
        assertFalse(out.get(1).defaultCategory());
    }

    @Test
    void no_usage_history_flags_nothing() {
        // suggestFor returns nothing for a new employee → sentinel most-used.
        // Explicit type argument: a single Object[] would otherwise bind to List.of(E...).
        List<Object[]> rows = List.<Object[]>of(planRow("Rejser", 4030, "Rejser og ophold"));

        List<PlanCategory> out = ExpenseAccountSuggestionService.groupByCategory(rows, Integer.MIN_VALUE);

        assertEquals(1, out.size());
        assertFalse(out.get(0).defaultCategory());
        assertFalse(out.get(0).expenseAccounts().get(0).defaultAccount());
    }

    @Test
    void no_assignable_accounts_yields_empty_plan() {
        assertTrue(ExpenseAccountSuggestionService.groupByCategory(List.of(), 4030).isEmpty());
    }
}
