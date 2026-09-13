package dk.trustworks.intranet.aggregates.crm.plan.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.plan.dto.AccountPlanDTO;
import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlan;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanAction;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanObjective;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanRelation;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanReview;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanSentence;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanSnapshot;
import dk.trustworks.intranet.aggregates.crm.plan.model.ClientPlanStakeholder;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionCadence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionPriority;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionStatus;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.BuyingRole;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.Influence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.MeasureKind;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ObjectiveCategory;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanStatus;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.RelationRole;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.RelationSource;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The account plan (CRM spec §3.3, account-plan data model of 2026-09-12).
 *
 * <p>One method per reducer in the frontend's {@code accountPlanReducers.ts}, so the pure
 * reducers keep their tests and become the optimistic client-side transition while this is
 * the authority. Every rule the reducers enforce is enforced again here, because a rule
 * that only lives in the browser is not a rule.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>At most four objectives.</b> The fifth is refused. A plan of eight objectives
 *       is a list, and the fifth is never read anyway.</li>
 *   <li><b>A green needs a reason.</b> {@code rag = GREEN} with no {@code ragWhy} is
 *       stored as NOT ASSESSED (null), not as green — rule 8: a blank must never read as
 *       green.</li>
 *   <li><b>An action needs a due date or a cadence.</b> One with neither is incomplete —
 *       rule 2 — and is refused.</li>
 *   <li><b>Closing a review bumps the version and writes a snapshot</b> — rule 9 — so the
 *       numbers the meeting actually looked at survive every later edit.</li>
 * </ul>
 *
 * <p>Validation is hand-rolled: {@code quarkus-hibernate-validator} is absent from this
 * build, so every {@code @NotBlank} would be inert decoration.
 *
 * <p>The two pure link tables ({@code client_plan_objective_lead},
 * {@code client_plan_review_attendee} / {@code _decision}) have no entity — two columns
 * and no behaviour each. They are read and written with bound native queries; no string is
 * ever concatenated into SQL.
 */
@JBossLog
@ApplicationScoped
public class AccountPlanService {

    /** At most four. The fifth is never read (CRM spec §3.3). */
    public static final int MAX_OBJECTIVES = 4;

    public static final int MAX_SENTENCE_CHARS = 1000;
    public static final int MAX_TITLE_CHARS = 300;
    public static final int MAX_WHY_CHARS = 500;
    public static final int MAX_RESULT_CHARS = 1000;
    public static final int MAX_OUTCOME_CHARS = 1000;
    public static final int MAX_DECISION_CHARS = 500;
    public static final int MAX_NAME_CHARS = 255;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    ClientService clientService;

    /** What the accounts list needs about a plan — nothing more. */
    public record PlanSummary(String rag, LocalDate updatedAt, boolean started) {
    }

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * The plan for a client. An account with no plan gets an empty one with status
     * {@code NONE} rather than a 404 — "no plan yet" is a state the tab renders, not an
     * error, and every Active account starts there.
     */
    public AccountPlanDTO read(String clientUuid) {
        requireClient(clientUuid);
        ClientPlan plan = ClientPlan.findById(clientUuid);
        if (plan == null) {
            return emptyPlan(clientUuid);
        }
        return new AccountPlanDTO(
                clientUuid,
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
                sentences(clientUuid),
                objectives(clientUuid),
                actions(clientUuid),
                stakeholders(clientUuid),
                reviews(clientUuid),
                snapshot(clientUuid));
    }

    /** Plan health and freshness for every client that has a plan — for the accounts list. */
    public Map<String, PlanSummary> summariesForAll() {
        Map<String, PlanSummary> summaries = new LinkedHashMap<>();
        for (ClientPlan plan : ClientPlan.<ClientPlan>listAll()) {
            summaries.put(plan.getClientUuid(), new PlanSummary(
                    plan.getHealthRag() == null ? null : plan.getHealthRag().name(),
                    toDate(plan.getUpdatedAt()),
                    plan.getStatus() == PlanStatus.ACTIVE));
        }
        return summaries;
    }

