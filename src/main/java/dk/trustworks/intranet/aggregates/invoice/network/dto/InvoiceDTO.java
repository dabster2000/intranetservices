package dk.trustworks.intranet.aggregates.invoice.network.dto;

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
    List<InvoiceItemDTO> items = new ArrayList<>();
    double discounts; //Subtotal discounts - numbers only	0
    int tax; //Tax - numbers only	0
    int shipping; //Shipping - numbers only	0
    double amount_paid; //Amount paid - numbers only	0
    String notes; //Notes - any extra information not included elsewhere	null
    String terms; //Terms and conditions - all the details	null

    private InvoiceDTO(String from, String to, String date, String dueDate, String currency) {
        logo = "";
        due_date = dueDate;
        this.currency = currency;
        tax = "DKK".equals(currency)?25:0;
        this.from = from;
        this.to = to;
        this.date = date;
    }

    private InvoiceDTO(String header, InvoiceFieldsDTO invoiceFieldsDTO, String from, String to, String number, String date, String dueDate, double discounts, String currency, String notes) {
        this(from, to, date, dueDate, currency);
        this.header = header;
        this.fields = invoiceFieldsDTO;
        this.number = number;
        this.discounts = discounts;
        this.notes = notes;
    }

    public InvoiceDTO(Invoice invoice) {
        this(invoice.getType().name(),
                new InvoiceFieldsDTO((invoice.getDiscount()>0.0?"%":"false"), false),
                invoice.getCompany().getName() + "\n" +
                        invoice.getCompany().getAddress() + "\n" +
                        invoice.getCompany().getZipcode() + " " + invoice.getCompany().getCity() + ", "+invoice.getCompany().getCountry()+"\n" +
                        "CVR: "+invoice.getCompany().getCvr()+"\n\n"+
                        "Phone: "+invoice.getCompany().getPhone()+"\n"+
                        "Email: "+invoice.getCompany().getEmail(),
                invoice.getClientname()+"\n"+
                        invoice.getClientaddresse()+"\n"+
                        invoice.getZipcity()+"\n"+
                        ((invoice.cvr!=null && !invoice.cvr.equals(""))?"CVR: "+invoice.getCvr()+"\n":"")+
                        ((invoice.ean!=null && !invoice.ean.equals(""))?"EAN: "+invoice.getEan()+"\n":"")+
                        ((invoice.attention!=null && !invoice.attention.equals(""))?"ATT: "+invoice.getAttention()+"\n":""),
                StringUtils.convertInvoiceNumberToString(invoice.invoicenumber),
                invoice.getInvoicedate().format(DateTimeFormatter.ofPattern("dd. MMM yyyy")),
                invoice.getDuedate()!=null?
                        invoice.getDuedate().format(DateTimeFormatter.ofPattern("dd. MMM yyyy")):
                        invoice.getInvoicedate().plusMonths(1).format(DateTimeFormatter.ofPattern("dd. MMM yyyy")),
                invoice.getDiscount(), invoice.getCurrency(),
                ((invoice.contractref!=null && !invoice.contractref.isEmpty())?invoice.getContractref()+"\n":"")+
                        ((invoice.projectref!=null && !invoice.projectref.isEmpty())?invoice.getProjectref()+"\n":"")+
                        ((invoice.specificdescription!=null && !invoice.specificdescription.isEmpty())?invoice.getSpecificdescription():""));
        for (InvoiceItem invoiceItem : invoice.getInvoiceitems()) {
            items.add(new InvoiceItemDTO(invoiceItem.itemname, invoiceItem.hours, invoiceItem.rate, invoiceItem.description));
        }
        Contract contract = Contract.findById(invoice.contractuuid);
        if(contract != null && contract.getContractType().equals(ContractType.SKI0217_2021)) { // null happens when invoice is an internal service invoice
            ContractTypeItem contractTypeItem = contract.getContractTypeItems().stream().findAny().get();
            double sumNoTax = invoice.getInvoiceitems().stream().mapToDouble(value -> value.hours * value.rate).sum();
            double keyDiscount = (sumNoTax * (NumberUtils.parseDouble(contract.getContractTypeItems().stream().findAny().get().getValue()) / 100.0));
            items.add(new InvoiceItemDTO(contractTypeItem.getValue() + "% " + contractTypeItem.getKey(), 1, -keyDiscount, ""));
            double adminDiscount = ((sumNoTax - keyDiscount) * 0.02);
            items.add(new InvoiceItemDTO("2% SKI administrationsgebyr", 1, -adminDiscount, ""));
            items.add(new InvoiceItemDTO("Faktureringsgebyr", 1, -2000, ""));
        }
        terms = "Payment via bank transfer to the following account: Nykredit, reg.nr. "+invoice.getCompany().getRegnr()+", account number "+invoice.getCompany().getAccount()+"\n ";
        if(invoice.getCompany().getUuid().equals("44592d3b-2be5-4b29-bfaf-4fafc60b0fa3")) {
            terms += "IBAN: DK1054700004058023, SWIFT: NYKBDKKK";
        } else {
            terms += "IBAN: DK7954700003965795, SWIFT: NYKBDKKK";
        }
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

    public List<InvoiceItemDTO> getItems() {
        return items;
    }

    public void setItems(List<InvoiceItemDTO> items) {
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
                ", items=" + items +
                ", discounts=" + discounts +
                ", tax=" + tax +
                ", shipping=" + shipping +
                ", amount_paid=" + amount_paid +
                ", notes='" + notes + '\'' +
                ", terms='" + terms + '\'' +
                '}';
    }
}
