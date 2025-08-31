package dk.trustworks.intranet.aggregates.invoice.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.contracts.model.Contract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class RuleContext {

    private final Contract contract;
    private final Invoice invoice;
    private final ObjectMapper om;

    private final Map<String, Map<String, Object>> paramsByRule = new HashMap<>();
    private final int scale = 2;
    private final RoundingMode rm = RoundingMode.HALF_UP;

    RuleContext(Contract contract, Invoice invoice, ObjectMapper om) {
        this.contract = contract;
        this.invoice = invoice;
        this.om = om;
    }

    public Contract contract() { return contract; }
    public Invoice invoice() { return invoice; }
    public int scale() { return scale; }
    public RoundingMode rm() { return rm; }

    public void withParams(String ruleCode, Map<String, Object> params) {
        if (params != null) paramsByRule.put(ruleCode, params);
    }
    public <T> T params(String ruleCode, Class<T> type) {
        Map<String, Object> m = paramsByRule.get(ruleCode);
        if (m == null) try { return type.getDeclaredConstructor().newInstance(); }
        catch (Exception e) { throw new RuntimeException(e); }
        return om.convertValue(m, type);
    }

    public List<InvoiceItem> userItems() {
        return invoice.getInvoiceitems().stream()
                .filter(ii -> ii.origin == null || ii.origin == InvoiceItem.ItemOrigin.USER)
                .collect(Collectors.toList());
    }
    public List<InvoiceItem> systemItems() {
        return invoice.getInvoiceitems().stream()
                .filter(ii -> ii.origin == InvoiceItem.ItemOrigin.AUTO_RULE)
                .collect(Collectors.toList());
    }

    public BigDecimal subtotalBeforeRules() { return sum(userItems()); }
    public BigDecimal subtotalAfterRules()  { return sum(invoice.getInvoiceitems()); }

    private BigDecimal sum(List<InvoiceItem> items) {
        return items.stream()
                .map(ii -> BigDecimal.valueOf(ii.rate).multiply(BigDecimal.valueOf(ii.hours)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void removeSystemLinesByRule(String ruleCode) {
        invoice.getInvoiceitems().removeIf(ii ->
                ii.origin == InvoiceItem.ItemOrigin.AUTO_RULE && Objects.equals(ii.ruleCode, ruleCode));
    }

    /** Adds a locked system line. Convention: rate = amount, hours = 1 */
    public void addSystemAmountLine(String label, BigDecimal amount, String ruleCode, String note) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return;
        InvoiceItem ii = new InvoiceItem(
                null,                               // consultantuuid
                label,                              // itemname
                note != null ? note : "",           // description
                amount.doubleValue(),               // rate holds the amount
                1.0,                                // hours
                invoice.getUuid());
        ii.origin = InvoiceItem.ItemOrigin.AUTO_RULE;
        ii.locked = true;
        ii.ruleCode = ruleCode;
        ii.calcNote = note;
        invoice.getInvoiceitems().add(ii);
    }

    public BigDecimal round(BigDecimal v) { return v.setScale(scale, rm); }
    public String format(BigDecimal v) { return new DecimalFormat("#,##0.00").format(v); }
}
