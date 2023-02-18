package dk.trustworks.intranet.invoiceservice.network.dto;

public class InvoiceItemDTO {

    String name;
    double quantity;
    double unit_cost;
    String description;

    public InvoiceItemDTO(String name, double quantity, double unit_cost, String description) {
        this.name = name;
        this.quantity = quantity;
        this.unit_cost = unit_cost;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public double getUnit_cost() {
        return unit_cost;
    }

    public void setUnit_cost(double unit_cost) {
        this.unit_cost = unit_cost;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
