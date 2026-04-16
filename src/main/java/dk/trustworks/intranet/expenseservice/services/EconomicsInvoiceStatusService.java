package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@JBossLog
@ApplicationScoped
public class EconomicsInvoiceStatusService {

    @Inject
    EconomicsAgreementResolver agreementResolver;

    @Inject
    @RestClient
    EconomicsBookingApiClient bookApi;

    public boolean isPaid(Invoice invoice) {
        try {
            if (invoice.getEconomicsBookedNumber() == null || invoice.getEconomicsBookedNumber() == 0) {
                return false;   // legacy invoices no longer auto-mark PAID
            }
            return isPaidViaBookedApi(invoice);
        } catch (Exception e) {
            log.errorf(e, "isPaid failed for invoice %s", invoice != null ? invoice.getUuid() : "null");
            return false;
        }
    }

    /**
     * New-flow paid check: GET /invoices/booked/{bookedInvoiceNumber} and inspect remainder.
     * SPEC-INV-001 §8.11.
     */
    private boolean isPaidViaBookedApi(Invoice invoice) {
        try {
            EconomicsAgreementResolver.Tokens tokens =
                    agreementResolver.tokens(invoice.getCompany().getUuid());
            EconomicsBookedInvoice booked = bookApi.getBooked(
                    tokens.appSecret(),
                    tokens.agreementGrant(),
                    invoice.getEconomicsBookedNumber());
            boolean paid = booked.getRemainder() != null && booked.getRemainder() == 0.0;
            log.debugf("isPaidViaBookedApi: invoiceUuid=%s bookedNumber=%d remainder=%s paid=%s",
                    invoice.getUuid(), invoice.getEconomicsBookedNumber(),
                    booked.getRemainder(), paid);
            return paid;
        } catch (Exception e) {
            log.warnf(e, "isPaidViaBookedApi: failed for invoice %s bookedNumber=%d",
                    invoice.getUuid(), invoice.getEconomicsBookedNumber());
            return false;
        }
    }
}
