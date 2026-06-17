package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.supplier.EconomicsSupplierResolver;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Journal;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.SupplierInvoice;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Voucher;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for EconomicsInvoiceService#buildJSONRequest (debtor-side
 * supplier-invoice branch). No DB / no Quarkus container: the two DateUtils calls
 * are pure and IntegrationKey.IntegrationKeyValue is a directly-constructable record.
 * Only agreementResolver + supplierResolver are used by buildJSONRequest; the other
 * @Inject fields (invoicePdfS3Service, bookingApi) are left null.
 *
 * Covers AC1 (3050), AC2 (3055), AC3 (creditor + cost on contra), AC4 (I25),
 * AC5 (unmapped fallback keeps today's behaviour), AC7 (manual branch untouched).
 */
@ExtendWith(MockitoExtension.class)
class EconomicsInvoiceServiceVoucherTest {

    private static final String AS_UUID    = "d8894494-2fb4-4f72-9e05-e6032e6dd691"; // debtor A/S
    private static final String TECH_UUID  = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3"; // issuer Technology
    private static final String CYBER_UUID = "e4b0a2a4-0963-4153-b0a2-a409637153a2"; // issuer Cyber
    private static final String TECH_CVR   = "39913708";
    private static final int INTERNAL_JOURNAL = 77;
    private static final int INVOICE_ACCOUNT  = 2101; // debtor invoice-account-number (fallback / contra)

    @InjectMocks
    EconomicsInvoiceService service;

    @Mock EconomicsAgreementResolver agreementResolver;
    @Mock EconomicsSupplierResolver supplierResolver;

    // Canonical record order: (url, appSecretToken, agreementGrantToken,
    // expenseJournalNumber, invoiceJournalNumber, invoiceAccountNumber,
    // internalJournalNumber, invoiceProductNumber)
    private IntegrationKey.IntegrationKeyValue keys() {
        return new IntegrationKey.IntegrationKeyValue(
                "https://restapi.e-conomic.com", "APP", "GRANT",
                10, 20, INVOICE_ACCOUNT, INTERNAL_JOURNAL, 1);
    }

    private Company debtorAS() {
        Company c = new Company();
        c.setUuid(AS_UUID);
        c.setName("Trustworks A/S");
        return c;
    }

    private Invoice internalInvoice(String issuerUuid, String issuerCvr) {
        Invoice inv = new Invoice();
        inv.setUuid("inv-001");
        inv.setInvoicenumber(91001);
        inv.setInvoicedate(LocalDate.of(2026, 4, 15));
        inv.setGrandTotal(50_000.0);
        inv.setVat(25.0); // DKK intercompany invoices carry 25% — grandTotal is the VAT-inclusive gross
        Company issuer = new Company();
        issuer.setUuid(issuerUuid);
        issuer.setCvr(issuerCvr);
        issuer.setName("Issuer ApS");
        inv.setCompany(issuer);
        return inv;
    }

    @Test
    void technology_to_AS_books_cost_on_3050_contra_with_creditor() {
        Invoice inv = internalInvoice(TECH_UUID, TECH_CVR);
        Journal journal = new Journal(INTERNAL_JOURNAL); // == internalJournalNumber -> supplier branch
        when(agreementResolver.intercompanyCostAccount(AS_UUID, TECH_UUID)).thenReturn(Optional.of(3050));
        when(supplierResolver.resolveByCvr(AS_UUID, TECH_CVR)).thenReturn(Optional.of(700));

        Voucher voucher = service.buildJSONRequest(inv, journal, "Client, Faktura 91001", keys(), debtorAS());

        SupplierInvoice si = voucher.getEntries().getSupplierInvoices().get(0);
        // e-conomic ignores `account` on a supplierInvoices entry; the cost account is the CONTRA
        // (offset) leg. It must be present, else e-conomic books only the creditor with no cost.
        assertEquals(3050, si.getContraAccount().getAccountNumber()); // AC1 — cost lands on 3050 (contra)
        assertEquals(3050, si.getAccount().getAccountNumber());       // mirrors unmapped shape (account==contra)
        assertNotNull(si.getSupplier());                              // AC3 — creditor present
        assertEquals(700, si.getSupplier().supplierNumber);
        assertEquals("I25", si.getContraVatAccount().vatCode);        // AC4
        // The debtor OWES the issuer, so the creditor must be CREDITED — e-conomic credits the
        // supplier only for a NEGATIVE amount; a positive amount debits it (the reported bug).
        assertEquals(-50_000.0, si.getAmount(), 0.001);
        assertNull(voucher.getEntries().getManualCustomerInvoices()); // supplier branch only
    }

    @Test
    void cyber_to_AS_books_cost_on_3055() {
        Invoice inv = internalInvoice(CYBER_UUID, "12345678");
        Journal journal = new Journal(INTERNAL_JOURNAL);
        when(agreementResolver.intercompanyCostAccount(AS_UUID, CYBER_UUID)).thenReturn(Optional.of(3055));
        when(supplierResolver.resolveByCvr(AS_UUID, "12345678")).thenReturn(Optional.of(701));

        Voucher voucher = service.buildJSONRequest(inv, journal, "txt", keys(), debtorAS());

        SupplierInvoice si = voucher.getEntries().getSupplierInvoices().get(0);
        assertEquals(3055, si.getContraAccount().getAccountNumber()); // AC2 — cost lands on 3055 (contra)
        assertEquals(3055, si.getAccount().getAccountNumber());
        assertEquals(-50_000.0, si.getAmount(), 0.001);               // creditor credited (negative)
    }

    @Test
    void mapped_pair_with_non_25_vat_rate_is_refused_to_avoid_lifting_vat_from_net() {
        // Guard: the invoice carries vat=0 (its grandTotal is the NET, not the gross), yet the
        // supplier resolves so I25 would be applied. e-conomic would then lift VAT out of the net.
        // The voucher build must fail closed rather than mis-state VAT.
        Invoice inv = internalInvoice(TECH_UUID, TECH_CVR);
        inv.setVat(0.0);
        Journal journal = new Journal(INTERNAL_JOURNAL);
        when(agreementResolver.intercompanyCostAccount(AS_UUID, TECH_UUID)).thenReturn(Optional.of(3050));
        when(supplierResolver.resolveByCvr(AS_UUID, TECH_CVR)).thenReturn(Optional.of(700));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.buildJSONRequest(inv, journal, "txt", keys(), debtorAS()));
        assertTrue(ex.getMessage().contains("VAT rate"),
                "guard message should explain the VAT-rate mismatch, was: " + ex.getMessage());
    }

    @Test
    void unmapped_pair_keeps_todays_behaviour_account_and_contra_both_2101() {
        Invoice inv = internalInvoice(TECH_UUID, TECH_CVR);
        Journal journal = new Journal(INTERNAL_JOURNAL);
        when(agreementResolver.intercompanyCostAccount(AS_UUID, TECH_UUID)).thenReturn(Optional.empty()); // unmapped
        when(supplierResolver.resolveByCvr(AS_UUID, TECH_CVR)).thenReturn(Optional.empty());

        Voucher voucher = service.buildJSONRequest(inv, journal, "txt", keys(), debtorAS());

        SupplierInvoice si = voucher.getEntries().getSupplierInvoices().get(0);
        assertEquals(INVOICE_ACCOUNT, si.getAccount().getAccountNumber());            // AC5 — fallback 2101
        assertNotNull(si.getContraAccount());                                        // today's behaviour kept
        assertEquals(INVOICE_ACCOUNT, si.getContraAccount().getAccountNumber());      // contra still 2101
    }

    @Test
    void regular_journal_still_builds_manual_customer_invoice_unchanged() {
        Invoice inv = internalInvoice(TECH_UUID, TECH_CVR);
        Journal journal = new Journal(20); // != internalJournalNumber(77) -> manual branch

        Voucher voucher = service.buildJSONRequest(inv, journal, "txt", keys(), debtorAS());

        assertNull(voucher.getEntries().getSupplierInvoices());          // AC7 — not the supplier path
        assertNotNull(voucher.getEntries().getManualCustomerInvoices()); // manual branch produced
        assertEquals(1, voucher.getEntries().getManualCustomerInvoices().size());
        // The manual branch is untouched: the issuer-aware resolver is never consulted.
        verifyNoInteractions(agreementResolver);
        verifyNoInteractions(supplierResolver);
    }
}
