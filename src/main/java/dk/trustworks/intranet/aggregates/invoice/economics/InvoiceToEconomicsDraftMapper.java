package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContactRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomerRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftLine;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.utils.CountryCodeMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@link DraftContext} to the Q2C API v5.1.0 flat draft-invoice payload.
 * All field truncations match the API character limits documented in SPEC-INV-001 §6.4, §6.5.
 *
 * <p>This class is a pure mapping component — no transactions, no side-effects,
 * no business rules beyond what the spec mandates.</p>
 */
@ApplicationScoped
public class InvoiceToEconomicsDraftMapper {

    private static final Logger LOG = Logger.getLogger(InvoiceToEconomicsDraftMapper.class);

    @Inject
    ClientEconomicsCustomerRepository customerRepo;

    @Inject
    ClientEconomicsContactRepository contactRepo;

    /**
     * Builds the full {@link EconomicsDraftInvoice} header from the context.
     * Lines are NOT embedded here; use {@link #toLines(DraftContext)} for lines
     * and attach them separately when posting to the API.
     *
     * @throws IllegalStateException when the billing client is not paired to an
     *                               e-conomic customer for the invoice's company.
     */
    public EconomicsDraftInvoice toDraft(DraftContext ctx) {
        Invoice inv = ctx.invoice();
        Client  billing = ctx.billingClient();
        String  companyUuid = inv.getCompany().getUuid();
        String  clientUuid  = billing.getUuid();

        var customer = customerRepo.findByClientAndCompany(clientUuid, companyUuid)
                .orElseThrow(() -> new IllegalStateException(
                        "Client '" + clientUuid + "' is not paired to an e-conomic customer " +
                        "for company '" + companyUuid + "'. Run the customer sync first."));

        var draft = new EconomicsDraftInvoice();

        // Customer
        draft.setCustomerNumber(customer.getCustomerNumber());
        draft.setCustomerName(truncate(billing.getName(), 255));

        // Header scalars
        draft.setDate(inv.getInvoicedate());
        draft.setCurrencyCode(inv.getCurrency());
        draft.setLayoutNumber(ctx.layoutNumber());
        draft.setTermOfPaymentNumber(ctx.termOfPaymentNumber());
        draft.setVatZoneNumber(ctx.vatZoneNumber());

        // Recipient (flat)
        draft.setCustomerAddress(truncate(billing.getBillingAddress(), 250));
        draft.setCustomerPostalCode(billing.getBillingZipcode());
        draft.setCustomerCity(truncate(billing.getBillingCity(), 250));
        draft.setCustomerCountry(resolveCountry(billing.getBillingCountry()));

        // References — single line carries all three refs, separator " | ",
        // empties dropped. See spec 2026-05-05-invoice-refs-redesign-design.md §2.
        draft.setOtherReference(
                buildOtherReference(inv.getContractref(), inv.getProjectref(), inv.getSpecificdescription()));
        draft.setHeading(inv.getType() == InvoiceType.CREDIT_NOTE ? "Kreditnota" : "Faktura");

        // Attention — only when contract carries a non-blank billingAttention
        // and a contact mapping exists for that name
        if (ctx.contract() != null) {
            String attention = ctx.contract().getBillingAttention();
            if (attention != null && !attention.isBlank()) {
                contactRepo.findByClientCompanyAndName(clientUuid, companyUuid, attention)
                        .ifPresent(c -> draft.setAttentionNumber(c.getCustomerContactNumber()));
            }
        }

        return draft;
    }

