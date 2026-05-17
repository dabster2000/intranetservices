package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_prompt_template")
public class AIPromptTemplate extends PanacheEntityBase {
    @Id @Column(name = "template_key")    public String templateKey;
    @Lob public String body;
    @Column(name = "current_version")     public int currentVersion;
    @Column(name = "updated_at")          public LocalDateTime updatedAt;
    @Column(name = "updated_by")          public String updatedBy;
}
