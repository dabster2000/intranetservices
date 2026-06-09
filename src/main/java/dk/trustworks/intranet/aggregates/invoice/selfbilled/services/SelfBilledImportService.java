package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Captures per-consultant self-billed lines from e-conomic into selfbilled_line. Augments — never touches — the lump-PHANTOM import. */
@ApplicationScoped
public class SelfBilledImportService {

    private static final Logger log = Logger.getLogger(SelfBilledImportService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /** Raw e-conomic debtor line, before parsing. */
    public record RawLine(int account, int voucher, long entry, LocalDate date, String text, BigDecimal amount) {}

    EconomicsAPI buildEconomicsApi(IntegrationKey.IntegrationKeyValue keys) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(keys.url()))
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

    /** Accounting-year codes overlapping [from,to]. Mirrors EconomicRevenueImportService.discoverAccountingYears. */
    List<String> discoverAccountingYears(EconomicsAPI api, LocalDate from, LocalDate to) {
        List<String> codes = new ArrayList<>();
        try (Response r = api.getAccountingYears(50)) {
            JsonNode root = mapper.readTree(r.readEntity(String.class));
            JsonNode coll = root.has("collection") ? root.get("collection") : root;
            for (JsonNode n : coll) {
                LocalDate f = parseDate(n.path("fromDate").asText(null));
                LocalDate t = parseDate(n.path("toDate").asText(null));
                if (f == null || t == null || t.isBefore(from) || f.isAfter(to)) continue;
                String self = n.path("self").asText("");
                int slash = self.lastIndexOf('/');
                if (slash >= 0 && slash < self.length() - 1) codes.add(self.substring(slash + 1));
            }
        } catch (Exception e) {
            log.errorf(e, "SelfBilledImportService: discoverAccountingYears failed");
        }
        return codes;
    }

    /** All debtor lines on one account in one accounting year, paginated. */
    List<RawLine> fetchAccountLines(EconomicsAPI api, int account, String yearCode) {
        List<RawLine> out = new ArrayList<>();
        int skip = 0;
        while (true) {
            try (Response r = api.getAccountEntries(account, yearCode, null, 1000, skip)) {
                JsonNode root = mapper.readTree(r.readEntity(String.class));
                JsonNode coll = root.has("collection") ? root.get("collection") : root;
                int count = 0;
                for (JsonNode n : coll) {
                    BigDecimal amt = n.has("amountInBaseCurrency")
                            ? new BigDecimal(n.get("amountInBaseCurrency").asText("0"))
                            : new BigDecimal(n.path("amount").asText("0"));
                    out.add(new RawLine(
                            n.path("account").path("accountNumber").asInt(account),
                            n.path("voucherNumber").asInt(),
                            n.path("entryNumber").asLong(),
                            parseDate(n.path("date").asText(null)),
                            n.path("text").asText(""),
                            amt));
                    count++;
                }
                int pageSize = root.path("pagination").path("pageSize").asInt(1000);
                int total = root.path("pagination").path("results").asInt(-1);
                if (count < pageSize) break;
                if (total >= 0 && (skip + 1) * pageSize >= total) break;
                if (++skip > 50) { log.warnf("fetchAccountLines: skip cap account=%d year=%s", account, yearCode); break; }
            } catch (Exception e) {
                log.warnf(e, "fetchAccountLines: error account=%d year=%s skip=%d — stopping", account, yearCode, skip);
                break;
            }
        }
        return out;
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.substring(0, Math.min(10, s.length()))); }
        catch (Exception e) { return null; }
    }
}
