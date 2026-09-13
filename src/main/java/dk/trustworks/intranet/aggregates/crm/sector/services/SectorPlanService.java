package dk.trustworks.intranet.aggregates.crm.sector.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.plan.dto.AccountPlanDTO;
import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionCadence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionPriority;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionStatus;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.MeasureKind;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ObjectiveCategory;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanStatus;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorPlanDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorPlanRefDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorReviewRequest;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlan;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlanAction;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlanObjective;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlanReview;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlanSentence;
import dk.trustworks.intranet.aggregates.crm.sector.model.SectorPlanSnapshot;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The sector plan (sectors spec §3, §4): the account plan's rules, keyed by segment, minus
 * the people — plus the one thing an account plan does not have, the objectives that other
 * plans SERVE.
 *
 * <h2>The rules, enforced here as on the account plan</h2>
 * <ul>
 *   <li>At most four objectives; the fifth is refused.</li>
 *   <li>A green needs a reason; a green without one is stored as not assessed (rule 8).</li>
 *   <li>An action needs a due date or a cadence (rule 2).</li>
 *   <li>Closing a review bumps the version and writes a snapshot (rule 9).</li>
 *   <li>Sector objectives are closed, never deleted — account objectives point at them.</li>
 * </ul>
 *
 * <h2>Derived, never stored</h2>
 * {@code derivedRag} on a sector objective is the worst of the effective colours of the
 * account objectives serving it, computed on every read from
 * {@code client_plan_objective.sector_objective_uuid}. It is not a column: a stored derived
 * value is a derived value that goes stale.
 *
 * <p>Validation is hand-rolled — bean validation is not active in this build.
 */
@JBossLog
@ApplicationScoped
public class SectorPlanService {

    public static final int MAX_OBJECTIVES = 4;
    public static final int MAX_SENTENCE_CHARS = 1000;
    public static final int MAX_TITLE_CHARS = 300;
    public static final int MAX_WHY_CHARS = 500;
    public static final int MAX_RESULT_CHARS = 1000;
    public static final int MAX_OUTCOME_CHARS = 1000;
    public static final int MAX_DECISION_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    EntityManager em;

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /** The plan for a sector; a sector with no plan gets an empty one at status NONE, never a 404. */
    public SectorPlanDTO read(ClientSegment segment) {
        requireSegment(segment);
        SectorPlan plan = SectorPlan.findById(segment);
        if (plan == null) {
            return emptyPlan(segment);
        }
        return new SectorPlanDTO(
                segment.name(),
                segment.getDisplayName(),
                plan.getStatus().name(),
                plan.getVersion(),
                toDate(plan.getUpdatedAt()),
                person(plan.getUpdatedBy()),
                plan.getNextReview(),
                new AccountPlanDTO.PlanHealthDTO(
                        plan.getHealthRag() == null ? null : plan.getHealthRag().name(),
                        plan.getHealthWhy(),
                        person(plan.getHealthSetBy()),
                        toDate(plan.getHealthSetAt())),
                sentences(segment),
                objectives(segment),
                actions(segment),
                reviews(segment),
                snapshot(segment));
    }

    /** The plan as a chip, for the account header and the sector card. */
    public SectorPlanRefDTO reference(ClientSegment segment) {
        return SectorService.planRef(segment == null ? null : SectorPlan.findById(segment));
    }

