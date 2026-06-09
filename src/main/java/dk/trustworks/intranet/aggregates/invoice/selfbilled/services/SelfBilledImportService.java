package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLine;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledLineStatus;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.SelfBilledSource;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.parse.ParsedLine;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.parse.SelfBilledTextParser;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.parse.SelfBilledVoucherAggregator;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captures per-consultant self-billed lines from e-conomic into selfbilled_line. Augments — never touches — the lump-PHANTOM import. */
@ApplicationScoped
public class SelfBilledImportService {

    private static final Logger log = Logger.getLogger(SelfBilledImportService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject SelfBilledCodeResolver codeResolver;
    @Inject SelfBilledImportService self;   // proxy so REQUIRES_NEW engages on self-calls

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

    /** Capture all enabled sources over the work window [from,to]. Each source commits independently. */
    public Map<String, Integer> capture(LocalDate from, LocalDate to) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SelfBilledSource source : SelfBilledSource.listEnabled()) {
            try {
                counts.put(source.label + "(" + source.accountNumber + ")", self.captureSource(source, from, to));
            } catch (RuntimeException e) {
                log.errorf(e, "capture: source %s failed", source.accountNumber);
                counts.put("ERROR:" + source.accountNumber, -1);
            }
        }
        log.infof("SelfBilledImportService.capture %s..%s -> %s", from, to, counts);
        return counts;
    }

    /** Fetch one source's lines from e-conomic, then process+upsert them. Own transaction. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int captureSource(SelfBilledSource source, LocalDate from, LocalDate to) {
        Company company = Company.findById(source.agreementCompanyUuid);
        if (company == null) { log.warnf("captureSource: company %s missing", source.agreementCompanyUuid); return 0; }
        IntegrationKey.IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(company);

        List<RawLine> raw = new ArrayList<>();
        try (EconomicsAPI api = buildEconomicsApi(keys)) {
            for (String yearCode : discoverAccountingYears(api, from, to)) {
                raw.addAll(fetchAccountLines(api, source.accountNumber, yearCode));
            }
        } catch (Exception e) {
            log.errorf(e, "captureSource: e-conomic fetch failed for account=%d", source.accountNumber);
        }
        return processLines(source, raw);
    }

    /**
     * Pure-of-HTTP: voucher-net the raw lines (D10), resolve consultant + issuer once per voucher,
     * and upsert each entry STAMPING the voucher-resolved period/code/consultant/status onto every
     * sibling (so corrections net in the SQL aggregates). Idempotent on entry_number. Caller supplies
     * the transaction (captureSource is REQUIRES_NEW; the IT wraps it @Transactional).
     */
    public int processLines(SelfBilledSource source, List<RawLine> raw) {
        List<SelfBilledVoucherAggregator.LineInput> inputs = new ArrayList<>();
        Map<Long, RawLine> byEntry = new LinkedHashMap<>();
        for (RawLine l : raw) {
            ParsedLine parsed = SelfBilledTextParser.parse(l.text()).orElse(null);
            inputs.add(new SelfBilledVoucherAggregator.LineInput(l.account(), l.voucher(), l.entry(), l.amount(), parsed, l.text()));
            byEntry.put(l.entry(), l);
        }
        List<SelfBilledVoucherAggregator.VoucherNet> vouchers = SelfBilledVoucherAggregator.aggregate(inputs);

        int upserts = 0;
        LocalDateTime now = LocalDateTime.now();
        for (SelfBilledVoucherAggregator.VoucherNet v : vouchers) {
            String consultantUuid = null, issuerCompanyUuid = null;
            SelfBilledLineStatus status;
            if (!v.resolved()) {
                status = SelfBilledLineStatus.UNPARSEABLE;
            } else {
                consultantUuid = codeResolver.resolve(source.agreementCompanyUuid, source.accountNumber, v.code());
                if (consultantUuid == null) {
                    status = SelfBilledLineStatus.UNMAPPED_CODE;
                } else {
                    issuerCompanyUuid = codeResolver.resolveIssuerCompany(consultantUuid, v.workYear(), v.workMonth());
                    status = SelfBilledLineStatus.RESOLVED;
                }
            }
            for (Long entry : v.entries()) {
                upsertLine(source, v, byEntry.get(entry), consultantUuid, issuerCompanyUuid, status, now);
                upserts++;
            }
        }
        log.infof("processLines account=%d vouchers=%d entries=%d", source.accountNumber, vouchers.size(), upserts);
        return upserts;
    }

    /** Upsert one entry. Period/code/consultant/issuer/status are VOUCHER-resolved (stamped on every sibling); only faktura is per-line. */
    private void upsertLine(SelfBilledSource source, SelfBilledVoucherAggregator.VoucherNet v, RawLine l,
                            String consultantUuid, String issuerCompanyUuid,
                            SelfBilledLineStatus status, LocalDateTime now) {
        if (l == null) return;
        SelfBilledLine row = SelfBilledLine.findByEntry(l.entry());
        if (row == null) {
            row = new SelfBilledLine();
            row.uuid = UUID.randomUUID().toString();
            row.entryNumber = l.entry();
            row.createdAt = now;
            row.persist();
        }
        row.sourceUuid = source.uuid;
        row.clientUuid = source.clientUuid;
        row.debtorCompanyUuid = source.agreementCompanyUuid;
        row.accountNumber = l.account();
        row.voucherNumber = l.voucher();
        row.fakturaNumber = SelfBilledTextParser.parse(l.text()).map(ParsedLine::faktura).orElse(null); // per-line audit
        row.workYear = v.resolved() ? v.workYear() : null;        // VOUCHER-resolved (corrections inherit) — F1 fix
        row.workMonth = v.resolved() ? v.workMonth() : null;
        row.code = v.resolved() ? v.code() : null;
        row.consultantUuid = consultantUuid;                      // voucher-level
        row.issuerCompanyUuid = issuerCompanyUuid;                // voucher-level
        row.amount = l.amount();                                  // per-line signed amount (SUM nets the voucher)
        row.sourceText = l.text() != null && l.text().length() > 255 ? l.text().substring(0, 255) : l.text();
        row.status = status;                                      // voucher-level
        row.refreshedAt = now;
    }
}
