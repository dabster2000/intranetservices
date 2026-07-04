package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests (no datasource, no Quarkus boot) for
 * {@link InvoiceService#firstDuplicateItemUuid(List)} — the guard that stops the
 * {@code PUT /invoices/{uuid}} "duplicate line-item id -> opaque 500" path.
 *
 * <p>Background (prod incident 2026-07-03): editing a credit-note draft re-persists the incoming
 * items reusing their uuids. The shipped fix ({@code em.clear()} in
 * {@link InvoiceService#legacyUpdateDraft}) detaches the DB-loaded rows so the reused uuids insert
 * cleanly. It does <b>not</b>, however, cover a request body that carries the <i>same</i> uuid on two
 * lines — persisting two objects with one identifier still raises
 * {@code NonUniqueObjectException}/{@code EntityExistsException}, which (no SQL cause) surfaces as an
 * opaque, auto-save-retried 500. {@code firstDuplicateItemUuid} detects that up-front so
 * {@code updateDraftInvoice} can reject it with a stable, non-retryable 400.
 *
 * <p>This test is intentionally not a {@code @QuarkusTest}: it exercises a pure static method, so it
 * runs in the DB-less sandbox where DB-backed Quarkus tests cannot boot.
 */
class InvoiceServiceUpdateDraftGuardTest {

    private static InvoiceItem item(String uuid) {
        InvoiceItem i = new InvoiceItem();
        i.uuid = uuid; // overrides the random uuid the no-arg constructor assigns
        return i;
    }

    @Test
    void nullList_hasNoDuplicate() {
        assertNull(InvoiceService.firstDuplicateItemUuid(null));
    }

    @Test
    void emptyList_hasNoDuplicate() {
        assertNull(InvoiceService.firstDuplicateItemUuid(Collections.emptyList()));
    }

    @Test
    void uniqueUuids_hasNoDuplicate() {
        List<InvoiceItem> items = Arrays.asList(item("a"), item("b"), item("c"));
        assertNull(InvoiceService.firstDuplicateItemUuid(items));
    }

    @Test
    void duplicateUuid_returnsTheOffendingUuid() {
        String dup = "c0ac2cc7-7977-414b-81ba-076eb7d4f770"; // the item from the prod burst
        List<InvoiceItem> items = Arrays.asList(item(dup), item("other"), item(dup));
        assertEquals(dup, InvoiceService.firstDuplicateItemUuid(items));
    }

    @Test
    void nullItemsAndNullUuids_areSkipped() {
        List<InvoiceItem> items = Arrays.asList(item("a"), null, item(null), item(null), item("b"));
        assertNull(InvoiceService.firstDuplicateItemUuid(items));
    }
}
