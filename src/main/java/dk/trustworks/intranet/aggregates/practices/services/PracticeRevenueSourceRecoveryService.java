package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Incident-only cleanup guarded by duration expiry, exact owner token and a held named lock. */
@JBossLog
@ApplicationScoped
public class PracticeRevenueSourceRecoveryService {

    static final int LOCK_RELEASE_TIMEOUT_MS = 5_000;
    static final String ACQUIRE_LOCK_SQL = "SELECT GET_LOCK(:lock, 0)";
    static final String RELEASE_LOCK_SQL = "SELECT RELEASE_LOCK(:lock)";

    static final String PUBLICATION_OWNER_SQL = """
            SELECT status, owner_token, attempt_generation_id,
                   TIMESTAMPDIFF(SECOND, started_at, UTC_TIMESTAMP(6))
            FROM practice_revenue_publication
            WHERE publication_key='PRACTICE_CONTRIBUTION'
            FOR UPDATE
            """;
    static final String PUBLICATION_FAIL_SQL = """
            UPDATE practice_revenue_publication
            SET status='FAILED', owner_token=NULL, attempt_generation_id=NULL,
                failed_at=UTC_TIMESTAMP(6), failure_code='STALE_OWNER_RECOVERED',
                publication_version=publication_version+1
            WHERE publication_key='PRACTICE_CONTRIBUTION'
              AND status='RUNNING' AND owner_token=:token
              AND attempt_generation_id=:generation
            """;
    static final String DELETE_ATTEMPT_SQL = """
            DELETE FROM fact_practice_net_revenue_item_mat
            WHERE generation_id=:generation
            """;
    static final String COST_OWNER_SQL = """
            SELECT status, owner_token, request_id,
                   TIMESTAMPDIFF(SECOND, claimed_at, UTC_TIMESTAMP(6))
            FROM practice_cost_basis_refresh_request
            WHERE status='RUNNING' AND owner_token=:token
            FOR UPDATE
            """;
    static final String COST_FAIL_SQL = """
            UPDATE practice_cost_basis_refresh_request
            SET status='FAILED', owner_token=NULL, claimed_at=NULL,
                failed_at=UTC_TIMESTAMP(6), completed_at=UTC_TIMESTAMP(6),
                safe_reason='STALE_OWNER_RECOVERED', optimistic_version=optimistic_version+1
            WHERE request_id=:id AND status='RUNNING' AND owner_token=:token
            """;
    static final String SOURCE_OWNER_SQL = """
            SELECT source_state, attempt_token, NULL,
                   TIMESTAMPDIFF(SECOND, started_at, UTC_TIMESTAMP(6))
            FROM practice_revenue_source_watermark
            WHERE source_name=:source
            FOR UPDATE
            """;
    static final String SOURCE_RECOVERY_CLAIM_SQL = """
            UPDATE practice_revenue_source_watermark
            SET source_state='RUNNING', attempt_token=:recoveryToken,
                started_at=UTC_TIMESTAMP(6), completed_at=NULL,
                safe_reason='SOURCE_RECOVERY_RUNNING',
                recovery_target_fact_change_log_id=NULL,recovery_token=NULL,recovery_started_at=NULL,
                optimistic_version=optimistic_version+1
            WHERE source_name=:source AND source_name<>'DELIVERY_EVIDENCE'
              AND source_state='RUNNING' AND attempt_token=:staleToken
            """;
    static final String DELIVERY_RECOVERY_PRECONDITION_SQL="""
            SELECT d.source_state,d.safe_reason,d.retention_gap_reason,d.attempt_token,d.recovery_token,
                   c.refresh_enabled,c.revenue_recovery_owner_token,
                   (SELECT COUNT(*) FROM practice_revenue_source_watermark ready
                     WHERE ready.source_name<>'DELIVERY_EVIDENCE' AND ready.source_state='READY'),
                   (SELECT COUNT(*) FROM practice_revenue_source_watermark other
                     WHERE other.source_name<>'DELIVERY_EVIDENCE')
            FROM practice_revenue_source_watermark d
            JOIN practice_contribution_publication_control c ON c.control_id=1
            WHERE d.source_name='DELIVERY_EVIDENCE'
            FOR UPDATE
            """;
    static final String DELIVERY_RECOVERY_CLAIM_SQL="""
            UPDATE practice_revenue_source_watermark d
            SET d.source_state='RUNNING',d.attempt_token=:recoveryToken,
                d.started_at=UTC_TIMESTAMP(6),d.completed_at=NULL,
                d.safe_reason='DELIVERY_RETENTION_RECOVERY_RUNNING',
                d.recovery_target_fact_change_log_id=:target,
                d.recovery_token=:recoveryToken,d.recovery_started_at=UTC_TIMESTAMP(6),
                d.optimistic_version=d.optimistic_version+1
            WHERE d.source_name='DELIVERY_EVIDENCE' AND d.source_state='FAILED'
              AND d.safe_reason='FACT_CHANGE_LOG_RETENTION_GAP'
              AND d.retention_gap_reason='FACT_CHANGE_LOG_RETENTION_GAP'
              AND d.attempt_token IS NULL AND d.recovery_token IS NULL
              AND EXISTS (SELECT 1 FROM practice_contribution_publication_control c
                          WHERE c.control_id=1 AND c.refresh_enabled=FALSE
                            AND c.revenue_recovery_owner_token IS NULL)
              AND 8=(SELECT COUNT(*) FROM practice_revenue_source_watermark ready
                     WHERE ready.source_name<>'DELIVERY_EVIDENCE' AND ready.source_state='READY')
              AND 8=(SELECT COUNT(*) FROM practice_revenue_source_watermark other
                     WHERE other.source_name<>'DELIVERY_EVIDENCE')
            """;
    static final String DELIVERY_TARGET_SQL="SELECT COALESCE(MAX(id),0) FROM fact_change_log";
    static final List<String> DELIVERY_RECOVERY_LOCKS=List.of(
            "bi_refresh","practice_revenue","practice_revenue_source_delivery_evidence");

