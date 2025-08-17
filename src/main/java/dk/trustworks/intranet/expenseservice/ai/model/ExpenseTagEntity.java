package dk.trustworks.intranet.expenseservice.ai.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "expense_insight_tags", uniqueConstraints = @UniqueConstraint(columnNames = {"expense_uuid", "tag"}))
public class ExpenseTagEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_uuid", nullable = false)
    private ExpenseInsight insight;

    @Column(name = "tag", length = 64, nullable = false)
    private String tag;

    @Column(name = "confidence")
    private Double confidence; // optional
}
