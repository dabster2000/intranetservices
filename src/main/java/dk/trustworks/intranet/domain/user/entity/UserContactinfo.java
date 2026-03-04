package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "user_contactinfo")
@EntityListeners(AuditEntityListener.class)
@NoArgsConstructor
public class UserContactinfo extends PanacheEntityBase implements Auditable {

    @Id
    private String uuid;

    @Column(name = "street")
    private String streetname;

    private String postalcode;

    private String city;

    private String phone;

    @JsonIgnore
    private String useruuid;

    @Column(name = "active_date", nullable = false)
    @NotNull
    private LocalDate activeDate;

    @Column(name = "slackusername", length = 100)
    private String slackusername;

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

    public static List<UserContactinfo> findAllByUseruuid(String useruuid) {
        return find("useruuid", useruuid).list();
    }

    public static UserContactinfo findByUseruuid(String useruuid) {
        return find("useruuid", useruuid).firstResult();
    }

    /**
     * Find the current contact info for a user (active_date <= today, most recent first).
     */
    public static UserContactinfo findCurrentByUseruuid(String useruuid) {
        return find("useruuid = ?1 and activeDate <= ?2", Sort.descending("activeDate"), useruuid, LocalDate.now())
                .firstResult();
    }

}
