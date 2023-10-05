package dk.trustworks.intranet.invoiceservice.network.dto;

import dk.trustworks.intranet.invoiceservice.model.Invoice;
import dk.trustworks.intranet.invoiceservice.model.InvoiceItem;
import dk.trustworks.intranet.invoiceservice.utils.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class InvoiceDTO {

    String header;
    /*
    String to_title;
    String invoice_number_title;
    String date_title;
    String payment_terms_title;	// Payment Terms
    String due_date_title; //Due Date
    String purchase_order_title; //Purchase Order
    String quantity_header; //Quantity
    String item_header; //Item
    String unit_cost_header; //Rate
    String amount_header; //Amount
    String subtotal_title; //Subtotal
    String discounts_title; //Discounts
    String tax_title; //Tax
    String shipping_title; //Shipping
    String total_title; //Total
    String amount_paid_title; //Amount Paid
    String balance_title; //Balance
    String terms_title; //Terms
    String notes_title; //Notes
    */
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

    private InvoiceDTO(String from, String to, String date, String dueDate) {
        tax = 25;
        logo = "";
        due_date = dueDate;
        currency = "DKK";
        this.from = from;
        this.to = to;
        this.date = date;
    }

    private InvoiceDTO(String header, InvoiceFieldsDTO invoiceFieldsDTO, String from, String to, String number, String date, String dueDate, double discounts, String notes) {
        this(from, to, date, dueDate);
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
                invoice.getInvoicedate().plusMonths(1).format(DateTimeFormatter.ofPattern("dd. MMM yyyy")),
                invoice.getDiscount(),
                ((invoice.contractref!=null && !invoice.contractref.equals(""))?invoice.getContractref()+"\n":"")+
                        ((invoice.projectref!=null && !invoice.projectref.equals(""))?invoice.getProjectref()+"\n":"")+
                        ((invoice.specificdescription!=null && !invoice.specificdescription.equals(""))?invoice.getSpecificdescription():""));
        for (InvoiceItem invoiceItem : invoice.getInvoiceitems()) {
            items.add(new InvoiceItemDTO(invoiceItem.itemname, invoiceItem.hours, invoiceItem.rate, invoiceItem.description));
        }
        terms = "Payment via bank transfer to the following account: Nykredit, reg.nr. "+invoice.getCompany().getRegnr()+", account number "+invoice.getCompany().getAccount()+"\nPayment due in 1 month";
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
