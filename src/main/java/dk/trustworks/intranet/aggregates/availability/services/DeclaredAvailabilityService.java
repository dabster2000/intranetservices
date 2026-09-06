package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.config.DeclaredAvailabilityPolicy;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityCopyRequest;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityDTO;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityRangeDTO;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityUpsertRequest;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability;
import dk.trustworks.intranet.aggregates.availability.model.UserDeclaredAvailability.Source;
import dk.trustworks.intranet.bi.services.UserDayRecalculationService;
import dk.trustworks.intranet.dao.workservice.services.MonthSubmissionService;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Declared availability (JK Team 2.0 WP1, spec §4.1): reads, bulk upsert, delete and
 * copy-forward, with the month lock, cache invalidation and the targeted recalculation
 * that follows every write.
 *
 * <p>Row-level authorization is the resource's job (self intrinsic, {@code TEAM} reach for
 * a lead); this service trusts its caller on that and owns everything else.
 *
 * <p><b>Recalculation.</b> After a write commits, the affected days go through
 * {@link UserDayRecalculationService} — the same four-step pipeline the nightly
 * {@code budget-aggregation} job runs — on a worker thread, so the junior sees Staffing
 * and the timesheet move within seconds rather than overnight. Only in {@code LIVE} mode:
 * in {@code SHADOW} the resolver ignores the table, so recalculating would be pure cost.
 */
@JBossLog
@ApplicationScoped
public class DeclaredAvailabilityService {

    @Inject
    MonthSubmissionService monthSubmissionService;

    @Inject
    DeclaredAvailabilityPolicy policy;

    @Inject
    UserDayRecalculationService recalculationService;

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

    @Inject
    ManagedExecutor managedExecutor;

    // ── reads ───────────────────────────────────────────────────────────────

