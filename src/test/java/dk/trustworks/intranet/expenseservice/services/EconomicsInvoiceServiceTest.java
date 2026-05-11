package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.supplier.EconomicsSupplierResolver;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver.Tokens;
import dk.trustworks.intranet.aggregates.invoice.services.InvoicePdfS3Service;
import dk.trustworks.intranet.expenseservice.exceptions.PdfNotYetRenderedException;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Journal;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.SupplierInvoice;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.Voucher;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.model.Company;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the PDF-loading fallback in {@link EconomicsInvoiceService}.
 *
 * <p>The 2026-04-16 refactor removed the local PDF write path. For invoices
 * created after that date, neither {@code pdf_storage_key} nor {@code pdf} are
 * populated — the PDF lives only in e-conomic. These tests lock in the new
 * fallback that fetches the booked PDF from e-conomic as a last resort.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsInvoiceServiceTest {

    @InjectMocks EconomicsInvoiceService service;

    @Mock InvoicePdfS3Service invoicePdfS3Service;
    @Mock EconomicsBookingApiClient bookingApi;
    @Mock EconomicsAgreementResolver agreementResolver;
    @Mock EconomicsSupplierResolver supplierResolver;

    @Test
    void loadInvoicePdfBytes_reads_from_s3_when_storage_key_present() throws IOException {
        Invoice inv = internalInvoice("inv-1", "issuer-1", null);
        inv.setPdfStorageKey("invoices/inv-1.pdf");
        byte[] expected = new byte[]{1, 2, 3};
        when(invoicePdfS3Service.getPdfByKey("invoices/inv-1.pdf")).thenReturn(expected);

        byte[] bytes = service.loadInvoicePdfBytes(inv);

        assertArrayEquals(expected, bytes);
        verifyNoInteractions(bookingApi, agreementResolver);
    }

    @Test
    void loadInvoicePdfBytes_reads_from_db_blob_when_storage_key_absent() throws IOException {
        Invoice inv = internalInvoice("inv-1", "issuer-1", null);
        byte[] expected = new byte[]{4, 5, 6};
        inv.setPdf(expected);

        byte[] bytes = service.loadInvoicePdfBytes(inv);

        assertArrayEquals(expected, bytes);
        verifyNoInteractions(invoicePdfS3Service, bookingApi, agreementResolver);
    }

    @Test
    void loadInvoicePdfBytes_fetches_from_economic_when_both_local_sources_null_and_bookedNumber_set()
            throws IOException {
        Invoice inv = internalInvoice("inv-1", "issuer-1", 80123);
        when(agreementResolver.tokens("issuer-1")).thenReturn(new Tokens("APP", "GRANT"));
        byte[] expected = new byte[]{7, 8, 9};
        when(bookingApi.getBookedPdf("APP", "GRANT", 80123))
                .thenReturn(new ByteArrayInputStream(expected));

        byte[] bytes = service.loadInvoicePdfBytes(inv);

        assertArrayEquals(expected, bytes);
        verifyNoInteractions(invoicePdfS3Service);
    }

    @Test
    void loadInvoicePdfBytes_throws_PdfNotYetRendered_when_economic_returns_404() {
        Invoice inv = internalInvoice("inv-1", "issuer-1", 80123);
        when(agreementResolver.tokens("issuer-1")).thenReturn(new Tokens("APP", "GRANT"));
        Response notFound = Response.status(Response.Status.NOT_FOUND).build();
        when(bookingApi.getBookedPdf("APP", "GRANT", 80123))
                .thenThrow(new WebApplicationException(notFound));

        PdfNotYetRenderedException thrown = assertThrows(
                PdfNotYetRenderedException.class,
                () -> service.loadInvoicePdfBytes(inv));

        assertTrue(thrown.getMessage().contains("inv-1"),
                "Exception message should include the invoice uuid for log correlation, got: " + thrown.getMessage());
    }

    @Test
    void loadInvoicePdfBytes_propagates_non_404_WebApplicationException() {
        Invoice inv = internalInvoice("inv-1", "issuer-1", 80123);
        when(agreementResolver.tokens("issuer-1")).thenReturn(new Tokens("APP", "GRANT"));
        Response serverError = Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        when(bookingApi.getBookedPdf("APP", "GRANT", 80123))
                .thenThrow(new WebApplicationException(serverError));

        WebApplicationException thrown = assertThrows(
                WebApplicationException.class,
                () -> service.loadInvoicePdfBytes(inv));

        assertEquals(500, thrown.getResponse().getStatus());
    }

    @Test
    void loadInvoicePdfBytes_throws_when_all_sources_absent() {
        Invoice inv = internalInvoice("inv-1", "issuer-1", null);

        IOException thrown = assertThrows(
                IOException.class,
                () -> service.loadInvoicePdfBytes(inv));

        assertTrue(thrown.getMessage().contains("inv-1"),
                "Exception message should include the invoice uuid for log correlation, got: " + thrown.getMessage());
        verifyNoInteractions(bookingApi);
    }

    private Invoice internalInvoice(String uuid, String companyUuid, Integer bookedNumber) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        Company company = new Company();
        company.setUuid(companyUuid);
        inv.setCompany(company);
        inv.setEconomicsBookedNumber(bookedNumber);
        return inv;
    }

    private Invoice internalInvoiceWithCvr(String invoiceUuid,
                                           String issuerUuid,
                                           String issuerCvr,
                                           String debtorUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(invoiceUuid);
        inv.setInvoicenumber(20240315);
        inv.setClientname("Trustworks A/S");
        inv.setGrandTotal(12500.00);
        inv.setInvoicedate(java.time.LocalDate.of(2025, 2, 14));

        Company issuer = new Company();
        issuer.setUuid(issuerUuid);
        issuer.setName("Trustworks A/S");
        issuer.setCvr(issuerCvr);
        inv.setCompany(issuer);

        inv.setDebtorCompanyuuid(debtorUuid);

        return inv;
    }

    @Test
    void buildJSONRequest_internalJournal_enrichesWhenSupplierResolved() {
        Invoice inv = internalInvoiceWithCvr("inv-int-1", "issuer-1", "44232855", "debtor-1");

        IntegrationKey.IntegrationKeyValue keys = new IntegrationKey.IntegrationKeyValue(
                "https://restapi.e-conomic.com",
                "app-secret",
                "agreement-grant",
                /*expenseJournalNumber*/ 1,
                /*invoiceJournalNumber*/ 5,
                /*invoiceAccountNumber*/ 2101,
                /*internalJournalNumber*/ 7,
                /*invoiceProductNumber*/ 1);

        Company debtor = new Company();
        debtor.setUuid("debtor-1");
        debtor.setName("Trustworks NO AS");

        Journal journal = new Journal(7);  // internal journal
        when(supplierResolver.resolveByCvr("debtor-1", "44232855"))
                .thenReturn(java.util.Optional.of(50007));

        Voucher voucher = service.buildJSONRequest(inv, journal, "text", keys, debtor);

        java.util.List<SupplierInvoice> supplierInvoices = voucher.getEntries().getSupplierInvoices();
        assertNotNull(supplierInvoices);
        assertEquals(1, supplierInvoices.size());
        SupplierInvoice si = supplierInvoices.get(0);
        assertNotNull(si.getSupplier(), "supplier should be set");
        assertEquals(50007, si.getSupplier().getSupplierNumber());
        assertNotNull(si.getContraVatAccount(), "contraVatAccount should be set");
        assertEquals("I25", si.getContraVatAccount().getVatCode());
        // Customer-invoice list must be null in the supplier-journal branch
        assertNull(voucher.getEntries().getManualCustomerInvoices());
    }

    @Test
    void buildJSONRequest_internalJournal_omitsEnrichmentWhenResolverEmpty() {
        Invoice inv = internalInvoiceWithCvr("inv-int-2", "issuer-1", "44232855", "debtor-1");

        IntegrationKey.IntegrationKeyValue keys = new IntegrationKey.IntegrationKeyValue(
                "https://restapi.e-conomic.com",
                "app-secret",
                "agreement-grant",
                1, 5, 2101, 7, 1);

        Company debtor = new Company();
        debtor.setUuid("debtor-1");
        debtor.setName("Trustworks NO AS");

        Journal journal = new Journal(7);
        when(supplierResolver.resolveByCvr("debtor-1", "44232855"))
                .thenReturn(java.util.Optional.empty());

        Voucher voucher = service.buildJSONRequest(inv, journal, "text", keys, debtor);

        SupplierInvoice si = voucher.getEntries().getSupplierInvoices().get(0);
        assertNull(si.getSupplier(), "supplier must remain unset when resolver returns empty");
        assertNull(si.getContraVatAccount(), "contraVatAccount must remain unset when resolver returns empty");
    }

    @Test
    void buildJSONRequest_customerJournal_doesNotInvokeResolver() {
        Invoice inv = internalInvoiceWithCvr("inv-cust-1", "issuer-1", "44232855", "debtor-1");

        IntegrationKey.IntegrationKeyValue keys = new IntegrationKey.IntegrationKeyValue(
                "https://restapi.e-conomic.com",
                "app-secret",
                "agreement-grant",
                1, 5, 2101, 7, 1);

        Company issuerAsTarget = new Company();
        issuerAsTarget.setUuid("issuer-1");
        issuerAsTarget.setName("Trustworks A/S");

        Journal journal = new Journal(5);  // customer journal (NOT internal)

        Voucher voucher = service.buildJSONRequest(inv, journal, "text", keys, issuerAsTarget);

        assertNull(voucher.getEntries().getSupplierInvoices());
        assertNotNull(voucher.getEntries().getManualCustomerInvoices());
        verifyNoInteractions(supplierResolver);
    }
}
