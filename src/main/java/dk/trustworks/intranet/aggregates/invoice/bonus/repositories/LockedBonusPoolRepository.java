package dk.trustworks.intranet.aggregates.invoice.bonus.repositories;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.LockedBonusPoolData;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing {@link LockedBonusPoolData} entities.
 *
 * Provides access to locked bonus pool snapshots for fiscal years.
 * All write operations invalidate the cache to ensure consistency.
 */
@ApplicationScoped
public class LockedBonusPoolRepository implements PanacheRepository<LockedBonusPoolData> {

    /**
     * Find locked bonus data by fiscal year with caching.
     *
     * @param fiscalYear the fiscal year to search for
     * @return optional locked bonus data
     */
    @CacheResult(cacheName = "lockedBonusPool")
    public Optional<LockedBonusPoolData> findByFiscalYear(Integer fiscalYear) {
        return find("fiscalYear", fiscalYear).firstResultOptional();
    }

    /**
     * Check if locked data exists for a fiscal year.
     *
     * @param fiscalYear the fiscal year to check
     * @return true if locked data exists
     */
    public boolean existsByFiscalYear(Integer fiscalYear) {
        return count("fiscalYear", fiscalYear) > 0;
    }

    /**
     * Find all locked bonus data ordered by fiscal year descending.
     *
     * @return list of all locked bonus data
     */
    public List<LockedBonusPoolData> findAllOrderByFiscalYearDesc() {
        return find("order by fiscalYear desc").list();
    }

    /**
     * Find locked bonus data by user who locked it.
     *
     * @param lockedBy the username to search for
     * @return list of locked bonus data
     */
    public List<LockedBonusPoolData> findByLockedBy(String lockedBy) {
        return find("lockedBy = ?1 order by lockedAt desc", lockedBy).list();
    }

    /**
     * Find locked bonus data created after a specific date.
     *
     * @param date the date threshold
     * @return list of locked bonus data
     */
    public List<LockedBonusPoolData> findLockedAfter(LocalDateTime date) {
        return find("lockedAt > ?1 order by lockedAt desc", date).list();
    }

    /**
     * Save or update locked bonus data and evict cache.
     *
     * @param entity the entity to save
     * @return the saved entity
     */
    @Transactional
    @CacheInvalidate(cacheName = "lockedBonusPool")
    public LockedBonusPoolData save(LockedBonusPoolData entity) {
        persist(entity);
        return entity;
    }

    /**
     * Delete locked bonus data and evict cache.
     *
     * @param entity the entity to delete
     */
    @Transactional
    @CacheInvalidate(cacheName = "lockedBonusPool")
    public void remove(LockedBonusPoolData entity) {
        delete(entity);
    }

    /**
     * Delete by fiscal year and evict cache.
     *
     * @param fiscalYear the fiscal year to delete
     */
    @Transactional
    @CacheInvalidate(cacheName = "lockedBonusPool")
    public boolean deleteByFiscalYear(Integer fiscalYear) {
        return delete("fiscalYear", fiscalYear) > 0;
    }

    /**
     * Delete all and clear entire cache.
     */
    @Transactional
    @CacheInvalidate(cacheName = "lockedBonusPool")
    public void removeAll() {
        deleteAll();
    }
}
