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

        // References
        draft.setOtherReference(truncate(inv.getProjectref(), 250));
        draft.setHeading(inv.getType() == InvoiceType.CREDIT_NOTE ? "Kreditnota" : "Faktura");
        draft.setTextLine1(truncate(inv.getSpecificdescription(), 1000));
        if (ctx.contract() != null) {
            draft.setTextLine2(truncate(ctx.contract().getBillingRef(), 1000));
        }

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
     */
    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
