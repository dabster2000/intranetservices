package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "expense_classification")
public class ExpenseClassification extends PanacheEntityBase {
    @Id
    public String uuid;
    @Column(name = "expense_uuid")
    public String expenseUuid;
    public String useruuid;
    @Column(name = "analysis_id")
    public String analysisId;
    @Column(name = "tree_version")
    public String treeVersion;
    @Column(name = "ai_used")
    public boolean aiUsed;
    @Column(name = "ai_ignored")
    public boolean aiIgnored;
    @Column(name = "decision_result_key")
    public String decisionResultKey;
    @Column(name = "account_key")
    public String accountKey;
    @Column(name = "account_number")
    public String accountNumber;
    @Column(name = "account_name")
    public String accountName;
    @Column(name = "requires_finance_review")
    public boolean requiresFinanceReview;
    @Column(name = "answers_json", columnDefinition = "json")
    public String answersJson;
    @Column(name = "ignored_ai_answers_json", columnDefinition = "json")
    public String ignoredAiAnswersJson;
    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
