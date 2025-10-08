package dk.trustworks.intranet.model;

import java.time.LocalDateTime;

/**
 * Interface for entities that support automatic audit tracking.
 * <p>
 * Entities implementing this interface will have their audit fields automatically populated
 * by {@link dk.trustworks.intranet.security.AuditEntityListener} during JPA lifecycle events.
 * </p>
 * <p>
 * Audit fields track:
 * <ul>
 *   <li>createdAt/updatedAt: Timestamps for creation and last modification</li>
 *   <li>createdBy/modifiedBy: User identifiers (typically UUIDs from X-Requested-By header)</li>
 * </ul>
 * </p>
 * <p>
 * To make an entity auditable:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Add the four audit fields with appropriate JPA annotations</li>
 *   <li>Add @EntityListeners(AuditEntityListener.class) to the entity class</li>
 *   <li>Mark audit fields as @JsonProperty(access = JsonProperty.Access.READ_ONLY)</li>
 * </ol>
 * </p>
 *
 * @see dk.trustworks.intranet.security.AuditEntityListener
 */
public interface Auditable {

    /**
     * @return Timestamp when the entity was created
     */
    LocalDateTime getCreatedAt();

    /**
     * Sets the creation timestamp.
     * This is typically set automatically by the audit listener.
     *
     * @param createdAt the creation timestamp
     */
    void setCreatedAt(LocalDateTime createdAt);

    /**
     * @return Timestamp when the entity was last updated
     */
    LocalDateTime getUpdatedAt();

    /**
     * Sets the last update timestamp.
     * This is automatically updated by the audit listener on each modification.
     *
     * @param updatedAt the last update timestamp
     */
    void setUpdatedAt(LocalDateTime updatedAt);

    /**
     * @return User identifier (typically UUID) who created the entity
     */
    String getCreatedBy();

    /**
     * Sets the user identifier who created the entity.
     * This is typically set automatically by the audit listener from the X-Requested-By header.
     *
     * @param createdBy user identifier (typically UUID)
     */
    void setCreatedBy(String createdBy);

    /**
     * @return User identifier (typically UUID) who last modified the entity
     */
    String getModifiedBy();

    /**
     * Sets the user identifier who last modified the entity.
     * This is automatically updated by the audit listener on each modification.
     *
     * @param modifiedBy user identifier (typically UUID)
     */
    void setModifiedBy(String modifiedBy);
}
