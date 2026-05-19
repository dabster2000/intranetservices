package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_classification_result")
public class ExpenseClassificationResult extends PanacheEntityBase {
    @Id
    @JsonIgnore
    public String uuid;
    @Column(name = "tree_version")
    public String treeVersion;
    @Column(name = "result_key")
    public String resultKey;
    @Column(name = "employee_label")
    public String employeeLabel;
    @Column(name = "employee_summary")
    public String employeeSummary;
    @Column(name = "account_key")
    public String accountKey;
    @Column(name = "tax_treatment")
    public String taxTreatment;
    @Column(name = "requires_finance_review")
    public boolean requiresFinanceReview;
    @Column(name = "conditions_json", columnDefinition = "json")
    public String conditionsJson;
    @Column(name = "sort_order")
    public int sortOrder;
}
