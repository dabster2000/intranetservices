package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonusLine;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for {@link InvoiceBonusService#recalcForInvoice}.
 *
 * <p>Guards the production defect of 2026-08-02: on the draft-creation path
 * ({@code InvoiceGenerator.createDraftInvoiceFromProject} → {@code InvoiceService.createDraftInvoice}
 * (REQUIRES_NEW, commits) → {@code InvoiceService.updateDraftInvoice}) the outer transaction's
 * REPEATABLE READ snapshot predates the committed insert, so {@code Invoice.findById} returned null
 * and the recalculation of {@code computedAmount} — a money field — was skipped behind nothing but a
 * WARN. It fired 12 times in one evening, once for every draft the user created.
 *
 * <p>Panache access is routed through the service's {@code protected} test hooks (the same
 * convention as {@code InvoiceItemRecalculator}), so no database is required. Panache's inherited
 * statics cannot be mocked with {@code mockStatic} — they are declared on {@code PanacheEntityBase}
 * and are only bytecode-enhanced onto the entity during Quarkus augmentation.
 */
class InvoiceBonusServiceRecalcTest {

    private static final String INVOICE_UUID = "7cadeb5f-6d25-47f1-bf20-cfa61fde2852";

    /**
     * Stubs only the Panache touchpoints; every line of the recalculation logic under test is the
     * real one. {@code invoiceById} is deliberately left null by default so a test fails loudly if
     * the entity overload ever regresses into re-reading by uuid.
     */
    private static final class TestableService extends InvoiceBonusService {
        Invoice invoiceById;
        List<InvoiceBonus> bonuses = List.of();
        Map<String, List<InvoiceBonusLine>> linesByBonus = Map.of();
        int findInvoiceByIdCalls;

        @Override protected Invoice findInvoiceById(String invoiceuuid) {
            findInvoiceByIdCalls++;
            return invoiceById;
        }
        @Override public List<InvoiceBonus> findByInvoice(String invoiceuuid) {
            return bonuses;
        }
        @Override protected List<InvoiceBonusLine> findLinesByBonus(String bonusuuid) {
            return linesByBonus.getOrDefault(bonusuuid, List.of());
        }
    }

    private final TestableService service = new TestableService();

    // ------------------------------------------------------------- draft-save path

    /**
     * The path that actually broke in production: the caller holds the freshly priced entity, so
     * the recalculation must run off it and never consult the uuid — which in that transaction
     * still resolves to null.
     */
    @Test
    void recalcForInvoice_withLoadedEntity_recomputesWithoutReReadingByUuid() {
        Invoice invoice = draftInvoiceWithItems(1000.0, 200.0);
        InvoiceBonus bonus = spy(percentBonus("b1", 50.0));
        doNothing().when(bonus).persist();
        service.bonuses = List.of(bonus);

        service.recalcForInvoice(invoice);

        // 50% of (1000 + 200), taken from the passed entity's items.
        assertEquals(600.0, bonus.getComputedAmount(), 0.001);
        verify(bonus).persist();
        assertEquals(0, service.findInvoiceByIdCalls,
                "entity overload must not re-read the invoice by uuid");
    }

    /** Line selections win over the flat percentage, and are computed from the passed entity. */
    @Test
    void recalcForInvoice_withLoadedEntity_honoursLineSelections() {
        Invoice invoice = draftInvoiceWithItems(1000.0, 500.0);
        String selectedItem = invoice.getInvoiceitems().get(0).getUuid();
        InvoiceBonus bonus = spy(percentBonus("b1", 100.0));
        doNothing().when(bonus).persist();
        service.bonuses = List.of(bonus);
        service.linesByBonus = Map.of("b1", List.of(line("b1", selectedItem, 40.0)));

        service.recalcForInvoice(invoice);

        // 40% of the first BASE line only; no CALCULATED lines, discount 0.
        assertEquals(400.0, bonus.getComputedAmount(), 0.001);
        assertEquals(InvoiceBonus.ShareType.AMOUNT, bonus.getShareType(),
                "line-selected bonuses are pinned to a fixed AMOUNT");
        assertEquals(400.0, bonus.getShareValue(), 0.001);
    }

    /** A credit note's bonus must come out negative, off the passed entity. */
    @Test
    void recalcForInvoice_withLoadedCreditNote_negatesComputedAmount() {
        Invoice creditNote = draftInvoiceWithItems(800.0);
        creditNote.setType(InvoiceType.CREDIT_NOTE);
        InvoiceBonus bonus = spy(percentBonus("b1", 100.0));
        doNothing().when(bonus).persist();
        service.bonuses = List.of(bonus);

        service.recalcForInvoice(creditNote);

        assertEquals(-800.0, bonus.getComputedAmount(), 0.001);
    }

