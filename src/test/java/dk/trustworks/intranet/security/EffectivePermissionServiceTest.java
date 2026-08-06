package dk.trustworks.intranet.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fast-tier tests for the Phase 4 permission cache (task 4.8): 30 s per-user
 * TTL, an {@code authz_version} poll at most once per second, and a full flush
 * when the version moves. Uses a fake store and a fake nano clock — no DB.
 */
class EffectivePermissionServiceTest {

    private FakeStore store;
    private FakeClock clock;
    private EffectivePermissionService service;

    private static final String ALICE = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String BOB = "bbbbbbbb-0000-0000-0000-000000000002";

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        clock = new FakeClock();
        store.permissions.put(ALICE, Set.of("users:read", "invoices:read"));
        store.permissions.put(BOB, Set.of("users:read"));
        service = new EffectivePermissionService(store, clock::nanos);
    }

    @Test
    void resolvesThroughTheStoreAndCachesPerUser() {
        assertEquals(Set.of("users:read", "invoices:read"), service.effectivePermissions(ALICE));
        assertEquals(Set.of("users:read", "invoices:read"), service.effectivePermissions(ALICE));
        assertEquals(1, store.loadCalls(ALICE), "second read inside the TTL must come from the cache");

        assertEquals(Set.of("users:read"), service.effectivePermissions(BOB));
        assertEquals(1, store.loadCalls(BOB), "cache is keyed by user UUID");
    }

    @Test
    void cacheExpiresAfterThirtySeconds() {
        service.effectivePermissions(ALICE);
        clock.advance(Duration.ofSeconds(29));
        service.effectivePermissions(ALICE);
        assertEquals(1, store.loadCalls(ALICE), "29 s is inside the TTL");

        clock.advance(Duration.ofSeconds(2));
        service.effectivePermissions(ALICE);
        assertEquals(2, store.loadCalls(ALICE), "31 s is past the TTL");
    }

    @Test
    void versionIsPolledAtMostOncePerSecond() {
        service.effectivePermissions(ALICE);
        service.effectivePermissions(ALICE);
        service.effectivePermissions(BOB);
        assertEquals(1, store.versionReads, "three reads inside one second poll the version once");

        clock.advance(Duration.ofMillis(1100));
        service.effectivePermissions(ALICE);
        assertEquals(2, store.versionReads, "a read after the interval polls again");
    }

    @Test
    void versionChangeFlushesTheWholeCache() {
        service.effectivePermissions(ALICE);
        service.effectivePermissions(BOB);

        store.version = 2; // an authorization write on another task bumped it
        store.permissions.put(ALICE, Set.of("users:read"));
        clock.advance(Duration.ofMillis(1100));

        assertEquals(Set.of("users:read"), service.effectivePermissions(ALICE),
                "the flush must surface the new grant set well before the 30 s TTL");
        assertEquals(2, store.loadCalls(ALICE));
        service.effectivePermissions(BOB);
        assertEquals(2, store.loadCalls(BOB), "a version change flushes every user, not only the reader");
    }

    @Test
    void unchangedVersionDoesNotFlush() {
        service.effectivePermissions(ALICE);
        clock.advance(Duration.ofMillis(1100));
        service.effectivePermissions(ALICE);
        assertEquals(1, store.loadCalls(ALICE), "polling an unchanged version must not evict");
    }

    @Test
    void bumpDelegatesToTheStore() {
        service.bumpVersion();
        assertEquals(1, store.bumps);
    }

    // ---- fakes ----

    private static final class FakeClock {
        private long nanos;

        long nanos() {
            return nanos;
        }

        void advance(Duration d) {
            nanos += d.toNanos();
        }
    }

    private static final class FakeStore implements AuthzStore {
        final Map<String, Set<String>> permissions = new HashMap<>();
        final Map<String, Integer> loads = new HashMap<>();
        long version = 1;
        int versionReads;
        int bumps;

        int loadCalls(String uuid) {
            return loads.getOrDefault(uuid, 0);
        }

        @Override
        public Set<String> loadEffectivePermissions(String userUuid) {
            loads.merge(userUuid, 1, Integer::sum);
            return permissions.getOrDefault(userUuid, Set.of());
        }

        @Override
        public long currentVersion() {
            versionReads++;
            return version;
        }

        @Override
        public void bumpVersion() {
            bumps++;
            version++;
        }

        @Override
        public List<CatalogueRow> catalogueRows() {
            return List.of();
        }

        @Override
        public long countPermissionBindings(String role) {
            return 0;
        }
    }
}
