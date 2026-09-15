package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSyncHealthDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.model.CalendarSyncState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A complete calendar read is a snapshot, so its write must not race a later read or an
 * explicit opt-out. The mailbox row is locked only while reserving or committing; no
 * database transaction spans Graph. Generation membership is independent of DATETIME
 * precision. All code changing explicit consent takes the same mailbox lock.
 */
@ApplicationScoped
public class CalendarSyncStateService {
    public static final int RECOVERY_VERSION = 1;

    @Inject
    EntityManager em;

    public record Lease(String userUuid, long generation, LocalDateTime startedAt, boolean recoveryRequired) { }
    public record SyncCounts(int meetings, int attendees, int staleRemoved) {
        public static SyncCounts empty() { return new SyncCounts(0, 0, 0); }
        public SyncCounts {
            if (meetings < 0 || attendees < 0 || staleRemoved < 0) {
                throw new IllegalArgumentException("Sync counts must not be negative");
            }
        }
    }
    public record Completion(boolean applied, SyncCounts counts) { }

    /** Checked before each Graph page; an in-flight request may finish after revocation. */
    @Transactional
    public boolean canRead(Lease lease) {
        if (lease == null) return false;
        Long current = em.createQuery("select s.generation from CalendarSyncState s where s.userUuid = :user and s.outcome = 'RUNNING'", Long.class)
                .setParameter("user", lease.userUuid()).getResultStream().findFirst().orElse(null);
        return current != null && current == lease.generation() && isCurrentlyEnabled(lease.userUuid());
    }

    /** Returns null when sharing is no longer enabled, before any mailbox is read. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Lease reserve(String userUuid, Instant startedAt) {
        CalendarSyncState state = lock(userUuid);
        state.setGeneration(Math.addExact(state.getGeneration(), 1));
        state.setLastAttemptAt(LocalDateTime.ofInstant(startedAt, ZoneOffset.UTC));
        state.setFailureCode(null);
        state.setMeetingsKept(0);
        state.setAttendeesKept(0);
        state.setStaleRemoved(0);
        if (!isCurrentlyEnabled(userUuid)) {
            state.setOutcome("REVOKED");
            state.setLastCompletedAt(now());
            return null;
        }
        state.setOutcome("RUNNING");
        return new Lease(userUuid, state.getGeneration(), state.getLastAttemptAt(),
                state.getRecoveryVersion() < RECOVERY_VERSION);
    }

    /**
     * Invokes writes, flush and (only for a complete read) reconciliation under the mailbox
     * fence. The callback returns the retained counts AFTER reconciliation. An exception
     * rolls this entire transaction back; the caller then records a safe failure code.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Completion complete(Lease lease, boolean fullRead, boolean readComplete,
                               Supplier<SyncCounts> writeAndReconcile) {
        CalendarSyncState state = lock(lease.userUuid());
        if (state.getGeneration() != lease.generation() || !"RUNNING".equals(state.getOutcome())) {
            return new Completion(false, SyncCounts.empty());
        }
        if (!isCurrentlyEnabled(lease.userUuid())) {
            state.setOutcome("REVOKED");
            state.setLastCompletedAt(now());
            return new Completion(false, SyncCounts.empty());
        }
        SyncCounts counts = writeAndReconcile.get();
        em.flush();
        state.setMeetingsKept(counts.meetings());
        state.setAttendeesKept(counts.attendees());
        state.setStaleRemoved(counts.staleRemoved());
        state.setLastCompletedAt(now());
        state.setOutcome(readComplete ? "SUCCEEDED" : "INCOMPLETE");
        state.setFailureCode(null);
        if (readComplete) {
            state.setLastSuccessfulAt(state.getLastCompletedAt());
            if (fullRead) {
                state.setLastFullSuccessfulAt(state.getLastCompletedAt());
                state.setRecoveryVersion(RECOVERY_VERSION);
            }
        }
        return new Completion(true, counts);
    }

    /** Never replaces the result of a newer pass and never stores exception text. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void fail(Lease lease, String failureCode) {
        if (lease == null) return;
        CalendarSyncState state = lock(lease.userUuid());
        if (state.getGeneration() != lease.generation() || !"RUNNING".equals(state.getOutcome())) return;
        state.setOutcome("FAILED");
        state.setLastCompletedAt(now());
        state.setFailureCode(safeFailureCode(failureCode));
    }

    /** Must be called BEFORE writing explicit consent, inside that same transaction. */
    @Transactional(Transactional.TxType.MANDATORY)
    public void fenceConsentChange(String userUuid, boolean enabled) {
        CalendarSyncState state = lock(userUuid);
        state.setGeneration(Math.addExact(state.getGeneration(), 1));
        if (!enabled) {
            state.setOutcome("REVOKED");
            state.setLastCompletedAt(now());
            state.setFailureCode(null);
            // Past shared meetings retain their existing retention semantics. The generation
            // invalidates pending reads and commits, without erasing previously agreed history.
        } else {
            state.setRecoveryVersion(0);
            state.setOutcome("PENDING");
        }
    }

