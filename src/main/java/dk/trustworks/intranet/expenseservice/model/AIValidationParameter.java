package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_validation_parameter")
public class AIValidationParameter extends PanacheEntityBase {
    @Id @Column(name = "parameter_key")   public String parameterKey;
    @Column(name = "parameter_value")     public String parameterValue;
    @Column(name = "value_type")          public String valueType;
    public String description;
    @Column(name = "updated_at")          public LocalDateTime updatedAt;
    @Column(name = "updated_by")          public String updatedBy;
}
