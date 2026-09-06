package dk.trustworks.intranet.aggregates.internalassignment.services;

import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentDTO;
import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentRequest;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;
import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment.Status;
import dk.trustworks.intranet.aggregates.internalassignment.services.InternalAssignmentValidator.Sizing;
import dk.trustworks.intranet.bi.services.UserDayRecalculationService;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.ScopeResolution;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Internal assignments (JK Team 2.0 WP3, spec §4.3): the junior creates a DRAFT, a team
 * lead approves it — never the junior themselves (funktionsadskillelse, the precedent set by
 * {@code CompetenceDecisionService}) — and only APPROVED rows emit demand.
 *
 * <p>Every state change that can alter demand (approve, reject, delete, an edit of an
 * approved row) re-runs the affected weekdays through the shared per-day pipeline after the
 * transaction commits, so Staffing moves within seconds rather than overnight. Independent
 * of the declared-availability mode: internal demand is clamped against net availability
 * whatever that resolves to.
 */
@JBossLog
@ApplicationScoped
public class InternalAssignmentService {

    /** Approval and rejection are {@code teams:write} with reach over the assignee. */
    public static final String PERMISSION_DECIDE = "teams:write";

    @Inject
    AuthorizationService authorizationService;

    @Inject
    UserDayRecalculationService recalculationService;

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

    @Inject
    ManagedExecutor managedExecutor;

    // ── reads ───────────────────────────────────────────────────────────────

    public List<InternalAssignmentDTO> listForUser(String useruuid, LocalDate from, LocalDate to) {
        return InternalAssignment.findOverlapping(useruuid, from, to).stream()
                .map(InternalAssignmentDTO::from).toList();
    }

    /** The approval queue as one actor sees it: DRAFT rows within their {@code teams:write} reach. */
    public List<InternalAssignmentDTO> pendingFor(String actorUuid) {
        ScopeResolution reach = reachOf(actorUuid);
        List<InternalAssignment> rows = reach.unbounded()
                ? InternalAssignment.findPending()
                : InternalAssignment.findPendingFor(reach.subjects());
        return rows.stream().map(InternalAssignmentDTO::from).toList();
    }

    public InternalAssignment require(String uuid) {
        InternalAssignment row = InternalAssignment.findById(uuid);
        if (row == null) {
            throw new WebApplicationException("No such internal assignment", Response.Status.NOT_FOUND);
        }
        return row;
    }

    // ── writes ──────────────────────────────────────────────────────────────

    @Transactional
    public InternalAssignmentDTO create(String useruuid, InternalAssignmentRequest request, String actorUuid) {
        requireValid(request);
        Sizing sizing = InternalAssignmentValidator.sizingOf(request);
        LocalDateTime now = LocalDateTime.now();
        InternalAssignment row = new InternalAssignment();
        row.setUuid(UUID.randomUUID().toString());
        row.setUseruuid(useruuid);
        apply(row, request, sizing);
        row.setStatus(Status.DRAFT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actorUuid);
        row.persist();
        log.infof("Internal assignment created: uuid=%s user=%s actor=%s", row.getUuid(), useruuid, actorUuid);
        return InternalAssignmentDTO.from(row);
    }

    /**
     * Edits a DRAFT or APPROVED row. Editing an approved row withdraws the approval — it
     * becomes a DRAFT again and its demand is retracted — because the lead approved the
     * previous shape, not this one. REJECTED rows are immutable.
     */
    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public InternalAssignmentDTO update(InternalAssignment row, InternalAssignmentRequest request, String actorUuid) {
        if (row.getStatus() == Status.REJECTED) {
            throw new WebApplicationException("A rejected assignment cannot be edited", Response.Status.CONFLICT);
        }
        requireValid(request);
        Sizing sizing = InternalAssignmentValidator.sizingOf(request);
        Set<LocalDate> affected = new LinkedHashSet<>();
        boolean wasApproved = row.getStatus() == Status.APPROVED;
        if (wasApproved) {
            affected.addAll(weekdays(row.getActiveFrom(), row.getActiveTo()));
        }
        apply(row, request, sizing);
        if (wasApproved) {
            row.setStatus(Status.DRAFT);
            row.setApprovedBy(null);
            row.setApprovedAt(null);
            affected.addAll(weekdays(row.getActiveFrom(), row.getActiveTo()));
        }
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedBy(actorUuid);
        log.infof("Internal assignment updated: uuid=%s user=%s actor=%s approvalWithdrawn=%s",
                row.getUuid(), row.getUseruuid(), actorUuid, wasApproved);
        recalculateAfterCommit(row.getUseruuid(), affected);
        return InternalAssignmentDTO.from(row);
    }

