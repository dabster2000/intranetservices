package dk.trustworks.intranet.documentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One per-company fact (V544): a named value the {@code COMPANY}
 * placeholder {@link dk.trustworks.intranet.documentservice.model.enums.DataSource}
 * resolves into documents and {@code ${...}} default-signer fields.
 * <p>
 * Every observed inter-company difference in the signed contract corpus
 * is a fact, not wording (template-clauses spec §4.9): legal name and its
 * stored Danish genitive, CVR, address, pension provider and
 * percentages, insurance, lunch price, counter-signatory. Facts live
 * once per company with an audit trail; documents only reference them —
 * a missing fact fails closed at prepare time, never a silently blank
 * document (the poi-tl discard trap).
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "company_facts")
@EntityListeners(AuditEntityListener.class)
public class CompanyFactEntity extends PanacheEntityBase implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "company_uuid", nullable = false, length = 36)
    @NotBlank(message = "Company is required")
    private String companyUuid;

    @Column(name = "fact_key", nullable = false, length = 50)
    @NotBlank(message = "Fact key is required")
    private String factKey;

    @Column(name = "fact_value", nullable = false, length = 500)
    @NotBlank(message = "Fact value is required")
    private String factValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 36, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by", length = 36)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }

    // --- Panache finder methods ---

    public static List<CompanyFactEntity> findByCompany(String companyUuid) {
        return list("companyUuid = ?1 ORDER BY factKey", companyUuid);
    }

    public static Optional<CompanyFactEntity> findByCompanyAndKey(String companyUuid, String factKey) {
        return find("companyUuid = ?1 AND factKey = ?2", companyUuid, factKey).firstResultOptional();
    }

    /** All of one company's facts as {@code factKey → factValue}. */
    public static Map<String, String> factMap(String companyUuid) {
        return findByCompany(companyUuid).stream()
                .collect(Collectors.toMap(CompanyFactEntity::getFactKey, CompanyFactEntity::getFactValue));
    }
}
