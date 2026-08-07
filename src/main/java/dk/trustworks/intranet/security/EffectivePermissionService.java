package dk.trustworks.intranet.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Resolves a user UUID to their effective permission set
 * (user → roles → role_permission → permission, tombstone-aware).
 *
 * <p>DORMANT in Phase 4: only {@code GET /users/{uuid}/permissions} reads it;
 * no authorization decision consumes it until Phase 5.
 *
 * <p>Staleness model (phase file 4.8): results are cached per user UUID for
 * 30 s, but {@code authz_version} is polled at most once per second and any
 * change flushes the whole local cache. Every authorization write bumps the
 * version in the same transaction ({@link AuthzStore#bumpVersion()}), so the
 * worst-case staleness is ~1 s — correct across concurrently running Fargate
 * tasks with no shared cache infrastructure.
 */
@ApplicationScoped
public class EffectivePermissionService {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    static final long VERSION_POLL_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long NO_VERSION_SEEN = Long.MIN_VALUE;

    private final AuthzStore store;
    private final LongSupplier nanoClock;
    private final Cache<String, Set<String>> cache;
    private final Cache<String, Map<String, Set<DataScope>>> scopesCache;
    private final AtomicLong versionPollDueAtNanos;
    private final AtomicLong lastSeenVersion = new AtomicLong(NO_VERSION_SEEN);

    @Inject
    public EffectivePermissionService(AuthzStore store) {
        this(store, System::nanoTime);
    }

    /** Test seam: deterministic clock drives both the TTL and the poll interval. */
    EffectivePermissionService(AuthzStore store, LongSupplier nanoClock) {
        this.store = store;
        this.nanoClock = nanoClock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(4096)
                .ticker(nanoClock::getAsLong)
                .build();
        this.scopesCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(4096)
                .ticker(nanoClock::getAsLong)
                .build();
        this.versionPollDueAtNanos = new AtomicLong(nanoClock.getAsLong());
    }

    /**
     * The user's effective permission keys; cached, never {@code null}.
     * Since Phase 8 this is the legacy boolean projection — ALL-scope grants
     * only (see {@link AuthzStore#loadEffectivePermissions}).
     */
    public Set<String> effectivePermissions(String userUuid) {
        maybeRefreshOnVersionChange();
        return cache.get(userUuid, store::loadEffectivePermissions);
    }

    /**
     * The scope-aware projection (Phase 8): permission key → granted
     * {@link DataScope}s across the user's roles. Same staleness model as
     * {@link #effectivePermissions}: 30 s TTL, flushed within ~1 s of any
     * authorization write via the version poll.
     */
    public Map<String, Set<DataScope>> effectiveScopes(String userUuid) {
        maybeRefreshOnVersionChange();
        return scopesCache.get(userUuid, store::loadEffectivePermissionScopes);
    }

    /**
     * Bumps {@code authz_version} inside the caller's transaction. Call from
     * every authorization write so all tasks flush within ~1 s.
     */
    public void bumpVersion() {
        store.bumpVersion();
    }

    private void maybeRefreshOnVersionChange() {
        long now = nanoClock.getAsLong();
        long due = versionPollDueAtNanos.get();
        if (now - due < 0) {
            return; // polled less than a second ago
        }
        if (!versionPollDueAtNanos.compareAndSet(due, now + VERSION_POLL_INTERVAL_NANOS)) {
            return; // another thread claimed this poll
        }
        long version = store.currentVersion();
        long previous = lastSeenVersion.getAndSet(version);
        if (previous != NO_VERSION_SEEN && previous != version) {
            cache.invalidateAll();
            scopesCache.invalidateAll();
        }
    }
}