    /** {@code teams:write} with reach over the assignee, never the assignee themselves. */
    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public InternalAssignmentDTO approve(InternalAssignment row, String actorUuid) {
        String refusal = decide(actorUuid, row.getUseruuid(), reachOf(actorUuid).permits(row.getUseruuid()),
                row.getStatus(), Status.APPROVED);
        if (refusal != null) {
            throw new WebApplicationException(refusal, Response.Status.FORBIDDEN);
        }
        LocalDateTime now = LocalDateTime.now();
        row.setStatus(Status.APPROVED);
        row.setApprovedBy(actorUuid);
        row.setApprovedAt(now);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actorUuid);
        log.infof("Internal assignment approved: uuid=%s user=%s by=%s", row.getUuid(), row.getUseruuid(), actorUuid);
        recalculateAfterCommit(row.getUseruuid(), weekdays(row.getActiveFrom(), row.getActiveTo()));
        return InternalAssignmentDTO.from(row);
    }

    /** Same gate as {@link #approve}; retracts demand when the row was approved. */
    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public InternalAssignmentDTO reject(InternalAssignment row, String actorUuid, String note) {
        String refusal = decide(actorUuid, row.getUseruuid(), reachOf(actorUuid).permits(row.getUseruuid()),
                row.getStatus(), Status.REJECTED);
        if (refusal != null) {
            throw new WebApplicationException(refusal, Response.Status.FORBIDDEN);
        }
        boolean wasApproved = row.getStatus() == Status.APPROVED;
        row.setStatus(Status.REJECTED);
        row.setApprovedBy(null);
        row.setApprovedAt(null);
        if (note != null && !note.isBlank()) {
            String existing = row.getNotes() == null ? "" : row.getNotes() + "\n";
            row.setNotes(existing + "Rejected: " + note.trim());
        }
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedBy(actorUuid);
        log.infof("Internal assignment rejected: uuid=%s user=%s by=%s", row.getUuid(), row.getUseruuid(), actorUuid);
        if (wasApproved) {
            recalculateAfterCommit(row.getUseruuid(), weekdays(row.getActiveFrom(), row.getActiveTo()));
        }
        return InternalAssignmentDTO.from(row);
    }

    /** DRAFT rows only (spec §4.3.4); an approved row is withdrawn through {@link #reject}. */
    @Transactional
    public void delete(InternalAssignment row, String actorUuid) {
        if (row.getStatus() != Status.DRAFT) {
            throw new WebApplicationException("Only a draft can be deleted — reject an approved assignment instead",
                    Response.Status.CONFLICT);
        }
        row.delete();
        log.infof("Internal assignment deleted: uuid=%s user=%s actor=%s", row.getUuid(), row.getUseruuid(), actorUuid);
    }

    // ── rules ───────────────────────────────────────────────────────────────

    /**
     * Pure decision gate for approve / reject: {@code null} when allowed, else the refusal.
     * Reach before self on purpose — a lead outside the team learns nothing about whose row it is.
     */
    public static String decide(String actorUuid, String subjectUuid, boolean withinReach,
                                Status current, Status target) {
        if (actorUuid == null || actorUuid.isBlank()) {
            return "X-Requested-By is required — decisions always act for a named user";
        }
        if (!withinReach) {
            return "Outside your data scope";
        }
        if (actorUuid.equalsIgnoreCase(subjectUuid)) {
            return "You cannot " + (target == Status.APPROVED ? "approve" : "reject") + " your own assignment";
        }
        if (current == target) {
            return "The assignment is already " + target.name().toLowerCase();
        }
        if (target == Status.APPROVED && current == Status.REJECTED) {
            return "A rejected assignment cannot be approved — ask the junior to create it again";
        }
        return null;
    }

    /** Weekdays in {@code [from, to]}, the days whose facts an approval decision can change. */
    static List<LocalDate> weekdays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if (from == null || to == null) return out;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) out.add(d);
        }
        return out;
    }

    private ScopeResolution reachOf(String actorUuid) {
        if (actorUuid == null || actorUuid.isBlank()) {
            return ScopeResolution.none();
        }
        return authorizationService.resolveReach(actorUuid, PERMISSION_DECIDE, LocalDate.now(), Set.of());
    }

    private static void requireValid(InternalAssignmentRequest request) {
        List<String> problems = InternalAssignmentValidator.validate(request);
        if (!problems.isEmpty()) {
            throw new WebApplicationException(String.join("; ", problems), Response.Status.BAD_REQUEST);
        }
    }

    private static void apply(InternalAssignment row, InternalAssignmentRequest request, Sizing sizing) {
        row.setTitle(request.title().trim());
        row.setSponsorUseruuid(blankToNull(request.sponsorUseruuid()));
        row.setActiveFrom(request.activeFrom());
        row.setActiveTo(request.activeTo());
        row.setHoursPerWeek(sizing.hoursPerWeek());
        row.setEstimatedTotalHours(sizing.estimatedTotalHours());
        row.setStrategic(Boolean.TRUE.equals(request.strategic()));
        row.setNotes(blankToNull(request.notes()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void recalculateAfterCommit(String useruuid, java.util.Collection<LocalDate> days) {
        if (days == null || days.isEmpty()) {
            return;
        }
        List<LocalDate> snapshot = List.copyOf(days);
        Runnable work = () -> {
            try {
                recalculationService.recalculateDays(useruuid, snapshot);
            } catch (Exception e) {
                log.errorf(e, "Internal assignment recalculation failed user=%s days=%d", useruuid, snapshot.size());
            }
        };
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == jakarta.transaction.Status.STATUS_COMMITTED) {
                        managedExecutor.execute(work);
                    }
                }
            });
        } catch (Exception e) {
            managedExecutor.execute(work);
        }
    }
}