    public DeclaredAvailabilityRangeDTO range(String useruuid, LocalDate from, LocalDate to) {
        List<DeclaredAvailabilityDTO> rows = UserDeclaredAvailability.findForRange(useruuid, from, to).stream()
                .map(DeclaredAvailabilityDTO::from)
                .toList();
        List<String> lockedMonths = new ArrayList<>();
        YearMonth month = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!month.isAfter(last)) {
            if (monthSubmissionService.isMonthLocked(useruuid, month.getYear(), month.getMonthValue())) {
                lockedMonths.add(month.toString());
            }
            month = month.plusMonths(1);
        }
        LocalDate declaredThrough = UserDeclaredAvailability.findHorizon(useruuid, LocalDate.now()).orElse(null);
        return new DeclaredAvailabilityRangeDTO(useruuid, from, to, rows, lockedMonths, declaredThrough,
                policy.mode().name(), policy.floorDate());
    }

    /** Hours per declared day in {@code [from, to]}; days without a row are absent. */
    public Map<LocalDate, BigDecimal> findForRange(String useruuid, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        for (UserDeclaredAvailability row : UserDeclaredAvailability.findForRange(useruuid, from, to)) {
            byDay.put(row.getDay(), row.getHours());
        }
        return byDay;
    }

    public Optional<BigDecimal> findForDay(String useruuid, LocalDate day) {
        return UserDeclaredAvailability.findForDay(useruuid, day).map(UserDeclaredAvailability::getHours);
    }

    // ── writes ──────────────────────────────────────────────────────────────

    /**
     * Idempotent bulk upsert — all rows or none. Validation runs first so a single bad item
     * refuses the whole batch before anything is written; the transaction is the second belt.
     */
    @Transactional
    @CacheInvalidateAll(cacheName = "employee-availability")
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public List<DeclaredAvailabilityDTO> upsert(String useruuid, List<DeclaredAvailabilityUpsertRequest> items,
                                                Source source, String actorUuid) {
        List<String> problems = DeclaredAvailabilityValidator.validateUpsert(items);
        if (!problems.isEmpty()) {
            throw new WebApplicationException(String.join("; ", problems), Response.Status.BAD_REQUEST);
        }
        Set<LocalDate> days = new LinkedHashSet<>();
        for (DeclaredAvailabilityUpsertRequest item : items) {
            days.add(item.day());
        }
        requireUnlocked(useruuid, days);

        LocalDate min = days.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate max = days.stream().max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, UserDeclaredAvailability> existing = UserDeclaredAvailability.mapForRange(useruuid, min, max);

        LocalDateTime now = LocalDateTime.now();
        List<DeclaredAvailabilityDTO> saved = new ArrayList<>(items.size());
        int cleared = 0;
        for (DeclaredAvailabilityUpsertRequest item : items) {
            UserDeclaredAvailability row = existing.get(item.day());
            if (item.hours() == null) {
                // A clear: the day goes back to "no plan". Absent rows are a no-op, so the
                // batch stays idempotent.
                if (row != null) {
                    row.delete();
                    cleared++;
                }
                continue;
            }
            if (row == null) {
                row = new UserDeclaredAvailability();
                row.setUuid(UUID.randomUUID().toString());
                row.setUseruuid(useruuid);
                row.setDay(item.day());
                row.setCreatedAt(now);
                row.persist();
            }
            row.setHours(item.hours().setScale(2, java.math.RoundingMode.HALF_UP));
            row.setNote(item.note() == null || item.note().isBlank() ? null : item.note().trim());
            row.setSource(source);
            row.setUpdatedAt(now);
            row.setUpdatedBy(actorUuid);
            saved.add(DeclaredAvailabilityDTO.from(row));
        }
        log.infof("Declared availability upsert: user=%s days=%d cleared=%d source=%s actor=%s",
                useruuid, saved.size(), cleared, source, actorUuid);
        recalculateAfterCommit(useruuid, days);
        return saved;
    }

    /** Removes one declaration; the resolver then treats the day as undeclared (0 h when live). */
    @Transactional
    @CacheInvalidateAll(cacheName = "employee-availability")
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public boolean delete(String useruuid, LocalDate day, String actorUuid) {
        requireUnlocked(useruuid, List.of(day));
        Optional<UserDeclaredAvailability> row = UserDeclaredAvailability.findForDay(useruuid, day);
        if (row.isEmpty()) {
            return false;
        }
        row.get().delete();
        log.infof("Declared availability deleted: user=%s day=%s actor=%s", useruuid, day, actorUuid);
        recalculateAfterCommit(useruuid, List.of(day));
        return true;
    }

    /**
     * Copy one week forward onto N target weeks (spec §3 "kopiér"). Each target week ends up
     * mirroring the source: copied days get {@code source = SYSTEM}; target days the source
     * does not declare are removed. All-or-nothing across every target.
     *
     * @return the rows of every target week after the copy, oldest first
     */
    @Transactional
    @CacheInvalidateAll(cacheName = "employee-availability")
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public List<DeclaredAvailabilityDTO> copyForward(String useruuid, DeclaredAvailabilityCopyRequest request,
                                                     String actorUuid) {
        List<String> problems = DeclaredAvailabilityValidator.validateCopy(request);
        if (!problems.isEmpty()) {
            throw new WebApplicationException(String.join("; ", problems), Response.Status.BAD_REQUEST);
        }
        LocalDate sourceStart = request.sourceWeekStart();
        Map<LocalDate, UserDeclaredAvailability> sourceWeek =
                UserDeclaredAvailability.mapForRange(useruuid, sourceStart, sourceStart.plusDays(6));

        Set<LocalDate> touched = new LinkedHashSet<>();
        for (LocalDate target : request.targetWeekStarts()) {
            for (int offset = 0; offset < 7; offset++) {
                touched.add(target.plusDays(offset));
            }
        }
        requireUnlocked(useruuid, touched);

        LocalDateTime now = LocalDateTime.now();
        List<DeclaredAvailabilityDTO> result = new ArrayList<>();
        for (LocalDate target : request.targetWeekStarts()) {
            Map<LocalDate, UserDeclaredAvailability> targetWeek =
                    UserDeclaredAvailability.mapForRange(useruuid, target, target.plusDays(6));
            for (int offset = 0; offset < 7; offset++) {
                LocalDate sourceDay = sourceStart.plusDays(offset);
                LocalDate targetDay = target.plusDays(offset);
                UserDeclaredAvailability src = sourceWeek.get(sourceDay);
                UserDeclaredAvailability dst = targetWeek.get(targetDay);
                if (src == null) {
                    if (dst != null) {
                        dst.delete();
                    }
                    continue;
                }
                if (dst == null) {
                    dst = new UserDeclaredAvailability();
                    dst.setUuid(UUID.randomUUID().toString());
                    dst.setUseruuid(useruuid);
                    dst.setDay(targetDay);
                    dst.setCreatedAt(now);
                    dst.persist();
                }
                dst.setHours(src.getHours());
                dst.setNote(src.getNote());
                dst.setSource(Source.SYSTEM);
                dst.setUpdatedAt(now);
                dst.setUpdatedBy(actorUuid);
                result.add(DeclaredAvailabilityDTO.from(dst));
            }
        }
        log.infof("Declared availability copy-forward: user=%s source=%s targets=%d actor=%s",
                useruuid, sourceStart, request.targetWeekStarts().size(), actorUuid);
        recalculateAfterCommit(useruuid, touched);
        return result;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * The timesheet's month-submission lock, applied to declarations too (spec §4.1.3): a
     * submitted month is closed for planning as well as for hours. Structured 409, the same
     * status {@code WorkResource.save} answers with — never a silent no-op.
     */
    void requireUnlocked(String useruuid, Collection<LocalDate> days) {
        Set<YearMonth> months = new LinkedHashSet<>();
        for (LocalDate day : days) {
            months.add(YearMonth.from(day));
        }
        for (YearMonth month : months) {
            if (monthSubmissionService.isMonthLocked(useruuid, month.getYear(), month.getMonthValue())) {
                throw new WebApplicationException(
                        month.getMonth() + " " + month.getYear()
                                + " is submitted. Request an unlock to change declared availability.",
                        Response.Status.CONFLICT);
            }
        }
    }

    /**
     * Schedules the affected days through the shared per-day pipeline once the surrounding
     * transaction has committed. In {@code SHADOW} mode nothing is scheduled: the resolver
     * does not read the table, so the facts would come out identical.
     */
    void recalculateAfterCommit(String useruuid, Collection<LocalDate> days) {
        if (!policy.isLive() || days.isEmpty()) {
            return;
        }
        List<LocalDate> snapshot = List.copyOf(days);
        Runnable work = () -> {
            try {
                recalculationService.recalculateDays(useruuid, snapshot);
            } catch (Exception e) {
                log.errorf(e, "Declared availability recalculation failed user=%s days=%d", useruuid, snapshot.size());
            }
        };
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        managedExecutor.execute(work);
                    }
                }
            });
        } catch (Exception e) {
            // No active transaction (should not happen — every caller is @Transactional):
            // run right away rather than drop the refresh.
            managedExecutor.execute(work);
        }
    }
}
