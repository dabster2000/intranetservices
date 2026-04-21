package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver.Tokens;
import dk.trustworks.intranet.aggregates.invoice.services.InvoicePdfS3Service;
import dk.trustworks.intranet.expenseservice.exceptions.PdfNotYetRenderedException;
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
}
