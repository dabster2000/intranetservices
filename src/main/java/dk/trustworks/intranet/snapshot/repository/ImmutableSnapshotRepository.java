package dk.trustworks.intranet.snapshot.repository;

import dk.trustworks.intranet.snapshot.model.ImmutableSnapshot;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing {@link ImmutableSnapshot} entities.
 * Provides comprehensive querying capabilities across entity types and versions.
 * All write operations invalidate the cache to ensure consistency.
 */
@ApplicationScoped
public class ImmutableSnapshotRepository implements PanacheRepository<ImmutableSnapshot> {

    /**
     * Find all snapshots for a given entity type and ID.
     * Returns all versions ordered by version descending (latest first).
     *
     * @param entityType the entity type
     * @param entityId   the entity ID
     * @return list of all snapshot versions
     */
    public List<ImmutableSnapshot> findByEntityTypeAndId(String entityType, String entityId) {
        return find("entityType = ?1 and entityId = ?2 order by snapshotVersion desc",
            entityType, entityId).list();
    }

    /**
     * Find latest snapshot version for an entity.
     * Cached for performance since this is the most common query.
     *
     * @param entityType the entity type
     * @param entityId   the entity ID
     * @return optional latest snapshot
     */
    @CacheResult(cacheName = "immutableSnapshots")
    public Optional<ImmutableSnapshot> findLatestByEntityTypeAndId(String entityType, String entityId) {
        return find("entityType = ?1 and entityId = ?2 order by snapshotVersion desc",
            entityType, entityId)
            .firstResultOptional();
    }

    /**
     * Find specific snapshot version.
     *
     * @param entityType      the entity type
     * @param entityId        the entity ID
     * @param snapshotVersion the version number
     * @return optional snapshot
     */
    @CacheResult(cacheName = "immutableSnapshots")
    public Optional<ImmutableSnapshot> findByEntityTypeAndIdAndVersion(
            String entityType, String entityId, Integer snapshotVersion) {
        return find("entityType = ?1 and entityId = ?2 and snapshotVersion = ?3",
            entityType, entityId, snapshotVersion)
            .firstResultOptional();
    }

    /**
     * Get next version number for an entity.
     * Used when creating new snapshots.
     *
     * @param entityType the entity type
     * @param entityId   the entity ID
     * @return next version number (1 if no snapshots exist)
     */
    public Integer getNextVersion(String entityType, String entityId) {
        Optional<ImmutableSnapshot> latest = findLatestByEntityTypeAndId(entityType, entityId);
        return latest.map(snapshot -> snapshot.getSnapshotVersion() + 1).orElse(1);
    }

    /**
     * Check if any snapshot exists for an entity (any version).
     *
     * @param entityType the entity type
     * @param entityId   the entity ID
     * @return true if at least one snapshot exists
     */
    public boolean existsByEntityTypeAndId(String entityType, String entityId) {
        return count("entityType = ?1 and entityId = ?2", entityType, entityId) > 0;
    }

    /**
     * Check if specific version exists.
     *
     * @param entityType      the entity type
     * @param entityId        the entity ID
     * @param snapshotVersion the version number
     * @return true if version exists
     */
    public boolean existsByEntityTypeAndIdAndVersion(
            String entityType, String entityId, Integer snapshotVersion) {
        return count("entityType = ?1 and entityId = ?2 and snapshotVersion = ?3",
            entityType, entityId, snapshotVersion) > 0;
    }

    /**
     * Find all snapshots of a specific entity type.
     * Ordered by locked_at descending (most recent first).
     *
     * @param entityType the entity type
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByEntityType(String entityType) {
        return find("entityType = ?1 order by lockedAt desc", entityType).list();
    }

    /**
     * Find all snapshots of a specific entity type with pagination.
     *
     * @param entityType the entity type
     * @param page       page number (0-based)
     * @param size       page size
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByEntityType(String entityType, int page, int size) {
        return find("entityType = ?1", Sort.descending("lockedAt"), entityType)
            .page(Page.of(page, size))
            .list();
    }

    /**
     * Find all snapshots ordered by creation time descending.
     *
     * @return list of all snapshots
     */
    public List<ImmutableSnapshot> findAllOrderByCreatedAtDesc() {
        return find("order by createdAt desc").list();
    }

