package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttemptDecision;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.DecisionType;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeResolution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The approval queue and the append-only decision ledger (spec §5.10, §6.2, §10.1, §10.4).
 *
 * <p>Three properties carry the weight here.
 *
 * <ol>
 *   <li><strong>Reach is resolved server-side and fails closed.</strong> The queue is other
 *       people's training records — personal data about employees — so it is gated by
 *       {@code competence:approve} <em>and</em> narrowed per row by
 *       {@link AuthorizationService#resolveReach}. An unresolvable reach yields an empty
 *       queue and a per-item refusal, never the whole company (§3.3, §10.1). A TEAM-scoped
 *       team lead cannot widen their view by crafting a request; there is no useruuid
 *       parameter to tamper with in the first place.</li>
 *   <li><strong>A leader may not approve their own attempt.</strong> This is the module's own
 *       <em>funktionsadskillelse</em> rule applied to itself, and an auditor will check it —
 *       it is one of the seven points the module teaches, so failing it would discredit the
 *       content. See {@link #decide}.</li>
 *   <li><strong>Nothing is ever edited.</strong> {@code competence_attempt_decision} is
 *       append-only at the database level (UPDATE and DELETE both SIGNAL), so correcting a
 *       decision means appending a REVOKED row. The effective state of an attempt is its
 *       latest decision, or PENDING when there is none.</li>
 * </ol>
 *
 * <p>Approving while impersonating is refused outright, like every other evidence write in
 * this module (§10.4). An approval carrying the wrong actor uuid is worse than no approval:
 * the whole point of the ledger is that it names who attested.
 */
@JBossLog
@ApplicationScoped
public class CompetenceDecisionService {

    /** The permission whose data scope narrows the queue. Reads and writes share it. */
    public static final String PERMISSION_APPROVE = "competence:approve";

    /** Matches {@code competence_attempt_decision.note VARCHAR(1000)}. */
    static final int NOTE_MAX_LENGTH = 1000;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    CompetenceStatusService statusService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * One row of the approval queue.
     *
     * <p>The requirement travels with the attempt because the attempt row denormalises only
     * {@code kref} and the version label — the queue has to name the krav, and resolving it
     * per row would be an N+1 over a list that is read on every page load.
     *
     * @param requirement may be {@code null} only if the requirement row vanished under an
     *                    attempt, which the FK prevents; the resource should still not
     *                    dereference it blindly
     */
    public record PendingApproval(CompetenceAttempt attempt, CompetenceRequirement requirement) {
    }

    /**
     * The per-item verdict of a bulk decision. Bulk approve is partial-success by design
     * (§6.2): one self-approval or one row that drifted out of reach since the view was
     * rendered must not discard the rest of the batch.
     */
    public record DecisionOutcome(String attemptUuid, boolean ok, String message) {
    }

    // -----------------------------------------------------------------------
    // queue
    // -----------------------------------------------------------------------

    /**
     * Passed, non-abandoned, submitted attempts with no decision yet, oldest first, within
     * the actor's reach.
     *
     * <p>"No decision yet" means literally that — an attempt whose latest decision is
     * REVOKED does <em>not</em> return to the queue. Revocation moves the requirement back
     * to "Not passed" for the employee, and the way back to green is a new attempt, not a
     * second bite at the same one.
     *
     * <p>The actor's own passed attempt is deliberately left in the list rather than hidden:
     * it belongs in somebody's queue, just not one where this actor can act on it, and the
     * per-item refusal in {@link #decide} is the control. Hiding it would make the rule
     * invisible instead of enforced.
     *
     * @return oldest first, or an empty list when the actor's reach resolves to nothing
     */
    public List<PendingApproval> pendingApprovals(String actorUuid) {
        ScopeResolution reach = reachOf(actorUuid);
        // Fail closed (§3.3): no grant, unknown actor or a resolution failure all land on
        // none(), and none of them may be read as "everyone".
        if (reach.isNone()) {
            // Debug rather than a new INFO token: "why is my queue empty" is a support
            // question, not a monitored event, and the sweep's token list is explicit.
            log.debugf("Competence approval queue empty for %s — reach resolved to none", actorUuid);
            return List.of();
        }

        List<CompetenceAttempt> passed = CompetenceAttempt.listPassed();
        List<CompetenceAttempt> inReach = new ArrayList<>();
        for (CompetenceAttempt attempt : passed) {
            if (reach.permits(attempt.getUseruuid())) {
                inReach.add(attempt);
            }
        }
        if (inReach.isEmpty()) {
            return List.of();
        }

        // One query for the whole ledger slice, then one for the requirements — the queue
        // must not cost a query per row.
        Map<String, CompetenceAttemptDecision> decided = statusService.latestDecisionsFor(
                inReach.stream().map(CompetenceAttempt::getUuid).toList());
        Map<String, CompetenceRequirement> requirements = requirementIndex();

        List<PendingApproval> out = new ArrayList<>();
        for (CompetenceAttempt attempt : inReach) {
            if (decided.containsKey(attempt.getUuid())) {
                continue;
            }
            out.add(new PendingApproval(attempt, requirements.get(attempt.getRequirementUuid())));
        }
        return out;
    }

    private Map<String, CompetenceRequirement> requirementIndex() {
        Map<String, CompetenceRequirement> index = new HashMap<>();
        for (CompetenceRequirement requirement : CompetenceRequirement.listAllOrdered()) {
            index.put(requirement.getUuid(), requirement);
        }
        return index;
    }

    // -----------------------------------------------------------------------
    // decide
    // -----------------------------------------------------------------------

    /**
     * Writes one decision per supplied attempt, reporting each item separately.
     *
     * <p>The request must carry explicit attempt uuids. There is deliberately no "approve
     * everything pending" wildcard (§5.10): bulk approve approves exactly the rows the
     * leader was looking at, so what was attested is what was seen. A wildcard would let a
     * row that appeared between rendering and clicking be approved unseen.
     *
     * <p>Per item, in order: unknown attempt, outside reach, self-approval, wrong state,
     * missing revocation note. The reach check comes before the self-check on purpose —
     * "outside your data scope" is the honest answer for an actor whose grant does not even
     * cover themselves, and it keeps the scope check unconditional.
     *
     * @return one outcome per <em>distinct</em> attempt uuid, in the order supplied; a uuid
     *         repeated in the request is decided once, because a double-clicked bulk button
     *         must not append the same verdict to the ledger twice
     */
    @Transactional
    public List<DecisionOutcome> decide(List<String> attemptUuids, DecisionType decision,
                                        String note, String actorUuid) {
        // §10.4. Deliberately duplicated from CompetenceAttemptService rather than making
        // that method public: the guard is three lines, and widening a service's API so a
        // sibling can reuse a check is how the check ends up being called from places that
        // were never reasoned about.
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "You cannot decide a test attempt while impersonating another user",
                    Response.Status.FORBIDDEN);
        }
        if (attemptUuids == null || attemptUuids.isEmpty()) {
            throw new WebApplicationException("No attempts supplied", Response.Status.BAD_REQUEST);
        }
        if (decision == null) {
            throw new WebApplicationException("No decision supplied", Response.Status.BAD_REQUEST);
        }
        String cleanNote = (note == null || note.isBlank()) ? null : note.trim();
        // The column is VARCHAR(1000). Refusing up front rather than letting the INSERT fail
        // mid-loop matters: this method is transactional, so a late SQL error would roll back
        // the items that had already succeeded while the caller was told they succeeded.
        if (cleanNote != null && cleanNote.length() > NOTE_MAX_LENGTH) {
            throw new WebApplicationException(
                    "A note may be at most " + NOTE_MAX_LENGTH + " characters",
                    Response.Status.BAD_REQUEST);
        }

        ScopeResolution reach = reachOf(actorUuid);
        LocalDateTime now = LocalDateTime.now();
        List<DecisionOutcome> outcomes = new ArrayList<>();

        for (String attemptUuid : new LinkedHashSet<>(attemptUuids)) {
            CompetenceAttempt attempt = attemptUuid == null || attemptUuid.isBlank()
                    ? null : CompetenceAttempt.findByUuid(attemptUuid);
            if (attempt == null) {
                outcomes.add(new DecisionOutcome(attemptUuid, false, "No such attempt"));
                continue;
            }
            if (!reach.permits(attempt.getUseruuid())) {
                outcomes.add(new DecisionOutcome(attemptUuid, false, "Outside your data scope"));
                continue;
            }
            // Funktionsadskillelse, applied to the module itself (§5.10). An auditor will
            // test exactly this, and the module teaches the principle — it cannot fail it.
            if (attempt.getUseruuid().equals(actorUuid)) {
                outcomes.add(new DecisionOutcome(attemptUuid, false,
                        "You cannot approve your own attempt"));
                continue;
            }
            if (attempt.getSubmittedAt() == null || attempt.isAbandoned() || !attempt.isPassed()) {
                outcomes.add(new DecisionOutcome(attemptUuid, false,
                        "Only a submitted, passed attempt can be approved"));
                continue;
            }
            // A revocation takes something away from a person, so the ledger has to record
            // why. Approvals may stand on the score alone.
            if (decision == DecisionType.REVOKED && cleanNote == null) {
                outcomes.add(new DecisionOutcome(attemptUuid, false, "A revocation requires a note"));
                continue;
            }

            append(attempt, decision, cleanNote, actorUuid, now);
            // Stable token, and never the note — it is free text about a named colleague.
            // The production log sweep filters on an explicit token list, so an untokenised
            // line is invisible to monitoring.
            log.infof("COMPETENCE_DECISION attempt=%s subject=%s requirement=%s decision=%s "
                            + "actor=%s noted=%s",
                    attempt.getUuid(), attempt.getUseruuid(), attempt.getKref(), decision,
                    actorUuid, cleanNote != null);
            outcomes.add(new DecisionOutcome(attemptUuid, true, null));
        }
        return outcomes;
    }

    /** Appends to the ledger. Never updates — the table SIGNALs on UPDATE and DELETE. */
    private void append(CompetenceAttempt attempt, DecisionType decision, String note,
                        String actorUuid, LocalDateTime decidedAt) {
        CompetenceAttemptDecision row = new CompetenceAttemptDecision();
        row.setUuid(UUID.randomUUID().toString());
        row.setAttemptUuid(attempt.getUuid());
        row.setDecision(decision);
        row.setActorUuid(actorUuid);
        row.setDecidedAt(decidedAt);
        row.setNote(note);
        row.persist();
    }

    // -----------------------------------------------------------------------
    // ledger
    // -----------------------------------------------------------------------

    /**
     * The full decision history for one attempt, newest first.
     *
     * <p>The detail view shows the whole chain rather than only the effective state,
     * because "approved, then revoked with this note, then approved again" is the part an
     * auditor asks about.
     */
    public List<CompetenceAttemptDecision> decisionsFor(String attemptUuid) {
        if (attemptUuid == null || attemptUuid.isBlank()) {
            return List.of();
        }
        return CompetenceAttemptDecision.listForAttempts(List.of(attemptUuid));
    }

    // -----------------------------------------------------------------------

    /**
     * The actor's reach for {@link #PERMISSION_APPROVE} as of today. Resolved from live
     * team relationships on every call — a lead who lost the team this morning loses the
     * rows this morning.
     */
    private ScopeResolution reachOf(String actorUuid) {
        return authorizationService.resolveReach(
                actorUuid, PERMISSION_APPROVE, LocalDate.now(), Set.of());
    }
}
