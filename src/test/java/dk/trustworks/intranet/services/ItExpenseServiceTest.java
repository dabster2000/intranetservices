package dk.trustworks.intranet.services;

import dk.trustworks.intranet.dto.itbudget.ItBudgetSource;
import dk.trustworks.intranet.dto.itbudget.ItBudgetSummaryDTO;
import dk.trustworks.intranet.dto.itbudget.ItExpenseItemDTO;
import dk.trustworks.intranet.dto.itbudget.UpdateItExpenseRequest;
import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.model.ItExpenseItem;
import dk.trustworks.intranet.model.enums.ItExpenseStatus;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IT budget rules: when an item amortizes, what it contributes while it
 * does, and who is allowed to change it. All static package-visible helpers, so
 * this runs in the DB-free fast tier.
 * <p>
 * Before this remediation no test anywhere asserted a used-budget value, an
 * expiry date or the owner predicate — which is how a month-end rounding bug, a
 * data-losing bulk update and an unguarded row id all reached production.
 */
class ItExpenseServiceTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String SOMEBODY_ELSE = "22222222-2222-2222-2222-222222222222";

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static ItExpenseCategory category(int lifespanMonths) {
        ItExpenseCategory category = new ItExpenseCategory();
        category.setId(1);
        category.setName("laptop");
        category.setLongName("Laptop");
        category.setLifespan(lifespanMonths);
        return category;
    }

    private static ItExpenseItem item(String useruuid, int price, LocalDate invoicedate,
                                      ItExpenseStatus status, ItExpenseCategory category) {
        ItExpenseItem item = new ItExpenseItem();
        item.setUseruuid(useruuid);
        item.setDescription("MacBook Pro 14");
        item.setPrice(price);
        item.setInvoicedate(invoicedate);
        item.setStatus(status);
        item.setCategory(category);
        return item;
    }

    private static ItExpenseItemDTO dto(int id, int price, LocalDate invoicedate,
                                        ItExpenseStatus status, Integer lifespan, LocalDate today) {
        ItExpenseItem item = item(OWNER, price, invoicedate, status,
                lifespan == null ? null : category(lifespan));
        item.setId(id);
        return ItExpenseService.toItemDTO(item, today);
    }

    // ── Amortization date ─────────────────────────────────────────────────

    @Nested
    @DisplayName("expiryDate")
    class ExpiryDate {

        @Test
        void clampsAMonthEndInvoiceDateToTheShorterMonth() {
            // The bug this replaces: JS setMonth on 31 Aug + 30 months rolls
            // forward into 3 March. java.time clamps to the last valid day.
            assertEquals(LocalDate.of(2027, 2, 28),
                    ItExpenseService.expiryDate(LocalDate.of(2024, 8, 31), 30));
        }

        @Test
        void clampsToLeapDayInALeapYear() {
            assertEquals(LocalDate.of(2024, 2, 29),
                    ItExpenseService.expiryDate(LocalDate.of(2021, 8, 31), 30));
        }

        @Test
        void addsWholeMonthsForAnOrdinaryDate() {
            assertEquals(LocalDate.of(2027, 3, 6),
                    ItExpenseService.expiryDate(LocalDate.of(2024, 3, 6), 36));
        }

        @Test
        void isNullWithoutALifespan() {
            assertNull(ItExpenseService.expiryDate(LocalDate.of(2024, 3, 6), null));
            assertNull(ItExpenseService.expiryDate(LocalDate.of(2024, 3, 6), 0));
        }

        @Test
        void isNullWithoutAnInvoiceDate() {
            assertNull(ItExpenseService.expiryDate(null, 36));
        }
    }

    @Nested
    @DisplayName("the amortization cliff")
    class Cliff {

        private static final LocalDate INVOICED = LocalDate.of(2024, 3, 6);
        private static final LocalDate EXPIRY = LocalDate.of(2027, 3, 6);

        @Test
        void theDayBeforeExpiryTheItemStillConsumesItsFullPrice() {
            ItExpenseItemDTO item = dto(1, 18000, INVOICED, ItExpenseStatus.ACTIVE, 36, EXPIRY.minusDays(1));
            assertFalse(item.expired());
            assertTrue(item.countsTowardBudget());
            assertEquals(18000, ItExpenseService.usedBudget(List.of(item)));
        }

        @Test
        void onTheExpiryDateItselfTheItemStopsCounting() {
            // A cliff, not a straight-line write-down (requirements §5.3): it is
            // the full price right up to the date, then nothing.
            ItExpenseItemDTO item = dto(1, 18000, INVOICED, ItExpenseStatus.ACTIVE, 36, EXPIRY);
            assertTrue(item.expired());
            assertFalse(item.countsTowardBudget());
            assertEquals(0, ItExpenseService.usedBudget(List.of(item)));
        }

        @Test
        void theDayAfterExpiryItIsStillExpired() {
            ItExpenseItemDTO item = dto(1, 18000, INVOICED, ItExpenseStatus.ACTIVE, 36, EXPIRY.plusDays(1));
            assertTrue(item.expired());
            assertFalse(item.countsTowardBudget());
        }

        @Test
        void anExpiredActiveItemIsWhatTheBadgeMustCallAmortized() {
            // 291 live rows are in exactly this state: status ACTIVE, past their
            // lifespan. The DTO says so; the UI reads `expired`, not `status`.
            ItExpenseItemDTO item = dto(1, 18000, INVOICED, ItExpenseStatus.ACTIVE, 36, EXPIRY);
            assertEquals(ItExpenseStatus.ACTIVE, item.status());
            assertTrue(item.expired());
        }
    }

    @Nested
    @DisplayName("an item whose type has no amortization length")
    class NoLifespan {

        @Test
        void keepsCountingForever() {
            // Rule 6: never guess a default lifespan. An unconfigured type keeps
            // consuming budget until somebody configures one.
            ItExpenseItemDTO item = dto(1, 4000, LocalDate.of(2010, 1, 1),
                    ItExpenseStatus.ACTIVE, null, LocalDate.of(2026, 8, 29));
            assertNull(item.expiryDate());
            assertNull(item.monthsRemaining());
            assertFalse(item.expired());
            assertTrue(item.countsTowardBudget());
            assertEquals(4000, ItExpenseService.usedBudget(List.of(item)));
        }

        @Test
        void aZeroLifespanIsTreatedAsUnconfigured() {
            ItExpenseItemDTO item = dto(1, 4000, LocalDate.of(2010, 1, 1),
                    ItExpenseStatus.ACTIVE, 0, LocalDate.of(2026, 8, 29));
            assertNull(item.expiryDate());
            assertTrue(item.countsTowardBudget());
        }
    }

    @Nested
    @DisplayName("monthsRemaining")
    class MonthsRemaining {

        @Test
        void countsWholeCalendarMonthsNotThirtyDayApproximations() {
            // The React helper this replaces divided a day count by 30, which
            // reported a fresh 30-month laptop as "In 31 months".
            assertEquals(30, ItExpenseService.monthsRemaining(
                    LocalDate.of(2027, 3, 6), LocalDate.of(2024, 9, 6)));
        }

        @Test
        void doesNotRoundAPartialMonthUp() {
            assertEquals(6, ItExpenseService.monthsRemaining(
                    LocalDate.of(2027, 3, 6), LocalDate.of(2026, 8, 29)));
        }

        @Test
        void isFlooredAtZeroOnceExpired() {
            assertEquals(0, ItExpenseService.monthsRemaining(
                    LocalDate.of(2024, 3, 6), LocalDate.of(2026, 8, 29)));
        }

        @Test
        void isNullWithoutAnExpiryDate() {
            assertNull(ItExpenseService.monthsRemaining(null, LocalDate.of(2026, 8, 29)));
        }
    }

    // ── Budget arithmetic ─────────────────────────────────────────────────

    @Nested
    @DisplayName("usedBudget")
    class UsedBudget {

        private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

        @Test
        void sumsOnlyItemsThatStillCount() {
            List<ItExpenseItemDTO> items = List.of(
                    dto(1, 18000, LocalDate.of(2025, 3, 6), ItExpenseStatus.ACTIVE, 36, TODAY),
                    dto(2, 9000, LocalDate.of(2019, 3, 6), ItExpenseStatus.ACTIVE, 36, TODAY),
                    dto(3, 7000, LocalDate.of(2026, 3, 6), ItExpenseStatus.BROKEN, 36, TODAY));
            assertEquals(18000, ItExpenseService.usedBudget(items));
        }

        @Test
        void aNegativePriceNeverInflatesTheAvailableBudget() {
            // A credit note keyed in as a negative row would otherwise hand the
            // employee budget they never had.
            List<ItExpenseItemDTO> items = List.of(
                    dto(1, 18000, LocalDate.of(2025, 3, 6), ItExpenseStatus.ACTIVE, 36, TODAY),
                    dto(2, -5000, LocalDate.of(2025, 4, 1), ItExpenseStatus.ACTIVE, 36, TODAY));
            assertEquals(18000, ItExpenseService.usedBudget(items));
        }

        @Test
        void isZeroWithoutItems() {
            assertEquals(0, ItExpenseService.usedBudget(List.of()));
        }
    }

    @Nested
    @DisplayName("nextRelease")
    class NextRelease {

        private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

        @Test
        void picksTheEarliestStillCountingItem() {
            List<ItExpenseItemDTO> items = List.of(
                    dto(1, 18000, LocalDate.of(2025, 3, 6), ItExpenseStatus.ACTIVE, 36, TODAY),
                    dto(2, 6000, LocalDate.of(2025, 1, 10), ItExpenseStatus.ACTIVE, 24, TODAY));

            Optional<ItExpenseService.NextRelease> release = ItExpenseService.nextRelease(items);

            assertTrue(release.isPresent());
            assertEquals(LocalDate.of(2027, 1, 10), release.get().date());
            assertEquals(6000, release.get().amount());
        }

        @Test
        void sumsEverythingAmortizingOnThatSameDay() {
            List<ItExpenseItemDTO> items = List.of(
                    dto(1, 18000, LocalDate.of(2020, 3, 6), ItExpenseStatus.ACTIVE, 36, TODAY),
                    dto(2, 2500, LocalDate.of(2025, 3, 6), ItExpenseStatus.ACTIVE, 24, TODAY),
                    dto(3, 4000, LocalDate.of(2026, 3, 6), ItExpenseStatus.ACTIVE, 12, TODAY));

            // Items 2 and 3 both amortize on 2027-03-06; item 1 did so in 2023.
            Optional<ItExpenseService.NextRelease> release = ItExpenseService.nextRelease(items);

            assertTrue(release.isPresent());
            assertEquals(LocalDate.of(2027, 3, 6), release.get().date());
            assertEquals(6500, release.get().amount());
        }

        @Test
        void ignoresItemsWithNoAmortizationLength() {
            List<ItExpenseItemDTO> items = List.of(
                    dto(1, 4000, LocalDate.of(2024, 3, 6), ItExpenseStatus.ACTIVE, null, TODAY));
            assertTrue(ItExpenseService.nextRelease(items).isEmpty());
        }

        @Test
        void isEmptyWhenNothingIsCounting() {
            List<ItExpenseItemDTO> items = List.of(
                    dto(1, 18000, LocalDate.of(2019, 3, 6), ItExpenseStatus.ACTIVE, 36, TODAY));
            assertTrue(ItExpenseService.nextRelease(items).isEmpty());
        }
    }

    // ── Ownership and mutation ────────────────────────────────────────────

    @Nested
    @DisplayName("the owner predicate")
    class Ownership {

        @Test
        void acceptsTheOwnersOwnRow() {
            assertTrue(ItExpenseService.isOwnedBy(
                    item(OWNER, 1, LocalDate.of(2025, 1, 1), ItExpenseStatus.ACTIVE, null), OWNER));
        }

        @Test
        void rejectsAForeignRow() {
            // Update and delete used to key on the row id alone, so any
            // devices:write holder could mutate anyone's equipment.
            assertFalse(ItExpenseService.isOwnedBy(
                    item(SOMEBODY_ELSE, 1, LocalDate.of(2025, 1, 1), ItExpenseStatus.ACTIVE, null), OWNER));
        }

        @Test
        void rejectsAMissingRow() {
            assertFalse(ItExpenseService.isOwnedBy(null, OWNER));
        }

        @Test
        void rejectsAnOwnerlessRow() {
            assertFalse(ItExpenseService.isOwnedBy(
                    item(null, 1, LocalDate.of(2025, 1, 1), ItExpenseStatus.ACTIVE, null), OWNER));
        }
    }

    @Nested
    @DisplayName("applySuppliedFields")
    class ApplySuppliedFields {

        private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

        @Test
        void aStatusOnlyUpdateLeavesEveryOtherFieldAlone() {
            // The live data-loss bug: the status-change flow sends nothing but a
            // status, and the old bulk update NULLed description, price and
            // invoicedate on seven rows.
            ItExpenseCategory laptop = category(36);
            ItExpenseItem item = item(OWNER, 18000, LocalDate.of(2024, 3, 6), ItExpenseStatus.ACTIVE, laptop);

            ItExpenseService.applySuppliedFields(item,
                    new UpdateItExpenseRequest(null, null, null, null, ItExpenseStatus.BROKEN), null, TODAY);

            assertEquals(ItExpenseStatus.BROKEN, item.getStatus());
            assertEquals("MacBook Pro 14", item.getDescription());
            assertEquals(18000, item.getPrice());
            assertEquals(LocalDate.of(2024, 3, 6), item.getInvoicedate());
            assertEquals(laptop, item.getCategory());
        }

        @Test
        void writesEverySuppliedField() {
            ItExpenseCategory phone = category(24);
            ItExpenseItem item = item(OWNER, 18000, LocalDate.of(2024, 3, 6), ItExpenseStatus.ACTIVE, category(36));

            ItExpenseService.applySuppliedFields(item,
                    new UpdateItExpenseRequest(2, "iPhone 16", 9000, LocalDate.of(2026, 1, 5),
                            ItExpenseStatus.LOST),
                    phone, TODAY);

            assertEquals(phone, item.getCategory());
            assertEquals("iPhone 16", item.getDescription());
            assertEquals(9000, item.getPrice());
            assertEquals(LocalDate.of(2026, 1, 5), item.getInvoicedate());
            assertEquals(ItExpenseStatus.LOST, item.getStatus());
        }

        @Test
        void validatesASuppliedFieldTheSameWayCreateDoes() {
            ItExpenseItem item = item(OWNER, 18000, LocalDate.of(2024, 3, 6), ItExpenseStatus.ACTIVE, category(36));
            assertThrows(BadRequestException.class, () -> ItExpenseService.applySuppliedFields(item,
                    new UpdateItExpenseRequest(null, null, 0, null, null), null, TODAY));
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validation")
    class Validation {

        private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

        @Test
        void trimsAndAcceptsADescription() {
            assertEquals("MacBook Pro 14", ItExpenseService.requireDescription("  MacBook Pro 14 "));
        }

        @Test
        void rejectsABlankDescription() {
            assertThrows(BadRequestException.class, () -> ItExpenseService.requireDescription("   "));
            assertThrows(BadRequestException.class, () -> ItExpenseService.requireDescription(null));
        }

        @Test
        void rejectsADescriptionLongerThanTheColumn() {
            String tooLong = "x".repeat(ItExpenseService.DESCRIPTION_MAX + 1);
            assertThrows(BadRequestException.class, () -> ItExpenseService.requireDescription(tooLong));
        }

        @Test
        void rejectsANonPositivePrice() {
            assertThrows(BadRequestException.class, () -> ItExpenseService.requirePrice(0));
            assertThrows(BadRequestException.class, () -> ItExpenseService.requirePrice(-1));
            assertThrows(BadRequestException.class, () -> ItExpenseService.requirePrice(null));
        }

        @Test
        void acceptsAnInvoiceDateUpToAWeekAhead() {
            assertEquals(TODAY.plusDays(7), ItExpenseService.requireInvoicedate(TODAY.plusDays(7), TODAY));
        }

        @Test
        void rejectsAnInvoiceDateFurtherAhead() {
            // A live row is dated 2030-09-26 — a mistyped year that pins that
            // employee's budget for years and cannot be corrected in the UI.
            assertThrows(BadRequestException.class,
                    () -> ItExpenseService.requireInvoicedate(LocalDate.of(2030, 9, 26), TODAY));
        }

        @Test
        void rejectsAnInvoiceDateBeforeTheEpochOfTheCompany() {
            assertThrows(BadRequestException.class,
                    () -> ItExpenseService.requireInvoicedate(LocalDate.of(1999, 12, 31), TODAY));
        }

        @Test
        void rejectsAMissingInvoiceDate() {
            assertThrows(BadRequestException.class, () -> ItExpenseService.requireInvoicedate(null, TODAY));
        }
    }

    // ── Summary derivation ────────────────────────────────────────────────

    @Nested
    @DisplayName("the summary's derived fields")
    class Summary {

        @Test
        void repeatsTheTotalUnderTheLegacyItBudgetKey() {
            ItBudgetSummaryDTO summary = ItBudgetSummaryDTO.of(
                    25000, 18000, ItBudgetSource.TEAM, null, null, List.of(), List.of());
            assertEquals(25000, summary.itBudget());
            assertEquals(7000, summary.availableBudget());
            assertTrue(summary.canAddEquipment());
        }

        @Test
        void showsOverspendRatherThanClampingItToZero() {
            // Math.max(0, …) in the old BFF hid the overspend entirely, so a
            // 30 000 kr spend on a 25 000 kr budget read as "0 kr available".
            ItBudgetSummaryDTO summary = ItBudgetSummaryDTO.of(
                    25000, 30000, ItBudgetSource.TEAM, null, null, List.of(), List.of());
            assertEquals(-5000, summary.availableBudget());
        }

        @Test
        void aZeroBudgetMeansNoEquipmentMayBeAdded() {
            ItBudgetSummaryDTO summary = ItBudgetSummaryDTO.of(
                    0, 0, ItBudgetSource.NO_BUDGET_CONSULTANT_TYPE, null, null, List.of(), List.of());
            assertFalse(summary.canAddEquipment());
        }
    }

    // ── Presentation order ────────────────────────────────────────────────

    @Nested
    @DisplayName("list order (US-IT-002)")
    class ListOrder {

        private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

        @Test
        void putsTheNewestInvoiceDateFirstAndUndatedRowsLast() {
            ItExpenseItemDTO older = dto(1, 1000, LocalDate.of(2024, 1, 1), ItExpenseStatus.ACTIVE, 36, TODAY);
            ItExpenseItemDTO newer = dto(2, 2000, LocalDate.of(2026, 1, 1), ItExpenseStatus.ACTIVE, 36, TODAY);
            ItExpenseItemDTO undated = dto(3, 3000, null, ItExpenseStatus.ACTIVE, 36, TODAY);

            List<ItExpenseItemDTO> sorted = List.of(undated, older, newer).stream()
                    .sorted(ItExpenseService.NEWEST_INVOICE_FIRST)
                    .toList();

            assertEquals(List.of(2, 1, 3), sorted.stream().map(ItExpenseItemDTO::id).toList());
        }
    }
}