    /**
     * Find all snapshots with pagination.
     *
     * @param page page number (0-based)
     * @param size page size
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findAll(int page, int size) {
        return findAll(Sort.descending("createdAt"))
            .page(Page.of(page, size))
            .list();
    }

    /**
     * Find snapshots created by a specific user.
     *
     * @param lockedBy username or email
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByLockedBy(String lockedBy) {
        return find("lockedBy = ?1 order by lockedAt desc", lockedBy).list();
    }

    /**
     * Find snapshots created after a specific date.
     *
     * @param date the date threshold
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findLockedAfter(LocalDateTime date) {
        return find("lockedAt > ?1 order by lockedAt desc", date).list();
    }

    /**
     * Find snapshots created between two dates.
     *
     * @param startDate start date (inclusive)
     * @param endDate   end date (inclusive)
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findLockedBetween(LocalDateTime startDate, LocalDateTime endDate) {
        return find("lockedAt >= ?1 and lockedAt <= ?2 order by lockedAt desc",
            startDate, endDate).list();
    }

    /**
     * Find snapshots by entity type within date range.
     *
     * @param entityType the entity type
     * @param startDate  start date (inclusive)
     * @param endDate    end date (inclusive)
     * @return list of snapshots
     */
    public List<ImmutableSnapshot> findByEntityTypeAndLockedBetween(
            String entityType, LocalDateTime startDate, LocalDateTime endDate) {
        return find("entityType = ?1 and lockedAt >= ?2 and lockedAt <= ?3 order by lockedAt desc",
            entityType, startDate, endDate).list();
    }

    /**
     * Count snapshots by entity type.
     *
     * @param entityType the entity type
     * @return count of snapshots
     */
    public long countByEntityType(String entityType) {
        return count("entityType", entityType);
    }

    /**
     * Save or update snapshot and evict cache.
     *
     * @param entity the entity to save
     * @return the saved entity
     */
    @Transactional
    @CacheInvalidate(cacheName = "immutableSnapshots")
    public ImmutableSnapshot save(ImmutableSnapshot entity) {
        persist(entity);
        return entity;
    }

    /**
     * Delete snapshot and evict cache.
     *
     * @param entity the entity to delete
     */
    @Transactional
    @CacheInvalidate(cacheName = "immutableSnapshots")
    public void remove(ImmutableSnapshot entity) {
        delete(entity);
    }

    /**
     * Delete specific snapshot version by natural key.
     *
     * @param entityType      the entity type
     * @param entityId        the entity ID
     * @param snapshotVersion the version number
     * @return true if deleted, false if not found
     */
    @Transactional
    @CacheInvalidate(cacheName = "immutableSnapshots")
    public boolean deleteByEntityTypeAndIdAndVersion(
            String entityType, String entityId, Integer snapshotVersion) {
        return delete("entityType = ?1 and entityId = ?2 and snapshotVersion = ?3",
            entityType, entityId, snapshotVersion) > 0;
    }

    /**
     * Delete all versions of an entity.
     *
     * @param entityType the entity type
     * @param entityId   the entity ID
     * @return number of snapshots deleted
     */
    @Transactional
    @CacheInvalidate(cacheName = "immutableSnapshots")
    public long deleteByEntityTypeAndId(String entityType, String entityId) {
        return delete("entityType = ?1 and entityId = ?2", entityType, entityId);
    }

    /**
     * Delete all snapshots of an entity type.
     * Use with extreme caution.
     *
     * @param entityType the entity type
     * @return number of snapshots deleted
     */
    @Transactional
    @CacheInvalidate(cacheName = "immutableSnapshots")
    public long deleteByEntityType(String entityType) {
        return delete("entityType", entityType);
    }

    /**
     * Delete all snapshots and clear entire cache.
     * Use with extreme caution - destroys entire audit trail.
     */
    @Transactional
    @CacheInvalidate(cacheName = "immutableSnapshots")
    public void removeAll() {
        deleteAll();
    }
}
