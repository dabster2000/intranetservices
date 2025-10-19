package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for dynamic contract type definitions.
 * Allows contract types to be created and managed via REST API instead of hardcoded enums.
 *
 * Example usage:
 * <pre>
 * ContractTypeDefinition contractType = new ContractTypeDefinition();
 * contractType.setCode("SKI0217_2026");
 * contractType.setName("SKI Framework Agreement 2026");
 * contractType.setDescription("Updated framework with 5% admin fee");
 * contractType.persist();
 * </pre>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "contract_type_definitions")
public class ContractTypeDefinition extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    /**
     * Unique code for the contract type (e.g., "SKI0217_2026").
     * Must be alphanumeric with underscores, 3-50 characters.
     */
    @Column(unique = true, nullable = false, length = 50)
    @NotBlank(message = "Contract type code is required")
    @Size(min = 3, max = 50, message = "Code must be between 3 and 50 characters")
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "Code must contain only uppercase letters, numbers, and underscores")
    private String code;

    /**
     * Display name for the contract type.
     */
    @Column(nullable = false)
    @NotBlank(message = "Contract type name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;

    /**
     * Detailed description of the contract type.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Soft delete flag. Inactive types are hidden but preserved for historical data.
     */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- Panache finder methods ---

    /**
     * Find a contract type by its code.
     *
     * @param code The contract type code
     * @return The contract type definition, or null if not found
     */
    public static ContractTypeDefinition findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Find all active contract types.
     *
     * @return List of active contract types
     */
    public static List<ContractTypeDefinition> findAllActive() {
        return find("active", true).list();
    }

    /**
     * Find all contract types (including inactive).
     *
     * @return List of all contract types
     */
    public static List<ContractTypeDefinition> findAllIncludingInactive() {
        return listAll();
    }

    /**
     * Check if a contract type code already exists.
     *
     * @param code The code to check
     * @return true if the code exists, false otherwise
     */
    public static boolean existsByCode(String code) {
        return count("code", code) > 0;
    }

    /**
     * Soft delete by setting active to false.
     */
    public void softDelete() {
        this.active = false;
        this.persist();
    }

    /**
     * Reactivate a soft-deleted contract type.
     */
    public void activate() {
        this.active = true;
        this.persist();
    }
}
