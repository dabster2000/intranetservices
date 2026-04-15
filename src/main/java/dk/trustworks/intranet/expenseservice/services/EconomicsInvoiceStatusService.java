package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.net.URI;

@JBossLog
@ApplicationScoped
public class EconomicsInvoiceStatusService {

    @Inject
    EconomicsAgreementResolver agreementResolver;

    @Inject
    @RestClient
    EconomicsBookingApiClient bookApi;

    private EconomicsAPI getEconomicsAPI(IntegrationKey.IntegrationKeyValue key) {
        try {
            EconomicsAPI api = RestClientBuilder.newBuilder()
                    .baseUri(URI.create(key.url()))
                    .register(new EconomicsDynamicHeaderFilter(key.appSecretToken(), key.agreementGrantToken()))
                    .build(EconomicsAPI.class);
            log.debugf("Created EconomicsAPI client for baseUri=%s", key.url());
            return api;
        } catch (Exception e) {
            log.error("Failed to create EconomicsAPI client", e);
            throw e;
        }
    }

    private static String toAccountingYearId(String fiscalYearName) {
        if (fiscalYearName == null) return null;
        if (fiscalYearName.contains("_6_")) return fiscalYearName;
        if (fiscalYearName.contains("/")) {
            String[] parts = fiscalYearName.split("/", 2);
            String id = parts[0].trim() + "_6_" + parts[1].trim();
            log.debugf("Converted fiscalYearName=%s to accountingYearId=%s", fiscalYearName, id);
            return id;
        }
        log.debugf("Using accountingYearId as-is: %s", fiscalYearName);
        return fiscalYearName;
    }

    private static String readEntity(Response r) {
        try {
            return r.readEntity(String.class);
        } catch (Exception e) {
            // can't read body (maybe already consumed or error)
            return null;
        }
    }

    public boolean isBooked(Invoice invoice) {
        try {
            log.debugf("isBooked: invoiceUuid=%s, invoiceNumber=%d, company=%s", invoice.getUuid(), invoice.getInvoicenumber(), invoice.getCompany().getUuid());
            String fiscalYearName = DateUtils.getFiscalYearName(
                    DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()),
                    invoice.getCompany().getUuid());
            log.debugf("Computed fiscalYearName=%s", fiscalYearName);

            IntegrationKey.IntegrationKeyValue key = IntegrationKey.getIntegrationKeyValue(invoice.getCompany());
            try (EconomicsAPI api = getEconomicsAPI(key)) {
                int voucherNumber = invoice.getEconomicsVoucherNumber();
                if (voucherNumber <= 0) {
                    log.warnf("isBooked: Missing voucherNumber for invoice %s; skipping check", invoice.getUuid());
                    return false;
                }
                String filter = "voucherNumber$eq:" + voucherNumber;
                String accountingYearId = toAccountingYearId(fiscalYearName);
                log.debugf("Calling getYearEntries with accountingYearId='%s' and filter='%s'", accountingYearId, filter);
                try (Response r = api.getYearEntries(accountingYearId, filter, 1)) {
                    int status = r.getStatus();
                    log.debugf("getYearEntries status=%d", status);
                    if (status != 200) {
                        log.warnf("isBooked: non-200 status=%d for invoice %s", status, invoice.getUuid());
                        return false;
                    }
                    String s = readEntity(r);
                    if (s == null) {
                        log.warnf("isBooked: empty response entity for invoice %s", invoice.getUuid());
                        return false;
                    }
                    JsonNode coll = new ObjectMapper().readTree(s).get("collection");
                    int size = (coll != null && coll.isArray()) ? coll.size() : 0;
                    log.debugf("isBooked: entries found=%d for invoice %s", size, invoice.getUuid());
                    return size > 0;
                }
            }
        } catch (Exception e) {
            log.errorf(e, "isBooked failed for invoice %s", invoice != null ? invoice.getUuid() : "null");
            return false;
        }
    }

    public boolean isPaid(Invoice invoice) {
        try {
            log.debugf("isPaid: invoiceUuid=%s, invoiceNumber=%d, company=%s",
                    invoice.getUuid(), invoice.getInvoicenumber(), invoice.getCompany().getUuid());

            // New flow (SPEC-INV-001 §8.11): invoice booked via Q2C API carries
            // economicsBookedNumber — query GET /invoices/booked/{n} for remainder.
            if (invoice.getEconomicsBookedNumber() != null && invoice.getEconomicsBookedNumber() > 0) {
                return isPaidViaBookedApi(invoice);
            }

            // Legacy flow (pre-migration): uses voucherNumber + year-entries journal lookup.
            return isPaidViaVoucherJournal(invoice);
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

    /**
     * Legacy paid check: voucherNumber + year-entries journal lookup.
     * Kept exactly as the original isPaid implementation.
     */
    private boolean isPaidViaVoucherJournal(Invoice invoice) {
        try {
            String fiscalYearName = DateUtils.getFiscalYearName(
                    DateUtils.getFiscalStartDateBasedOnDate(invoice.getInvoicedate()),
                    invoice.getCompany().getUuid());
            log.debugf("Computed fiscalYearName=%s", fiscalYearName);

            IntegrationKey.IntegrationKeyValue key = IntegrationKey.getIntegrationKeyValue(invoice.getCompany());
            try (EconomicsAPI api = getEconomicsAPI(key)) {
                int voucherNumber = invoice.getEconomicsVoucherNumber();
                if (voucherNumber <= 0) {
                    log.warnf("isPaid (legacy): Missing voucherNumber for invoice %s; skipping check",
                            invoice.getUuid());
                    return false;
                }
                String filter = "voucherNumber$eq:" + voucherNumber;
                String accountingYearId = toAccountingYearId(fiscalYearName);
                log.debugf("Calling getYearEntries with accountingYearId='%s' and filter='%s'",
                        accountingYearId, filter);
                try (Response r = api.getYearEntries(accountingYearId, filter, 1)) {
                    int status = r.getStatus();
                    log.debugf("getYearEntries status=%d", status);
                    if (status != 200) {
                        log.warnf("isPaid (legacy): non-200 status=%d for invoice %s",
                                status, invoice.getUuid());
                        return false;
                    }
                    String s = readEntity(r);
                    if (s == null) {
                        log.warnf("isPaid (legacy): empty response entity for invoice %s", invoice.getUuid());
                        return false;
                    }
                    JsonNode coll = new ObjectMapper().readTree(s).get("collection");
                    if (coll == null || !coll.isArray() || coll.size() == 0) {
                        log.debugf("isPaid (legacy): no entries yet for invoice %s (likely not booked)",
                                invoice.getUuid());
                        return false;
                    }
                    JsonNode entry = coll.get(0);
                    if (entry == null || entry.get("remainder") == null) {
                        log.debugf("isPaid (legacy): entry missing remainder for invoice %s", invoice.getUuid());
                        return false;
                    }
                    BigDecimal remainder = entry.get("remainder").decimalValue();
                    boolean paid = remainder.compareTo(BigDecimal.ZERO) == 0;
                    log.debugf("isPaid (legacy): remainder=%s, paid=%s for invoice %s",
                            remainder.toPlainString(), paid, invoice.getUuid());
                    return paid;
                }
            }
        } catch (Exception e) {
            log.warnf(e, "isPaid (legacy) failed for invoice %s", invoice.getUuid());
            return false;
        }
    }
}
