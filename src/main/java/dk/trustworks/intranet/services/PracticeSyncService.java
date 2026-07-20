package dk.trustworks.intranet.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.model.TeamPracticeAssignment;
import dk.trustworks.intranet.model.UserPracticeHistory;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.PrimarySkillType;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sole writer of {@code user.practice} + {@code user.practice_uuid},
 * {@code user_practice_history} and (for team changes)
 * {@code team_practice_assignment} — Part 2 Phase 2 (spec §4.2). The V407/V424
 * trigger family is dropped by V426; from that migration on, every practice
 * transition flows through this service, which reproduces the triggers' exact
 * semantics (half-open intervals, null-safe change detection, same-day collapse,
 * code↔uuid twin maintenance) and adds provenance ({@code source},
 * {@code source_team_uuid}, {@code updated_by}).
 *
 * <p><b>Derivation rule:</b> a user's practice is the practice of the team where
 * they hold a <em>current</em> MEMBER role under the canonical temporal predicate
 * {@code startdate <= asOf AND (enddate IS NULL OR enddate > asOf)}.
 * LEADER/SPONSOR/GUEST roles never drive practice. Team without practice, or no
 * current MEMBER role → the {@code UD} sentinel (operational NULL is Phase 4) —
 * unless the user is team-less and manually assigned, in which case the manual
 * value stands (spec §4.2: derived when a current MEMBER team exists, manual
 * otherwise).
 *
 * <p><b>Effective-date rule (Phase 2 prescribed default):</b> a transition's
 * {@code effective_from} is the triggering effective date (membership startdate,
 * membership enddate, or team-change date), clamped to be ≥ the user's latest
 * history row's {@code effective_from} — half-open integrity is preserved and
 * true retroactive history rewrites are out of scope (a warning is logged when
 * clamping occurs). When the clamped date equals the open row's own
 * {@code effective_from}, the open row is updated in place (the triggers'
 * same-day collapse, generalized). Future-dated changes write nothing at event
 * time; the daily reconciliation tick materializes them on the day.
 *
 * <p><b>Event path vs tick:</b> the {@code TeamService} write hooks and the
 * team-practice endpoint apply transitions synchronously in the same transaction
 * (the spec's "event" is a direct service call); the daily
 * {@link #reconcileAll(LocalDate)} tick re-derives every user as the guarantee —
 * it materializes future-dated starts/ends and repairs out-of-band writes
 * (raw SQL updates no longer write history, by design). The tick is idempotent
 * and convergent: running twice, or on a draining ECS task, is harmless.
 *
 * <p>All decision logic is factored into static package-visible pure helpers
 * (Phase 0 house style) so it is unit-testable without a database.
 */
@JBossLog
@ApplicationScoped
public class PracticeSyncService {

    /** Provenance: transition derived from team membership / team practice change. */
    public static final String SOURCE_TEAM_SYNC = "TEAM_SYNC";

    /** Provenance: direct assignment on a team-less user (spec §4.2 override). */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** Sentinel "no practice" code — operational NULL arrives in Phase 4. */
    static final String NO_PRACTICE_CODE = "UD";

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // ── Event hooks (synchronous, join the caller's transaction) ──────────

    /**
     * Invoked by the {@code teamroles} write path after a MEMBER role was
     * created, replaced or deleted. Re-derives the user's practice as of
     * {@code asOf} and applies the transition if it changed.
     *
     * @param previousCurrentTeamUuid the team of the user's current MEMBER role
     *                                <em>before</em> the write (null if none) —
     *                                lets a deletion or role-type change that
     *                                leaves the user team-less derive to UD even
     *                                though no role row remains as evidence
     */
    @Transactional
    public void onMembershipChanged(String useruuid, String previousCurrentTeamUuid, LocalDate asOf) {
        reconcileUser(useruuid, asOf, previousCurrentTeamUuid, actor());
    }

    /**
     * Applies a team practice change (PUT /teams/{uuid}/practice): records the
     * transition in {@code team_practice_assignment}, updates the team's
     * denormalized {@code practice_code}/{@code practice_uuid}, and cascades a
     * re-derivation to every current MEMBER with {@code effective_from} = the
     * change date. {@code newPracticeCode} null/blank unsets the practice
     * (members derive to UD; no new assignment row is inserted).
     */
    @Transactional
    public Team applyTeamPracticeChange(String teamuuid, String newPracticeCode, LocalDate changeDate) {
        Team team = Team.findById(teamuuid);
        if (team == null) throw new NotFoundException("Team not found: " + teamuuid);

        String code = (newPracticeCode == null || newPracticeCode.isBlank()) ? null : newPracticeCode;
        Practice registryRow = code == null ? null : Practice.<Practice>find("code", code).firstResult();
        if (code != null && registryRow == null) {
            // The resource validates upstream; this guards direct service calls.
            throw new BadRequestException("Practice '" + code + "' is not in the registry");
        }
        String newUuid = registryRow == null ? null : registryRow.getUuid();

        if (Objects.equals(team.getPracticeCode(), code) && Objects.equals(team.getPracticeUuid(), newUuid)) {
            return team; // no-op — nothing written, no cascade
        }

        recordTeamPracticeAssignment(teamuuid, newUuid, changeDate);
        team.setPracticeCode(code);
        team.setPracticeUuid(newUuid);

        // Cascade: every current MEMBER (canonical predicate as of the change date)
        // transitions to the team's new practice — or UD when the practice is unset.
        String derivedCode = code == null ? NO_PRACTICE_CODE : code;
        String derivedUuid = code == null ? resolvePracticeUuid(NO_PRACTICE_CODE) : newUuid;
        String actor = actor();
        List<TeamRole> currentMembers = TeamRole.list(
                "teamuuid = ?1 and teammembertype = ?2 and startdate <= ?3 and (enddate is null or enddate > ?3)",
                teamuuid, TeamMemberType.MEMBER, changeDate);
        for (TeamRole member : currentMembers) {
            apply(member.getUseruuid(), derivedCode, derivedUuid, changeDate, changeDate,
                    SOURCE_TEAM_SYNC, teamuuid, actor);
        }
        return team;
    }

    /**
     * Direct (manual) practice assignment — permitted only for team-less users;
     * {@code UserService} enforces that rule before calling. A null practice is
     * stored as the {@code UD} sentinel this phase. Also writes the initial
     * history row on user creation (the retired insert trigger's job).
     */
    @Transactional
    public void applyManualPractice(String useruuid, PrimarySkillType practice, LocalDate effectiveFrom) {
        String code = practice == null ? NO_PRACTICE_CODE : practice.name();
        apply(useruuid, code, resolvePracticeUuid(code), effectiveFrom, effectiveFrom, SOURCE_MANUAL, null, actor());
    }

    // ── Daily reconciliation tick ─────────────────────────────────────────

    public record ReconcileSummary(int usersChecked, int usersChanged, int orphanHistoryRowsClosed, int skipped) {}

    /**
     * Re-derives every user's practice from their current MEMBER role as of
     * {@code asOf} and applies any differences through the same transition
     * writer as the event path ({@code updated_by} null, source
     * {@code TEAM_SYNC}). This is what materializes future-dated membership
     * starts/ends on their day — the event path is the optimization, the tick
     * is the guarantee. Also closes open history rows of users that no longer
     * exist (the retired delete trigger's job). No-op on a converged database.
     * <p>
     * Each user runs in its OWN transaction: a per-user catch inside one shared
     * transaction cannot isolate a flush-time failure (the session goes
     * rollback-only and the whole tick would be lost at commit) — one poisoned
     * row must cost exactly one user, never the run.
     */
    public ReconcileSummary reconcileAll(LocalDate asOf) {
        List<String> useruuids = QuarkusTransaction.requiringNew().call(
                () -> User.<User>listAll().stream().map(User::getUuid).toList());
        int changed = 0;
        int skipped = 0;
        for (String useruuid : useruuids) {
            try {
                if (QuarkusTransaction.requiringNew().call(
                        () -> reconcileUser(useruuid, asOf, null, null))) {
                    changed++;
                }
            } catch (RuntimeException e) {
                skipped++;
                log.errorf(e, "Practice reconciliation skipped user %s", useruuid);
            }
        }
        int orphansClosed;
        try {
            orphansClosed = QuarkusTransaction.requiringNew().call(() -> closeOrphanOpenHistoryRows(asOf));
        } catch (RuntimeException e) {
            orphansClosed = 0;
            log.error("Practice reconciliation: orphan-close pass failed", e);
        }
        ReconcileSummary summary = new ReconcileSummary(useruuids.size(), changed, orphansClosed, skipped);
        log.infof("Practice reconciliation tick asOf=%s: checked=%d changed=%d orphanHistoryRowsClosed=%d skipped=%d",
                asOf, summary.usersChecked(), summary.usersChanged(), summary.orphanHistoryRowsClosed(), summary.skipped());
        return summary;
    }

    // ── Shared derivation + transition core ───────────────────────────────

    /**
     * Re-derives one user's practice as of {@code asOf} and applies the
     * transition if it changed. Returns whether anything was written.
     * <p>
     * Locks the user row first: the retired triggers were serialized by the
     * {@code UPDATE user} row lock, and this replacement must be too — without
     * it a tick racing an event write for the same user could plan from a stale
     * snapshot and falsify or duplicate the half-open timeline. Every writer
     * converges on this lock ({@link #apply} takes it as well), so concurrent
     * transitions for one user execute strictly one after another.
     */
    private boolean reconcileUser(String useruuid, LocalDate asOf, String previousCurrentTeamUuid, String actor) {
        if (User.findById(useruuid, LockModeType.PESSIMISTIC_WRITE) == null) return false;
        List<TeamRole> memberRoles = TeamRole.list("useruuid = ?1 and teammembertype = ?2",
                useruuid, TeamMemberType.MEMBER);
        TeamRole current = currentMemberRole(memberRoles, asOf);

        if (current != null) {
            Team team = Team.findById(current.getTeamuuid());
            if (team == null) {
                log.warnf("Practice sync: user %s has a MEMBER role on unknown team %s — skipping",
                        useruuid, current.getTeamuuid());
                return false;
            }
            LocalDate roleStart = current.getStartdate();
            if (team.getPracticeCode() != null) {
                LocalDate assignmentStart = openAssignmentStart(team.getUuid());
                LocalDate since = latestOf(roleStart, assignmentStart);
                return apply(useruuid, team.getPracticeCode(), team.getPracticeUuid(),
                        since, asOf, SOURCE_TEAM_SYNC, team.getUuid(), actor);
            }
            LocalDate practiceLostOn = latestClosedAssignmentEnd(team.getUuid());
            LocalDate since = latestOf(roleStart, practiceLostOn);
            return apply(useruuid, NO_PRACTICE_CODE, resolvePracticeUuid(NO_PRACTICE_CODE),
                    since, asOf, SOURCE_TEAM_SYNC, team.getUuid(), actor);
        }

        // Team-less. A manual assignment stands (spec §4.2) — the value only
        // derives to UD when there is positive evidence that it is a stale
        // team-derived value: a membership that ended after the current history
        // period began, or (event path) a current membership that was just
        // deleted/re-typed, leaving no role row behind as evidence.
        LocalDate anchor = latestHistoryFrom(useruuid);
        TeamRole endedEvidence = staleDerivedEvidence(memberRoles, asOf, anchor);
        if (endedEvidence != null) {
            return apply(useruuid, NO_PRACTICE_CODE, resolvePracticeUuid(NO_PRACTICE_CODE),
                    endedEvidence.getEnddate(), asOf, SOURCE_TEAM_SYNC, endedEvidence.getTeamuuid(), actor);
        }
        if (previousCurrentTeamUuid != null) {
            LocalDate endedOn = latestEndedOn(memberRoles, previousCurrentTeamUuid, asOf);
            return apply(useruuid, NO_PRACTICE_CODE, resolvePracticeUuid(NO_PRACTICE_CODE),
                    endedOn != null ? endedOn : asOf, asOf, SOURCE_TEAM_SYNC, previousCurrentTeamUuid, actor);
        }
        return false;
    }

    /**
     * The single transition writer. Compares the target practice against the
     * user row and the open history row, and writes only what is stale:
     * unchanged practice → nothing (the triggers' change-detection preserved);
     * user row out of sync (e.g. raw SQL) with history intact → user row repaired
     * without a history transition; otherwise the history transition is written
     * per {@link #planTransition} and the user row follows. Returns whether
     * anything was written.
     */
    private boolean apply(String useruuid, String newCode, String newUuid,
                          LocalDate requestedFrom, LocalDate asOf,
                          String source, String sourceTeamUuid, String actor) {
        PrimarySkillType storable = parseStorable(newCode);
        if (storable == null) {
            // The user.practice column is enum-mapped until Phase 3 — a registry
            // code outside PrimarySkillType cannot be stored without breaking
            // every entity read. Documented Phase 2 limitation.
            log.errorf("Practice sync: code '%s' is not storable on user.practice (enum-mapped until Phase 3) — "
                    + "skipping transition for user %s", newCode, useruuid);
            return false;
        }
        if (requestedFrom == null || requestedFrom.isAfter(asOf)) {
            // Future-dated (or undated) triggers write nothing — the tick
            // materializes them on the day.
            if (requestedFrom != null) {
                log.debugf("Practice sync: future-dated transition for user %s (%s > %s) deferred to the tick",
                        useruuid, requestedFrom, asOf);
            }
            return false;
        }

        // Serialize all practice writers per user on the user row (see reconcileUser).
        User user = User.findById(useruuid, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            log.warnf("Practice sync: user %s not found — skipping", useruuid);
            return false;
        }
        String currentCode = user.getPractice() == null ? null : user.getPractice().name();
        boolean userRowStale = !Objects.equals(currentCode, newCode)
                || !Objects.equals(user.getPracticeUuid(), newUuid);

        HistoryState state = loadHistoryState(useruuid);
        TransitionPlan plan = planTransition(state, requestedFrom, newCode, newUuid);
        if (!userRowStale && plan.action() == TransitionAction.NOOP) return false;

        if (plan.clamped()) {
            log.warnf("Practice sync: effective date %s for user %s clamped to %s (latest history row wins; "
                            + "retroactive rewrites are out of scope)",
                    requestedFrom, useruuid, plan.effectiveFrom());
        }

        LocalDateTime now = LocalDateTime.now();
        switch (plan.action()) {
            case NOOP -> { /* history already correct — user row repaired below */ }
            case UPDATE_OPEN_ROW -> {
                UserPracticeHistory open = UserPracticeHistory.findById(state.openRowUuid());
                open.setPractice(newCode);
                open.setPracticeUuid(newUuid);
                open.setRecordedAt(now);
                open.setSource(source);
                open.setSourceTeamUuid(sourceTeamUuid);
                open.setUpdatedBy(actor);
            }
            case CLOSE_AND_INSERT -> {
                UserPracticeHistory open = UserPracticeHistory.findById(state.openRowUuid());
                open.setEffectiveTo(plan.effectiveFrom());
                open.setRecordedAt(now);
                insertOpenRow(useruuid, newCode, newUuid, plan.effectiveFrom(), now, source, sourceTeamUuid, actor);
            }
            case INSERT_OPEN_ROW ->
                    insertOpenRow(useruuid, newCode, newUuid, plan.effectiveFrom(), now, source, sourceTeamUuid, actor);
        }

        if (userRowStale) {
            // The row is already pessimistically locked and managed — write
            // through the entity so the persistence context stays consistent.
            user.setPractice(storable);
            user.setPracticeUuid(newUuid);
        }
        log.infof("Practice sync: user %s %s → %s (effective %s, source=%s, team=%s, by=%s, action=%s)",
                useruuid, currentCode, newCode, plan.effectiveFrom(), source, sourceTeamUuid, actor, plan.action());
        return true;
    }

    private void insertOpenRow(String useruuid, String code, String practiceUuid, LocalDate from,
                               LocalDateTime recordedAt, String source, String sourceTeamUuid, String actor) {
        UserPracticeHistory row = new UserPracticeHistory();
        row.setUuid(UUID.randomUUID().toString());
        row.setUseruuid(useruuid);
        row.setPractice(code);
        row.setPracticeUuid(practiceUuid);
        row.setEffectiveFrom(from);
        row.setEffectiveTo(null);
        row.setRecordedAt(recordedAt);
        row.setSource(source);
        row.setSourceTeamUuid(sourceTeamUuid);
        row.setUpdatedBy(actor);
        UserPracticeHistory.persist(row);
    }

    /**
     * Records a team practice transition in {@code team_practice_assignment}:
     * close the open row at the change date, insert the new open row (skipped
     * when unsetting). A change on the open row's own startdate collapses in
     * place, mirroring the history same-day rule.
     */
    private void recordTeamPracticeAssignment(String teamuuid, String newPracticeUuid, LocalDate changeDate) {
        TeamPracticeAssignment open = TeamPracticeAssignment
                .<TeamPracticeAssignment>find("teamUuid = ?1 and enddate is null", teamuuid).firstResult();
        if (open != null) {
            if (Objects.equals(open.getPracticeUuid(), newPracticeUuid)) return;
            if (open.getStartdate().equals(changeDate)) {
                if (newPracticeUuid == null) {
                    open.delete();
                } else {
                    open.setPracticeUuid(newPracticeUuid);
                }
                return;
            }
            open.setEnddate(changeDate);
        }
        if (newPracticeUuid != null) {
            TeamPracticeAssignment.persist(new TeamPracticeAssignment(
                    UUID.randomUUID().toString(), teamuuid, newPracticeUuid, changeDate, null));
        }
    }

    private int closeOrphanOpenHistoryRows(LocalDate asOf) {
        // The retired delete trigger closed a deleted user's open rows; the tick
        // now owns that. Same CHECK-safe date rule as the trigger (effective_to
        // must exceed effective_from).
        List<UserPracticeHistory> orphans = UserPracticeHistory.list(
                "effectiveTo is null and useruuid not in (select u.uuid from User u)");
        LocalDateTime now = LocalDateTime.now();
        for (UserPracticeHistory orphan : orphans) {
            orphan.setEffectiveTo(orphan.getEffectiveFrom().isBefore(asOf) ? asOf : orphan.getEffectiveFrom().plusDays(1));
            orphan.setRecordedAt(now);
        }
        return orphans.size();
    }

    // ── Lookups ───────────────────────────────────────────────────────────

    /** Registry uuid for a code, or null when the code has no registry row (trigger-mirror semantics). */
    public String resolvePracticeUuid(String code) {
        if (code == null) return null;
        Practice practice = Practice.<Practice>find("code", code).firstResult();
        return practice == null ? null : practice.getUuid();
    }

    /** The team of the user's current MEMBER role as of {@code asOf}, or null. */
    public String currentMemberTeamUuid(String useruuid, LocalDate asOf) {
        List<TeamRole> memberRoles = TeamRole.list("useruuid = ?1 and teammembertype = ?2",
                useruuid, TeamMemberType.MEMBER);
        TeamRole current = currentMemberRole(memberRoles, asOf);
        return current == null ? null : current.getTeamuuid();
    }

    private LocalDate openAssignmentStart(String teamuuid) {
        TeamPracticeAssignment open = TeamPracticeAssignment
                .<TeamPracticeAssignment>find("teamUuid = ?1 and enddate is null", teamuuid).firstResult();
        return open == null ? null : open.getStartdate();
    }

    private LocalDate latestClosedAssignmentEnd(String teamuuid) {
        TeamPracticeAssignment latest = TeamPracticeAssignment
                .<TeamPracticeAssignment>find("teamUuid = ?1 and enddate is not null order by enddate desc", teamuuid)
                .firstResult();
        return latest == null ? null : latest.getEnddate();
    }

    /** The user's latest history row's {@code effective_from} (the clamp anchor), or null when no history exists. */
    private LocalDate latestHistoryFrom(String useruuid) {
        UserPracticeHistory latest = UserPracticeHistory
                .<UserPracticeHistory>find("useruuid = ?1 order by effectiveFrom desc", useruuid).firstResult();
        return latest == null ? null : latest.getEffectiveFrom();
    }

    private HistoryState loadHistoryState(String useruuid) {
        UserPracticeHistory open = UserPracticeHistory
                .<UserPracticeHistory>find("useruuid = ?1 and effectiveTo is null order by effectiveFrom desc", useruuid)
                .firstResult();
        if (open != null) {
            return new HistoryState(open.getUuid(), open.getEffectiveFrom(), open.getPractice(),
                    open.getPracticeUuid(), null);
        }
        UserPracticeHistory latestClosed = UserPracticeHistory
                .<UserPracticeHistory>find("useruuid = ?1 and effectiveTo is not null order by effectiveTo desc", useruuid)
                .firstResult();
        return new HistoryState(null, null, null, null,
                latestClosed == null ? null : latestClosed.getEffectiveTo());
    }

    private String actor() {
        try {
            String actor = requestHeaderHolder.getUserUuid();
            return (actor == null || actor.isBlank()) ? null : actor;
        } catch (ContextNotActiveException e) {
            return null; // scheduled tick — no request context
        }
    }

    // ── Pure decision helpers (unit-tested without a database) ────────────

    /**
     * The user's current MEMBER role under the canonical temporal predicate
     * {@code startdate <= asOf AND (enddate IS NULL OR enddate > asOf)}
     * (spec §4.2, pinned). A null startdate is treated as the open past,
     * consistent with {@code DateUtils.periodsOverlap}. If the single-MEMBER
     * invariant is violated in the data, the latest-starting role wins.
     */
    static TeamRole currentMemberRole(List<TeamRole> memberRoles, LocalDate asOf) {
        TeamRole current = null;
        for (TeamRole role : memberRoles) {
            LocalDate start = role.getStartdate() == null ? LocalDate.MIN : role.getStartdate();
            if (start.isAfter(asOf)) continue;
            if (role.getEnddate() != null && !role.getEnddate().isAfter(asOf)) continue;
            if (current == null || start.isAfter(
                    current.getStartdate() == null ? LocalDate.MIN : current.getStartdate())) {
                current = role;
            }
        }
        return current;
    }

    /**
     * Positive evidence that a team-less user's practice is a stale
     * team-derived value rather than a manual assignment: a MEMBER role that
     * ended on or before {@code asOf} but after the current history period
     * began ({@code anchor}, null = no history → any ended role counts).
     * Returns the latest-ended such role, or null — in which case the current
     * value stands as manual (this is what keeps the tick from churning the
     * documented team-less users).
     */
    static TeamRole staleDerivedEvidence(List<TeamRole> memberRoles, LocalDate asOf, LocalDate anchor) {
        TeamRole latest = null;
        for (TeamRole role : memberRoles) {
            LocalDate end = role.getEnddate();
            if (end == null || end.isAfter(asOf)) continue;
            if (anchor != null && !end.isAfter(anchor)) continue;
            if (latest == null || end.isAfter(latest.getEnddate())) latest = role;
        }
        return latest;
    }

    /** The latest past-or-today enddate among the user's MEMBER roles on {@code teamuuid}, or null. */
    static LocalDate latestEndedOn(List<TeamRole> memberRoles, String teamuuid, LocalDate asOf) {
        LocalDate latest = null;
        for (TeamRole role : memberRoles) {
            if (!teamuuid.equals(role.getTeamuuid())) continue;
            LocalDate end = role.getEnddate();
            if (end == null || end.isAfter(asOf)) continue;
            if (latest == null || end.isAfter(latest)) latest = end;
        }
        return latest;
    }

    static LocalDate latestOf(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** {@code user.practice} is enum-mapped until Phase 3 — only enum codes are storable. */
    static PrimarySkillType parseStorable(String code) {
        if (code == null) return null;
        try {
            return PrimarySkillType.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    enum TransitionAction { NOOP, UPDATE_OPEN_ROW, CLOSE_AND_INSERT, INSERT_OPEN_ROW }

    /**
     * Snapshot of a user's history for the transition decision: the open row
     * (null {@code openRowUuid} when none exists) or, only when no open row
     * exists, the latest closed row's {@code effective_to}.
     */
    record HistoryState(String openRowUuid, LocalDate openFrom, String openPractice,
                        String openPracticeUuid, LocalDate latestClosedTo) {}

    record TransitionPlan(TransitionAction action, LocalDate effectiveFrom, boolean clamped) {}

    /**
     * Pure transition decision, reproducing the V407/V424 trigger semantics with
     * the Phase 2 effective-date clamp:
     * <ul>
     *   <li>open row already carries the target practice (code and uuid) → NOOP
     *       (the {@code <=>} change-detection guard);</li>
     *   <li>clamped effective date equals the open row's {@code effective_from}
     *       → update the open row in place (same-day collapse — also the only
     *       CHECK-safe action, since {@code effective_to > effective_from});</li>
     *   <li>otherwise → close the open row at the clamped date and insert the
     *       new open row;</li>
     *   <li>no open row → insert a new open row, clamped to the latest closed
     *       row's {@code effective_to} to keep the timeline collision-free.</li>
     * </ul>
     */
    static TransitionPlan planTransition(HistoryState state, LocalDate requestedFrom,
                                         String newCode, String newUuid) {
        if (state.openRowUuid() != null) {
            if (Objects.equals(state.openPractice(), newCode)
                    && Objects.equals(state.openPracticeUuid(), newUuid)) {
                return new TransitionPlan(TransitionAction.NOOP, state.openFrom(), false);
            }
            LocalDate effective = latestOf(requestedFrom, state.openFrom());
            boolean clamped = !effective.equals(requestedFrom);
            if (effective.equals(state.openFrom())) {
                return new TransitionPlan(TransitionAction.UPDATE_OPEN_ROW, effective, clamped);
            }
            return new TransitionPlan(TransitionAction.CLOSE_AND_INSERT, effective, clamped);
        }
        LocalDate effective = latestOf(requestedFrom, state.latestClosedTo());
        return new TransitionPlan(TransitionAction.INSERT_OPEN_ROW, effective,
                !effective.equals(requestedFrom));
    }
}
