package dk.trustworks.intranet.aggregates.invoice.network.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.utils.NumberUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class InvoiceDTO {

    String header;

    InvoiceFieldsDTO fields;
    String currency;

    String logo; //URL of your logo	null
    String from; //The name of your organization	null
    String to; //The entity being billed - multiple lines ok	null
    String number; //Invoice number	null
    String purchase_order; //Purchase order number	null
    String date; //Invoice date	current date
    String payment_terms; //Payment terms summary (i.e. NET 30)	null
    String due_date; //Invoice due date	null
    List<InvoiceItemDto> items = new ArrayList<>();
    double discounts; //Subtotal discounts - numbers only	0
    int tax; //Tax - numbers only	0
    int shipping; //Shipping - numbers only	0
    double amount_paid; //Amount paid - numbers only	0
    String notes; //Notes - any extra information not included elsewhere	null
    String terms; //Terms and conditions - all the details	null

    private InvoiceDTO(String from, String to, String date, String dueDate, String currency, double vat) {
        logo = "";
        due_date = dueDate;
        this.currency = currency;
        this.tax = NumberUtils.convertDoubleToInt(vat);
        this.from = from;
        this.to = to;
        this.date = date;
    }

    private InvoiceDTO(String header, InvoiceFieldsDTO invoiceFieldsDTO, String from, String to, String number, String date, String dueDate, double discounts, String currency, double vat, String notes) {
        this(from, to, date, dueDate, currency, vat);
        this.header = header;
        this.fields = invoiceFieldsDTO;
        this.number = number;
        this.discounts = discounts;
        this.notes = notes;
    }

    public InvoiceDTO(Invoice invoice) {
        // Detect if Pricing Engine has produced synthetic discount lines
        boolean hasSyntheticLines = invoice.getInvoiceitems().stream()
                .anyMatch(ii -> ii.getOrigin() != null
                        && ii.getOrigin() == InvoiceItemOrigin.CALCULATED);

        // When synthetic lines exist, we must not also use the API-level "Discounts" row
        // Convert BigDecimal headerDiscountPct to percentage value (e.g., 5.00 → 5.0)
        double headerDiscountPercentage = invoice.getHeaderDiscountPct().doubleValue();
        String discountsFieldMode = hasSyntheticLines ? "false"
                : (headerDiscountPercentage > 0.0 ? "%" : "false");
        double discountsValue = hasSyntheticLines ? 0.0 : headerDiscountPercentage;

        this.header = invoice.getType().name();
        this.fields  = new InvoiceFieldsDTO(discountsFieldMode, false); // tax defaults to "%", shipping disabled
        this.currency = invoice.getCurrency();
        // Convert BigDecimal vatPct to int (e.g., 25.00 → 25)
        this.tax = dk.trustworks.intranet.utils.NumberUtils.convertDoubleToInt(invoice.getVatPct().doubleValue());

        this.from = invoice.getCompany().getName() + "\n" +
                invoice.getCompany().getAddress() + "\n" +
                invoice.getCompany().getZipcode() + " " + invoice.getCompany().getCity() + ", " + invoice.getCompany().getCountry() + "\n" +
                "CVR: " + invoice.getCompany().getCvr() + "\n\n" +
                "Phone: " + invoice.getCompany().getPhone() + "\n" +
                "Email: " + invoice.getCompany().getEmail();

        // Build 'to' address from structured billTo fields
        StringBuilder toBuilder = new StringBuilder();
        if (invoice.getBillToName() != null && !invoice.getBillToName().isEmpty()) {
            toBuilder.append(invoice.getBillToName()).append("\n");
        }
        if (invoice.getBillToLine1() != null && !invoice.getBillToLine1().isEmpty()) {
            toBuilder.append(invoice.getBillToLine1()).append("\n");
        }
        if (invoice.getBillToLine2() != null && !invoice.getBillToLine2().isEmpty()) {
            toBuilder.append(invoice.getBillToLine2()).append("\n");
        }
        // Combine zip and city for zipcity field
        if (invoice.getBillToZip() != null || invoice.getBillToCity() != null) {
            String zip = invoice.getBillToZip() != null ? invoice.getBillToZip() : "";
            String city = invoice.getBillToCity() != null ? invoice.getBillToCity() : "";
            String zipCity = (zip + " " + city).trim();
            if (!zipCity.isEmpty()) {
                toBuilder.append(zipCity).append("\n");
            }
        }
        if (invoice.getBillToCvr() != null && !invoice.getBillToCvr().isEmpty()) {
            toBuilder.append("CVR: ").append(invoice.getBillToCvr()).append("\n");
        }
        if (invoice.getBillToEan() != null && !invoice.getBillToEan().isEmpty()) {
            toBuilder.append("EAN: ").append(invoice.getBillToEan()).append("\n");
        }
        if (invoice.getBillToAttn() != null && !invoice.getBillToAttn().isEmpty()) {
            toBuilder.append("ATT: ").append(invoice.getBillToAttn()).append("\n");
        }
        this.to = toBuilder.toString();

        this.number = dk.trustworks.intranet.aggregates.invoice.utils.StringUtils.convertInvoiceNumberToString(invoice.getInvoicenumber());
        this.date   = invoice.getInvoicedate().format(java.time.format.DateTimeFormatter.ofPattern("dd. MMM yyyy"));
        this.due_date = (invoice.getDuedate() != null
                ? invoice.getDuedate()
                : invoice.getInvoicedate().plusMonths(1))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd. MMM yyyy"));

        // IMPORTANT: set API-level discounts according to the above switch
        this.discounts = discountsValue;

        // Notes: invoice doesn't have contractref/projectref/specificdescription fields in unified model
        // Leave notes empty for now - these may need to be derived from other sources
        this.notes = "";

        // Line items (base + synthetic already in the entity)
        invoice.getInvoiceitems().stream()
                .sorted(java.util.Comparator.comparingInt(dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem::getPosition)
                        .thenComparing(ii -> ii.getItemname() == null ? "" : ii.getItemname(), String::compareToIgnoreCase)
                        .thenComparing(dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem::getUuid))
                .forEach(ii -> items.add(new InvoiceItemDto(
                        ii.getItemname(), ii.getHours(), ii.getRate(), ii.getDescription())));

        this.terms = "Payment via bank transfer to the following account: Nykredit, reg.nr. "
                + invoice.getCompany().getRegnr() + ", account number " + invoice.getCompany().getAccount() + "\n "
                + (invoice.getCompany().getUuid().equals("44592d3b-2be5-4b29-bfaf-4fafc60b0fa3")
                ? "IBAN: DK1054700004058023, SWIFT: NYKBDKKK"
                : "IBAN: DK7954700003965795, SWIFT: NYKBDKKK");
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public InvoiceFieldsDTO getFields() {
        return fields;
    }

    public void setFields(InvoiceFieldsDTO fields) {
        this.fields = fields;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getPurchase_order() {
        return purchase_order;
    }

    public void setPurchase_order(String purchase_order) {
        this.purchase_order = purchase_order;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getPayment_terms() {
        return payment_terms;
    }

    public void setPayment_terms(String payment_terms) {
        this.payment_terms = payment_terms;
    }

    public String getDue_date() {
        return due_date;
    }

    public void setDue_date(String due_date) {
        this.due_date = due_date;
    }

    public List<InvoiceItemDto> getItems() {
        return items;
    }

    public void setItems(List<InvoiceItemDto> items) {
        this.items = items;
    }

    public double getDiscounts() {
        return discounts;
    }

    public void setDiscounts(double discounts) {
        this.discounts = discounts;
    }

    public int getTax() {
        return tax;
    }

    public void setTax(int tax) {
        this.tax = tax;
    }

    public int getShipping() {
        return shipping;
    }

    public void setShipping(int shipping) {
        this.shipping = shipping;
    }

    public double getAmount_paid() {
        return amount_paid;
    }

    public void setAmount_paid(double amount_paid) {
        this.amount_paid = amount_paid;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getTerms() {
        return terms;
    }

    public void setTerms(String terms) {
        this.terms = terms;
    }

    @Override
    public String toString() {
        return "InvoiceDTO{" +
                "header='" + header + '\'' +
                ", fields=" + fields.discounts +
                ", currency='" + currency + '\'' +
                ", logo='" + logo + '\'' +
                ", from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", number='" + number + '\'' +
                ", purchase_order='" + purchase_order + '\'' +
                ", date='" + date + '\'' +
                ", payment_terms='" + payment_terms + '\'' +
                ", due_date='" + due_date + '\'' +
                ", items=" + items.size() +
                ", discounts=" + discounts +
                ", tax=" + tax +
                ", shipping=" + shipping +
                ", amount_paid=" + amount_paid +
                ", notes='" + notes + '\'' +
                ", terms='" + terms + '\'' +
                '}';
    }
}
