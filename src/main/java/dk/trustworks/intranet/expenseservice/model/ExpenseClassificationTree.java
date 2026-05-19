package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "expense_classification_tree")
public class ExpenseClassificationTree extends PanacheEntityBase {
    @Id
    @Column(name = "tree_version")
    public String treeVersion;
    public boolean active;
    @Column(name = "created_at")
    public LocalDateTime createdAt;
    @Column(name = "created_by")
    public String createdBy;
}