    /**
     * The open objective an account objective may serve, or a refusal: it must exist, be
     * open, and belong to the plan of the client's own segment. Called by
     * {@code AccountPlanService} when an account objective is linked.
     */
    public SectorPlanObjective requireOpenObjectiveFor(String objectiveUuid, ClientSegment segment) {
        if (objectiveUuid == null || objectiveUuid.isBlank()) {
            throw new WebApplicationException("A sector objective is required", Response.Status.BAD_REQUEST);
        }
        SectorPlanObjective objective = SectorPlanObjective.findById(objectiveUuid.trim());
        if (objective == null) {
            throw new WebApplicationException("Unknown sector objective", Response.Status.BAD_REQUEST);
        }
        if (objective.getClosedAt() != null) {
            throw new WebApplicationException(
                    "That sector objective is closed — an account objective can only serve an open one",
                    Response.Status.BAD_REQUEST);
        }
        if (segment != null && objective.getSegment() != segment) {
            throw new WebApplicationException(
                    "An account objective can only serve an objective of its own sector ("
                            + segment.getDisplayName() + ")",
                    Response.Status.BAD_REQUEST);
        }
        return objective;
    }

    /** Titles by uuid for a set of sector objectives — the account plan shows the title on its chip. */
    public Map<String, String> titlesOf(Collection<String> objectiveUuids) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (String uuid : objectiveUuids) {
            if (uuid == null || uuid.isBlank() || titles.containsKey(uuid)) {
                continue;
            }
            SectorPlanObjective objective = SectorPlanObjective.findById(uuid.trim());
            if (objective != null) {
                titles.put(uuid, objective.getClosedAt() == null
                        ? objective.getTitle()
                        : objective.getTitle() + " (closed)");
            }
        }
        return titles;
    }

    private SectorPlanDTO emptyPlan(ClientSegment segment) {
        return new SectorPlanDTO(
                segment.name(), segment.getDisplayName(), PlanStatus.NONE.name(), 1, null, null, null,
                new AccountPlanDTO.PlanHealthDTO(null, null, null, null),
                List.of(), List.of(), List.of(), List.of(), null);
    }

    private List<AccountPlanDTO.PlanSentenceDTO> sentences(ClientSegment segment) {
        List<SectorPlanSentence> rows = SectorPlanSentence.list("segment", segment);
        List<AccountPlanDTO.PlanSentenceDTO> out = new ArrayList<>();
        for (PlanSlot slot : PlanSlot.values()) {
            rows.stream().filter(row -> row.getSlot() == slot).findFirst().ifPresent(row ->
                    out.add(new AccountPlanDTO.PlanSentenceDTO(
                            row.getSlot().name(), row.getText(), person(row.getByUuid()),
                            toDate(row.getValidatedAt()))));
        }
        return out;
    }

    private List<SectorPlanDTO.SectorObjectiveDTO> objectives(ClientSegment segment) {
        List<SectorPlanObjective> rows = SectorPlanObjective
                .list("segment = ?1 and closedAt is null order by ordinal", segment);
        List<SectorPlanDTO.SectorObjectiveDTO> out = new ArrayList<>();
        for (SectorPlanObjective row : rows) {
            List<SectorPlanDTO.ServedByDTO> servedBy = servedBy(row.getUuid());
            List<String> rags = new ArrayList<>();
            for (SectorPlanDTO.ServedByDTO served : servedBy) {
                rags.add(served.rag());
            }
            out.add(new SectorPlanDTO.SectorObjectiveDTO(
                    row.getUuid(),
                    row.getCategory().name(),
                    row.getTitle(),
                    new AccountPlanDTO.PlanObjectiveDTO.MeasureDTO(
                            row.getMeasureKind().name(), row.getMeasureLabel(),
                            row.getBaseline(), row.getTarget(), row.getUnit(), row.isHold()),
                    row.getTargetDate(),
                    person(row.getOwnerUuid()),
                    row.getRag() == null ? null : row.getRag().name(),
                    row.getRagWhy(),
                    List.of(),
                    worstOf(rags),
                    servedBy));
        }
        return out;
    }

    /** The open account objectives that declared they serve this sector objective. */
    List<SectorPlanDTO.ServedByDTO> servedBy(String sectorObjectiveUuid) {
        Query query = em.createNativeQuery("""
                select o.uuid, o.client_uuid, c.name, o.title, o.rag, o.rag_why
                  from client_plan_objective o
                  join client c on c.uuid = o.client_uuid
                 where o.sector_objective_uuid = :uuid
                   and o.closed_at is null
                 order by c.name
                """);
        query.setParameter("uuid", sectorObjectiveUuid);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<SectorPlanDTO.ServedByDTO> out = new ArrayList<>();
        for (Object[] row : rows) {
            out.add(new SectorPlanDTO.ServedByDTO(
                    str(row[1]), str(row[2]), str(row[0]), str(row[3]),
                    effectiveRag(str(row[4]), str(row[5]))));
        }
        return out;
    }

    private List<AccountPlanDTO.PlanActionDTO> actions(ClientSegment segment) {
        List<SectorPlanAction> rows = SectorPlanAction
                .list("segment = ?1 order by status, coalesce(due, nextDue), createdAt", segment);
        List<AccountPlanDTO.PlanActionDTO> out = new ArrayList<>();
        for (SectorPlanAction row : rows) {
            out.add(new AccountPlanDTO.PlanActionDTO(
                    row.getUuid(), row.getTitle(), row.getHow(), person(row.getOwnerUuid()),
                    row.getDue(), row.getCadence() == null ? null : row.getCadence().name(),
                    row.getNextDue(), row.getStatus().name(), row.getPriority().name(),
                    row.getObjectiveUuid(), null, null,
                    row.getResult(), toDate(row.getClosedAt()), row.getFromSuggestionId()));
        }
        return out;
    }

    private List<AccountPlanDTO.PlanReviewDTO> reviews(ClientSegment segment) {
        List<SectorPlanReview> rows = SectorPlanReview.list("segment = ?1 order by reviewDate desc", segment);
        List<AccountPlanDTO.PlanReviewDTO> out = new ArrayList<>();
        for (SectorPlanReview row : rows) {
            out.add(new AccountPlanDTO.PlanReviewDTO(
                    row.getUuid(), row.getReviewDate(), row.getVersion(),
                    reviewAttendees(row.getUuid()), row.getOutcome(),
                    reviewDecisions(row.getUuid()), row.getNextReview()));
        }
        return out;
    }

    private SectorPlanDTO.SectorSnapshotDTO snapshot(ClientSegment segment) {
        SectorPlanSnapshot row = SectorPlanSnapshot.findById(segment);
        if (row == null) {
            return null;
        }
        return new SectorPlanDTO.SectorSnapshotDTO(
                row.getSnapshotDate(), row.getVersion(), row.getHealth().name(),
                readJsonMap(row.getObjectiveRags()), List.of(),
                new SectorPlanDTO.SectorSnapshotDTO.FactsDTO(
                        row.getFactWeightedRate(), row.getFactConsultants(), row.getFactOpenLeads(),
                        row.getFactWeightedPipeline(), row.getFactFyRevenue(),
                        readJsonIntMap(row.getFactAccountsByBand()), readJsonIntMap(row.getFactPlanCoverage())));
    }

    // ------------------------------------------------------------------------
    // Write — the plan itself
    // ------------------------------------------------------------------------

    @Transactional
    public SectorPlanDTO start(ClientSegment segment, PlanRequests.StartPlanRequest request, String actor) {
        requireSegment(segment);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        SectorPlan plan = SectorPlan.findById(segment);
        if (plan == null) {
            plan = new SectorPlan();
            plan.setSegment(segment);
            plan.setVersion(1);
            plan.setCreatedAt(now);
            plan.setCreatedBy(actor);
        }
        plan.setStatus(PlanStatus.ACTIVE);
        plan.setNextReview(request.nextReview());
        applyHealth(plan, request.healthRag(), request.healthWhy(), actor, now);
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();

        setSentence(segment, PlanSlot.WHY, request.why(), actor, now);
        setSentence(segment, PlanSlot.CURRENT, request.current(), actor, now);
        setSentence(segment, PlanSlot.DESIRED, request.desired(), actor, now);
        setSentence(segment, PlanSlot.QUESTION, request.question(), actor, now);

        log.infof("Sector plan started: segment=%s actor=%s", segment, actor);
        return read(segment);
    }

    /** "Still true", plus the health assessment and the next review date. */
    @Transactional
    public SectorPlanDTO patch(ClientSegment segment, PlanRequests.PlanPatchRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        LocalDateTime now = LocalDateTime.now();
        if (request != null && !request.confirmOnly()) {
            if (request.clearHealthRag()) {
                plan.setHealthRag(null);
                plan.setHealthWhy(null);
                plan.setHealthSetBy(actor);
                plan.setHealthSetAt(now);
            } else if (request.healthRag() != null) {
                applyHealth(plan, request.healthRag(), request.healthWhy(), actor, now);
            }
            if (request.clearNextReview()) {
                plan.setNextReview(null);
            } else if (request.nextReview() != null) {
                plan.setNextReview(request.nextReview());
            }
        }
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();
        return read(segment);
    }

    @Transactional
    public SectorPlanDTO putSentence(ClientSegment segment, String slotRaw, PlanRequests.SentenceRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        PlanSlot slot = parse(PlanSlot.class, slotRaw, "slot");
        LocalDateTime now = LocalDateTime.now();
        setSentence(segment, slot, request == null ? null : request.text(), actor, now);
        touch(plan, actor, now);
        return read(segment);
    }

    // ------------------------------------------------------------------------
    // Write — objectives
    // ------------------------------------------------------------------------

    @Transactional
    public SectorPlanDTO addObjective(ClientSegment segment, PlanRequests.ObjectiveRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        long open = SectorPlanObjective.count("segment = ?1 and closedAt is null", segment);
        if (open >= MAX_OBJECTIVES) {
            throw new WebApplicationException(
                    "A sector plan holds at most " + MAX_OBJECTIVES + " objectives — close one before adding another",
                    Response.Status.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        SectorPlanObjective objective = new SectorPlanObjective();
        objective.setUuid(UUID.randomUUID().toString());
        objective.setSegment(segment);
        objective.setOrdinal((int) open + 1);
        objective.setCreatedAt(now);
        objective.setCreatedBy(actor);
        applyObjective(objective, request, actor, now, true);
        objective.persist();
        touch(plan, actor, now);
        return read(segment);
    }

    @Transactional
    public SectorPlanDTO updateObjective(ClientSegment segment, String objectiveUuid,
                                         PlanRequests.ObjectiveRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        SectorPlanObjective objective = requireObjective(segment, objectiveUuid);
        LocalDateTime now = LocalDateTime.now();
        applyObjective(objective, request, actor, now, false);
        objective.persist();
        touch(plan, actor, now);
        return read(segment);
    }

    /** Closes a sector objective. The row stays: account objectives may still point at it. */
    @Transactional
    public SectorPlanDTO closeObjective(ClientSegment segment, String objectiveUuid, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        SectorPlanObjective objective = requireObjective(segment, objectiveUuid);
        LocalDateTime now = LocalDateTime.now();
        objective.setClosedAt(now);
        objective.setModifiedAt(now);
        objective.setModifiedBy(actor);
        objective.persist();
        touch(plan, actor, now);
        return read(segment);
    }

    // ------------------------------------------------------------------------
    // Write — actions
    // ------------------------------------------------------------------------

    @Transactional
    public SectorPlanDTO addAction(ClientSegment segment, PlanRequests.ActionRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        SectorPlanAction action = new SectorPlanAction();
        action.setUuid(UUID.randomUUID().toString());
        action.setSegment(segment);
        action.setStatus(ActionStatus.OPEN);
        action.setPriority(ActionPriority.NORMAL);
        action.setCreatedAt(now);
        action.setCreatedBy(actor);
        applyAction(action, request, actor, now, true);
        action.persist();
        touch(plan, actor, now);
        return read(segment);
    }

    @Transactional
    public SectorPlanDTO updateAction(ClientSegment segment, String actionUuid,
                                      PlanRequests.ActionRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        SectorPlanAction action = requireAction(segment, actionUuid);
        LocalDateTime now = LocalDateTime.now();
        applyAction(action, request, actor, now, false);
        action.persist();
        touch(plan, actor, now);
        return read(segment);
    }

    @Transactional
    public SectorPlanDTO removeAction(ClientSegment segment, String actionUuid, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        requireAction(segment, actionUuid).delete();
        touch(plan, actor, LocalDateTime.now());
        return read(segment);
    }

    // ------------------------------------------------------------------------
    // Write — reviews
    // ------------------------------------------------------------------------

    /** Closes a review: writes the row, bumps the version and freezes the snapshot (rule 9). */
    @Transactional
    public SectorPlanDTO closeReview(ClientSegment segment, SectorReviewRequest request, String actor) {
        SectorPlan plan = requirePlan(segment);
        requireActor(actor);
        if (request == null || isBlank(request.outcome())) {
            throw new WebApplicationException("A review needs an outcome", Response.Status.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDate date = request.date() == null ? LocalDate.now() : request.date();

        SectorPlanReview review = new SectorPlanReview();
        review.setUuid(UUID.randomUUID().toString());
        review.setSegment(segment);
        review.setReviewDate(date);
        review.setVersion(plan.getVersion());
        review.setOutcome(trimTo(request.outcome(), MAX_OUTCOME_CHARS));
        review.setNextReview(request.nextReview());
        review.setCreatedAt(now);
        review.setCreatedBy(actor);
        review.persist();
        writeReviewAttendees(review.getUuid(), request.attendeeUuids());
        writeReviewDecisions(review.getUuid(), request.decisions());

        SectorPlanSnapshot snapshot = SectorPlanSnapshot.findById(segment);
        if (snapshot == null) {
            snapshot = new SectorPlanSnapshot();
            snapshot.setSegment(segment);
        }
        snapshot.setSnapshotDate(date);
        snapshot.setVersion(plan.getVersion());
        // An unassessed plan freezes as AMBER — "we did not say" — never as a green nobody claimed.
        snapshot.setHealth(plan.getHealthRag() == null ? PlanRag.AMBER : plan.getHealthRag());
        snapshot.setObjectiveRags(writeJson(request.objectiveRags() == null
                ? currentObjectiveRags(segment) : request.objectiveRags()));
        snapshot.setFactFyRevenue(request.fyRevenue());
        snapshot.setFactWeightedRate(request.weightedRate());
        snapshot.setFactConsultants(request.consultants());
        snapshot.setFactOpenLeads(request.openLeads());
        snapshot.setFactWeightedPipeline(request.weightedPipeline());
        snapshot.setFactAccountsByBand(writeJson(request.accountsByBand() == null ? Map.of() : request.accountsByBand()));
        snapshot.setFactPlanCoverage(writeJson(request.planCoverage() == null ? Map.of() : request.planCoverage()));
        snapshot.setCreatedAt(now);
        snapshot.persist();

        plan.setVersion(plan.getVersion() + 1);
        plan.setNextReview(request.nextReview());
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();

        log.infof("Sector plan review closed: segment=%s version=%d actor=%s", segment, review.getVersion(), actor);
        return read(segment);
    }

    // ------------------------------------------------------------------------
    // Pure rules — package-private for the fast tier
    // ------------------------------------------------------------------------

    /** A green with no reason is not assessed (rule 8); everything else is what it says. */
    static String effectiveRag(String rag, String why) {
        if (rag == null || rag.isBlank()) {
            return null;
        }
        if ("GREEN".equalsIgnoreCase(rag) && (why == null || why.isBlank())) {
            return null;
        }
        return rag.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The worst colour of the account objectives serving a sector objective: red beats
     * amber beats green, and an unassessed one is ignored. Null when none is assessed —
     * a blank must never read as green.
     */
    static String worstOf(Collection<String> rags) {
        String worst = null;
        for (String rag : rags) {
            if (rag == null) {
                continue;
            }
            if ("RED".equals(rag)) {
                return "RED";
            }
            if ("AMBER".equals(rag)) {
                worst = "AMBER";
            } else if ("GREEN".equals(rag) && worst == null) {
                worst = "GREEN";
            }
        }
        return worst;
    }

    // ------------------------------------------------------------------------
    // Field application
    // ------------------------------------------------------------------------

    private void applyHealth(SectorPlan plan, String ragRaw, String why, String actor, LocalDateTime now) {
        PlanRag rag = ragRaw == null || ragRaw.isBlank() ? null : parse(PlanRag.class, ragRaw, "rag");
        String trimmedWhy = trimTo(why, MAX_WHY_CHARS);
        if (rag == PlanRag.GREEN && isBlank(trimmedWhy)) {
            rag = null;
        }
        plan.setHealthRag(rag);
        plan.setHealthWhy(trimmedWhy);
        plan.setHealthSetBy(actor);
        plan.setHealthSetAt(now);
    }

    private void applyObjective(SectorPlanObjective objective, PlanRequests.ObjectiveRequest request,
                                String actor, LocalDateTime now, boolean creating) {
        if (request != null) {
            if (creating || request.category() != null) {
                objective.setCategory(parse(ObjectiveCategory.class, orDefault(request.category(), "COMMERCIAL"), "category"));
            }
            if (creating || request.title() != null) {
                String title = trimTo(request.title(), MAX_TITLE_CHARS);
                if (isBlank(title)) {
                    throw new WebApplicationException("An objective needs a title", Response.Status.BAD_REQUEST);
                }
                objective.setTitle(title);
            }
            if (creating || request.measureKind() != null) {
                objective.setMeasureKind(parse(MeasureKind.class, orDefault(request.measureKind(), "TEXT"), "measureKind"));
            }
            if (request.measureLabel() != null) objective.setMeasureLabel(trimTo(request.measureLabel(), 200));
            if (request.baseline() != null) objective.setBaseline(trimTo(request.baseline(), 80));
            if (request.target() != null) objective.setTarget(trimTo(request.target(), 80));
            if (request.unit() != null) objective.setUnit(trimTo(request.unit(), 40));
            if (request.hold() != null) objective.setHold(request.hold());
            if (creating || request.targetDate() != null) {
                if (request.targetDate() == null) {
                    throw new WebApplicationException("An objective needs a target date", Response.Status.BAD_REQUEST);
                }
                objective.setTargetDate(request.targetDate());
            }
            if (creating || request.ownerUuid() != null) {
                objective.setOwnerUuid(requireUser(orDefault(request.ownerUuid(), actor)));
            }
            if (request.clearRag()) {
                objective.setRag(null);
                objective.setRagWhy(null);
            } else if (request.rag() != null) {
                PlanRag rag = parse(PlanRag.class, request.rag(), "rag");
                String why = trimTo(request.ragWhy(), MAX_WHY_CHARS);
                objective.setRag(rag == PlanRag.GREEN && isBlank(why) ? null : rag);
                objective.setRagWhy(why);
            } else if (request.ragWhy() != null) {
                objective.setRagWhy(trimTo(request.ragWhy(), MAX_WHY_CHARS));
            }
        }
        objective.setModifiedAt(now);
        objective.setModifiedBy(actor);
    }

    private void applyAction(SectorPlanAction action, PlanRequests.ActionRequest request,
                             String actor, LocalDateTime now, boolean creating) {
        if (request != null) {
            if (creating || request.title() != null) {
                String title = trimTo(request.title(), MAX_TITLE_CHARS);
                if (isBlank(title)) {
                    throw new WebApplicationException("An action needs a title", Response.Status.BAD_REQUEST);
                }
                action.setTitle(title);
            }
            if (request.how() != null) action.setHow(trimTo(request.how(), 500));
            if (creating || request.ownerUuid() != null) {
                action.setOwnerUuid(requireUser(orDefault(request.ownerUuid(), actor)));
            }
            if (request.clearDue()) action.setDue(null);
            else if (request.due() != null) action.setDue(request.due());

            if (request.clearCadence()) {
                action.setCadence(null);
                action.setNextDue(null);
            } else if (request.cadence() != null) {
                ActionCadence cadence = parse(ActionCadence.class, request.cadence(), "cadence");
                action.setCadence(cadence);
                action.setNextDue(nextOccurrence(cadence, now.toLocalDate()));
            }
            if (request.priority() != null) {
                action.setPriority(parse(ActionPriority.class, request.priority(), "priority"));
            }
            if (request.clearObjective()) action.setObjectiveUuid(null);
            else if (request.objectiveUuid() != null) action.setObjectiveUuid(request.objectiveUuid());
            if (request.fromSuggestionId() != null) action.setFromSuggestionId(trimTo(request.fromSuggestionId(), 120));
            if (request.status() != null) {
                ActionStatus status = parse(ActionStatus.class, request.status(), "status");
                action.setStatus(status);
                if (status == ActionStatus.DONE) {
                    action.setClosedAt(now);
                    action.setResult(trimTo(request.result(), MAX_RESULT_CHARS));
                } else {
                    action.setClosedAt(null);
                    action.setResult(null);
                }
            } else if (request.result() != null) {
                action.setResult(trimTo(request.result(), MAX_RESULT_CHARS));
            }
        }
        if (action.getDue() == null && action.getCadence() == null) {
            throw new WebApplicationException(
                    "An action needs a due date or a cadence — one with neither is a wish, not an action",
                    Response.Status.BAD_REQUEST);
        }
        action.setModifiedAt(now);
        action.setModifiedBy(actor);
    }

    private void setSentence(ClientSegment segment, PlanSlot slot, String text, String actor, LocalDateTime now) {
        String trimmed = trimTo(text, MAX_SENTENCE_CHARS);
        if (isBlank(trimmed)) {
            SectorPlanSentence.delete("segment = ?1 and slot = ?2", segment, slot);
            return;
        }
        SectorPlanSentence sentence = SectorPlanSentence.find("segment = ?1 and slot = ?2", segment, slot).firstResult();
        if (sentence == null) {
            sentence = new SectorPlanSentence();
            sentence.setSegment(segment);
            sentence.setSlot(slot);
        }
        sentence.setText(trimmed);
        sentence.setByUuid(actor);
        sentence.setValidatedAt(now);
        sentence.persist();
    }

    // ------------------------------------------------------------------------
    // Link tables — no entity, bound native queries
    // ------------------------------------------------------------------------

    private List<PersonDTO> reviewAttendees(String reviewUuid) {
        Query query = em.createNativeQuery("select user_uuid from sector_plan_review_attendee where review_uuid = :uuid");
        query.setParameter("uuid", reviewUuid);
        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        List<PersonDTO> people = new ArrayList<>();
        for (Object row : rows) {
            PersonDTO person = person(String.valueOf(row));
            if (person != null) {
                people.add(person);
            }
        }
        return people;
    }

    private void writeReviewAttendees(String reviewUuid, List<String> attendeeUuids) {
        if (attendeeUuids == null) {
            return;
        }
        for (String userUuid : attendeeUuids.stream().distinct().toList()) {
            if (userUuid == null || userUuid.isBlank()) {
                continue;
            }
            Query insert = em.createNativeQuery(
                    "insert into sector_plan_review_attendee (review_uuid, user_uuid) values (:review, :user)");
            insert.setParameter("review", reviewUuid);
            insert.setParameter("user", userUuid.trim());
            insert.executeUpdate();
        }
    }

    private List<String> reviewDecisions(String reviewUuid) {
        Query query = em.createNativeQuery(
                "select decision_text from sector_plan_review_decision where review_uuid = :uuid order by ordinal");
        query.setParameter("uuid", reviewUuid);
        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        return rows.stream().map(String::valueOf).toList();
    }

    private void writeReviewDecisions(String reviewUuid, List<String> decisions) {
        if (decisions == null) {
            return;
        }
        int ordinal = 1;
        for (String decision : decisions) {
            String trimmed = trimTo(decision, MAX_DECISION_CHARS);
            if (isBlank(trimmed)) {
                continue;
            }
            Query insert = em.createNativeQuery("""
                    insert into sector_plan_review_decision (uuid, review_uuid, ordinal, decision_text)
                    values (:uuid, :review, :ordinal, :text)
                    """);
            insert.setParameter("uuid", UUID.randomUUID().toString());
            insert.setParameter("review", reviewUuid);
            insert.setParameter("ordinal", ordinal++);
            insert.setParameter("text", trimmed);
            insert.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private Map<String, String> currentObjectiveRags(ClientSegment segment) {
        Map<String, String> rags = new LinkedHashMap<>();
        for (SectorPlanObjective objective :
                SectorPlanObjective.<SectorPlanObjective>list("segment = ?1 and closedAt is null", segment)) {
            rags.put(objective.getUuid(), objective.getRag() == null ? null : objective.getRag().name());
        }
        return rags;
    }

    private void touch(SectorPlan plan, String actor, LocalDateTime now) {
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();
    }

    static LocalDate nextOccurrence(ActionCadence cadence, LocalDate from) {
        return switch (cadence) {
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
        };
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            log.warnf("Could not serialise sector snapshot fragment: %s", e.getMessage());
            return "{}";
        }
    }

    private Map<String, String> readJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Integer> readJsonIntMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Integer>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private PersonDTO person(String userUuid) {
        if (isBlank(userUuid)) {
            return null;
        }
        return PersonDTO.from(User.findById(userUuid.trim()));
    }

    private static LocalDate toDate(LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private void requireSegment(ClientSegment segment) {
        if (segment == null) {
            throw new WebApplicationException("A sector is required", Response.Status.BAD_REQUEST);
        }
    }

    private SectorPlan requirePlan(ClientSegment segment) {
        requireSegment(segment);
        SectorPlan plan = SectorPlan.findById(segment);
        if (plan == null) {
            throw new WebApplicationException("This sector has no plan yet — start one first", Response.Status.CONFLICT);
        }
        return plan;
    }

    private SectorPlanObjective requireObjective(ClientSegment segment, String objectiveUuid) {
        SectorPlanObjective objective = SectorPlanObjective
                .find("uuid = ?1 and segment = ?2", objectiveUuid, segment).firstResult();
        if (objective == null) {
            throw new WebApplicationException("Unknown objective", Response.Status.NOT_FOUND);
        }
        return objective;
    }

    private SectorPlanAction requireAction(ClientSegment segment, String actionUuid) {
        SectorPlanAction action = SectorPlanAction
                .find("uuid = ?1 and segment = ?2", actionUuid, segment).firstResult();
        if (action == null) {
            throw new WebApplicationException("Unknown action", Response.Status.NOT_FOUND);
        }
        return action;
    }

    private String requireUser(String userUuid) {
        if (isBlank(userUuid)) {
            throw new WebApplicationException("An owner is required", Response.Status.BAD_REQUEST);
        }
        if (User.<User>findById(userUuid.trim()) == null) {
            throw new WebApplicationException("Unknown colleague: " + userUuid, Response.Status.BAD_REQUEST);
        }
        return userUuid.trim();
    }

    private static void requireActor(String actor) {
        if (isBlank(actor)) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a plan change records who made it",
                    Response.Status.BAD_REQUEST);
        }
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new WebApplicationException(field + " is required", Response.Status.BAD_REQUEST);
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown " + field + ": " + raw, Response.Status.BAD_REQUEST);
        }
    }

    static String trimTo(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
