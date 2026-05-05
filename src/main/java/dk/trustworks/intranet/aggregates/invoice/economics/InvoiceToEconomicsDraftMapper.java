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
        return joinWithBudget(contractref, projectref, specificdescription, 250);
    }

    /** Returns null when blank; otherwise trims surrounding whitespace so segments don't render with padded separators. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Joins three customer-facing refs into a single line with " | " separators,
     * shrinking segments in priority order (specificdescription first, projectref
     * next, contractref last) until the joined result fits the budget. Each shrink
     * appends "…" to the cut segment and logs a WARN. Empty/null segments are
     * dropped entirely.
     *
     * <p>The priority order is encoded as the sequence of {@code if} blocks below:
     * the LEAST-critical segment for customer payment is shrunk first, the
     * MOST-critical (contractref) last.
     *
     * <p>Returns null when all three segments are blank, so the JSON property is
     * omitted (EconomicsDraftInvoice uses {@code @JsonInclude(NON_NULL)}).
     */
    private static String joinWithBudget(String contractref, String projectref, String specificdescription, int budget) {
        // Normalize: blank → null, trim non-blank
        contractref = blankToNull(contractref);
        projectref = blankToNull(projectref);
        specificdescription = blankToNull(specificdescription);

        String joined = joinNonNull(contractref, projectref, specificdescription);
        if (joined == null || joined.length() <= budget) return joined;

        // Priority 1 (lowest): shrink specificdescription first
        if (specificdescription != null) {
            specificdescription = shrinkSegment(specificdescription, joined.length() - budget);
            joined = joinNonNull(contractref, projectref, specificdescription);
            LOG.warnf("otherReference truncated: shrunk specificdescription (final length=%d)", joined.length());
            if (joined.length() <= budget) return joined;
        }

        // Priority 2: shrink projectref next
        if (projectref != null) {
            projectref = shrinkSegment(projectref, joined.length() - budget);
            joined = joinNonNull(contractref, projectref, specificdescription);
            LOG.warnf("otherReference truncated: shrunk projectref (final length=%d)", joined.length());
            if (joined.length() <= budget) return joined;
        }

        // Priority 3 (highest): shrink contractref last (customer payment depends on it)
        if (contractref != null) {
            contractref = shrinkSegment(contractref, joined.length() - budget);
            joined = joinNonNull(contractref, projectref, specificdescription);
            LOG.warnf("otherReference truncated: shrunk contractref (final length=%d)", joined.length());
            if (joined.length() <= budget) return joined;
        }

        // Last resort — defensive, unreachable for budget=250 with 3 segments
        LOG.warnf("otherReference still over budget after segment shrink — hard-truncating to %d chars", budget);
        return joined.substring(0, budget - 1) + "…";
    }

    /** Joins non-null arguments with " | "; returns null if all are null. */
    private static String joinNonNull(String... segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (s == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
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
