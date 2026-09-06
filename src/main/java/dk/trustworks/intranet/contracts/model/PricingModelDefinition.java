package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A pricing model for consultant lines (V570, JK Team 2.0 WP5): reference data, not an
 * enum, so the four junior models can be extended or retired without a deploy. Referenced
 * by {@code contract_consultants.pricing_model_code}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pricing_model_definitions")
public class PricingModelDefinition extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static List<PricingModelDefinition> findActive() {
        return list("active = true order by id");
    }

    /** The codes a consultant line may carry right now. */
    public static Set<String> activeCodes() {
        return findActive().stream().map(PricingModelDefinition::getCode).collect(Collectors.toSet());
    }
}
