package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper=false)
@Entity
@Table(name = "contract_type_items")
public class ContractTypeItem extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;
    private String contractuuid;
    @Column(name = "name")
    private String key;
    private String value;

    /**
     * Normalizes the value field before persist/update:
     * - Converts empty strings to null
     * - Validates that non-null values are valid numbers (used in CAST operations by BI views)
     */
    @PrePersist
    @PreUpdate
    void normalizeValue() {
        if (value != null && value.isBlank()) {
            value = null;
        }
        if (value != null) {
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "ContractTypeItem value must be a valid number, got: '" + value + "'");
            }
        }
    }
}