    private AccountPlanDTO emptyPlan(String clientUuid) {
        return new AccountPlanDTO(
                clientUuid, PlanStatus.NONE.name(), 1, null, null, null,
                new AccountPlanDTO.PlanHealthDTO(null, null, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private List<AccountPlanDTO.PlanSentenceDTO> sentences(String clientUuid) {
        List<ClientPlanSentence> rows = ClientPlanSentence.list("clientUuid", clientUuid);
        List<AccountPlanDTO.PlanSentenceDTO> out = new ArrayList<>();
        // Emitted in slot order rather than insertion order: the tab reads them as a
        // sequence — why, where we stand, where we want to be, the open question.
        for (PlanSlot slot : PlanSlot.values()) {
            rows.stream().filter(row -> row.getSlot() == slot).findFirst().ifPresent(row ->
                    out.add(new AccountPlanDTO.PlanSentenceDTO(
                            row.getSlot().name(), row.getText(), person(row.getByUuid()),
                            toDate(row.getValidatedAt()))));
        }
        return out;
    }

    private List<AccountPlanDTO.PlanObjectiveDTO> objectives(String clientUuid) {
        List<ClientPlanObjective> rows = ClientPlanObjective
                .list("clientUuid = ?1 and closedAt is null order by ordinal", clientUuid);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> leads = leadsByObjective(rows.stream().map(ClientPlanObjective::getUuid).toList());
        List<AccountPlanDTO.PlanObjectiveDTO> out = new ArrayList<>();
        for (ClientPlanObjective row : rows) {
            out.add(new AccountPlanDTO.PlanObjectiveDTO(
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
                    leads.getOrDefault(row.getUuid(), List.of())));
        }
        return out;
    }

    private List<AccountPlanDTO.PlanActionDTO> actions(String clientUuid) {
        List<ClientPlanAction> rows = ClientPlanAction
                .list("clientUuid = ?1 order by status, coalesce(due, nextDue), createdAt", clientUuid);
        List<AccountPlanDTO.PlanActionDTO> out = new ArrayList<>();
        for (ClientPlanAction row : rows) {
            out.add(new AccountPlanDTO.PlanActionDTO(
                    row.getUuid(), row.getTitle(), row.getHow(), person(row.getOwnerUuid()),
                    row.getDue(), row.getCadence() == null ? null : row.getCadence().name(),
                    row.getNextDue(), row.getStatus().name(), row.getPriority().name(),
                    row.getObjectiveUuid(), row.getSignalUuid(), row.getStakeholderUuid(),
                    row.getResult(), toDate(row.getClosedAt()), row.getFromSuggestionId()));
        }
        return out;
    }

    private List<AccountPlanDTO.PlanStakeholderDTO> stakeholders(String clientUuid) {
        List<ClientPlanStakeholder> rows = ClientPlanStakeholder
                .list("clientUuid = ?1 order by influence, createdAt", clientUuid);
        List<AccountPlanDTO.PlanStakeholderDTO> out = new ArrayList<>();
        for (ClientPlanStakeholder row : rows) {
            List<ClientPlanRelation> relations =
                    ClientPlanRelation.list("stakeholderUuid", row.getUuid());
            List<AccountPlanDTO.PlanStakeholderDTO.PlanRelationDTO> relationDtos = new ArrayList<>();
            for (ClientPlanRelation relation : relations) {
                relationDtos.add(new AccountPlanDTO.PlanStakeholderDTO.PlanRelationDTO(
                        person(relation.getUserUuid()),
                        relation.getCurrentLevel(),
                        relation.getTargetLevel(),
                        relation.getRole().name(),
                        relation.getLastInteraction(),
                        relation.getSource().name(),
                        person(relation.getAssessedBy()),
                        toDate(relation.getAssessedAt())));
            }
            out.add(new AccountPlanDTO.PlanStakeholderDTO(
                    row.getUuid(), row.getName(), row.getRoleLabel(), row.getTitle(), row.getUnit(),
                    row.getBuying().name(), row.getInfluence().name(), toDate(row.getValidatedAt()),
                    row.getFromSignalUuid(), relationDtos));
        }
        return out;
    }

    private List<AccountPlanDTO.PlanReviewDTO> reviews(String clientUuid) {
        List<ClientPlanReview> rows = ClientPlanReview
                .list("clientUuid = ?1 order by reviewDate desc", clientUuid);
        List<AccountPlanDTO.PlanReviewDTO> out = new ArrayList<>();
        for (ClientPlanReview row : rows) {
            out.add(new AccountPlanDTO.PlanReviewDTO(
                    row.getUuid(), row.getReviewDate(), row.getVersion(),
                    reviewAttendees(row.getUuid()), row.getOutcome(),
                    reviewDecisions(row.getUuid()), row.getNextReview()));
        }
        return out;
    }

    private AccountPlanDTO.PlanSnapshotDTO snapshot(String clientUuid) {
        ClientPlanSnapshot row = ClientPlanSnapshot.findById(clientUuid);
        if (row == null) {
            return null;
        }
        return new AccountPlanDTO.PlanSnapshotDTO(
                row.getSnapshotDate(), row.getVersion(), row.getHealth().name(),
                readJsonMap(row.getObjectiveRags()), readJsonList(row.getStakeholderIds()),
                new AccountPlanDTO.PlanSnapshotDTO.FactsDTO(
                        row.getFactWeightedRate(), row.getFactConsultants(),
                        row.getFactOpenLeads(), row.getFactWeightedPipeline()));
    }

    // ------------------------------------------------------------------------
    // Write — the plan itself
    // ------------------------------------------------------------------------

    @Transactional
    public AccountPlanDTO start(String clientUuid, PlanRequests.StartPlanRequest request, String actor) {
        requireClient(clientUuid);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        ClientPlan plan = ClientPlan.findById(clientUuid);
        if (plan == null) {
            plan = new ClientPlan();
            plan.setClientUuid(clientUuid);
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

        setSentence(plan.getClientUuid(), PlanSlot.WHY, request.why(), actor, now);
        setSentence(plan.getClientUuid(), PlanSlot.CURRENT, request.current(), actor, now);
        setSentence(plan.getClientUuid(), PlanSlot.DESIRED, request.desired(), actor, now);
        setSentence(plan.getClientUuid(), PlanSlot.QUESTION, request.question(), actor, now);

        log.infof("Account plan started: client=%s actor=%s", clientUuid, actor);
        return read(clientUuid);
    }

    /** "Still true", plus the health assessment behind it. */
    @Transactional
    public AccountPlanDTO patch(String clientUuid, PlanRequests.PlanPatchRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
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

        // Every patch re-dates the plan — that IS "Still true".
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();
        return read(clientUuid);
    }

    @Transactional
    public AccountPlanDTO putSentence(String clientUuid, String slotRaw,
                                      PlanRequests.SentenceRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        PlanSlot slot = parse(PlanSlot.class, slotRaw, "slot");
        LocalDateTime now = LocalDateTime.now();
        setSentence(clientUuid, slot, request == null ? null : request.text(), actor, now);
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();
        return read(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Write — objectives
    // ------------------------------------------------------------------------

    @Transactional
    public AccountPlanDTO addObjective(String clientUuid, PlanRequests.ObjectiveRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }

        long open = ClientPlanObjective.count("clientUuid = ?1 and closedAt is null", clientUuid);
        if (open >= MAX_OBJECTIVES) {
            throw new WebApplicationException(
                    "A plan holds at most " + MAX_OBJECTIVES
                            + " objectives — close one before adding another",
                    Response.Status.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        ClientPlanObjective objective = new ClientPlanObjective();
        objective.setUuid(UUID.randomUUID().toString());
        objective.setClientUuid(clientUuid);
        objective.setOrdinal((int) open + 1);
        objective.setCreatedAt(now);
        objective.setCreatedBy(actor);
        applyObjective(objective, request, actor, now, true);
        objective.persist();
        replaceObjectiveLeads(objective.getUuid(), request.linkedLeadUuids());

        touch(plan, actor, now);
        return read(clientUuid);
    }

    @Transactional
    public AccountPlanDTO updateObjective(String clientUuid, String objectiveUuid,
                                          PlanRequests.ObjectiveRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        ClientPlanObjective objective = requireObjective(clientUuid, objectiveUuid);
        LocalDateTime now = LocalDateTime.now();
        applyObjective(objective, request, actor, now, false);
        objective.persist();
        if (request != null && request.linkedLeadUuids() != null) {
            replaceObjectiveLeads(objectiveUuid, request.linkedLeadUuids());
        }
        touch(plan, actor, now);
        return read(clientUuid);
    }

    /**
     * Closes an objective as achieved. It leaves the live list but stays in the table, so
     * a snapshot that mentions it can still resolve its title.
     */
    @Transactional
    public AccountPlanDTO closeObjective(String clientUuid, String objectiveUuid, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        ClientPlanObjective objective = requireObjective(clientUuid, objectiveUuid);
        LocalDateTime now = LocalDateTime.now();
        objective.setClosedAt(now);
        objective.setModifiedAt(now);
        objective.setModifiedBy(actor);
        objective.persist();
        touch(plan, actor, now);
        return read(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Write — actions
    // ------------------------------------------------------------------------

    @Transactional
    public AccountPlanDTO addAction(String clientUuid, PlanRequests.ActionRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        ClientPlanAction action = new ClientPlanAction();
        action.setUuid(UUID.randomUUID().toString());
        action.setClientUuid(clientUuid);
        action.setStatus(ActionStatus.OPEN);
        action.setPriority(ActionPriority.NORMAL);
        action.setCreatedAt(now);
        action.setCreatedBy(actor);
        applyAction(action, request, actor, now, true);
        action.persist();
        touch(plan, actor, now);
        return read(clientUuid);
    }

    @Transactional
    public AccountPlanDTO updateAction(String clientUuid, String actionUuid,
                                       PlanRequests.ActionRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        ClientPlanAction action = requireAction(clientUuid, actionUuid);
        LocalDateTime now = LocalDateTime.now();
        applyAction(action, request, actor, now, false);
        action.persist();
        touch(plan, actor, now);
        return read(clientUuid);
    }

    @Transactional
    public AccountPlanDTO removeAction(String clientUuid, String actionUuid, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        requireAction(clientUuid, actionUuid).delete();
        touch(plan, actor, LocalDateTime.now());
        return read(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Write — stakeholders
    // ------------------------------------------------------------------------

    @Transactional
    public AccountPlanDTO addStakeholder(String clientUuid, PlanRequests.StakeholderRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        ClientPlanStakeholder stakeholder = new ClientPlanStakeholder();
        stakeholder.setUuid(UUID.randomUUID().toString());
        stakeholder.setClientUuid(clientUuid);
        stakeholder.setCreatedAt(now);
        stakeholder.setCreatedBy(actor);
        applyStakeholder(stakeholder, request, actor, now, true);
        stakeholder.persist();
        replaceRelations(stakeholder.getUuid(), request.relations(), actor, now);
        touch(plan, actor, now);
        return read(clientUuid);
    }

    @Transactional
    public AccountPlanDTO updateStakeholder(String clientUuid, String stakeholderUuid,
                                            PlanRequests.StakeholderRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        ClientPlanStakeholder stakeholder = requireStakeholder(clientUuid, stakeholderUuid);
        LocalDateTime now = LocalDateTime.now();
        applyStakeholder(stakeholder, request, actor, now, false);
        stakeholder.persist();
        if (request != null && request.relations() != null) {
            replaceRelations(stakeholderUuid, request.relations(), actor, now);
        }
        touch(plan, actor, now);
        return read(clientUuid);
    }

    @Transactional
    public AccountPlanDTO removeStakeholder(String clientUuid, String stakeholderUuid, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        ClientPlanStakeholder stakeholder = requireStakeholder(clientUuid, stakeholderUuid);
        ClientPlanRelation.delete("stakeholderUuid", stakeholderUuid);
        stakeholder.delete();
        touch(plan, actor, LocalDateTime.now());
        return read(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Write — reviews
    // ------------------------------------------------------------------------

    /**
     * Closes a review: writes the review row, bumps the plan version and freezes a
     * snapshot of what the plan and the account looked like (rule 9).
     *
     * <p>The facts in the snapshot come from the request, not from a recomputation. They
     * are what the PAGE was showing while the meeting happened — the weighted pipeline the
     * attendees actually discussed — and recomputing them afterwards would record a
     * different meeting from the one that took place.
     */
    @Transactional
    public AccountPlanDTO closeReview(String clientUuid, PlanRequests.ReviewRequest request, String actor) {
        ClientPlan plan = requirePlan(clientUuid);
        requireActor(actor);
        if (request == null || isBlank(request.outcome())) {
            throw new WebApplicationException("A review needs an outcome", Response.Status.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDate date = request.date() == null ? LocalDate.now() : request.date();

        ClientPlanReview review = new ClientPlanReview();
        review.setUuid(UUID.randomUUID().toString());
        review.setClientUuid(clientUuid);
        review.setReviewDate(date);
        review.setVersion(plan.getVersion());
        review.setOutcome(trimTo(request.outcome(), MAX_OUTCOME_CHARS));
        review.setNextReview(request.nextReview());
        review.setCreatedAt(now);
        review.setCreatedBy(actor);
        review.persist();

        writeReviewAttendees(review.getUuid(), request.attendeeUuids());
        writeReviewDecisions(review.getUuid(), request.decisions());

        ClientPlanSnapshot snapshot = ClientPlanSnapshot.findById(clientUuid);
        if (snapshot == null) {
            snapshot = new ClientPlanSnapshot();
            snapshot.setClientUuid(clientUuid);
        }
        snapshot.setSnapshotDate(date);
        snapshot.setVersion(plan.getVersion());
        // A snapshot must carry a colour; an unassessed plan is frozen as AMBER — "we did
        // not say" — rather than as a green nobody claimed.
        snapshot.setHealth(plan.getHealthRag() == null ? PlanRag.AMBER : plan.getHealthRag());
        snapshot.setObjectiveRags(writeJson(request.objectiveRags() == null
                ? currentObjectiveRags(clientUuid) : request.objectiveRags()));
        snapshot.setStakeholderIds(writeJson(ClientPlanStakeholder
                .<ClientPlanStakeholder>list("clientUuid", clientUuid).stream()
                .map(ClientPlanStakeholder::getUuid).toList()));
        snapshot.setFactWeightedRate(request.weightedRate());
        snapshot.setFactConsultants(request.consultants());
        snapshot.setFactOpenLeads(request.openLeads());
        snapshot.setFactWeightedPipeline(request.weightedPipeline());
        snapshot.setCreatedAt(now);
        snapshot.persist();

        plan.setVersion(plan.getVersion() + 1);
        plan.setNextReview(request.nextReview());
        plan.setUpdatedAt(now);
        plan.setUpdatedBy(actor);
        plan.persist();

        log.infof("Account plan review closed: client=%s version=%d actor=%s",
                clientUuid, review.getVersion(), actor);
        return read(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Field application
    // ------------------------------------------------------------------------

    /**
     * A green needs a reason. {@code GREEN} with no {@code why} is stored as NOT ASSESSED,
     * because a colour nobody can justify is not an assessment — rule 8, and the only
     * place it can actually be enforced.
     */
    private void applyHealth(ClientPlan plan, String ragRaw, String why, String actor, LocalDateTime now) {
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

    private void applyObjective(ClientPlanObjective objective, PlanRequests.ObjectiveRequest request,
                                String actor, LocalDateTime now, boolean creating) {
        if (request != null) {
            if (creating || request.category() != null) {
                objective.setCategory(parse(ObjectiveCategory.class,
                        orDefault(request.category(), "COMMERCIAL"), "category"));
            }
            if (creating || request.title() != null) {
                String title = trimTo(request.title(), MAX_TITLE_CHARS);
                if (isBlank(title)) {
                    throw new WebApplicationException("An objective needs a title", Response.Status.BAD_REQUEST);
                }
                objective.setTitle(title);
            }
            if (creating || request.measureKind() != null) {
                objective.setMeasureKind(parse(MeasureKind.class,
                        orDefault(request.measureKind(), "TEXT"), "measureKind"));
            }
            if (request.measureLabel() != null) objective.setMeasureLabel(trimTo(request.measureLabel(), 200));
            if (request.baseline() != null) objective.setBaseline(trimTo(request.baseline(), 80));
            if (request.target() != null) objective.setTarget(trimTo(request.target(), 80));
            if (request.unit() != null) objective.setUnit(trimTo(request.unit(), 40));
            if (request.hold() != null) objective.setHold(request.hold());
            if (creating || request.targetDate() != null) {
                if (request.targetDate() == null) {
                    throw new WebApplicationException("An objective needs a target date",
                            Response.Status.BAD_REQUEST);
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
                // Same rule as plan health: a green without a reason is not assessed.
                objective.setRag(rag == PlanRag.GREEN && isBlank(why) ? null : rag);
                objective.setRagWhy(why);
            } else if (request.ragWhy() != null) {
                objective.setRagWhy(trimTo(request.ragWhy(), MAX_WHY_CHARS));
            }
        }
        objective.setModifiedAt(now);
        objective.setModifiedBy(actor);
    }

    /**
     * An action needs a due date OR a cadence. Neither is refused — rule 2 — because
     * "keep in touch with the programme office", with no date and no rhythm, is a wish.
     */
    private void applyAction(ClientPlanAction action, PlanRequests.ActionRequest request,
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

            if (request.signalUuid() != null) action.setSignalUuid(request.signalUuid());
            if (request.stakeholderUuid() != null) action.setStakeholderUuid(request.stakeholderUuid());
            if (request.fromSuggestionId() != null) {
                action.setFromSuggestionId(trimTo(request.fromSuggestionId(), 120));
            }
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

    private void applyStakeholder(ClientPlanStakeholder stakeholder, PlanRequests.StakeholderRequest request,
                                  String actor, LocalDateTime now, boolean creating) {
        if (request != null) {
            if (request.name() != null) stakeholder.setName(trimTo(request.name(), MAX_NAME_CHARS));
            if (request.roleLabel() != null) stakeholder.setRoleLabel(trimTo(request.roleLabel(), MAX_NAME_CHARS));
            if (creating || request.title() != null) {
                stakeholder.setTitle(orDefault(trimTo(request.title(), MAX_NAME_CHARS), "—"));
            }
            if (creating || request.unit() != null) {
                stakeholder.setUnit(orDefault(trimTo(request.unit(), MAX_NAME_CHARS), "—"));
            }
            if (creating || request.buying() != null) {
                stakeholder.setBuying(parse(BuyingRole.class,
                        orDefault(request.buying(), "USER_BUYER"), "buying"));
            }
            if (creating || request.influence() != null) {
                stakeholder.setInfluence(parse(Influence.class,
                        orDefault(request.influence(), "MEDIUM"), "influence"));
            }
            if (request.fromSignalUuid() != null) stakeholder.setFromSignalUuid(request.fromSignalUuid());
        }
        if (isBlank(stakeholder.getName()) && isBlank(stakeholder.getRoleLabel())) {
            throw new WebApplicationException(
                    "A person on the plan needs a name or a seat — one with neither cannot be found again",
                    Response.Status.BAD_REQUEST);
        }
        // Any edit IS a validation: somebody just looked at the row and said it is right.
        stakeholder.setValidatedAt(now);
        stakeholder.setModifiedAt(now);
        stakeholder.setModifiedBy(actor);
    }

    private void replaceRelations(String stakeholderUuid,
                                  List<PlanRequests.StakeholderRequest.RelationRequest> relations,
                                  String actor, LocalDateTime now) {
        ClientPlanRelation.delete("stakeholderUuid", stakeholderUuid);
        if (relations == null) {
            return;
        }
        for (PlanRequests.StakeholderRequest.RelationRequest request : relations) {
            if (request == null || isBlank(request.userUuid())) {
                continue;
            }
            ClientPlanRelation relation = new ClientPlanRelation();
            relation.setUuid(UUID.randomUUID().toString());
            relation.setStakeholderUuid(stakeholderUuid);
            relation.setUserUuid(requireUser(request.userUuid()));
            relation.setCurrentLevel(clamp(request.current()));
            relation.setTargetLevel(clamp(request.target()));
            relation.setRole(parse(RelationRole.class, orDefault(request.role(), "SUPPORTING"), "role"));
            relation.setLastInteraction(request.lastInteraction());
            relation.setSource(parse(RelationSource.class, orDefault(request.source(), "CONTRACT"), "source"));
            relation.setAssessedBy(actor);
            relation.setAssessedAt(now);
            relation.persist();
        }
    }

    private void setSentence(String clientUuid, PlanSlot slot, String text, String actor, LocalDateTime now) {
        String trimmed = trimTo(text, MAX_SENTENCE_CHARS);
        if (isBlank(trimmed)) {
            // An empty sentence is deleted rather than stored as "": planGaps() asks
            // whether the slot is filled, and an empty string would answer yes.
            ClientPlanSentence.delete("clientUuid = ?1 and slot = ?2", clientUuid, slot);
            return;
        }
        ClientPlanSentence sentence = ClientPlanSentence
                .find("clientUuid = ?1 and slot = ?2", clientUuid, slot).firstResult();
        if (sentence == null) {
            sentence = new ClientPlanSentence();
            sentence.setClientUuid(clientUuid);
            sentence.setSlot(slot);
        }
        sentence.setText(trimmed);
        sentence.setByUuid(actor);
        sentence.setValidatedAt(now);
        sentence.persist();
    }

    // ------------------------------------------------------------------------
    // Link tables — two columns each, no behaviour, so no entity
    // ------------------------------------------------------------------------

    private Map<String, List<String>> leadsByObjective(List<String> objectiveUuids) {
        if (objectiveUuids.isEmpty()) {
            return Map.of();
        }
        Query query = em.createNativeQuery(
                "select objective_uuid, lead_uuid from client_plan_objective_lead where objective_uuid in (:uuids)");
        query.setParameter("uuids", objectiveUuids);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, List<String>> byObjective = new HashMap<>();
        for (Object[] row : rows) {
            byObjective.computeIfAbsent(String.valueOf(row[0]), key -> new ArrayList<>())
                    .add(String.valueOf(row[1]));
        }
        return byObjective;
    }

    private void replaceObjectiveLeads(String objectiveUuid, List<String> leadUuids) {
        Query delete = em.createNativeQuery(
                "delete from client_plan_objective_lead where objective_uuid = :uuid");
        delete.setParameter("uuid", objectiveUuid);
        delete.executeUpdate();
        if (leadUuids == null) {
            return;
        }
        for (String leadUuid : leadUuids.stream().distinct().toList()) {
            if (leadUuid == null || leadUuid.isBlank()) {
                continue;
            }
            Query insert = em.createNativeQuery(
                    "insert into client_plan_objective_lead (objective_uuid, lead_uuid) values (:objective, :lead)");
            insert.setParameter("objective", objectiveUuid);
            insert.setParameter("lead", leadUuid.trim());
            insert.executeUpdate();
        }
    }

    private List<PersonDTO> reviewAttendees(String reviewUuid) {
        Query query = em.createNativeQuery(
                "select user_uuid from client_plan_review_attendee where review_uuid = :uuid");
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
                    "insert into client_plan_review_attendee (review_uuid, user_uuid) values (:review, :user)");
            insert.setParameter("review", reviewUuid);
            insert.setParameter("user", userUuid.trim());
            insert.executeUpdate();
        }
    }

    private List<String> reviewDecisions(String reviewUuid) {
        Query query = em.createNativeQuery(
                "select decision_text from client_plan_review_decision where review_uuid = :uuid order by ordinal");
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
                    insert into client_plan_review_decision (uuid, review_uuid, ordinal, decision_text)
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

    private Map<String, String> currentObjectiveRags(String clientUuid) {
        Map<String, String> rags = new LinkedHashMap<>();
        for (ClientPlanObjective objective :
                ClientPlanObjective.<ClientPlanObjective>list("clientUuid = ?1 and closedAt is null", clientUuid)) {
            rags.put(objective.getUuid(), objective.getRag() == null ? null : objective.getRag().name());
        }
        return rags;
    }

    private void touch(ClientPlan plan, String actor, LocalDateTime now) {
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

    static int clamp(int level) {
        return Math.max(0, Math.min(4, level));
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            // A snapshot that cannot be serialised must not take the review down with it;
            // an empty object still lets the review close and be read back.
            log.warnf("Could not serialise snapshot fragment: %s", e.getMessage());
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

    private List<String> readJsonList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<ArrayList<String>>() {
            });
        } catch (Exception e) {
            return List.of();
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

    private Client requireClient(String clientUuid) {
        if (isBlank(clientUuid)) {
            throw new WebApplicationException("A client uuid is required", Response.Status.BAD_REQUEST);
        }
        Client client = clientService.findByUuid(clientUuid.trim());
        if (client == null) {
            throw new WebApplicationException("Unknown client", Response.Status.NOT_FOUND);
        }
        return client;
    }

    private ClientPlan requirePlan(String clientUuid) {
        requireClient(clientUuid);
        ClientPlan plan = ClientPlan.findById(clientUuid);
        if (plan == null) {
            throw new WebApplicationException(
                    "This account has no plan yet — start one first", Response.Status.CONFLICT);
        }
        return plan;
    }

    private ClientPlanObjective requireObjective(String clientUuid, String objectiveUuid) {
        ClientPlanObjective objective = ClientPlanObjective
                .find("uuid = ?1 and clientUuid = ?2", objectiveUuid, clientUuid).firstResult();
        if (objective == null) {
            throw new WebApplicationException("Unknown objective", Response.Status.NOT_FOUND);
        }
        return objective;
    }

    private ClientPlanAction requireAction(String clientUuid, String actionUuid) {
        ClientPlanAction action = ClientPlanAction
                .find("uuid = ?1 and clientUuid = ?2", actionUuid, clientUuid).firstResult();
        if (action == null) {
            throw new WebApplicationException("Unknown action", Response.Status.NOT_FOUND);
        }
        return action;
    }

    private ClientPlanStakeholder requireStakeholder(String clientUuid, String stakeholderUuid) {
        ClientPlanStakeholder stakeholder = ClientPlanStakeholder
                .find("uuid = ?1 and clientUuid = ?2", stakeholderUuid, clientUuid).firstResult();
        if (stakeholder == null) {
            throw new WebApplicationException("Unknown person on the plan", Response.Status.NOT_FOUND);
        }
        return stakeholder;
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

    private void requireActor(String actor) {
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
