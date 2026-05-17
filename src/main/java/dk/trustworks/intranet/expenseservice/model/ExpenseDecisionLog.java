package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "expense_decision_log")
public class ExpenseDecisionLog extends PanacheEntityBase {
    @Id public String uuid;
    @Column(name = "expense_uuid", nullable = false) public String expenseUuid;
    @Column(name = "occurred_at", nullable = false)  public LocalDateTime occurredAt;
    @Column(name = "actor_uuid") public String actorUuid;
    @Column(name = "actor_role", nullable = false) public String actorRole;
    @Column(name = "action",     nullable = false) public String action;
    @Column(name = "from_status")       public String fromStatus;
    @Column(name = "to_status")         public String toStatus;
    @Column(name = "from_review_state") public String fromReviewState;
    @Column(name = "to_review_state")   public String toReviewState;
    @Column(name = "ai_rule_id")        public String aiRuleId;
    @Column(name = "reason_text", columnDefinition = "text") public String reasonText;
}