    @Inject
    EntityManager em;

    @Inject
    Instance<PracticeRevenueSourceRecoveryService> self;

    @Inject
    Instance<PracticeRevenueSourceRebuildHandler> rebuildHandlers;

    Clock clock = Clock.systemUTC();

    @Inject
    PracticeRevenueDirtyMarker dirtyMarker;

    @ConfigProperty(name = "practices.contribution.stale-owner-after", defaultValue = "PT35M")
    Duration staleAfter;

    /**
     * Cleans one stale owner. The recovery worker holds the corresponding database lock from
     * proof-of-death through the token-guarded update, closing the former IS_FREE_LOCK TOCTOU gap.
     */
    @Transactional
    public RecoveryResult recover(Category category, String expectedOwnerToken) {
        if (category == null) throw new IllegalArgumentException("recovery category is required");
        if(category==Category.DELIVERY_EVIDENCE){
            if(expectedOwnerToken!=null)
                throw new IllegalArgumentException("delivery recovery does not accept an owner token");
            String recoveryToken=UUID.randomUUID().toString();
            SourceRecoveryLease lease=self==null?startDeliveryRecovery(recoveryToken)
                    :self.get().startDeliveryRecovery(recoveryToken);
            return finishSourceRecovery(category,lease);
        }
        String token = requireCanonicalToken(expectedOwnerToken);
        requireValidDuration();
        if (category.sourceCategory()) {
            String recoveryToken=UUID.randomUUID().toString();
            SourceRecoveryLease lease = self == null
                    ? recoverStaleSourceOwner(category, token, recoveryToken)
                    : self.get().recoverStaleSourceOwner(category, token, recoveryToken);
            return finishSourceRecovery(category,lease);
        }
        String lockName = category.lockName();
        acquireRecoveryLock(lockName);
        RuntimeException failure = null;
        try {
            return switch (category) {
                case PUBLICATION -> recoverPublication(token);
                case COST_BASIS -> recoverCost(token);
                case FINANCE_GL, SELF_BILLED, PHANTOM_ATTRIBUTION, DELIVERY_EVIDENCE ->
                        throw new IllegalStateException("source recovery must use its isolated boundary");
            };
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                releaseRecoveryLock(lockName);
            } catch (RuntimeException releaseFailure) {
                if (failure != null) failure.addSuppressed(releaseFailure);
                else throw releaseFailure;
            }
        }
    }

    /** Commits stale cleanup before a bounded rebuild starts, even when called by a transactional REST action. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SourceRecoveryLease recoverStaleSourceOwner(
            Category category, String token, String recoveryToken) {
        if(category==null||!category.sourceCategory()||category==Category.DELIVERY_EVIDENCE){
            throw new IllegalArgumentException("source recovery category is required");
        }
        token=requireCanonicalToken(token);
        recoveryToken=requireCanonicalToken(recoveryToken);
        requireValidDuration();
        String lockName = category.lockName();
        acquireRecoveryLock(lockName);
        RuntimeException failure = null;
        try {
            return recoverWatermark(category, token,recoveryToken);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                releaseRecoveryLock(lockName);
            } catch (RuntimeException releaseFailure) {
                if (failure != null) failure.addSuppressed(releaseFailure);
                else throw releaseFailure;
            }
        }
    }

    /** Claims only an explicit retention gap while ordinary build remains disabled. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SourceRecoveryLease startDeliveryRecovery(String recoveryToken){
        recoveryToken=requireCanonicalToken(recoveryToken);
        List<String> held=new ArrayList<>();
        RuntimeException failure=null;
        try{
            for(String lock:DELIVERY_RECOVERY_LOCKS){acquireRecoveryLock(lock);held.add(lock);}
            Object[] row=(Object[])timed(em.createNativeQuery(DELIVERY_RECOVERY_PRECONDITION_SQL))
                    .getSingleResult();
            requireDeliveryRecoveryPreconditions(row);
            BigInteger target=integer(timed(em.createNativeQuery(DELIVERY_TARGET_SQL)).getSingleResult());
            int updated=timed(em.createNativeQuery(DELIVERY_RECOVERY_CLAIM_SQL))
                    .setParameter("recoveryToken",recoveryToken).setParameter("target",target).executeUpdate();
            requireOne(updated,"DELIVERY_RECOVERY_PRECONDITION_FAILED");
            return new SourceRecoveryLease(Category.DELIVERY_EVIDENCE,recoveryToken,target);
        }catch(RuntimeException e){failure=e;throw e;}
        finally{
            Collections.reverse(held);
            RuntimeException firstReleaseFailure=null;
            for(String lock:held){
                try{releaseRecoveryLock(lock);}
                catch(RuntimeException releaseFailure){
                    if(failure!=null)failure.addSuppressed(releaseFailure);
                    else if(firstReleaseFailure==null)firstReleaseFailure=releaseFailure;
                    else firstReleaseFailure.addSuppressed(releaseFailure);
                }
            }
            if(failure==null&&firstReleaseFailure!=null)throw firstReleaseFailure;
        }
    }

    private RecoveryResult finishSourceRecovery(Category category,SourceRecoveryLease lease){
        try{return rebuildSource(category,lease);}
        catch(RuntimeException failure){
            try{dirtyMarker.failRecovery(source(category),lease.recoveryToken());}
            catch(RuntimeException cleanupFailure){failure.addSuppressed(cleanupFailure);}
            throw failure;
        }
    }

    private RecoveryResult rebuildSource(Category category, SourceRecoveryLease lease) {
        List<PracticeRevenueSourceRebuildHandler> handlers = rebuildHandlers == null
                ? List.of()
                : rebuildHandlers.stream().filter(handler -> handler.category() == category).toList();
        if (handlers.size() != 1) {
            throw new RecoveryConflictException("SOURCE_REBUILD_UNAVAILABLE");
        }
        RecoveryWindow window=recoveryWindow(clock);
        LocalDate from=window.fromInclusive();
        LocalDate to=window.toInclusive();
        PracticeRevenueSourceRebuildHandler.RebuildResult rebuilt = handlers.getFirst().rebuild(
                new PracticeRevenueSourceRebuildHandler.RebuildRequest(from,to,lease.recoveryToken(),
                        lease.recoveryTargetFactChangeLogId()));
        if (rebuilt == null || !rebuilt.complete()) {
            throw new RecoveryConflictException("SOURCE_REBUILD_INCOMPLETE");
        }
        if(category==Category.DELIVERY_EVIDENCE&&rebuilt.observedFactChangeLogId()==null){
            throw new RecoveryConflictException("DELIVERY_RECOVERY_CURSOR_MISSING");
        }
        dirtyMarker.completeRecovery(source(category),lease.recoveryToken(),YearMonth.from(from),
                YearMonth.from(to),rebuilt.observedFactChangeLogId());
        return new RecoveryResult(category.name(),1,"REBUILT:"+category.name());
    }

    private RecoveryResult recoverPublication(String token) {
        Object[] row = (Object[]) timed(em.createNativeQuery(PUBLICATION_OWNER_SQL)).getSingleResult();
        requireStale(row, token);
        String generation = requiredText(row[2], "ATTEMPT_GENERATION_MISSING");
        Query fail = timed(em.createNativeQuery(PUBLICATION_FAIL_SQL))
                .setParameter("token", token)
                .setParameter("generation", generation);
        requireOne(fail.executeUpdate(), "PUBLICATION_OWNER_CHANGED");
        timed(em.createNativeQuery(DELETE_ATTEMPT_SQL))
                .setParameter("generation", generation)
                .executeUpdate();
        return new RecoveryResult(Category.PUBLICATION.name(), 1, generation);
    }

    private RecoveryResult recoverCost(String token) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = timed(em.createNativeQuery(COST_OWNER_SQL))
                .setParameter("token", token)
                .getResultList();
        if (rows.size() != 1) throw new RecoveryConflictException("COST_OWNER_NOT_FOUND");
        Object[] row = rows.getFirst();
        requireStale(row, token);
        Object requestId = row[2];
        int updated = timed(em.createNativeQuery(COST_FAIL_SQL))
                .setParameter("id", requestId)
                .setParameter("token", token)
                .executeUpdate();
        requireOne(updated, "COST_OWNER_CHANGED");
        return new RecoveryResult(Category.COST_BASIS.name(), 1, String.valueOf(requestId));
    }

    private SourceRecoveryLease recoverWatermark(Category category,String token,String recoveryToken) {
        String source = category.name();
        Object[] row = (Object[]) timed(em.createNativeQuery(SOURCE_OWNER_SQL))
                .setParameter("source", source)
                .getSingleResult();
        requireStale(row, token);
        int updated = timed(em.createNativeQuery(SOURCE_RECOVERY_CLAIM_SQL))
                .setParameter("source", source)
                .setParameter("staleToken",token)
                .setParameter("recoveryToken",recoveryToken)
                .executeUpdate();
        requireOne(updated, "SOURCE_OWNER_CHANGED");
        return new SourceRecoveryLease(category,recoveryToken,null);
    }

    private static void requireDeliveryRecoveryPreconditions(Object[] row){
        if(row==null||row.length<9||!"FAILED".equals(String.valueOf(row[0]))
                ||!"FACT_CHANGE_LOG_RETENTION_GAP".equals(String.valueOf(row[1]))
                ||!"FACT_CHANGE_LOG_RETENTION_GAP".equals(String.valueOf(row[2]))
                ||row[3]!=null||row[4]!=null||!isFalse(row[5])||row[6]!=null
                ||!(row[7] instanceof Number ready)||ready.intValue()!=8
                ||!(row[8] instanceof Number count)||count.intValue()!=8){
            throw new RecoveryConflictException("DELIVERY_RECOVERY_PRECONDITION_FAILED");
        }
    }

    private static boolean isFalse(Object value){
        return value instanceof Boolean bool?!bool:value instanceof Number number&&number.intValue()==0;
    }

    private void acquireRecoveryLock(String lockName) {
        Object value = timed(em.createNativeQuery(ACQUIRE_LOCK_SQL))
                .setParameter("lock", lockName)
                .getSingleResult();
        if (!(value instanceof Number number) || number.intValue() != 1) {
            throw new RecoveryConflictException("OWNER_LOCK_STILL_HELD");
        }
    }

    private void releaseRecoveryLock(String lockName) {
        Object value = timed(em.createNativeQuery(RELEASE_LOCK_SQL))
                .setParameter("lock", lockName)
                .getSingleResult();
        if (!(value instanceof Number number) || number.intValue() != 1) {
            throw new RecoveryConflictException("RECOVERY_LOCK_RELEASE_FAILED");
        }
    }

    private Query timed(Query query) {
        return query.setHint("jakarta.persistence.query.timeout", LOCK_RELEASE_TIMEOUT_MS);
    }

    private void requireStale(Object[] row, String token) {
        if (row == null || row.length < 4
                || !"RUNNING".equals(String.valueOf(row[0]))
                || !token.equals(String.valueOf(row[1]))) {
            throw new RecoveryConflictException("OWNER_TOKEN_MISMATCH");
        }
        if (!(row[3] instanceof Number ageSeconds)) {
            throw new RecoveryConflictException("OWNER_START_TIME_UNAVAILABLE");
        }
        if (ageSeconds.longValue() < staleAfter.toSeconds()) {
            throw new RecoveryConflictException("OWNER_NOT_EXPIRED");
        }
    }

    private void requireValidDuration() {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()
                || staleAfter.toSeconds() == 0) {
            throw new IllegalStateException("stale-owner duration must be positive");
        }
    }

    private static String requireCanonicalToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("expected owner token is required");
        }
        try {
            String canonical = UUID.fromString(token).toString();
            if (!canonical.equals(token)) throw new IllegalArgumentException("expected owner token must be canonical UUID");
            return canonical;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("expected owner token must be canonical UUID", e);
        }
    }

    private static String requiredText(Object value, String code) {
        if (value == null || String.valueOf(value).isBlank()) throw new RecoveryConflictException(code);
        return String.valueOf(value);
    }

    private static void requireOne(int affectedRows, String code) {
        if (affectedRows != 1) throw new RecoveryConflictException(code);
    }

    private static PracticeRevenueDirtyMarker.Source source(Category category){
        return PracticeRevenueDirtyMarker.Source.valueOf(category.name());
    }

    static RecoveryWindow recoveryWindow(Clock clock){
        YearMonth last=YearMonth.now(clock.withZone(ZoneId.of("Europe/Copenhagen"))).minusMonths(1);
        return new RecoveryWindow(last.minusMonths(59).atDay(1),last.atEndOfMonth());
    }

    private static BigInteger integer(Object value){
        return value instanceof BigInteger integer?integer:new BigInteger(value.toString());
    }

    public enum Category {
        PUBLICATION("practice_revenue"),
        COST_BASIS("practice_cost_basis"),
        FINANCE_GL("practice_revenue_source_finance_gl"),
        SELF_BILLED("practice_revenue_source_self_billed"),
        PHANTOM_ATTRIBUTION("practice_revenue_source_phantom_attribution"),
        DELIVERY_EVIDENCE("practice_revenue_source_delivery_evidence");

        private final String lockName;

        Category(String lockName) {
            this.lockName = lockName;
        }

        String lockName() {
            return lockName;
        }

        boolean sourceCategory() {
            return this != PUBLICATION && this != COST_BASIS;
        }

        public static Category fromPath(String value) {
            if (value == null || value.isBlank() || !value.equals(value.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("unknown recovery category");
            }
            try {
                return Category.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown recovery category", e);
            }
        }
    }

    public record RecoveryResult(String category, int affectedRows, String recoveredIdentity) {
    }

    public record SourceRecoveryLease(Category category,String recoveryToken,
                                      BigInteger recoveryTargetFactChangeLogId) {}
    record RecoveryWindow(LocalDate fromInclusive,LocalDate toInclusive){}

    public static class RecoveryConflictException extends IllegalStateException {
        public RecoveryConflictException(String message) {
            super(message);
        }
    }
}
