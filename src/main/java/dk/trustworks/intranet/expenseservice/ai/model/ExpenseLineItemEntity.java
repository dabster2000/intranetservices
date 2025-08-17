package dk.trustworks.intranet.expenseservice.ai.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "expense_insight_line_items")
public class ExpenseLineItemEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_uuid", nullable = false)
    private ExpenseInsight insight;

    @Column(name = "description")
    private String description;

    @Column(name = "quantity")
    private Double quantity;

    @Column(name = "unit_price")
    private Double unitPrice;

    @Column(name = "total")
    private Double total;

    @Column(name = "item_category")
    private String itemCategory; // JUICE, ALCOHOL, COFFEE, etc.
}
