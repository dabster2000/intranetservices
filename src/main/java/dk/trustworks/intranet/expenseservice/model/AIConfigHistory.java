package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_config_history")
public class AIConfigHistory extends PanacheEntityBase {
    @Id public String uuid;
    @Column(name = "entity_kind")   public String entityKind;
    @Column(name = "entity_key")    public String entityKey;
    @Column(name = "change_action") public String changeAction;
    @Column(name = "snapshot_json", columnDefinition = "json") public String snapshotJson;
    @Column(name = "changed_at")    public LocalDateTime changedAt;
    @Column(name = "changed_by")    public String changedBy;
}
