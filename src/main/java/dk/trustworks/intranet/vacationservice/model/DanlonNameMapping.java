package dk.trustworks.intranet.vacationservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Persisted Danløn-name → user mapping. Written when an admin resolves an
 * unmatched import row, so every later upload auto-matches the same name.
 */
@Data
@Entity
@Table(name = "danlon_name_mappings")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class DanlonNameMapping extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "normalized_name")
    private String normalizedName;

    private String useruuid;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    public static Optional<DanlonNameMapping> findByNormalizedName(String normalizedName) {
        return find("normalizedName", normalizedName).firstResultOptional();
    }
}
