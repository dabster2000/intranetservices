package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "expense_receipt_analysis")
public class ExpenseReceiptAnalysis extends PanacheEntityBase {
    @Id
    @Column(name = "analysis_id")
    public String analysisId;
    public String useruuid;
    @Column(name = "tree_version")
    public String treeVersion;
    @Column(name = "created_at")
    public LocalDateTime createdAt;
    @Column(name = "receipt_facts_json", columnDefinition = "json")
    public String receiptFactsJson;
    @Column(name = "proposed_answers_json", columnDefinition = "json")
    public String proposedAnswersJson;
    @Column(name = "warnings_json", columnDefinition = "json")
    public String warningsJson;
    @Column(name = "raw_model_summary", columnDefinition = "TEXT")
    public String rawModelSummary;
}
