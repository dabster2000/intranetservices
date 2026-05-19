package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_classification_option")
public class ExpenseClassificationOption extends PanacheEntityBase {
    @Id
    @JsonIgnore
    public String uuid;
    @Column(name = "tree_version")
    public String treeVersion;
    @Column(name = "node_key")
    public String nodeKey;
    @Column(name = "answer_key")
    public String answerKey;
    public String label;
    @Column(name = "sort_order")
    public int sortOrder;
}
