package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.services.ExpenseAccountSuggestionService.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests (no Quarkus, no DB) for the pure top-N suggestion selection. */
class ExpenseAccountSuggestionServiceTest {

    private static Object[] row(String account, int uses) {
        return new Object[]{account, uses};
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
}
