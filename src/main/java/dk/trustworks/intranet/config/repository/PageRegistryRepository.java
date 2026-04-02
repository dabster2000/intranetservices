package dk.trustworks.intranet.config.repository;

import dk.trustworks.intranet.config.model.PageRegistry;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PageRegistryRepository implements PanacheRepository<PageRegistry> {

    private static final String CACHE_NAME = "page-registry";

    @CacheResult(cacheName = CACHE_NAME)
    public List<PageRegistry> findAllOrdered() {
        return find("ORDER BY displayOrder, pageKey").list();
    }

    public Optional<PageRegistry> findByPageKey(String pageKey) {
        return find("pageKey", pageKey).firstResultOptional();
    }

    @CacheResult(cacheName = CACHE_NAME)
    public List<PageRegistry> findVisible() {
        return find("visible = true ORDER BY displayOrder").list();
    }

    @CacheResult(cacheName = CACHE_NAME)
    public List<PageRegistry> findHidden() {
        return find("visible = false ORDER BY displayOrder").list();
    }

    public List<PageRegistry> findBySection(String section) {
        return find("section = ?1 ORDER BY displayOrder", section).list();
    }

    public List<PageRegistry> findWithoutSection() {
        return find("section IS NULL ORDER BY displayOrder").list();
    }

    public boolean isVisible(String pageKey) {
        return findByPageKey(pageKey)
                .map(PageRegistry::isVisible)
                .orElse(false);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public Optional<PageRegistry> toggleVisibility(String pageKey) {
        Optional<PageRegistry> pageOpt = findByPageKey(pageKey);

        if (pageOpt.isPresent()) {
            PageRegistry page = pageOpt.get();
            page.setVisible(!page.isVisible());
            persist(page);
            return Optional.of(page);
        }

        return Optional.empty();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public Optional<PageRegistry> setVisibility(String pageKey, boolean visible) {
        Optional<PageRegistry> pageOpt = findByPageKey(pageKey);

        if (pageOpt.isPresent()) {
            PageRegistry page = pageOpt.get();
            page.setVisible(visible);
            persist(page);
            return Optional.of(page);
        }

        return Optional.empty();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public Optional<PageRegistry> setRequiredRoles(String pageKey, String requiredRoles) {
        Optional<PageRegistry> pageOpt = findByPageKey(pageKey);

        if (pageOpt.isPresent()) {
            PageRegistry page = pageOpt.get();
            page.setRequiredRoles(requiredRoles);
            persist(page);
            return Optional.of(page);
        }

        return Optional.empty();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public PageRegistry save(PageRegistry entity) {
        persist(entity);
        return entity;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public void remove(PageRegistry entity) {
        delete(entity);
    }

    public List<String> findDistinctSections() {
        return getEntityManager()
                .createQuery("SELECT DISTINCT p.section FROM PageRegistry p WHERE p.section IS NOT NULL ORDER BY p.section", String.class)
                .getResultList();
    }
}
