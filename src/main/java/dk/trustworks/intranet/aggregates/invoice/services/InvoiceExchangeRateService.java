package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.network.CurrencyAPI;
import dk.trustworks.intranet.aggregates.invoice.network.dto.CurrencyData;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Stamps {@link Invoice#exchangeRate} on foreign-currency invoices at finalization.
 *
 * <p>Every revenue path in the app values an invoice as
 * {@code SUM(invoiceitems.rate * invoiceitems.hours)}. Until V503 nothing stored a
 * rate, so a EUR or SEK invoice was counted at face value as kroner — for FY2025/26
 * that understated Technology's 6 EUR invoices by 1,100,566 DKK and overstated A/S's
 * 9 SEK invoices by 453,469 DKK. V503 added the column and taught the revenue views
 * and the dashboard builders to apply it; this service is what keeps it populated
 * going forward, so the fix does not decay the moment the next EUR invoice is raised.
 *
 * <p><b>Rate direction:</b> DKK per one unit of the invoice's currency, which is what
 * {@code CurrencyAPI.getExchangeRate(date, base=<invoice currency>, currencies="DKK")}
 * returns. A EUR invoice therefore gets ~7.46, a SEK invoice ~0.69.
 *
 * <p><b>Rate date:</b> the invoice date, so the stamped rate is contemporaneous with
 * the document. It will not match the rate e-conomic later books to the last decimal;
 * V504 backfilled history from the realised bookings, and the residual difference on a
 * new invoice is basis-point noise on a single document.
 *
 * <p><b>Failure is non-fatal.</b> A currency-service outage must never block invoice
 * finalization — an unsent invoice is worse than an imprecise report. The rate is left
 * null (revenue falls back to face value) and a WARN names the invoice so the gap is
 * visible rather than silent.
 */
@JBossLog
@ApplicationScoped
public class InvoiceExchangeRateService {

    static final String BASE_CURRENCY = "DKK";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @RestClient
    CurrencyAPI currencyAPI;

    @ConfigProperty(name = "currencyapi.key")
    String apiKey;

    /**
     * Sets {@code inv.exchangeRate} when the invoice is in a foreign currency.
     * No-op (and explicitly nulls the field) for DKK, so a currency correction on a
     * draft cannot leave a stale rate behind.
     */
    public void stampIfForeignCurrency(Invoice inv) {
        if (inv == null) return;
        if (!isForeignCurrency(inv.getCurrency())) {
            inv.setExchangeRate(null);
            return;
        }
        LocalDate rateDate = inv.getInvoicedate() != null ? inv.getInvoicedate() : LocalDate.now();
        BigDecimal rate = lookupRate(inv.getCurrency(), rateDate);
        if (rate == null) {
            log.warnf("invoice %s is in %s but no exchange rate could be sourced for %s — "
                            + "revenue will count it at face value until the rate is set",
                    inv.getUuid(), inv.getCurrency(), rateDate);
            return;
        }
        inv.setExchangeRate(rate);
        log.infof("invoice %s: stamped exchange rate %s DKK per 1 %s (%s)",
                inv.getUuid(), rate.toPlainString(), inv.getCurrency(), rateDate);
    }

    /** True for any currency that is not DKK. Null/blank is treated as DKK, matching the column default. */
    static boolean isForeignCurrency(String currency) {
        return currency != null && !currency.isBlank() && !BASE_CURRENCY.equalsIgnoreCase(currency.trim());
    }

    /**
     * DKK per one unit of {@code currency} on {@code date}, or null when the lookup
     * fails for any reason. Never throws — see the class note on non-fatal failure.
     */
    BigDecimal lookupRate(String currency, LocalDate date) {
        try (Response response = currencyAPI.getExchangeRate(
                date.toString(), currency.trim().toUpperCase(), BASE_CURRENCY, apiKey)) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warnf("currency lookup for %s on %s returned HTTP %d", currency, date, response.getStatus());
                return null;
            }
            CurrencyData data = objectMapper.readValue(response.readEntity(String.class), CurrencyData.class);
            Double rate = data.getExchangeRate(date.toString(), BASE_CURRENCY);
            if (rate == null || rate <= 0) {
                log.warnf("currency lookup for %s on %s carried no usable %s rate", currency, date, BASE_CURRENCY);
                return null;
            }
            return BigDecimal.valueOf(rate).setScale(8, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warnf(e, "currency lookup failed for %s on %s", currency, date);
            return null;
        }
    }
}