    /** A changed star/domain requires a fresh full read, and supersedes an older snapshot. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void requestFullRead(String userUuid) {
        resetForFullRead(userUuid);
    }

    /** Sorted locking avoids deadlocks when several callers invalidate the same mailboxes. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void requestFullRead(Set<String> userUuids) {
        if (userUuids == null) return;
        userUuids.stream().filter(user -> user != null && !user.isBlank()).sorted()
                .forEach(this::resetForFullRead);
    }

    private void resetForFullRead(String userUuid) {
        CalendarSyncState state = lock(userUuid);
        state.setGeneration(Math.addExact(state.getGeneration(), 1));
        state.setRecoveryVersion(0);
        state.setOutcome("PENDING");
        state.setFailureCode(null);
    }

    /** One aggregate response; permission enforcement belongs on the resource. */
    @Transactional
    public CalendarSyncHealthDTO health() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                select s.outcome, s.last_successful_at, s.last_full_successful_at, s.last_attempt_at
                  from `user` u
                  left join user_calendar_consent c on c.user_uuid = u.uuid
                  left join calendar_sync_state s on s.user_uuid = u.uuid
                 where c.enabled = 1
                    or (c.user_uuid is null and exists
                        (select 1 from roles r where r.useruuid = u.uuid
                           and r.role in ('SALES', 'PARTNER', 'ADMIN')))
                """).getResultList();
        int success = 0, failed = 0, incomplete = 0, never = 0, running = 0, interrupted = 0;
        LocalDateTime lastSuccess = null, lastFull = null;
        LocalDateTime interruptedBefore = now().minusHours(2);
        for (Object[] row : rows) {
            String outcome = row[0] == null ? "NEVER_SYNCED" : row[0].toString();
            switch (outcome) {
                case "SUCCEEDED" -> success++;
                case "FAILED" -> failed++;
                case "INCOMPLETE" -> incomplete++;
                case "RUNNING" -> {
                    LocalDateTime attempted = asDateTime(row[3]);
                    if (attempted == null || !attempted.isAfter(interruptedBefore)) {
                        // Effective WORKER_INTERRUPTED health. Reading health never changes
                        // a lease or interferes with a worker that eventually returns.
                        failed++;
                        interrupted++;
                    } else running++;
                }
                default -> { }
            }
            if (row[1] == null) never++;
            lastSuccess = latest(lastSuccess, asDateTime(row[1]));
            lastFull = latest(lastFull, asDateTime(row[2]));
        }
        return new CalendarSyncHealthDTO(rows.size(), success, failed, incomplete, never,
                running, interrupted,
                lastSuccess == null ? null : lastSuccess.toInstant(ZoneOffset.UTC),
                lastFull == null ? null : lastFull.toInstant(ZoneOffset.UTC));
    }

    private CalendarSyncState lock(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) throw new IllegalArgumentException("A mailbox user uuid is required");
        em.createNativeQuery("""
                insert into calendar_sync_state (user_uuid, generation, recovery_version, outcome,
                                                 meetings_kept, attendees_kept, stale_removed)
                values (:user, 0, 0, 'NEVER_SYNCED', 0, 0, 0)
                on duplicate key update user_uuid = values(user_uuid)
                """).setParameter("user", userUuid).executeUpdate();
        CalendarSyncState state = em.find(CalendarSyncState.class, userUuid, LockModeType.PESSIMISTIC_WRITE);
        // Refresh matters for callers sharing an EntityManager over multiple transactions.
        em.refresh(state, LockModeType.PESSIMISTIC_WRITE);
        return state;
    }

    private boolean isCurrentlyEnabled(String userUuid) {
        Object value = em.createNativeQuery("""
                select coalesce((select enabled from user_calendar_consent where user_uuid = :user),
                                exists(select 1 from roles where useruuid = :user
                                         and role in ('SALES','PARTNER','ADMIN')))
                """).setParameter("user", userUuid).getSingleResult();
        return value instanceof Boolean flag ? flag : ((Number) value).intValue() != 0;
    }

    static String safeFailureCode(String code) {
        return code != null && Set.of("GRAPH_READ", "PERSISTENCE", "NO_MAILBOX", "SYNC_FAILED",
                "GRAPH_FORBIDDEN", "GRAPH_NOT_FOUND", "GRAPH_THROTTLED", "GRAPH_TIMEOUT",
                "WORKER_INTERRUPTED", "INTERRUPTED", "CANCELLED", "INTERNAL").contains(code)
                ? code : "INTERNAL";
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b != null && b.isAfter(a) ? b : a;
    }
    private static LocalDateTime asDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        throw new IllegalArgumentException("Unsupported calendar health timestamp");
    }
}
