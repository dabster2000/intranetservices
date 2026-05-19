package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_classification_node")
public class ExpenseClassificationNode extends PanacheEntityBase {
    @Id
    @JsonIgnore
    public String uuid;
    @Column(name = "tree_version")
    public String treeVersion;
    @Column(name = "node_key")
    public String nodeKey;
    public String prompt;
    @Column(name = "answer_source_policy")
    public String answerSourcePolicy;
    public boolean required;
    @Column(name = "sort_order")
    public int sortOrder;
    @Column(name = "visible_when_json", columnDefinition = "json")
    public String visibleWhenJson;
}
