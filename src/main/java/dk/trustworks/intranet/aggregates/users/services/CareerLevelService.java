package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.exceptions.InconsistantDataException;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.exception.ConstraintViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class CareerLevelService {

    /**
     * Self-proxy. Lets {@link #create(UserCareerLevel)} re-enter the transactional unit of work
     * {@link #createInTx(UserCareerLevel)} through the CDI/interceptor stack so the duplicate-key
     * retry runs in a <b>fresh</b> transaction (a plain {@code this.} call would bypass
     * {@code @Transactional} via self-invocation and reuse the rolled-back session).
     */
    @Inject
    CareerLevelService self;

    public List<UserCareerLevel> listAll(String useruuid) {
        return UserCareerLevel.findByUseruuid(useruuid);
    }

    public Optional<UserCareerLevel> getCurrent(String useruuid) {
        return UserCareerLevel.findCurrentByUseruuid(useruuid);
    }

    /**
     * Idempotent save for POST /users/{useruuid}/careerlevels. The stored row is unique on
     * {@code uq_career_level_user_date} = (useruuid, active_from); the BFF mints a fresh uuid on
     * every save, so a same-day re-save arrives with an unknown uuid while the (user, date) row
     * already exists — the save must converge on "one row per user per day" rather than 500.
     *
     * <p>The natural-key lookup in {@link #createInTx(UserCareerLevel)} turns the common
     * (serialized) re-save into an in-place update. Two genuinely concurrent requests can both
     * pass that lookup — each JTA transaction has its own MariaDB REPEATABLE READ snapshot — and
     * the loser's {@code persistAndFlush()} then trips the unique key. We swallow only that
     * specific duplicate-key {@link PersistenceException} and retry the unit of work <b>once</b>
     * in a fresh transaction (via {@link #self}); its new snapshot sees the winning row and takes
     * the update path. A second consecutive failure is left to propagate — the flushed violation
     * is an unchecked {@link PersistenceException}, mapped to a clean 409 by
     * {@code DatabaseConstraintViolationExceptionMapper}, never a 500.
     */
    public void create(@Valid UserCareerLevel careerLevel) {
        if (careerLevel.getUuid() == null || careerLevel.getUuid().isEmpty()) return;

        // Validate once, before entering the retryable transactional unit. InconsistantDataException
        // maps to a clean 400 with the message preserved (an IllegalArgumentException here would fall
        // through to GenericExceptionMapper as a 500).
        if (careerLevel.getCareerLevel().getTrack() != null &&
                !careerLevel.getCareerLevel().getTrack().equals(careerLevel.getCareerTrack())) {
            throw new InconsistantDataException(
                    "Career level " + careerLevel.getCareerLevel() +
                    " does not belong to track " + careerLevel.getCareerTrack() +
                    " (expected " + careerLevel.getCareerLevel().getTrack() + ")");
        }

        try {
            self.createInTx(careerLevel);
        } catch (PersistenceException e) {
            if (!isDuplicateCareerLevelKeyViolation(e)) {
                throw e;
            }
            if (findByUuid(careerLevel.getUuid()).isPresent()) {
                // Deterministic update-collision: the caller's row exists and its new active_from
                // is already owned by another row of the same user. A retry would fail identically —
                // let the violation surface as a 409 immediately.
                throw e;
            }
            log.warnf("Concurrent duplicate career level insert (useruuid=%s, activeFrom=%s) — reconciling idempotently in a fresh transaction",
                    careerLevel.getUseruuid(), careerLevel.getActiveFrom());
            self.createInTx(careerLevel);
        }
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void createInTx(UserCareerLevel careerLevel) {
        // By-uuid first (a caller that really holds the row's uuid), then the natural key behind
        // uq_career_level_user_date — the BFF mints a fresh uuid per save, so a same-day re-save
        // only ever matches on (useruuid, active_from).
        Optional<UserCareerLevel> existing = findByUuid(careerLevel.getUuid())
                .or(() -> findByUserAndDate(careerLevel.getUseruuid(), careerLevel.getActiveFrom()));
        existing.ifPresentOrElse(cl -> {
            log.info("CareerLevelService.create -> updating career level " + cl.getUuid());
            bulkUpdate(cl.getUuid(), careerLevel);
        }, () -> {
            log.info("CareerLevelService.create -> creating career level");
            // persistAndFlush (not persist) so a concurrent-insert race trips the unique key HERE,
            // at flush, as an unchecked PersistenceException the orchestrator can catch and retry —
            // instead of a deferred commit-time RollbackException that Arc rewraps as
            // ArcUndeclaredThrowableException and GenericExceptionMapper turns into a 500.
            persistNew(careerLevel);
        });
    }

    /**
     * Panache primary-key lookup. Package-private seam so the reconcile decision in
     * {@link #createInTx(UserCareerLevel)} can be unit-tested without a live database.
     */
    Optional<UserCareerLevel> findByUuid(String uuid) {
        return UserCareerLevel.findByIdOptional(uuid);
    }

    /**
     * Panache lookup on the {@code uq_career_level_user_date} natural key (useruuid, active_from).
     * Package-private seam so {@link #createInTx(UserCareerLevel)} can be unit-tested without a
     * live database.
     */
    Optional<UserCareerLevel> findByUserAndDate(String useruuid, LocalDate activeFrom) {
        return UserCareerLevel.find("useruuid = ?1 AND activeFrom = ?2", useruuid, activeFrom)
                .firstResultOptional();
    }

    /**
     * Immediate JPQL update of the existing row with the incoming values; the incoming row's
     * fresh uuid is discarded so the (useruuid, active_from) tuple keeps exactly one row.
     *
     * <p>The loaded managed entity is deliberately <b>not</b> mutated: Hibernate 7 does not
     * auto-flush before JPQL DML, so a dirty managed entity would be flushed <i>again</i> at JTA
     * commit — where a lost race with a concurrent delete surfaces as a commit-time
     * StaleStateException that escapes as ArcUndeclaredThrowableException → 500 (the exact escape
     * this service exists to close). Executing the update in-method keeps a unique-key violation
     * (e.g. an update moving active_from onto another row's date) catchable as an unchecked
     * {@link PersistenceException} that maps to 409. Audit columns are set explicitly because a
     * bulk update bypasses AuditEntityListener. Package-private seam for unit tests.
     */
    void bulkUpdate(String uuid, UserCareerLevel incoming) {
        UserCareerLevel.update(
                "activeFrom = ?1, careerTrack = ?2, careerLevel = ?3, updatedAt = ?4, modifiedBy = ?5 WHERE uuid = ?6",
                incoming.getActiveFrom(), incoming.getCareerTrack(), incoming.getCareerLevel(),
                LocalDateTime.now(), currentUserUuid(), uuid);
    }

    /** Package-private seam for unit tests. */
    void persistNew(UserCareerLevel careerLevel) {
        careerLevel.persistAndFlush();
    }

    /** Mirrors AuditEntityListener's user resolution (X-Requested-By via RequestHeaderHolder). */
    private static String currentUserUuid() {
        try {
            String userUuid = CDI.current().select(RequestHeaderHolder.class).get().getUserUuid();
            return (userUuid == null || userUuid.isEmpty()) ? "system" : userUuid;
        } catch (Exception e) {
            return "system";
        }
    }

    /**
     * True only for a duplicate on the {@code uq_career_level_user_date} unique key. Scoped to that
     * one constraint so we retry a lost insert race but never loop on an unrelated violation
     * (e.g. a foreign key), which is left to surface as a 409.
     */
    private static boolean isDuplicateCareerLevelKeyViolation(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase().contains("uq_career_level_user_date")) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains("uq_career_level_user_date")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        log.info("CareerLevelService.delete: " + uuid);
        UserCareerLevel.deleteById(uuid);
    }
}
