package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateDocument;
import dk.trustworks.intranet.recruitmentservice.dto.FactsLedgerResponse;
import dk.trustworks.intranet.recruitmentservice.dto.FormAnswer;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomAiFlags;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomCandidate;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomDraft;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomInterview;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomPriorRound;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomShelf;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse.RoomSubject;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewResponse;
import dk.trustworks.intranet.recruitmentservice.dto.NoteRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomDraftRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomFactRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomLandRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomLandResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomPresenceResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomPresenceResponse.PresenceEntry;
import dk.trustworks.intranet.recruitmentservice.dto.ScorecardSubmitRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.AskRole;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactField;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary.FactGroup;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterviewNote;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidanceCatalog;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Interview Room's command and read-model service (room spec
 * 2026-08-26 §5, §6.2): the one-round-trip room read model, draft
 * autosave with the last-write-wins revision guard, the presence poll,
 * and the atomic land. The resource has already resolved the interview
 * and the viewer's grant (assigned interviewer or full-profile reader);
 * everything here trusts that resolution and applies the finer rules —
 * the restricted shelf, the blind rule (via
 * {@link RecruitmentInterviewService#scorecardsFor}), the comp scoping of
 * the ledger.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentInterviewRoomService {

    /** Presence window: a draft touched within this many seconds = in the room. */
    static final int PRESENCE_ACTIVE_SECONDS = 60;

    /** Draft caps — a generous ceiling, not a target (24–40 visible lines). */
    static final int MAX_DRAFT_LINES = 2000;
    static final int MAX_DRAFT_BYTES = 1_000_000;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager em;

    @Inject
    RecruitmentInterviewService interviewService;

    @Inject
    CandidateService candidateService;

    @Inject
    RecruitmentFactLedgerService factLedgerService;

    @Inject
    CandidateProfileReadService profileReadService;

    @Inject
    CandidateBriefService briefService;

    @Inject
    RecruitmentAiFeatureFlag aiFeatureFlag;

    @Inject
    CandidateAiReadService aiReadService;

    @Inject
    dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder eventRecorder;

    // ------------------------------------------------------------------
    // The room read model
    // ------------------------------------------------------------------

    /**
     * Assemble the room in one round trip.
     *
     * @param viewerUuid the caller (assigned interviewer or profile reader)
     * @param restricted the caller holds only the interviewer grant — the
     *                   shelf is brief-scoped, compensation and competition
     *                   are absent (decision 6, spec §7.3)
     * @param compTier   whether the caller reads compensation values
     */
    public InterviewRoomResponse room(String viewerUuid,
                                      RecruitmentInterview interview,
                                      RecruitmentApplication application,
                                      RecruitmentPosition position,
                                      RecruitmentCandidate candidate,
                                      boolean restricted,
                                      boolean compTier) {
        List<RoomSubject> subjects = subjectsFor(position);
        // The brief deliberately excludes other people's scorecards, before
        // or after the blind unlock (CandidateBriefResponse) — a restricted
        // shelf therefore carries NO prior rounds at all; the blind filter
        // would otherwise hand an unlocked round's panel to a viewer the
        // brief boundary excludes (security review, decision 6).
        List<RoomPriorRound> priorRounds = restricted
                ? List.of()
                : priorRounds(viewerUuid, interview, application, position);

        FactsLedgerResponse facts = null;
        List<String> gapFields;
        if (restricted) {
            // The restricted lane names start date and never compensation
            // (spec §5.1); competition and references stay behind the
            // candidate boundary the viewer does not hold (decision 5/6).
            FactsLedgerResponse full = factLedgerService.ledger(candidate, false);
            gapFields = gaps(full, Set.of(FactGroup.TIMING), null);
        } else {
            facts = factLedgerService.ledger(candidate, compTier);
            // Compensation gaps surface only for comp-tier viewers — a
            // technical interviewer's prep lane never asks for salary.
            gapFields = gaps(facts,
                    Set.of(FactGroup.values()),
                    compTier ? null : FactGroup.COMPENSATION);
        }

        // Scoped to THIS interview's application, restricted or not: the room
        // is one interview's surface, and the history it shows belongs to the
        // application that interview hangs off.
        List<dk.trustworks.intranet.recruitmentservice.dto.CandidateAiStateResponse.AiEmployment>
                employment = aiReadService.employmentForApplications(
                        List.of(interview.getApplicationUuid()));
        RoomShelf shelf = restricted
                ? new RoomShelf(briefService.briefDocuments(candidate.getUuid()),
                        profileReadService.answersForCandidate(candidate.getUuid()).answers(),
                        employment)
                : new RoomShelf(profileReadService.documents(candidate.getUuid(), false).documents(),
                        profileReadService.answersForCandidate(candidate.getUuid()).answers(),
                        employment);

        RoomCandidate roomCandidate = restricted
                ? new RoomCandidate(candidate.getUuid(), fullName(candidate), null,
                        candidate.getLinkedinUrl(), null)
                : new RoomCandidate(candidate.getUuid(), fullName(candidate),
                        candidate.getEmail(), candidate.getLinkedinUrl(),
                        candidate.getTargetStartDate() == null ? null
                                : candidate.getTargetStartDate().toString());

        RecruitmentInterviewNote draft = findDraft(interview.getUuid(), viewerUuid);
        boolean scorecardSubmitted = RecruitmentScorecard.count(
                "interviewUuid = ?1 and interviewerUuid = ?2",
                interview.getUuid(), viewerUuid) > 0;

        Map<String, String> names = resolveNames(interview.getInterviewerUuids());
        List<InterviewResponse.InterviewerInfo> interviewers = interview.getInterviewerUuids()
                .stream()
                .map(uuid -> new InterviewResponse.InterviewerInfo(uuid,
                        names.getOrDefault(uuid, "Unknown"), false))
                .toList();

        return new InterviewRoomResponse(
                new RoomInterview(interview.getUuid(), interview.getKind().name(),
                        interview.getRound(),
                        interview.getScheduledAt() == null ? null
                                : interview.getScheduledAt().toString(),
                        interview.getDurationMinutes(), interview.getLocation(),
                        interview.getJoinUrl(), interview.getStatus().name(), interviewers,
                        application.getUuid(), position.getUuid(), position.getTitle()),
                roomCandidate,
                restricted,
                ScorecardGuidanceCatalog.USAGE_NOTE,
                subjects,
                priorRounds,
                facts,
                gapFields,
                draft == null ? null
                        : new RoomDraft(parseLines(draft.getLines()), draft.getClientRevision(),
                                draft.getUpdatedAt() == null ? null : draft.getUpdatedAt().toString()),
                shelf,
                scorecardSubmitted,
                new RoomAiFlags(aiFeatureFlag.isInterviewRoomPrepEnabled(),
                        aiFeatureFlag.isInterviewRoomExtractionEnabled(),
                        aiFeatureFlag.isInterviewRoomTidyEnabled(),
                        aiFeatureFlag.isInterviewRoomAlignmentEnabled()));
    }

    /** Guidance per template code — custom codes get label-only entries. */
    private List<RoomSubject> subjectsFor(RecruitmentPosition position) {
        List<ScorecardAttribute> template = position.getScorecardTemplate();
        if (template == null || template.isEmpty()) {
            return ScorecardGuidanceCatalog.standard().stream()
                    .map(g -> new RoomSubject(g.code(), g.label(), g.shortHint(),
                            g.whatYouAreScoring(), g.probes(), g.anchors()))
                    .toList();
        }
        return template.stream()
                .map(attribute -> ScorecardGuidanceCatalog.forCode(attribute.code())
                        .map(g -> new RoomSubject(g.code(), g.label(), g.shortHint(),
                                g.whatYouAreScoring(), g.probes(), g.anchors()))
                        .orElseGet(() -> new RoomSubject(attribute.code(), attribute.label(),
                                null, null, List.of(), List.of())))
                .toList();
    }

    /** Earlier ROUND interviews with blind-filtered scorecards for this viewer. */
    private List<RoomPriorRound> priorRounds(String viewerUuid,
                                             RecruitmentInterview current,
                                             RecruitmentApplication application,
                                             RecruitmentPosition position) {
        List<RecruitmentInterview> rounds = RecruitmentInterview.list(
                "applicationUuid = ?1 and kind = ?2 and status <> 'CANCELLED' "
                        + "and uuid <> ?3 order by round, createdAt",
                application.getUuid(), RecruitmentInterviewKind.ROUND, current.getUuid());
        return rounds.stream()
                .filter(round -> round.getRound() != null
                        && (current.getRound() == null || round.getRound() < current.getRound()))
                .map(round -> new RoomPriorRound(round.getUuid(), round.getRound(),
                        round.getScheduledAt() == null ? null : round.getScheduledAt().toString(),
                        interviewService.scorecardsFor(viewerUuid, round, application, position)))
                .toList();
    }

    /** Fields still UNKNOWN/ASKED/STALE, filtered to the viewer's lane. */
    private static List<String> gaps(FactsLedgerResponse ledger,
                                     Set<FactGroup> includedGroups,
                                     FactGroup excludedGroup) {
        Set<String> open = ledger.facts().stream()
                .filter(f -> "UNKNOWN".equals(f.state()) || "ASKED".equals(f.state())
                        || "STALE".equals(f.state()))
                .map(FactsLedgerResponse.FactEntry::field)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return RecruitmentFactVocabulary.all().stream()
                .filter(field -> open.contains(field.key()))
                .filter(field -> includedGroups.contains(field.group()))
                .filter(field -> excludedGroup == null || field.group() != excludedGroup)
                // Required-or-useful ordering: required gaps first.
                .map(FactField::key)
                .toList();
    }

    // ------------------------------------------------------------------
    // Draft autosave
    // ------------------------------------------------------------------

    /**
     * Upsert the caller's draft. Revision semantics (spec §4.1): LOWER than
     * stored ⇒ 409 (the room offers to reload); equal ⇒ idempotent re-save
     * that bumps {@code updated_at} — the presence heartbeat; higher ⇒
     * store. Creation races on the UNIQUE (interview, author) key resolve
     * as a plain conflict for the loser.
     */
    @Transactional
    public RoomDraft saveDraft(RecruitmentInterview interview, String authorUuid,
                               RoomDraftRequest request) {
        Objects.requireNonNull(request, "request body must not be null");
        String serialized = validateLines(request.lines());
        RecruitmentInterviewNote draft = findDraft(interview.getUuid(), authorUuid);
        if (draft == null) {
            draft = new RecruitmentInterviewNote();
            draft.setInterviewUuid(interview.getUuid());
            draft.setAuthorUuid(authorUuid);
            draft.setLines(serialized);
            draft.setClientRevision(request.clientRevision());
            draft.persist();
        } else {
            if (request.clientRevision() < draft.getClientRevision()) {
                throw new WebApplicationException(
                        "Stale draft revision " + request.clientRevision()
                                + " (stored " + draft.getClientRevision() + ") — reload the room",
                        Response.Status.CONFLICT);
            }
            draft.setLines(serialized);
            draft.setClientRevision(request.clientRevision());
            // The audit listener bumps updated_at; touch it explicitly so an
            // equal-revision heartbeat with unchanged lines still registers.
            draft.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return new RoomDraft(parseLines(draft.getLines()), draft.getClientRevision(),
                LocalDateTime.now(ZoneOffset.UTC).toString());
    }

    /** Discard the caller's own draft. Idempotent. */
    @Transactional
    public boolean deleteDraft(RecruitmentInterview interview, String authorUuid) {
        return RecruitmentInterviewNote.delete(
                "interviewUuid = ?1 and authorUuid = ?2",
                interview.getUuid(), authorUuid) > 0;
    }

    // ------------------------------------------------------------------
    // Presence
    // ------------------------------------------------------------------

    /**
     * Who is in the room and how many lines each has — NEVER text (spec
     * §5.2). Derived from draft {@code updated_at}: the room autosaves on
     * pause and heartbeats on an equal revision, so a live tab keeps its
     * row fresh.
     */
    public RoomPresenceResponse presence(RecruitmentInterview interview) {
        List<RecruitmentInterviewNote> drafts = RecruitmentInterviewNote.list(
                "interviewUuid = ?1 order by updatedAt desc", interview.getUuid());
        Map<String, String> names = resolveNames(drafts.stream()
                .map(RecruitmentInterviewNote::getAuthorUuid).toList());
        LocalDateTime activeSince =
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(PRESENCE_ACTIVE_SECONDS);
        List<PresenceEntry> entries = drafts.stream()
                .map(draft -> new PresenceEntry(
                        draft.getAuthorUuid(),
                        names.getOrDefault(draft.getAuthorUuid(), "Unknown"),
                        countLines(draft.getLines()),
                        draft.getUpdatedAt() == null ? null : draft.getUpdatedAt().toString(),
                        draft.getUpdatedAt() != null && draft.getUpdatedAt().isAfter(activeSince)))
                .toList();
        return new RoomPresenceResponse(entries);
    }

    // ------------------------------------------------------------------
    // Land — one transaction (spec §5.3)
    // ------------------------------------------------------------------

    /**
     * The atomic land: {@code SCORECARD_SUBMITTED} (unchanged shape) + one
     * {@code NOTE_ADDED} per confirmed fact + draft delete, all in ONE
     * transaction — partial success is not a state that exists. Kinds that
     * take no scorecard land their prose as a plain note instead.
     * <p>
     * Authorization is the interview assignment (the resource enforced it);
     * fact writes deliberately bypass the per-person candidate-visibility
     * gate — a restricted interviewer capturing "three months' notice" IS
     * the feature (spec §7.1: anyone entitled to write a fact may write it
     * if the candidate volunteers it).
     */
    @Transactional
    public RoomLandResponse land(RecruitmentInterview interview,
                                 RecruitmentApplication application,
                                 RecruitmentPosition position,
                                 RecruitmentCandidate candidate,
                                 RoomLandRequest request,
                                 java.util.UUID actor) {
        Objects.requireNonNull(request, "request body must not be null");
        validateFacts(request.facts());

        String scorecardUuid = null;
        if (interview.getKind().takesScorecard()) {
            RecruitmentScorecard scorecard = interviewService.submitScorecard(
                    interview, application, position,
                    new ScorecardSubmitRequest(request.scores(), request.recommendation(),
                            request.notes()),
                    actor, RecruitmentInterviewService.ORIGIN_ROOM);
            scorecardUuid = scorecard.getUuid();
        } else if (request.notes() != null && !request.notes().isBlank()) {
            // The interview uuid rides along (payload.interview_uuid) exactly
            // as it does on fact notes. Without it a screening's notes land in
            // the discussion as an unattributed wall of text with no way back
            // to the conversation they came from — which is precisely how they
            // became unfindable.
            candidateService.addNote(java.util.UUID.fromString(candidate.getUuid()),
                    new NoteRequest(request.notes(), false, null, null, null, null,
                            interview.getUuid()),
                    actor, "interview_room", null);
        }

        int factsRecorded = 0;
        for (RoomLandRequest.LandFact fact : nullSafe(request.facts())) {
            appendFact(interview, candidate, actor, fact.field(), fact.value(),
                    fact.confirmed(), fact.asked(), fact.suggestionId());
            factsRecorded++;
        }

        boolean draftDeleted = RecruitmentInterviewNote.delete(
                "interviewUuid = ?1 and authorUuid = ?2",
                interview.getUuid(), actor.toString()) > 0;

        return new RoomLandResponse(scorecardUuid, factsRecorded, draftDeleted);
    }

    /**
     * The live ⌥↵ capture (spec §5.2): one fact note, authorized by the
     * interview assignment the resource enforced — the room-scoped sibling
     * of the notes route (see {@link RoomFactRequest}).
     *
     * @return the appended NOTE_ADDED event
     */
    @Transactional
    public dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent recordFact(
            RecruitmentInterview interview,
            RecruitmentCandidate candidate,
            RoomFactRequest request,
            java.util.UUID actor) {
        Objects.requireNonNull(request, "request body must not be null");
        if (!RecruitmentFactVocabulary.isKnown(request.field())) {
            throw new BusinessRuleViolation("Unknown fact field: " + request.field());
        }
        if (!request.asked() && (request.value() == null || request.value().isBlank())) {
            throw new BusinessRuleViolation(
                    "A fact needs a value unless it is marked asked: " + request.field());
        }
        return appendFact(interview, candidate, actor, request.field(), request.value(),
                request.confirmed(), request.asked(), request.suggestionId());
    }

    /**
     * Resolve a fact-bearing {@code NOTE_ADDED} on this candidate, or 404.
     *
     * <p>Separate from the redaction itself because the caller has to know
     * the FIELD before it can apply the compensation gates — and it must
     * apply them before anything is written, not after.
     */
    public dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent requireFactNote(
            RecruitmentCandidate candidate, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new BusinessRuleViolation("eventId is required");
        }
        var note = dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent
                .<dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent>
                        find("eventId", eventId).firstResult();
        if (note == null
                || !candidate.getUuid().equals(note.getCandidateUuid())
                || note.getEventType() != dk.trustworks.intranet.recruitmentservice.events
                        .RecruitmentEventType.NOTE_ADDED) {
            throw new jakarta.ws.rs.NotFoundException("Fact not found: " + eventId);
        }
        return note;
    }

    /** The vocabulary field a fact note carries, or 400 when it carries none. */
    public String factFieldOf(
            dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent note) {
        String field = null;
        try {
            var payload = objectMapper.readValue(
                    note.getPayload() == null ? "{}" : note.getPayload(),
                    new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.Map<String, Object>>() {
                    });
            if (payload.get("field") instanceof String f) {
                field = f;
            }
        } catch (Exception e) {
            field = null;
        }
        if (!RecruitmentFactVocabulary.isKnown(field)) {
            throw new BusinessRuleViolation(
                    "Only a recorded fact can be redacted — this note carries no field");
        }
        return field;
    }

    /** One NOTE_ADDED fact append + the AI resolve bookkeeping when accepted from a chip. */
    private dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent appendFact(
            RecruitmentInterview interview, RecruitmentCandidate candidate,
            java.util.UUID actor, String field, String value,
            boolean confirmed, boolean asked, String suggestionId) {
        var event = candidateService.addNote(java.util.UUID.fromString(candidate.getUuid()),
                new NoteRequest(
                        asked ? "(asked — nothing usable yet)" : value,
                        false, field, null,
                        asked ? NoteRequest.OUTCOME_ASKED : null,
                        confirmed ? Boolean.TRUE : null,
                        interview.getUuid()),
                actor, "interview_room", null);
        if (suggestionId != null && suggestionId.length() > 64) {
            // Client-controlled string headed for a structural payload —
            // a real id is a UUID; anything longer is garbage or worse.
            suggestionId = suggestionId.substring(0, 64);
        }
        if (suggestionId != null && !suggestionId.isBlank()) {
            // A model never writes a fact — the human accepted this one, and
            // that decision is recorded (spec §5.4, §9 oversight contract).
            eventRecorder.record(dk.trustworks.intranet.recruitmentservice.events
                    .RecruitmentEventBuilder
                    .event(dk.trustworks.intranet.recruitmentservice.events
                            .RecruitmentEventType.AI_SUGGESTION_RESOLVED)
                    .candidate(candidate.getUuid())
                    .actorUser(actor.toString())
                    .payload("suggestion_id", suggestionId)
                    .payload("field", field)
                    .payload("accepted", true)
                    .payload("origin", "INTERVIEW_ROOM")
                    .payload("interview_uuid", interview.getUuid()));
        }
        return event;
    }

    private static void validateFacts(List<RoomLandRequest.LandFact> facts) {
        for (RoomLandRequest.LandFact fact : nullSafe(facts)) {
            if (!RecruitmentFactVocabulary.isKnown(fact.field())) {
                throw new BusinessRuleViolation("Unknown fact field: " + fact.field());
            }
            if (!fact.asked() && (fact.value() == null || fact.value().isBlank())) {
                throw new BusinessRuleViolation(
                        "A fact needs a value unless it is marked asked: " + fact.field());
            }
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Which vocabulary keys the viewer's prep lane may raise, per the
     * whose-job markers (spec §7.1) — exposed for the resource's gap
     * filtering and tested directly.
     */
    public static Set<String> askableKeys(boolean recruiterTier, boolean hiringOwner) {
        return RecruitmentFactVocabulary.all().stream()
                .filter(field -> switch (field.askRole()) {
                    case RECRUITER -> recruiterTier;
                    case HIRING_OWNER -> recruiterTier || hiringOwner;
                    case ANY_INTERVIEWER -> true;
                })
                .map(FactField::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private RecruitmentInterviewNote findDraft(String interviewUuid, String authorUuid) {
        return RecruitmentInterviewNote.<RecruitmentInterviewNote>find(
                        "interviewUuid = ?1 and authorUuid = ?2", interviewUuid, authorUuid)
                .firstResult();
    }

    /**
     * The draft must be a JSON array of at most {@link #MAX_DRAFT_LINES}
     * objects, serialized at most {@link #MAX_DRAFT_BYTES} — the backend
     * stores the array whole and never interprets individual lines.
     */
    String validateLines(JsonNode lines) {
        if (lines == null || !lines.isArray()) {
            throw new WebApplicationException("lines must be a JSON array",
                    Response.Status.BAD_REQUEST);
        }
        if (lines.size() > MAX_DRAFT_LINES) {
            throw new WebApplicationException(
                    "Draft exceeds " + MAX_DRAFT_LINES + " lines", Response.Status.BAD_REQUEST);
        }
        String serialized = lines.toString();
        if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
            throw new WebApplicationException("Draft exceeds the size limit",
                    Response.Status.BAD_REQUEST);
        }
        return serialized;
    }

    private JsonNode parseLines(String lines) {
        try {
            return objectMapper.readTree(lines);
        } catch (Exception e) {
            log.warnf("Unparseable draft lines JSON — returning empty array");
            return objectMapper.createArrayNode();
        }
    }

    private int countLines(String lines) {
        JsonNode node = parseLines(lines);
        return node.isArray() ? node.size() : 0;
    }

    private static String fullName(RecruitmentCandidate candidate) {
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        return (first + " " + last).trim();
    }

    private Map<String, String> resolveNames(Collection<String> userUuids) {
        List<String> distinct = userUuids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT uuid, TRIM(CONCAT(COALESCE(firstname, ''), ' ', COALESCE(lastname, '')))
                        FROM user
                        WHERE uuid IN (:uuids)
                        """)
                .setParameter("uuids", distinct)
                .getResultList();
        return rows.stream()
                .filter(row -> row[1] != null && !((String) row[1]).isBlank())
                .collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
    }
}
