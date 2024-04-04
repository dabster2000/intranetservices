package dk.trustworks.intranet.aggregates.invoice.network.dto;

public class InvoiceFieldsDTO {

    String tax;
    String discounts;
    boolean shipping;

    public InvoiceFieldsDTO() {
        this.tax = "%";
        discounts = "false";
        shipping = false;
    }

    public InvoiceFieldsDTO(String discounts, boolean shipping) {
        this.tax = "%";
        this.discounts = discounts;
        this.shipping = shipping;
    }

    public String getTax() {
        return tax;
    }

    public void setTax(String tax) {
        this.tax = tax;
    }

    public String getDiscounts() {
        return discounts;
    }

    public void setDiscounts(String discounts) {
        this.discounts = discounts;
    }

    public boolean isShipping() {
        return shipping;
    }

    public void setShipping(boolean shipping) {
        this.shipping = shipping;
    }
}
