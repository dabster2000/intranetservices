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
    @Column(name = "updated_at")      public LocalDateTime updatedAt;
    @Column(name = "updated_by")      public String updatedBy;
}
