package dk.trustworks.intranet.security;

import dk.trustworks.intranet.model.Auditable;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * JPA Entity Listener that automatically populates audit fields for entities implementing {@link Auditable}.
 * <p>
 * This listener integrates with the application's security context to capture the current user identifier
 * (typically a UUID from the X-Requested-By header) and set it along with timestamps on entity creation
 * and modification.
 * </p>
 * <p>
 * Usage: Add {@code @EntityListeners(AuditEntityListener.class)} to any entity implementing {@link Auditable}.
 * </p>
 * <p>
 * User identification priority (via {@link HeaderInterceptor}):
 * <ol>
 *   <li>X-Requested-By header (contains user UUID) - primary method</li>
 *   <li>JWT preferred_username claim - fallback</li>
 *   <li>Query parameter 'username' - fallback</li>
 *   <li>"anonymous" - default if none available</li>
 * </ol>
 * If no user can be identified, defaults to "system".
 * </p>
 *
 * @see Auditable
 * @see RequestHeaderHolder
 * @see HeaderInterceptor
 */
@JBossLog
public class AuditEntityListener {

    /**
     * Called before entity is persisted to the database.
     * Sets all audit fields: createdAt, createdBy, updatedAt, and modifiedBy.
     *
     * @param entity the entity being persisted (must implement Auditable)
     */
    @PrePersist
    public void prePersist(Object entity) {
        setAuditFields(entity, true);
    }

    /**
     * Called before entity is updated in the database.
     * Updates only updatedAt and modifiedBy fields, leaving creation fields unchanged.
     *
     * @param entity the entity being updated (must implement Auditable)
     */
    @PreUpdate
    public void preUpdate(Object entity) {
        setAuditFields(entity, false);
    }

    /**
     * Sets audit fields on the entity based on current request context.
     *
     * @param entity the entity to audit
     * @param isNew  true if this is a new entity (persist), false if updating
     */
    private void setAuditFields(Object entity, boolean isNew) {
        if (!(entity instanceof Auditable)) {
            return;
        }

        try {
            // Get current user identifier from request context
            // Note: RequestHeaderHolder.username is misleadingly named but contains the user identifier
            // from X-Requested-By header (typically a UUID)
            RequestHeaderHolder holder = CDI.current().select(RequestHeaderHolder.class).get();
            String userIdentifier = holder.getUsername();

            // Fallback to "system" if no user identifier available
            if (userIdentifier == null || userIdentifier.isEmpty()) {
                userIdentifier = "system";
                log.debug("No user identifier found in request context, using 'system'");
            }

            LocalDateTime now = LocalDateTime.now();
            Auditable auditable = (Auditable) entity;

            // On creation, set all audit fields
            if (isNew) {
                auditable.setCreatedAt(now);
                auditable.setCreatedBy(userIdentifier);
                log.debugf("Setting creation audit fields - user: %s, timestamp: %s", userIdentifier, now);
            }

            // Always update modification fields
            auditable.setUpdatedAt(now);
            auditable.setModifiedBy(userIdentifier);
            log.debugf("Setting modification audit fields - user: %s, timestamp: %s", userIdentifier, now);

        } catch (Exception e) {
            // Log error but don't fail the transaction
            // This ensures data operations succeed even if audit tracking fails
            log.error("Failed to set audit fields on entity: " + entity.getClass().getSimpleName(), e);
        }
    }
}