    /** A null entity is a programming error, not something to swallow. */
    @Test
    void recalcForInvoice_withNullEntity_throws() {
        assertThrows(NullPointerException.class, () -> service.recalcForInvoice((Invoice) null));
    }

    // -------------------------------------------------- uuid overload: no silent skip

    /** When the invoice resolves, the uuid overload must delegate and produce the same figure. */
    @Test
    void recalcForInvoice_byUuid_whenResolvable_recomputes() {
        Invoice invoice = draftInvoiceWithItems(2000.0);
        InvoiceBonus bonus = spy(percentBonus("b1", 25.0));
        doNothing().when(bonus).persist();
        service.invoiceById = invoice;
        service.bonuses = List.of(bonus);

        service.recalcForInvoice(INVOICE_UUID);

        assertEquals(500.0, bonus.getComputedAmount(), 0.001);
        verify(bonus).persist();
    }

    /**
     * The core of the fix: an unresolvable invoice that still has bonus rows means real money is
     * about to be left on a stale value. That must fail loudly rather than log-and-return.
     */
    @Test
    void recalcForInvoice_byUuid_unresolvableButBonusesExist_throwsRatherThanSkipping() {
        InvoiceBonus stranded = spy(percentBonus("b1", 100.0));
        service.invoiceById = null;
        service.bonuses = List.of(stranded);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.recalcForInvoice(INVOICE_UUID));

        assertTrue(ex.getMessage().contains(INVOICE_UUID), "message must identify the invoice");
        assertTrue(ex.getMessage().contains("recalcForInvoice(Invoice)"),
                "message must point the caller at the entity overload");
        verify(stranded, org.mockito.Mockito.never()).persist();
        assertEquals(0.0, stranded.getComputedAmount(), 0.001);
    }

    /**
     * A genuinely deleted invoice cannot have bonus rows (FK fk_invbonus_invoice), so returning is
     * correct — the skip is provably a no-op, not a dropped recalculation.
     */
    @Test
    void recalcForInvoice_byUuid_genuinelyMissingWithNoBonuses_returnsQuietly() {
        service.invoiceById = null;
        service.bonuses = List.of();

        service.recalcForInvoice(INVOICE_UUID);

        assertEquals(1, service.findInvoiceByIdCalls);
    }

    // ------------------------------------------------------------------- guard rails

    /** The entity overload must exist and be what the invoice-save call sites can bind to. */
    @Test
    void entityOverloadIsPubliclyAvailable() throws NoSuchMethodException {
        assertFalse(java.lang.reflect.Modifier.isPrivate(
                InvoiceBonusService.class.getMethod("recalcForInvoice", Invoice.class).getModifiers()));
    }

    // ---------------------------------------------------------------------- fixtures

    private static Invoice draftInvoiceWithItems(double... lineTotals) {
        Invoice invoice = new Invoice();
        invoice.setUuid(INVOICE_UUID);
        invoice.setType(InvoiceType.INVOICE);
        invoice.setDiscount(0.0);
        List<InvoiceItem> items = new ArrayList<>();
        int i = 0;
        for (double total : lineTotals) {
            InvoiceItem item = new InvoiceItem();
            item.uuid = "item-" + (i++);
            item.setHours(1.0);
            item.setRate(total);
            item.setOrigin(InvoiceItemOrigin.BASE);
            item.setInvoiceuuid(INVOICE_UUID);
            items.add(item);
        }
        invoice.setInvoiceitems(items);
        return invoice;
    }

    private static InvoiceBonus percentBonus(String uuid, double percent) {
        InvoiceBonus bonus = new InvoiceBonus();
        bonus.setUuid(uuid);
        bonus.setInvoiceuuid(INVOICE_UUID);
        bonus.setUseruuid("d25e26a8-fdf2-11e4-b1b0-49f6f6edc206");
        bonus.setShareType(InvoiceBonus.ShareType.PERCENT);
        bonus.setShareValue(percent);
        bonus.setComputedAmount(0.0);
        return bonus;
    }

    private static InvoiceBonusLine line(String bonusuuid, String invoiceitemuuid, double percentage) {
        InvoiceBonusLine l = new InvoiceBonusLine();
        l.setBonusuuid(bonusuuid);
        l.setInvoiceuuid(INVOICE_UUID);
        l.setInvoiceitemuuid(invoiceitemuuid);
        l.setPercentage(percentage);
        return l;
    }
}
