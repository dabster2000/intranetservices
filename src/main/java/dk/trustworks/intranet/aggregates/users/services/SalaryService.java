package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateSalaryLogEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteSalaryEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateSalaryEvent;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.exception.ConstraintViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class SalaryService {

    /**
     * Self-proxy. Lets {@link #create(Salary)} re-enter the transactional unit of work
     * {@link #createInTx(Salary)} through the CDI/interceptor stack so the duplicate-key
     * retry runs in a <b>fresh</b> transaction (a plain {@code this.} call would bypass
     * {@code @Transactional} via self-invocation and reuse the rolled-back session).
     */
    @Inject
    SalaryService self;

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonEventDetector danlonEventDetector;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService danlonAssignmentService;

    public List<Salary> listAll(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    public Salary getUserSalaryByMonth(String useruuid, LocalDate month) {
        return Salary.<Salary>find("useruuid = ?1 and activefrom <= ?2 order by activefrom desc",
                useruuid, month).firstResultOptional().orElse(new Salary(month, 0, useruuid));
    }

    public boolean isSalaryChanged(String useruuid, LocalDate month) {
        return Salary.<Salary>find("activefrom = ?1 and useruuid = ?2", month, useruuid).firstResultOptional().isPresent();
    }

    /**
     * Idempotent save for POST /users/{useruuid}/salaries. The stored row is unique on
     * {@code uq_salary_user_date} = (useruuid, activefrom); the BFF only forwards the row's uuid
     * on the edit flow, so an "add" for a month that already has a row arrives with no/fresh uuid
     * while the (user, month) row already exists — the save must converge on "one salary per user
     * per effective month" rather than 409.
     *
     * <p>The natural-key lookup in {@link #createInTx(Salary)} turns the common (serialized)
     * re-save into an in-place update. Two genuinely concurrent requests can both pass that
     * lookup — each JTA transaction has its own MariaDB REPEATABLE READ snapshot — and the
     * loser's {@code persistAndFlush()} then trips the unique key. We swallow only that specific
     * duplicate-key {@link PersistenceException} and retry the unit of work <b>once</b> in a
     * fresh transaction (via {@link #self}); its new snapshot sees the winning row and takes the
     * update path. A second consecutive failure is left to propagate — the flushed violation is
     * an unchecked {@link PersistenceException}, mapped to a clean 409 by
     * {@code DatabaseConstraintViolationExceptionMapper}, never a 500.
     */
    public void create(@Valid Salary salary) {
        if (salary.getUuid() == null || salary.getUuid().isBlank()) {
            String generatedUuid = UUID.randomUUID().toString();
            log.infof("No salary UUID provided for user %s, generating new one: %s", salary.getUseruuid(), generatedUuid);
            salary.setUuid(generatedUuid);
        }
        try {
            self.createInTx(salary);
        } catch (PersistenceException e) {
            if (!isDuplicateSalaryKeyViolation(e)) {
                throw e;
            }
            if (findByUuid(salary.getUuid()).isPresent()) {
                // Deterministic update-collision: the caller's row exists and its new activefrom
                // is already owned by another row of the same user. A retry would fail identically —
                // let the violation surface as a 409 immediately.
                throw e;
            }
            log.warnf("Concurrent duplicate salary insert (useruuid=%s, activefrom=%s) — reconciling idempotently in a fresh transaction",
                    salary.getUseruuid(), salary.getActivefrom());
            self.createInTx(salary);
        }
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void createInTx(Salary salary) {
        // By-uuid first (the edit flow forwards the row's real uuid), then the natural key behind
        // uq_salary_user_date — the add flow arrives with a freshly minted uuid, so a same-month
        // re-save only ever matches on (useruuid, activefrom).
        Optional<Salary> existingSalary = findByUuid(salary.getUuid())
                .or(() -> findByUserAndDate(salary.getUseruuid(), salary.getActivefrom()));
        existingSalary.ifPresentOrElse(s -> {
            // UPDATE EXISTING SALARY RECORD
            log.debugf("Updating existing salary record for user %s (UUID: %s)", s.getUseruuid(), s.getUuid());

            // Capture old salary type before updating
            SalaryType oldType = s.getType();

            bulkUpdate(s.getUuid(), salary);

            // NEW BUSINESS RULE: Check for HOURLY → NORMAL transition
            SalaryType newType = salary.getType();
            if (oldType == SalaryType.HOURLY && newType == SalaryType.NORMAL) {
                log.infof("Detected salary type change HOURLY → NORMAL for user %s, effective %s (UPDATE path)",
                        s.getUseruuid(), salary.getActivefrom());
                handleSalaryTypeChange(s.getUseruuid(), salary.getActivefrom());
            }

            sendUpdateEvent(reconciled(s.getUuid(), salary));
        }, () -> {
            // CREATE NEW SALARY RECORD
            log.infof("Creating new salary record for user %s (UUID: %s, effective: %s, type: %s)",
                    salary.getUseruuid(), salary.getUuid(), salary.getActivefrom(), salary.getType());

            persistNew(salary);
            sendCreateEvent(salary);

            // FIX: Check for salary type change even when creating new record
            // This handles the case where a new NORMAL salary record is created instead of updating existing HOURLY record
            if (salary.getType() == SalaryType.NORMAL) {
                log.debugf("New salary is NORMAL type - checking if previous was HOURLY for user %s", salary.getUseruuid());

                // Look up previous salary for this user (month before new salary's effective date)
                Salary previousSalary = getUserSalaryByMonth(salary.getUseruuid(), salary.getActivefrom().minusMonths(1));

                if (previousSalary != null && previousSalary.getType() == SalaryType.HOURLY) {
                    log.infof("Detected salary type change HOURLY → NORMAL for user %s, effective %s (CREATE path, previous: %s)",
                            salary.getUseruuid(), salary.getActivefrom(), previousSalary.getActivefrom());
                    handleSalaryTypeChange(salary.getUseruuid(), salary.getActivefrom());
                } else {
                    log.debugf("Previous salary for user %s was not HOURLY (was: %s) - no Danløn generation",
                            salary.getUseruuid(), previousSalary != null ? previousSalary.getType() : "null");
                }
            }
        });
    }

    /**
     * Panache primary-key lookup. Package-private seam so the reconcile decision in
     * {@link #createInTx(Salary)} can be unit-tested without a live database.
     */
    Optional<Salary> findByUuid(String uuid) {
        return Salary.findByIdOptional(uuid);
    }

    /**
     * Panache lookup on the {@code uq_salary_user_date} natural key (useruuid, activefrom).
     * Package-private seam so {@link #createInTx(Salary)} can be unit-tested without a
     * live database.
     */
    Optional<Salary> findByUserAndDate(String useruuid, LocalDate activefrom) {
        return Salary.find("useruuid = ?1 AND activefrom = ?2", useruuid, activefrom)
                .firstResultOptional();
    }

    /**
     * Immediate JPQL update of the existing row with the incoming values; the incoming row's
     * fresh uuid is discarded so the (useruuid, activefrom) tuple keeps exactly one row. The
     * field set matches the historical update path ({@code internet} was never updated there
     * and stays untouched).
     *
     * <p>The loaded managed entity is deliberately <b>not</b> mutated: Hibernate 7 does not
     * auto-flush before JPQL DML, so a dirty managed entity would be flushed <i>again</i> at JTA
     * commit — where a lost race with a concurrent delete surfaces as a commit-time
     * StaleStateException that escapes as ArcUndeclaredThrowableException → 500. Executing the
     * update in-method keeps a unique-key violation (e.g. an update moving activefrom onto
     * another row's month) catchable as an unchecked {@link PersistenceException} that maps to
     * 409. Audit columns are set explicitly because a bulk update bypasses AuditEntityListener.
     * Package-private seam for unit tests.
     */
    void bulkUpdate(String uuid, Salary incoming) {
        Salary.update("salary = ?1, " +
                        "activefrom = ?2, " +
                        "type = ?3, " +
                        "lunch = ?4, " +
                        "phone = ?5, " +
                        "prayerDay = ?6, " +
                        "updatedAt = ?7, " +
                        "modifiedBy = ?8 " +
                        "WHERE uuid = ?9",
                incoming.getSalary(),
                incoming.getActivefrom(),
                incoming.getType(),
                incoming.isLunch(),
                incoming.isPhone(),
                incoming.isPrayerDay(),
                LocalDateTime.now(),
                currentUserUuid(),
                uuid);
    }

    /**
     * Package-private seam for unit tests.
     *
     * <p>persistAndFlush (not persist) so a constraint violation surfaces to the caller HERE, not
     * later inside handleSalaryTypeChange's best-effort try/catch (which would swallow the
     * business-save failure → false success), and so the retry orchestrator in
     * {@link #create(Salary)} can catch it as an unchecked {@link PersistenceException} instead
     * of a deferred commit-time 500.
     */
    void persistNew(Salary salary) {
        salary.persistAndFlush();
    }

    /**
     * Detached copy carrying the incoming values under the existing row's uuid — the payload for
     * the update event. The incoming entity keeps its fresh uuid untouched so the
     * deterministic-collision check in {@link #create(Salary)} still sees what the caller
     * actually sent.
     */
    private static Salary reconciled(String uuid, Salary incoming) {
        Salary copy = new Salary(uuid, incoming.getSalary(), incoming.getActivefrom(), incoming.getUseruuid());
        copy.setType(incoming.getType());
        copy.setLunch(incoming.isLunch());
        copy.setPhone(incoming.isPhone());
        copy.setInternet(incoming.isInternet());
        copy.setPrayerDay(incoming.isPrayerDay());
        return copy;
    }

    /** Package-private seam for unit tests. */
    void sendCreateEvent(Salary salary) {
        aggregateEventSender.handleEvent(new CreateSalaryLogEvent(salary.getUseruuid(), salary));
    }

    /** Package-private seam for unit tests. */
    void sendUpdateEvent(Salary salary) {
        aggregateEventSender.handleEvent(new UpdateSalaryEvent(salary.getUseruuid(), salary));
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
     * True only for a duplicate on the {@code uq_salary_user_date} unique key. Scoped to that
     * one constraint so we retry a lost insert race but never loop on an unrelated violation
     * (e.g. a foreign key), which is left to surface as a 409.
     */
    private static boolean isDuplicateSalaryKeyViolation(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase().contains("uq_salary_user_date")) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains("uq_salary_user_date")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String salaryuuid) {
        Optional<Salary> optionalSalary = Salary.findByIdOptional(salaryuuid);

        // Forward-only Danløn (spec §7): on deleting the NORMAL salary that backed a mint, raise a
        // CLOSE proposal for that month's OPEN system-minted row instead of hard-deleting it.
        optionalSalary.ifPresent(salary -> {
            if (salary.getType() == SalaryType.NORMAL) {
                LocalDate month = salary.getActivefrom().withDayOfMonth(1);
                UserDanlonHistory row = UserDanlonHistory.findRowForMonth(salary.getUseruuid(), month);
                if (row != null && !row.isClosed() && row.getEventType() != null) {
                    danlonAssignmentService.proposeClose(row.getUuid(),
                            "Triggering NORMAL salary for " + month + " was deleted");
                }
            }
        });

        Salary.deleteById(salaryuuid);
        optionalSalary.ifPresent(salary ->
                aggregateEventSender.handleEvent(new DeleteSalaryEvent(salary.getUseruuid(), salaryuuid)));
    }

    /**
     * REST-facing create/update: the salary record being written must belong to the user named
     * in the endpoint path. Without this, a caller authorized for one employee could overwrite
     * another employee's record by passing that record's uuid in the body (audit finding M1).
     * Responds 404 rather than 403 so the endpoint does not confirm that a guessed salary uuid
     * exists under another user.
     *
     * <p>Deliberately NOT {@code @Transactional}: the duplicate-key retry in
     * {@link #create(Salary)} needs each {@link #createInTx(Salary)} attempt to run in its own
     * transaction — a transaction opened here would be marked rollback-only by the first failed
     * attempt and doom the retry. The ownership guard is a plain read.
     */
    public void createForUser(String useruuid, Salary salary) {
        salary.setUseruuid(useruuid);
        if (salary.getUuid() != null && !salary.getUuid().isBlank()) {
            Salary existing = findByUuid(salary.getUuid()).orElse(null);
            if (existing != null && !useruuid.equals(existing.getUseruuid())) {
                throw new NotFoundException("Salary record not found for user");
            }
        }
        create(salary);
    }

    /** REST-facing delete: ownership guard mirroring {@link #createForUser(String, Salary)}. */
    @Transactional
    public void deleteForUser(String useruuid, String salaryuuid) {
        Salary existing = Salary.findById(salaryuuid);
        if (existing != null && !useruuid.equals(existing.getUseruuid())) {
            throw new NotFoundException("Salary record not found for user");
        }
        delete(salaryuuid);
    }

    public List<Salary> findByUseruuid(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    /**
     * Scoped variant (Phase 8, task 8.6): the resolved subject set is bound into
     * the WHERE clause — never applied as a post-filter. With the subject filter
     * in the query, {@code UserScopeResponseFilter} stays defence in depth with
     * nothing to strip (task 8.7).
     */
    public List<Salary> findByUseruuid(String useruuid, Set<String> permittedSubjects) {
        if (permittedSubjects == null || permittedSubjects.isEmpty()) {
            return List.of(); // fail closed — an empty reach never falls back to unscoped
        }
        return Salary.list("useruuid = ?1 and useruuid in ?2", useruuid, permittedSubjects);
    }

    /**
     * HOURLY → NORMAL transition (spec §6): delegate to the shared detector so the same
     * precedence/window applies whether the status or the salary write triggers it, and raise
     * at most one proposal — minting nothing (AC1). Never rolls back the salary save (N5):
     * detection runs in the caller's transaction (so the just-written NORMAL salary is visible),
     * and only the proposal write is isolated in a nested transaction; the reconciliation scan
     * (AC10) re-derives and re-raises if the write fails. Package-private seam for unit tests.
     */
    void handleSalaryTypeChange(String useruuid, LocalDate effectiveDate) {
        LocalDate month = effectiveDate.withDayOfMonth(1);
        UserStatus status = UserStatus.<UserStatus>find(
                "useruuid = ?1 and statusdate <= ?2 and status not in (?3, ?4) order by statusdate desc",
                useruuid, month.plusMonths(1).minusDays(1), StatusType.TERMINATED, StatusType.PREBOARDING)
                .firstResult();
        if (status == null || status.getCompany() == null) {
            log.infof("No qualifying status/company for user %s month %s — no Danløn proposal raised", useruuid, month);
            return;
        }
        String companyUuid = status.getCompany().getUuid();
        // Best-effort: detect in this (caller) transaction so the just-written NORMAL salary is
        // visible, isolate the WRITE in requiringNew, and wrap the whole thing so neither a
        // detection-read glitch nor a propose failure rolls back the salary save (N5). Reconciliation
        // (AC10) re-derives and re-raises on its next run.
        try {
            var event = danlonEventDetector.detectMostSpecific(useruuid, month, companyUuid);
            if (event.isEmpty()) return;
            QuarkusTransaction.requiringNew().run(() ->
                    danlonAssignmentService.proposeIfNeeded(useruuid, month, event.get(), companyUuid));
        } catch (RuntimeException e) {
            log.warnf(e, "Danløn detect/propose failed for user %s month %s — salary save best-effort; reconciliation (AC10) will retry",
                    useruuid, month);
        }
    }
}
