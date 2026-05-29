package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_rule_catalog")
public class AIRuleCatalog extends PanacheEntityBase {
    @Id public String uuid;
    @Column(name = "rule_id")         public String ruleId;
    @Column(name = "display_name")    public String displayName;
    public String description;
    public String severity;
    @Column(name = "resolution_type") public String resolutionType;
    public int    priority;
    public boolean active;
    @Column(name = "outcome_mode")         public String outcomeMode;
    @Column(name = "confidence_threshold") public Double confidenceThreshold;
    @Column(name = "updated_at")      public LocalDateTime updatedAt;
    @Column(name = "updated_by")      public String updatedBy;

    public static final String OUTCOME_MODE_BLOCK     = "BLOCK";
    public static final String OUTCOME_MODE_SOFT_FLAG = "SOFT_FLAG";
    public static final String OUTCOME_MODE_OFF       = "OFF";
}
