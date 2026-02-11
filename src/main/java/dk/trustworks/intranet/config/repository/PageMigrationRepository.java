package dk.trustworks.intranet.config.repository;

import dk.trustworks.intranet.config.model.PageMigration;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing {@link PageMigration} entities.
 *
 * Provides methods to query page migration status for React/Vaadin coexistence.
 * Results are cached to minimize database queries - both frontends will
 * fetch this data frequently for menu rendering.
 */
@ApplicationScoped
public class PageMigrationRepository implements PanacheRepository<PageMigration> {

    private static final String CACHE_NAME = "migration-registry";

    /**
     * Find all page migrations ordered by display order.
     * This is the primary method used by both frontends to build navigation.
     *
     * @return list of all page migrations ordered for display
     */
    @CacheResult(cacheName = CACHE_NAME)
    public List<PageMigration> findAllOrdered() {
        return find("ORDER BY displayOrder, pageKey").list();
    }

    /**
     * Find a specific page by its key.
     *
     * @param pageKey the unique page identifier (e.g., 'dashboard', 'timesheet')
     * @return optional page migration
     */
    public Optional<PageMigration> findByPageKey(String pageKey) {
        return find("pageKey", pageKey).firstResultOptional();
    }

    /**
     * Find all migrated pages (React-enabled).
     *
     * @return list of pages where is_migrated = true
     */
    @CacheResult(cacheName = CACHE_NAME)
    public List<PageMigration> findMigrated() {
        return find("migrated = true ORDER BY displayOrder").list();
    }

    /**
     * Find all non-migrated pages (Vaadin-only).
     *
     * @return list of pages where is_migrated = false
     */
    @CacheResult(cacheName = CACHE_NAME)
    public List<PageMigration> findNotMigrated() {
        return find("migrated = false ORDER BY displayOrder").list();
    }

    /**
     * Find pages by section.
     *
     * @param section the section name (e.g., 'CRM', 'INVOICING')
     * @return list of pages in that section
     */
    public List<PageMigration> findBySection(String section) {
        return find("section = ?1 ORDER BY displayOrder", section).list();
    }

    /**
     * Find pages without a section (top-level navigation).
     *
     * @return list of pages with no section
     */
    public List<PageMigration> findWithoutSection() {
        return find("section IS NULL ORDER BY displayOrder").list();
    }

    /**
     * Check if a page is migrated to React.
     *
     * @param pageKey the page key
     * @return true if migrated, false otherwise
     */
    public boolean isMigrated(String pageKey) {
        return findByPageKey(pageKey)
                .map(PageMigration::isMigrated)
                .orElse(false);
    }

    /**
     * Toggle migration status for a page.
     * Invalidates cache to ensure both frontends see the change.
     *
     * @param pageKey the page key to toggle
     * @return the updated page migration, or empty if not found
     */
    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public Optional<PageMigration> toggleMigration(String pageKey) {
        Optional<PageMigration> pageOpt = findByPageKey(pageKey);

        if (pageOpt.isPresent()) {
            PageMigration page = pageOpt.get();
            page.setMigrated(!page.isMigrated());

            // Update migrated_at timestamp
            if (page.isMigrated()) {
                page.setMigratedAt(LocalDateTime.now());
            } else {
                page.setMigratedAt(null);
            }

            persist(page);
            return Optional.of(page);
        }

        return Optional.empty();
    }

    /**
     * Set migration status for a page.
     * Invalidates cache to ensure both frontends see the change.
     *
     * @param pageKey  the page key
     * @param migrated the new migration status
     * @return the updated page migration, or empty if not found
     */
    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public Optional<PageMigration> setMigrationStatus(String pageKey, boolean migrated) {
        Optional<PageMigration> pageOpt = findByPageKey(pageKey);

        if (pageOpt.isPresent()) {
            PageMigration page = pageOpt.get();
            page.setMigrated(migrated);
            page.setMigratedAt(migrated ? LocalDateTime.now() : null);
            persist(page);
            return Optional.of(page);
        }

        return Optional.empty();
    }

    /**
     * Update required roles for a page.
     * Invalidates cache to ensure both frontends see the change.
     *
     * @param pageKey       the page key
     * @param requiredRoles comma-separated role string (e.g., "HR,ADMIN")
     * @return the updated page migration, or empty if not found
     */
    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public Optional<PageMigration> setRequiredRoles(String pageKey, String requiredRoles) {
        Optional<PageMigration> pageOpt = findByPageKey(pageKey);

        if (pageOpt.isPresent()) {
            PageMigration page = pageOpt.get();
            page.setRequiredRoles(requiredRoles);
            persist(page);
            return Optional.of(page);
        }

        return Optional.empty();
    }

    /**
     * Save a page migration and invalidate cache.
     *
     * @param entity the entity to save
     * @return the saved entity
     */
    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public PageMigration save(PageMigration entity) {
        persist(entity);
        return entity;
    }

    /**
     * Delete a page migration and invalidate cache.
     *
     * @param entity the entity to delete
     */
    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public void remove(PageMigration entity) {
        delete(entity);
    }

    /**
     * Get distinct section names for menu grouping.
     *
     * @return list of section names (excluding null)
     */
    public List<String> findDistinctSections() {
        return getEntityManager()
                .createQuery("SELECT DISTINCT p.section FROM PageMigration p WHERE p.section IS NOT NULL ORDER BY p.section", String.class)
                .getResultList();
    }
}
