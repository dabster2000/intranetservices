package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@Entity
@Table(name = "user_personal_details")
@EntityListeners(AuditEntityListener.class)
public class UserPersonalDetails extends PanacheEntityBase implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "useruuid", nullable = false)
    private String useruuid;

    @NotNull
    @Column(name = "active_date", nullable = false)
    private LocalDate activeDate;

    @Column(name = "pension")
    private boolean pension;

    @Column(name = "healthcare")
    private boolean healthcare;

    @Column(name = "pensiondetails", columnDefinition = "TEXT")
    private String pensiondetails;

    @Column(name = "photoconsent")
    private boolean photoconsent;

    @Column(name = "defects", columnDefinition = "TEXT")
    private String defects;

    @Column(name = "other", columnDefinition = "TEXT")
    private String other;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public UserPersonalDetails(String useruuid, LocalDate activeDate) {
        this.uuid = UUID.randomUUID().toString();
        this.useruuid = useruuid;
        this.activeDate = activeDate;
    }

    public static List<UserPersonalDetails> findByUseruuid(String useruuid) {
        return find("useruuid", useruuid).list();
    }

    public static UserPersonalDetails findActiveByUseruuid(String useruuid, LocalDate asOf) {
        return UserPersonalDetails.<UserPersonalDetails>list("useruuid = ?1 AND activeDate <= ?2 ORDER BY activeDate DESC", useruuid, asOf)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
