package dk.trustworks.intranet.apis.openai;

import lombok.Data;

import java.util.List;

@Data
public class ExpenseMetadata {
    private String merchantName;
    private String merchantCategory; // normalized primary category, e.g., FOOD, DRINKS, INTERNET_HOME
    private Double confidence; // 0..1
    private String expenseDate; // ISO date yyyy-MM-dd
    private String currency;
    private Double totalAmount;
    private Double subtotalAmount;
    private Double vatAmount;
    private String paymentMethod;
    private String country;
    private String city;

    // Beverage breakdown (optional; any can be null)
    private Double drinksTotal; // aggregate for all drinks
    private Double alcoholTotal;
    private Double coffeeTotal;
    private Double juiceTotal;
    private Double waterTotal;
    private Double softDrinkTotal; // soda etc.

    private List<String> tags; // e.g., ["FOOD","LUNCH","CAFE","INTERNET_HOME","DRINKS"]
    private List<LineItem> lineItems;

    @Data
    public static class LineItem {
        private String description;
        private Double quantity;
        private Double unitPrice;
        private Double total;
        private String itemCategory; // e.g., JUICE, ALCOHOL, COFFEE, FOOD, OTHER
    }
}
