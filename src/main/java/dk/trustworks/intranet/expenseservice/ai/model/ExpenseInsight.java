package dk.trustworks.intranet.expenseservice.ai.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "expense_insights")
public class ExpenseInsight extends PanacheEntityBase {

    @Id
    @Column(name = "expense_uuid", length = 36)
    private String expenseUuid; // FK to expenses.uuid

    @Column(name = "useruuid", length = 36)
    private String useruuid;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "merchant_category")
    private String merchantCategory; // normalized

    private Double confidence;

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    private String currency;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Column(name = "subtotal_amount")
    private Double subtotalAmount;

    @Column(name = "vat_amount")
    private Double vatAmount;

    @Column(name = "payment_method")
    private String paymentMethod;

    private String country;
    private String city;

    // Beverage rollups
    @Column(name = "drinks_total")
    private Double drinksTotal;
    @Column(name = "alcohol_total")
    private Double alcoholTotal;
    @Column(name = "coffee_total")
    private Double coffeeTotal;
    @Column(name = "juice_total")
    private Double juiceTotal;
    @Column(name = "water_total")
    private Double waterTotal;
    @Column(name = "soft_drink_total")
    private Double softDrinkTotal;

    @Lob
    @Column(name = "raw_json")
    private String rawJson;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "insight", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseLineItemEntity> lineItems;

    @OneToMany(mappedBy = "insight", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseTagEntity> tags;
}
