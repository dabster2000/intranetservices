package dk.trustworks.intranet.expenseservice.exceptions;

import java.io.IOException;

/**
 * Thrown when e-conomic returns HTTP 404 for a booked-invoice PDF because the
 * PDF rendering is still in progress. The booking itself has already succeeded;
 * the rendered artifact typically becomes available 1-3 seconds later.
 *
 * <p>Surfaces as an {@link IOException} so callers on the voucher-upload path
 * treat it like any other transient upload failure. The caller's retry
 * mechanism (e.g. {@code EconomicsUploadRetryBatchlet}, 1m/5m/15m/1h/4h backoff)
 * will re-attempt the upload on the next cycle, by which point the PDF is
 * almost always rendered.
 */
public class PdfNotYetRenderedException extends IOException {

    private final String invoiceUuid;
    private final Integer economicsBookedNumber;

    public PdfNotYetRenderedException(String invoiceUuid, Integer economicsBookedNumber) {
        super("E-conomic PDF not yet rendered for invoice " + invoiceUuid
                + " (bookedNumber=" + economicsBookedNumber + ") — retry later");
        this.invoiceUuid = invoiceUuid;
        this.economicsBookedNumber = economicsBookedNumber;
    }

    public String getInvoiceUuid() {
        return invoiceUuid;
    }

    public Integer getEconomicsBookedNumber() {
        return economicsBookedNumber;
    }
}