    /**
     * Builds the invoice lines from the context.
     * Priced lines carry the product number from {@link DraftContext#productNumber()}.
     * Credit note lines have their {@code unitNetPrice} negated.
     */
    public List<EconomicsDraftLine> toLines(DraftContext ctx) {
        Invoice inv = ctx.invoice();
        boolean isCreditNote = inv.getType() == InvoiceType.CREDIT_NOTE;
        List<InvoiceItem> items = inv.getInvoiceitems();
        List<EconomicsDraftLine> lines = new ArrayList<>(items.size());

        for (InvoiceItem item : items) {
            var line = new EconomicsDraftLine();
            line.setProductNumber(ctx.productNumber());
            line.setDescription(truncate(buildDescription(item), 2500));
            line.setQuantity(item.getHours());
            double unitPrice = item.getRate();
            line.setUnitNetPrice(isCreditNote ? -unitPrice : unitPrice);
            lines.add(line);
        }

        return lines;
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Joins the three customer-facing refs into a single "Øvrig ref" line.
     * Empty/null segments are dropped. If the joined result exceeds the 250-char
     * e-conomic limit, segments are shrunk in order: specificdescription first,
     * then projectref, then contractref (most-critical for customer payment is
     * preserved longest). Ellipsis "…" appended when a segment is shrunk.
     * Returns null when all three are blank so the JSON property is omitted
     * (EconomicsDraftInvoice uses {@code @JsonInclude(NON_NULL)}).
     */
    private static String buildOtherReference(String contractref, String projectref, String specificdescription) {
        String[] segments = new String[]{
                blankToNull(contractref),
                blankToNull(projectref),
                blankToNull(specificdescription)
        };
        return joinWithBudget(segments, 250);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Joins non-null segments with " | ", shrinking from the end of the segment
     * list (specificdescription, projectref, contractref) until the joined result
     * fits within {@code budget} chars. Each shrink keeps a prefix and appends "…".
     */
    private static String joinWithBudget(String[] segments, int budget) {
        // Drop nulls
        List<String> active = new ArrayList<>(segments.length);
        for (String s : segments) if (s != null) active.add(s);
        if (active.isEmpty()) return null;

        String joined = String.join(" | ", active);
        if (joined.length() <= budget) return joined;

        // Shrink in priority order: specificdescription (idx 2), then projectref (idx 1), then contractref (idx 0).
        int[] shrinkOrder = new int[]{2, 1, 0};
        for (int idx : shrinkOrder) {
            if (segments[idx] == null) continue;
            int overshoot = joined.length() - budget;
            segments[idx] = shrinkSegment(segments[idx], overshoot);
            // Rebuild active list and joined string
            active.clear();
            for (String s : segments) if (s != null) active.add(s);
            joined = String.join(" | ", active);
            LOG.warnf("otherReference truncated: segment shrunk to fit 250-char budget (final length=%d)", joined.length());
            if (joined.length() <= budget) return joined;
        }
        // Last resort: hard-truncate the whole joined string to budget
        LOG.warnf("otherReference still over budget after segment shrink — hard-truncating to %d chars", budget);
        return joined.substring(0, budget - 1) + "…";
    }

    /** Cuts {@code value} from the end and appends "…". {@code overshoot} is how many chars must be removed. */
    private static String shrinkSegment(String value, int overshoot) {
        if (value == null) return null;
        // We must remove `overshoot` chars and replace the trailing chars with "…" (1 char), so cut overshoot+1.
        int newLen = Math.max(0, value.length() - overshoot - 1);
        if (newLen == 0) return "…";
        return value.substring(0, newLen) + "…";
    }

    /**
     * Builds the line description.
     * Format: "itemname - description" when itemname is non-blank, else just "description".
     */
    private static String buildDescription(InvoiceItem item) {
        String itemname = item.getItemname();
        String description = item.getDescription();
        if (itemname != null && !itemname.isBlank()) {
            return itemname + " - " + description;
        }
        return description != null ? description : "";
    }

    /**
     * Resolves an ISO 3166-1 alpha-2 country code to the English name expected by e-conomic.
     * Falls back to "Denmark" when the code is null or blank (mirrors Client default).
     */
    private static String resolveCountry(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return CountryCodeMapper.toEconomicsName("DK");
        }
        return CountryCodeMapper.toEconomicsName(iso2);
    }

    /**
     * Truncates {@code value} to at most {@code maxLen} characters.
     * Returns null when value is null (API fields are {@code @JsonInclude(NON_NULL)}).
     * Logs a WARN the first time any field overruns its limit so data-quality
     * issues surface instead of being silently corrupted in e-conomic.
     */
    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        if (value.length() <= maxLen) return value;
        LOG.warnf("Truncated draft-invoice field from %d to %d chars — review source data",
                value.length(), maxLen);
        return value.substring(0, maxLen);
    }
}
