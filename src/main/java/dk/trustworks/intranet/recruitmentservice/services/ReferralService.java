package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.ai.RecruitmentAiDirectory;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralRow;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralAiSuggestions;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralRow;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TriageQueueAnswer;
import dk.trustworks.intranet.recruitmentservice.dto.TriageQueueCandidate;
import dk.trustworks.intranet.recruitmentservice.dto.TriageQueueResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralClosedReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralRelation;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Command and query handlers for the referral channel (ATS plan §P6).
 * Every mutation persists state and appends its {@code REFERRAL_*} event
 * through {@link RecruitmentEventRecorder} in the same transaction.
 * <p>
 * The triage create leg deliberately composes the existing paths instead
 * of re-implementing them (findings §P3/§P4 carry-overs):
 * {@link CandidateService#createCandidate} owns candidate invariants and
 * GDPR Art. 14 bookkeeping (and emits {@code CANDIDATE_CREATED});
 * {@link RecruitmentApplicationService#create} owns the attach invariants
 * (and emits {@code APPLICATION_CREATED}).
 * <p>
 * Command methods are callable without JAX-RS context — the P14 Slack
 * {@code /refer} twin reuses them verbatim; validation therefore lives
 * HERE, not in the resource (the module has no active bean-validation
 * extension — findings §P4).
 */
@JBossLog
@ApplicationScoped
public class ReferralService {

    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_LINKEDIN_LENGTH = 500;
    static final int MAX_EMAIL_LENGTH = 255;
    static final int MAX_WHY_LENGTH = 2000;

    /** Same basic shape check as the P5 public form. */
    private static final Pattern EMAIL_FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    CandidateService candidateService;

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentAiFeatureFlag aiFeatureFlag;

    @Inject
    RecruitmentAiDirectory aiDirectory;

    @Inject
    ObjectMapper objectMapper;

    // ---- Submit ---------------------------------------------------------------

    /**
     * Persist a new referral (status {@code SUBMITTED}) and append
     * {@code REFERRAL_SUBMITTED}: structural facts in payload
     * ({@code referral_uuid}, {@code relation}, {@code has_linkedin},
     * {@code has_email}, {@code origin=web}), every personal field in pii.
     *
     * @param actor the submitting employee (X-Requested-By)
     * @throws WebApplicationException 400 on any invalid field
     */
    @Transactional
    public ReferralCreateResponse submit(ReferralCreateRequest request, UUID actor) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        String candidateName = requireLength(request.candidateName(), "candidateName", MAX_NAME_LENGTH);
        String whyText = requireLength(request.whyText(), "whyText", MAX_WHY_LENGTH);
        RecruitmentReferralRelation relation = parseEnum(RecruitmentReferralRelation.class,
                request.referrerRelation(), "referrerRelation");
        String linkedinUrl = optionalLinkedin(request.linkedinUrl());
        String email = optionalEmail(request.email());
        String externalReferrerName = optionalLength(
                request.externalReferrerName(), "externalReferrerName", MAX_NAME_LENGTH);

        RecruitmentReferral referral = new RecruitmentReferral();
        referral.setReferrerUuid(actor.toString());
        referral.setReferrerRelation(relation);
        referral.setExternalReferrerName(externalReferrerName);
        referral.setCandidateName(candidateName);
        referral.setLinkedinUrl(linkedinUrl);
        referral.setEmail(email);
        referral.setWhyText(whyText);
        referral.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        referral.persist();

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.REFERRAL_SUBMITTED)
                .actorUser(actor.toString())
                .payload("referral_uuid", referral.getUuid())
                .payload("relation", relation.name())
                .payload("has_linkedin", linkedinUrl != null)
                .payload("has_email", email != null)
                .payload("origin", "web")
                .pii("candidate_name", candidateName)
                .pii("why_text", whyText);
        piiIfPresent(event, "linkedin_url", linkedinUrl);
        piiIfPresent(event, "email", email);
        piiIfPresent(event, "external_referrer_name", externalReferrerName);
        eventRecorder.record(event);

        log.infof("Referral submitted: %s (relation=%s) by actor=%s",
                referral.getUuid(), relation, actor);
        return new ReferralCreateResponse(referral.getUuid());
    }

    // ---- My referrals ---------------------------------------------------------

    /**
     * The caller's own referrals, newest first, each with its milestone-level
     * {@link RecruitmentReferralDerivedStatus} computed live (plan §P6 —
     * pipeline state is never mirrored onto the referral row). Derivation is
     * batched: one query for the linked candidates, one for those
     * candidates' applications — no per-row lookups.
     */
    public MyReferralsResponse listMine(UUID referrer) {
        Objects.requireNonNull(referrer, "referrer must not be null");
        List<RecruitmentReferral> referrals = RecruitmentReferral.list(
                "referrerUuid = ?1 order by submittedAt desc", referrer.toString());

        List<String> candidateUuids = referrals.stream()
                .map(RecruitmentReferral::getCandidateUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, RecruitmentCandidate> candidates = candidateUuids.isEmpty() ? Map.of()
                : RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1", candidateUuids).stream()
                        .collect(Collectors.toMap(RecruitmentCandidate::getUuid, Function.identity()));
        Map<String, List<RecruitmentApplication>> applications = candidateUuids.isEmpty() ? Map.of()
                : RecruitmentApplication.<RecruitmentApplication>list("candidateUuid in ?1", candidateUuids).stream()
                        .collect(Collectors.groupingBy(RecruitmentApplication::getCandidateUuid));

        List<MyReferralRow> rows = referrals.stream()
                .map(r -> {
                    // Untriaged/dismissed rows have no candidate uuid — and
                    // Map.of() rejects null keys, so guard before the lookups.
                    String candidateUuid = r.getCandidateUuid();
                    return new MyReferralRow(
                            r.getUuid(),
                            r.getCandidateName(),
                            r.getReferrerRelation(),
                            r.getExternalReferrerName(),
                            r.getSubmittedAt(),
                            deriveStatus(r,
                                    candidateUuid == null ? null : candidates.get(candidateUuid),
                                    candidateUuid == null ? List.of()
                                            : applications.getOrDefault(candidateUuid, List.of())));
                })
                .toList();
        return new MyReferralsResponse(rows, rows.size());
    }

    /**
     * Milestone-level status for the referrer (plan §P6, aligned with P12's
     * notification milestones). The referral row decides the two triage-side
     * cases; everything else derives from the candidate and its
     * applications:
     * <ol>
     *   <li>{@code SUBMITTED} → AWAITING_TRIAGE; {@code CLOSED} → CLOSED;</li>
     *   <li>candidate HIRED → HIRED; POOLED → IN_TALENT_POOL;</li>
     *   <li>any open application → its furthest stage bucketed
     *       (SCREENING → IN_SCREENING, INTERVIEW_* → INTERVIEWING,
     *       OFFER → OFFER);</li>
     *   <li>no open but ≥1 terminal application → NOT_PROCEEDING;</li>
     *   <li>candidate ACTIVE with no application → UNDER_REVIEW;</li>
     *   <li>anything else (ANONYMIZED, terminal without applications,
     *       missing row) → CLOSED.</li>
     * </ol>
     */
    static RecruitmentReferralDerivedStatus deriveStatus(RecruitmentReferral referral,
                                                         RecruitmentCandidate candidate,
                                                         List<RecruitmentApplication> applications) {
        if (referral.getStatus() == RecruitmentReferralStatus.SUBMITTED) {
            return RecruitmentReferralDerivedStatus.AWAITING_TRIAGE;
        }
        if (referral.getStatus() == RecruitmentReferralStatus.CLOSED || candidate == null) {
            return RecruitmentReferralDerivedStatus.CLOSED;
        }
        return deriveCandidateMilestone(candidate, applications);
    }

    /**
     * The candidate-side milestone derivation (steps 2–6 of
     * {@link #deriveStatus}), shared with P12's
     * {@code ReferrerNotificationReactor} so referrer DMs and the
     * "My referrals" page compute milestones from the exact same code
     * (findings §P6 carry-over).
     */
    public static RecruitmentReferralDerivedStatus deriveCandidateMilestone(
            RecruitmentCandidate candidate,
            List<RecruitmentApplication> applications) {
        if (candidate.getStatus() == CandidateStatus.HIRED) {
            return RecruitmentReferralDerivedStatus.HIRED;
        }
        if (candidate.getStatus() == CandidateStatus.POOLED) {
            return RecruitmentReferralDerivedStatus.IN_TALENT_POOL;
        }
        List<RecruitmentApplication> open = applications.stream()
                .filter(a -> a.getTerminal() == null)
                .toList();
        if (!open.isEmpty()) {
            // The furthest open stage wins — RecruitmentStage ordinal is the
            // canonical pipeline order (P2 carry-over).
            RecruitmentApplication furthest = open.stream()
                    .max(Comparator.comparing(a -> a.getStage().ordinal()))
                    .orElseThrow();
            return switch (furthest.getStage()) {
                case SCREENING -> RecruitmentReferralDerivedStatus.IN_SCREENING;
                case INTERVIEW_1, INTERVIEW_2, INTERVIEW_3 -> RecruitmentReferralDerivedStatus.INTERVIEWING;
                case OFFER -> RecruitmentReferralDerivedStatus.OFFER;
                case HIRED -> RecruitmentReferralDerivedStatus.HIRED;
            };
        }
        if (!applications.isEmpty()) {
            return RecruitmentReferralDerivedStatus.NOT_PROCEEDING;
        }
        if (candidate.getStatus() == CandidateStatus.ACTIVE) {
            return RecruitmentReferralDerivedStatus.UNDER_REVIEW;
        }
        return RecruitmentReferralDerivedStatus.CLOSED;
    }

    // ---- Pending (recruiter triage queue) --------------------------------------

    /**
     * The recruiter triage queue: SUBMITTED referrals, oldest first, with
     * the full referral facts. Referrer display names are resolved
     * client-side from {@code referrerUuid} (P2 precedent). Recruiter-tier
     * enforcement lives HERE as well as in the resource (defense in depth
     * — these are the documented P14 reuse points, and a future caller
     * must not be able to forget the check).
     *
     * @param actor the requesting user — must be recruiter tier (403 otherwise)
     */
    public PendingReferralsResponse listPending(UUID actor) {
        requireRecruiterTier(actor);
        List<RecruitmentReferral> referrals = RecruitmentReferral.list(
                "status = ?1 order by submittedAt", RecruitmentReferralStatus.SUBMITTED);
        Map<String, PendingReferralAiSuggestions> aiSuggestions =
                pendingAiSuggestions(referrals.stream().map(RecruitmentReferral::getUuid).toList());
        List<PendingReferralRow> rows = referrals.stream()
                .map(r -> new PendingReferralRow(
                        r.getUuid(),
                        r.getReferrerUuid(),
                        r.getReferrerRelation(),
                        r.getExternalReferrerName(),
                        r.getCandidateName(),
                        r.getLinkedinUrl(),
                        r.getEmail(),
                        r.getWhyText(),
                        r.getSubmittedAt(),
                        aiSuggestions.get(r.getUuid())))
                .toList();
        return new PendingReferralsResponse(rows, rows.size());
    }

    // ---- Pending-row AI suggestions (P9, contract §6.3) -------------------------

    /** Bounded scan over the latest referral-variant AI events (newest first). */
    static final int AI_SUGGESTION_SCAN_CAP = 500;

    /**
     * The latest AI triage suggestions per pending referral, re-validated
     * at read time (contract §6.3): one bounded event query (referral
     * variants are the {@code AI_SUGGESTIONS_GENERATED} events WITHOUT a
     * candidate subject — dossier §2.3), Java-joined on
     * {@code payload.referral_uuid}, newest generation wins. A
     * since-deactivated practice / no-longer-leading teamlead / invalid
     * experience level nulls that field; names resolved batched (no N+1).
     * Empty map when the referral-triage toggle is off.
     */
    private Map<String, PendingReferralAiSuggestions> pendingAiSuggestions(List<String> referralUuids) {
        if (referralUuids.isEmpty() || !aiFeatureFlag.isReferralTriageEnabled()) {
            return Map.of();
        }
        Set<String> wanted = new HashSet<>(referralUuids);
        List<RecruitmentEvent> events = RecruitmentEvent
                .<RecruitmentEvent>find("eventType = ?1 and candidateUuid is null order by seq desc",
                        RecruitmentEventType.AI_SUGGESTIONS_GENERATED)
                .page(0, AI_SUGGESTION_SCAN_CAP)
                .list();

        // Latest event per referral (list is seq-descending — first wins).
        Map<String, RecruitmentEvent> latestByReferral = new HashMap<>();
        Map<Long, Map<String, Object>> payloads = new HashMap<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parseJson(event.getPayload());
            payloads.put(event.getSeq(), payload);
            if (payload.get("referral_uuid") instanceof String uuid && wanted.contains(uuid)) {
                latestByReferral.putIfAbsent(uuid, event);
            }
        }
        if (latestByReferral.isEmpty()) {
            return Map.of();
        }

        // Read-time re-validation context, each fetched once.
        Map<String, String> activePracticeNames = aiDirectory.activePractices().stream()
                .collect(Collectors.toMap(
                        dk.trustworks.intranet.recruitmentservice.ai.AiReferralTriagePrompts.Option::uuid,
                        dk.trustworks.intranet.recruitmentservice.ai.AiReferralTriagePrompts.Option::name));
        Set<String> currentTeamleads = aiDirectory.currentTeamleadUuids();

        Map<String, PendingReferralAiSuggestions> out = new HashMap<>();
        Set<String> teamleadUuidsToName = new HashSet<>();
        Map<String, String[]> rawByReferral = new HashMap<>();
        Map<String, String[]> rationalesByReferral = new HashMap<>();
        Map<String, LocalDateTime> generatedAtByReferral = new HashMap<>();

        for (Map.Entry<String, RecruitmentEvent> entry : latestByReferral.entrySet()) {
            RecruitmentEvent event = entry.getValue();
            // [practiceUuid, experienceLevel, teamleadUuid]
            String[] values = new String[3];
            String[] rationales = new String[3];
            for (Map<String, Object> suggestion : piiSuggestions(event)) {
                String field = suggestion.get("field") instanceof String f ? f : "";
                String value = suggestion.get("value") instanceof String v ? v : null;
                String rationale = suggestion.get("rationale") instanceof String r ? r : null;
                switch (field) {
                    case AiReferralTriageReactorFields.PRACTICE -> {
                        if (value != null && activePracticeNames.containsKey(value)) {
                            values[0] = value;
                            rationales[0] = rationale;
                        }
                    }
                    case AiReferralTriageReactorFields.EXPERIENCE_LEVEL -> {
                        if (value != null && isValidExperienceLevel(value)) {
                            values[1] = value;
                            rationales[1] = rationale;
                        }
                    }
                    case AiReferralTriageReactorFields.RELEVANT_TEAMLEAD -> {
                        if (value != null && currentTeamleads.contains(value)) {
                            values[2] = value;
                            rationales[2] = rationale;
                            teamleadUuidsToName.add(value);
                        }
                    }
                    default -> {
                        // Unknown field code — ignore.
                    }
                }
            }
            if (values[0] == null && values[1] == null && values[2] == null) {
                continue; // everything invalidated at read time — no panel
            }
            rawByReferral.put(entry.getKey(), values);
            rationalesByReferral.put(entry.getKey(), rationales);
            generatedAtByReferral.put(entry.getKey(), event.getOccurredAt());
        }

        Map<String, String> teamleadNames = aiDirectory.userNamesByUuid(teamleadUuidsToName);
        for (Map.Entry<String, String[]> entry : rawByReferral.entrySet()) {
            String[] values = entry.getValue();
            String[] rationales = rationalesByReferral.get(entry.getKey());
            out.put(entry.getKey(), new PendingReferralAiSuggestions(
                    values[0],
                    values[0] != null ? activePracticeNames.get(values[0]) : null,
                    values[1],
                    values[2],
                    values[2] != null ? teamleadNames.get(values[2]) : null,
                    new PendingReferralAiSuggestions.Rationales(
                            rationales[0], rationales[1], rationales[2]),
                    generatedAtByReferral.get(entry.getKey())));
        }
        return out;
    }

    /** Field codes of the referral-variant AI suggestions (contract §4.2). */
    private static final class AiReferralTriageReactorFields {
        static final String PRACTICE = "PRACTICE";
        static final String EXPERIENCE_LEVEL = "EXPERIENCE_LEVEL";
        static final String RELEVANT_TEAMLEAD = "RELEVANT_TEAMLEAD";
    }

    private static boolean isValidExperienceLevel(String value) {
        try {
            CandidateExperienceLevel.valueOf(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<Map<String, Object>> piiSuggestions(RecruitmentEvent event) {
        Map<String, Object> pii = parseJson(event.getPii());
        List<Map<String, Object>> out = new ArrayList<>();
        if (pii.get("suggestions") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> suggestion = (Map<String, Object>) map;
                    out.add(suggestion);
                }
            }
        }
        return out;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ---- Triage ---------------------------------------------------------------

    /**
     * One-shot recruiter decision on a SUBMITTED referral.
     * <ul>
     *   <li><b>CREATE_CANDIDATE</b>: builds a {@link CandidateRequest} and
     *       calls {@link CandidateService#createCandidate} (source
     *       {@code REFERRAL}, or {@code PARTNER_REFERRAL} when a sponsoring
     *       partner is named; {@code referredByUserUuid} = the referrer;
     *       Art. 14 bookkeeping for free) — then optionally attaches an
     *       application via {@link RecruitmentApplicationService#create}
     *       (all P4 invariants; conflicts propagate as 409). Referral →
     *       TRIAGED, or CONVERTED when attached.</li>
     *   <li><b>DISMISS</b>: referral → CLOSED with the coded reason.</li>
     * </ul>
     * Both legs stamp {@code triaged_at}/{@code triaged_by_useruuid} and
     * append {@code REFERRAL_TRIAGED} in the same transaction.
     *
     * @throws NotFoundException     unknown referral; unknown or invisible
     *                               position on the attach leg
     * @throws BusinessRuleViolation referral not SUBMITTED (triage is
     *                               one-shot), or an attach invariant fired
     *                               — both surface as 409
     * @throws WebApplicationException 400 on invalid input, 403 when the
     *                               actor lacks decision rights on the
     *                               attach position
     */
    @Transactional
    public ReferralTriageResponse triage(UUID referralUuid, ReferralTriageRequest request, UUID actor) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireRecruiterTier(actor);
        RecruitmentReferral referral = RecruitmentReferral.findById(referralUuid.toString());
        if (referral == null) {
            throw new NotFoundException("Referral not found: " + referralUuid);
        }
        if (referral.getStatus() != RecruitmentReferralStatus.SUBMITTED) {
            // Checked BEFORE any side effect so a double-click (or the P14
            // Slack action retry) conflicts cleanly instead of creating a
            // second candidate.
            throw new BusinessRuleViolation(
                    "Referral %s is already %s — a referral is triaged exactly once"
                            .formatted(referral.getUuid(), referral.getStatus()));
        }

        String action = request.action() == null ? "" : request.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "CREATE_CANDIDATE" -> triageCreate(referral, request, actor);
            case "DISMISS" -> triageDismiss(referral, request, actor);
            default -> throw badRequest("action is required: CREATE_CANDIDATE or DISMISS");
        };
    }

    private ReferralTriageResponse triageCreate(RecruitmentReferral referral,
                                                ReferralTriageRequest request, UUID actor) {
        String firstName = requireLength(request.firstName(), "firstName", 100);
        String lastName = requireLength(request.lastName(), "lastName", 100);
        String email = optionalEmail(request.email());
        String phone = optionalLength(request.phone(), "phone", 50);
        // The recruiter can edit the link at triage — same strict host
        // validation as submit, or the XSS door reopens through this leg.
        String linkedinUrl = optionalLinkedin(request.linkedinUrl());
        String sponsoringPartnerUuid = trimToNull(request.sponsoringPartnerUuid());
        CandidateSource source = sponsoringPartnerUuid != null
                ? CandidateSource.PARTNER_REFERRAL
                : CandidateSource.REFERRAL;

        // Optional AI-suggested (always recruiter-editable) experience level
        // — validated explicitly, garbage answers 400 (P9, contract §6.4).
        CandidateExperienceLevel experienceLevel = optionalExperienceLevel(request.experienceLevel());

        // The existing ATS create path owns the invariants and the GDPR
        // Art. 14 bookkeeping — never bypassed (findings §P3 carry-over).
        CandidateResponse candidate = candidateService.createCandidate(new CandidateRequest(
                firstName, lastName, email, phone, linkedinUrl,
                null, null, null, null,
                source, null,
                referral.getReferrerUuid(), referral.getExternalReferrerName(),
                sponsoringPartnerUuid, trimToNull(request.relevantTeamleadUuid()),
                null, null, null, experienceLevel, null, null, null,
                null, null), actor);

        boolean attached = false;
        String positionUuid = trimToNull(request.positionUuid());
        if (positionUuid != null) {
            RecruitmentPosition position = requireDecidablePosition(positionUuid, actor);
            applicationService.create(
                    RecruitmentCandidate.findById(candidate.uuid()), position, actor);
            attached = true;
        }

        referral.triageToCandidate(candidate.uuid(), attached, actor);
        flushOrTriagedConcurrently(referral);
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.REFERRAL_TRIAGED)
                .candidate(candidate.uuid())
                .actorUser(actor.toString())
                .payload("referral_uuid", referral.getUuid())
                .payload("outcome", "CANDIDATE_CREATED"));

        log.infof("Referral %s triaged into candidate %s (source=%s, attached=%s) by actor=%s",
                referral.getUuid(), candidate.uuid(), source, attached, actor);
        return new ReferralTriageResponse(referral.getUuid(), referral.getStatus(), candidate.uuid());
    }

    private ReferralTriageResponse triageDismiss(RecruitmentReferral referral,
                                                 ReferralTriageRequest request, UUID actor) {
        RecruitmentReferralClosedReason reason = parseEnum(RecruitmentReferralClosedReason.class,
                request.dismissReason(), "dismissReason");
        referral.dismiss(reason, actor);
        flushOrTriagedConcurrently(referral);
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.REFERRAL_TRIAGED)
                .actorUser(actor.toString())
                .payload("referral_uuid", referral.getUuid())
                .payload("outcome", "DISMISSED")
                .payload("dismiss_reason", reason.name()));

        log.infof("Referral %s dismissed (%s) by actor=%s", referral.getUuid(), reason, actor);
        return new ReferralTriageResponse(referral.getUuid(), referral.getStatus(), null);
    }

    /**
     * Synchronously flush the triage status transition so the optimistic
     * lock ({@code recruitment_referrals.version}, {@code @Version} on the
     * entity) fires INSIDE this transaction. Two concurrent triage calls
     * both pass the plain {@code status == SUBMITTED} read; the loser's
     * {@code UPDATE ... WHERE version = ?} then matches zero rows here,
     * and the resulting {@link OptimisticLockException} is rethrown as the
     * same 409 {@link BusinessRuleViolation} the sequential path produces
     * — rolling back the whole {@code @Transactional} triage, including
     * any candidate/application created earlier in the same transaction
     * ({@code CandidateService.createCandidate} joins it; nothing uses
     * REQUIRES_NEW).
     */
    private static void flushOrTriagedConcurrently(RecruitmentReferral referral) {
        try {
            Panache.getEntityManager().flush();
        } catch (OptimisticLockException e) {
            throw new BusinessRuleViolation(
                    "Referral %s was triaged concurrently — a referral is triaged exactly once"
                            .formatted(referral.getUuid()), e);
        }
    }

    /**
     * Resolve the attach position with the P4 rules: existence AND
     * visibility answer one 404 (an invisible partner-track position never
     * leaks), and the actor needs decision rights on it (403 otherwise) —
     * mirroring the attach endpoint exactly.
     */
    private RecruitmentPosition requireDecidablePosition(String positionUuid, UUID actor) {
        RecruitmentPosition position = RecruitmentPosition.findById(positionUuid);
        if (position == null || !visibility.canReadPosition(actor.toString(), position)) {
            throw new NotFoundException("Position not found: " + positionUuid);
        }
        if (!visibility.canDecideOnApplication(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only the recruiter, the hiring owner or the position's teamlead may attach to this position",
                    Response.Status.FORBIDDEN);
        }
        return position;
    }

    // ---- Unsolicited triage queue (the P5 carry-over) ---------------------------

    /**
     * Unsolicited applicants awaiting routing: public-form candidates
     * ({@code created_by_useruuid = "public-form"}), still ACTIVE, not
     * pooled, with zero applications. Each card carries the desired
     * practice from {@code source_detail} (P5 keys) and the candidate's
     * candidate-scoped form answers, labelled via
     * {@link PublicApplyQuestions} — the P5 carry-over binding on P6
     * (findings §P5). Oldest first: it is a queue. Recruiter-tier
     * enforcement lives HERE as well as in the resource (defense in depth
     * — a documented P14 reuse point).
     *
     * @param actor the requesting user — must be recruiter tier (403 otherwise)
     */
    public TriageQueueResponse unsolicitedTriageQueue(UUID actor) {
        requireRecruiterTier(actor);
        List<RecruitmentCandidate> candidates = RecruitmentCandidate.list("""
                        createdByUseruuid = ?1 and status = ?2 and poolStatus is null
                        and uuid not in (select a.candidateUuid from RecruitmentApplication a)
                        order by createdAt
                        """,
                PublicApplyService.PUBLIC_FORM_CREATOR, CandidateStatus.ACTIVE);

        Map<String, List<RecruitmentApplicationAnswer>> answers = candidates.isEmpty() ? Map.of()
                : RecruitmentApplicationAnswer.<RecruitmentApplicationAnswer>list(
                                "candidateUuid in ?1",
                                candidates.stream().map(RecruitmentCandidate::getUuid).toList())
                        .stream()
                        .collect(Collectors.groupingBy(RecruitmentApplicationAnswer::getCandidateUuid));

        List<TriageQueueCandidate> rows = new ArrayList<>(candidates.size());
        for (RecruitmentCandidate c : candidates) {
            Map<String, Object> detail = c.getSourceDetail();
            rows.add(new TriageQueueCandidate(
                    c.getUuid(),
                    c.getFirstName(),
                    c.getLastName(),
                    c.getEmail(),
                    c.getCreatedAt(),
                    stringDetail(detail, "desiredPracticeUuid"),
                    stringDetail(detail, "desiredPracticeName"),
                    toAnswers(answers.getOrDefault(c.getUuid(), List.of()))));
        }
        return new TriageQueueResponse(rows, rows.size());
    }

    /** Answers in question display order, labelled from the code-defined set. */
    private static List<TriageQueueAnswer> toAnswers(List<RecruitmentApplicationAnswer> answers) {
        Map<String, PublicApplyQuestions.Question> questions = PublicApplyQuestions.all().stream()
                .collect(Collectors.toMap(PublicApplyQuestions.Question::key, Function.identity()));
        List<String> displayOrder = PublicApplyQuestions.keys();
        return answers.stream()
                .sorted(Comparator.comparingInt(a -> {
                    int index = displayOrder.indexOf(a.getQuestionKey());
                    return index >= 0 ? index : displayOrder.size();
                }))
                .map(a -> new TriageQueueAnswer(
                        a.getQuestionKey(),
                        questions.containsKey(a.getQuestionKey())
                                ? questions.get(a.getQuestionKey()).label()
                                : a.getQuestionKey(),
                        a.getAnswer()))
                .toList();
    }

    private static String stringDetail(Map<String, Object> detail, String key) {
        if (detail == null) {
            return null;
        }
        Object value = detail.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    // ---- Guards ----------------------------------------------------------------

    /**
     * The recruiter-tier gate (spec §7.2: ADMIN, HR or CXO), enforced at
     * the service so no future caller (the P14 Slack twin included) can
     * reach a recruiter surface without it. The resource keeps its own
     * check — it answers first with the friendlier message.
     */
    private void requireRecruiterTier(UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (!visibility.isRecruiterTier(actor.toString())) {
            throw new WebApplicationException(
                    "Reserved for the recruiter tier", Response.Status.FORBIDDEN);
        }
    }

    // ---- Validation helpers ----------------------------------------------------

    private static String requireLength(String value, String field, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw badRequest(field + " is required");
        }
        if (trimmed.length() > max) {
            throw badRequest(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String optionalLength(String value, String field, int max) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > max) {
            throw badRequest(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    /**
     * Strict validation + normalization for a pasted profile link. The
     * stored value is later rendered as an {@code href} in the recruiter
     * grid, so a substring check is not enough — {@code javascript:} URIs
     * that merely mention linkedin.com must never pass (stored XSS).
     * <ul>
     *   <li>a schemeless paste ("www.linkedin.com/in/jane") gets
     *       {@code https://} prepended — the normalized absolute URL is
     *       what gets stored;</li>
     *   <li>the result must parse as a {@link URI} with scheme http(s)
     *       and a host that IS {@code linkedin.com} or a subdomain of it
     *       (www./dk. locale hosts — the same family
     *       {@link dk.trustworks.intranet.recruitmentservice.util.LinkedInUrls}
     *       normalizes for dedupe);</li>
     *   <li>anything else — other schemes, other hosts, linkedin.com only
     *       in the path/query, unparseable input — is a 400.</li>
     * </ul>
     *
     * @return the normalized absolute URL, or {@code null} when absent
     */
    private static String optionalLinkedin(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.contains("://") ? trimmed : "https://" + trimmed;
        if (normalized.length() > MAX_LINKEDIN_LENGTH) {
            throw badRequest("linkedinUrl must be at most " + MAX_LINKEDIN_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException e) {
            throw badRequest("linkedinUrl must be a linkedin.com profile link");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean linkedinHost = host.equals("linkedin.com") || host.endsWith(".linkedin.com");
        if (!(scheme.equals("http") || scheme.equals("https")) || !linkedinHost) {
            throw badRequest("linkedinUrl must be a linkedin.com profile link");
        }
        return normalized;
    }

    private static String optionalEmail(String value) {
        String trimmed = optionalLength(value, "email", MAX_EMAIL_LENGTH);
        if (trimmed != null && !EMAIL_FORMAT.matcher(trimmed).matches()) {
            throw badRequest("email must be a valid address");
        }
        return trimmed;
    }

    /**
     * Optional {@link CandidateExperienceLevel} name (P9 triage extension) —
     * absent stays null; garbage answers 400 with the contract's
     * {@code INVALID_FIELD} error code (bean validation is inert, so the
     * check is explicit).
     */
    private static CandidateExperienceLevel optionalExperienceLevel(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return CandidateExperienceLevel.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_FIELD",
                            "message", "Invalid experienceLevel: " + trimmed))
                    .build());
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw badRequest(field + " is required");
        }
        try {
            return Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid " + field + ": " + trimmed);
        }
    }

    private static void piiIfPresent(RecruitmentEventBuilder event, String key, String value) {
        if (value != null && !value.isBlank()) {
            event.pii(key, value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
