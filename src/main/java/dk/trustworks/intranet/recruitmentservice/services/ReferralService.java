package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.ai.RecruitmentAiDirectory;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralOrigin;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralRow;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralAiSuggestions;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralRow;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCvDownload;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCvResponse;
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
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentPositionAccess;
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
import java.nio.charset.StandardCharsets;
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
import java.util.stream.Stream;

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
    RecruitmentS3StorageService storageService;

    @Inject
    ObjectMapper objectMapper;

    // ---- Submit ---------------------------------------------------------------

    /** Event-payload origin markers (spec §3.4 provenance). */
    public static final String ORIGIN_WEB = "web";
    public static final String ORIGIN_SLACK = "slack";
    /**
     * {@code DOCUMENT_UPLOADED.origin} for the CV a referral hands to the
     * candidate it becomes — distinct from {@code public_form} (the
     * applicant uploaded it themselves) and {@code manual} (a recruiter
     * attached it on the Documents tab), because who supplied a CV is a
     * fact the recruiter reads off the timeline.
     */
    public static final String ORIGIN_REFERRAL = "referral";

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
        return submit(request, actor, ORIGIN_WEB);
    }

    /**
     * {@link #submit(ReferralCreateRequest, UUID)} with an explicit event
     * origin — the P14 Slack {@code /refer} handler passes
     * {@link #ORIGIN_SLACK} so the timeline shows provenance and reports
     * can measure Slack adoption (P13 handler contract). Identical
     * validation and side effects.
     */
    @Transactional
    public ReferralCreateResponse submit(ReferralCreateRequest request, UUID actor, String origin) {
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
                .payload("origin", ORIGIN_SLACK.equals(origin) ? ORIGIN_SLACK : ORIGIN_WEB)
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

    // ---- CV attachment ---------------------------------------------------------

    /**
     * Attach the OPTIONAL CV the referrer uploaded straight after submitting
     * (2026-09-02). The refer form posts it as a second request — the file is
     * an afterthought to the referral, never a precondition for it, so a
     * failed upload must not cost the employee their referral.
     * <p>
     * Rules, all enforced here because the module has no active
     * bean-validation extension (findings §P4):
     * <ul>
     *   <li>only the submitting employee may attach — a referral is that
     *       person's statement about someone they know, and nobody else gets
     *       to add a document to it (404, not 403: a stranger must not learn
     *       that this referral uuid exists);</li>
     *   <li>only while {@code SUBMITTED} — once triaged, the file belongs on
     *       the candidate (Documents tab) or nowhere (409);</li>
     *   <li>re-attaching REPLACES: the referral carries at most one CV, and
     *       the superseded S3 object is deleted rather than orphaned.</li>
     * </ul>
     * Bytes, MIME type and magic bytes are validated by the resource against
     * {@link dk.trustworks.intranet.recruitmentservice.util.PublicApplyDocuments}
     * — the same guard the public apply forms and the Documents-tab upload
     * share — so this method receives an already-vetted file.
     *
     * @param safeFilename sanitised filename (stored, and served back in
     *                     Content-Disposition)
     * @param piiFilename  the filename as the employee's machine had it —
     *                     event pii only, never a path component
     */
    @Transactional
    public ReferralCvResponse attachCv(UUID referralUuid, UUID actor,
                                       byte[] bytes, String contentType,
                                       String safeFilename, String piiFilename) {
        Objects.requireNonNull(referralUuid, "referralUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(bytes, "bytes must not be null");

        RecruitmentReferral referral = requireOwnReferral(referralUuid, actor);
        if (referral.getStatus() != RecruitmentReferralStatus.SUBMITTED) {
            throw new BusinessRuleViolation(
                    "Referral %s is already %s — a CV can only be attached while it awaits triage"
                            .formatted(referral.getUuid(), referral.getStatus()));
        }

        String fileUuid = storageService.storeReferralCv(bytes, safeFilename, referralUuid);
        String replaced = referral.attachCv(fileUuid, safeFilename, contentType, bytes.length);
        if (replaced != null) {
            // Best-effort: the row now points at the new file either way, and
            // an undeleted predecessor is a stray object, not a data fault.
            try {
                storageService.deleteReferralCv(replaced);
            } catch (RuntimeException e) {
                log.warnf(e, "Could not delete superseded referral CV fileUuid=%s (referral=%s)",
                        replaced, referral.getUuid());
            }
        }

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.REFERRAL_CV_ATTACHED)
                .actorUser(actor.toString())
                .payload("referral_uuid", referral.getUuid())
                .payload("file_uuid", fileUuid)
                .payload("content_type", contentType)
                .payload("size_bytes", bytes.length)
                .payload("origin", ORIGIN_WEB)
                .pii("filename", piiFilename == null || piiFilename.isBlank()
                        ? safeFilename : piiFilename));

        log.infof("Referral CV attached: referral=%s fileUuid=%s size=%d by actor=%s",
                referral.getUuid(), fileUuid, bytes.length, actor);
        return new ReferralCvResponse(fileUuid, safeFilename, contentType, bytes.length);
    }

    /**
     * The attached CV's bytes for the triage queue's read/download control.
     * Recruiter-tier only ({@link #requireInboxTier}) — the same people who
     * may see the referral at all.
     *
     * @throws NotFoundException when the referral does not exist or carries
     *         no CV (one answer for both — a missing file is not worth a
     *         separate status to a UI that only ever asks when it saw one)
     */
    public ReferralCvDownload readCv(UUID referralUuid, UUID actor) {
        Objects.requireNonNull(referralUuid, "referralUuid must not be null");
        requireInboxTier(actor);
        RecruitmentReferral referral = RecruitmentReferral.findById(referralUuid.toString());
        if (referral == null || !referral.hasCv()) {
            throw new NotFoundException("No CV attached to referral: " + referralUuid);
        }
        byte[] bytes = storageService.fetchReferralCv(referral.getCvFileUuid());
        String contentType = referral.getCvContentType() == null
                ? "application/octet-stream"
                : referral.getCvContentType();
        String filename = referral.getCvFilename() == null
                ? "cv" : referral.getCvFilename();
        return new ReferralCvDownload(bytes, contentType, filename);
    }

    /**
     * Resolve a referral the actor actually submitted. A referral belonging
     * to someone else answers the same 404 as a nonexistent one — the
     * module's standing convention for "you cannot see this" (spec §7.1),
     * and the reason a guessed uuid buys nothing.
     */
    private RecruitmentReferral requireOwnReferral(UUID referralUuid, UUID actor) {
        RecruitmentReferral referral = RecruitmentReferral.findById(referralUuid.toString());
        if (referral == null || !actor.toString().equals(referral.getReferrerUuid())) {
            throw new NotFoundException("Referral not found: " + referralUuid);
        }
        return referral;
    }

    // ---- My referrals ---------------------------------------------------------

    /**
     * The caller's own referrals, newest first, each with its milestone-level
     * {@link RecruitmentReferralDerivedStatus} computed live (plan §P6 —
     * pipeline state is never mirrored onto the referral row).
     * <p>
     * The referrer↔candidate link lives in two places, and this read unions
     * both (see {@link MyReferralOrigin}):
     * <ol>
     *   <li>{@code recruitment_referrals.referrer_uuid} — the refer form;</li>
     *   <li>{@code recruitment_candidates.referred_by_user_uuid} — set by the
     *       Airtable migration and by the recruiter's "Referred by
     *       (colleague)" field, neither of which creates a referral row.</li>
     * </ol>
     * Reading only (1) is what made this page show nothing for every referrer
     * of a migrated candidate, while the profile page and the referrer's own
     * milestone DMs — {@code ReferrerNotificationReactor} has always keyed on
     * (2) — both showed the link. Source (2) is now the same truth here.
     * <p>
     * Source (2) is now filtered, though: since change request (e)
     * (2026-09-01) a public applicant can put their own typed guess into
     * {@code referred_by_user_uuid}, and an unverified claim by a stranger is
     * not "my referral" — the named employee gets one honest disclosure DM
     * instead ({@code ApplicantReferrerNotificationReactor}).
     * <p>
     * Derivation stays batched: at most four queries regardless of row count
     * — referral rows, candidates, the applicant-claim events, then those
     * candidates' applications.
     */
    public MyReferralsResponse listMine(UUID referrer) {
        Objects.requireNonNull(referrer, "referrer must not be null");
        String referrerUuid = referrer.toString();

        List<RecruitmentReferral> referrals = RecruitmentReferral.list(
                "referrerUuid = ?1 order by submittedAt desc", referrerUuid);
        List<RecruitmentCandidate> recordedOnCandidate = withoutApplicantClaims(
                RecruitmentCandidate.list("referredByUserUuid = ?1", referrerUuid),
                referrerUuid);

        // One candidate map for both sources: start from the directly-recorded
        // ones, then fetch only the referral-linked candidates still missing.
        Map<String, RecruitmentCandidate> candidates = recordedOnCandidate.stream()
                .collect(Collectors.toMap(RecruitmentCandidate::getUuid, Function.identity(),
                        (first, duplicate) -> first, HashMap::new));
        List<String> missing = referrals.stream()
                .map(RecruitmentReferral::getCandidateUuid)
                .filter(Objects::nonNull)
                .filter(uuid -> !candidates.containsKey(uuid))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1", missing)
                    .forEach(candidate -> candidates.put(candidate.getUuid(), candidate));
        }

        Map<String, List<RecruitmentApplication>> applications = candidates.isEmpty() ? Map.of()
                : RecruitmentApplication.<RecruitmentApplication>list(
                                "candidateUuid in ?1", List.copyOf(candidates.keySet())).stream()
                        .collect(Collectors.groupingBy(RecruitmentApplication::getCandidateUuid));

        List<MyReferralRow> rows = mergeMyReferrals(referrals, recordedOnCandidate, candidates, applications);
        return new MyReferralsResponse(rows, rows.size());
    }

    /**
     * Drop candidates whose {@code referred_by_user_uuid} was written by a
     * public applicant naming this employee rather than by the employee
     * referring anyone (change request (e), 2026-09-01). Event-derived state
     * (the P9 idiom — no new column): {@code APPLICANT_REFERRER_CLAIMED}
     * carries the uuid its directory match resolved to. One query for the
     * whole page; an empty input short-circuits it entirely, so the ordinary
     * "I have referred nobody" read is unchanged.
     */
    private List<RecruitmentCandidate> withoutApplicantClaims(
            List<RecruitmentCandidate> recordedOnCandidate, String referrerUuid) {
        if (recordedOnCandidate.isEmpty()) {
            return recordedOnCandidate;
        }
        List<String> candidateUuids = recordedOnCandidate.stream()
                .map(RecruitmentCandidate::getUuid)
                .toList();
        Set<String> claimed = RecruitmentEvent
                .<RecruitmentEvent>list("candidateUuid in ?1 and eventType = ?2",
                        candidateUuids, RecruitmentEventType.APPLICANT_REFERRER_CLAIMED)
                .stream()
                .filter(event -> referrerUuid.equals(
                        payloadString(event.getPayload(), "matched_user_uuid")))
                .map(RecruitmentEvent::getCandidateUuid)
                .collect(Collectors.toSet());
        if (claimed.isEmpty()) {
            return recordedOnCandidate;
        }
        return recordedOnCandidate.stream()
                .filter(candidate -> !claimed.contains(candidate.getUuid()))
                .toList();
    }

    /** One string value out of an event payload; null on anything unparseable. */
    private String payloadString(String payload, String key) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    objectMapper.readTree(payload).get(key);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Merge the two referrer↔candidate sources into one newest-first list.
     * Pure and package-private on purpose: the whole point of this method is
     * the dedupe/exclusion/ordering rules, and those are worth testing
     * without a database (the {@code @QuarkusTest} tier is not in the CI
     * deploy gate).
     * <p>
     * Rules, in order:
     * <ul>
     *   <li>every referral row renders — including untriaged and dismissed
     *       ones, which have no candidate at all;</li>
     *   <li>a directly-recorded candidate that one of those referral rows
     *       already links to is skipped, so a referral triaged the normal way
     *       (which sets BOTH sources) yields exactly one row, keeping the
     *       richer referral-row facts;</li>
     *   <li>{@link CandidateStatus#ANONYMIZED} candidates are skipped — P19
     *       erasure rewrote the name to "Anonymized Candidate", and erased
     *       PII must not resurface on a new surface;</li>
     *   <li>newest first across the union, by submission date for referral
     *       rows and registration date for the rest.</li>
     * </ul>
     */
    static List<MyReferralRow> mergeMyReferrals(List<RecruitmentReferral> referrals,
                                                List<RecruitmentCandidate> recordedOnCandidate,
                                                Map<String, RecruitmentCandidate> candidates,
                                                Map<String, List<RecruitmentApplication>> applications) {
        Set<String> linkedByReferralRow = referrals.stream()
                .map(RecruitmentReferral::getCandidateUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<MyReferralRow> rows = new ArrayList<>(referrals.size() + recordedOnCandidate.size());

        for (RecruitmentReferral referral : referrals) {
            // Untriaged/dismissed rows have no candidate uuid — and Map.of()
            // rejects null keys, so guard before the lookups.
            String candidateUuid = referral.getCandidateUuid();
            rows.add(new MyReferralRow(
                    referral.getUuid(),
                    referral.getCandidateName(),
                    referral.getReferrerRelation(),
                    referral.getExternalReferrerName(),
                    referral.getSubmittedAt(),
                    deriveStatus(referral,
                            candidateUuid == null ? null : candidates.get(candidateUuid),
                            candidateUuid == null ? List.of()
                                    : applications.getOrDefault(candidateUuid, List.of())),
                    MyReferralOrigin.REFERRAL_FORM));
        }

        for (RecruitmentCandidate candidate : recordedOnCandidate) {
            if (linkedByReferralRow.contains(candidate.getUuid())
                    || candidate.getStatus() == CandidateStatus.ANONYMIZED) {
                continue;
            }
            rows.add(new MyReferralRow(
                    syntheticRowId(candidate.getUuid()),
                    displayNameOf(candidate),
                    null, // no refer form was ever filled in — no declared relation
                    null,
                    candidate.getCreatedAt(),
                    deriveCandidateMilestone(candidate,
                            applications.getOrDefault(candidate.getUuid(), List.of())),
                    MyReferralOrigin.RECORDED_ON_CANDIDATE));
        }

        rows.sort(Comparator.comparing(MyReferralRow::submittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(rows);
    }

    /**
     * A stable list key for a candidate-recorded row, which has no referral
     * row to borrow a uuid from. Name-based (type 3) UUID over the candidate
     * uuid: identical on every read so the client keeps its list identity,
     * one-way so the DTO keeps its "no handle to the candidate" invariant.
     */
    private static String syntheticRowId(String candidateUuid) {
        return UUID.nameUUIDFromBytes(
                ("my-referral:" + candidateUuid).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** "First Last", tolerating either half being absent. */
    private static String displayNameOf(RecruitmentCandidate candidate) {
        return Stream.of(candidate.getFirstName(), candidate.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
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
        requireInboxTier(actor);
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
                        aiSuggestions.get(r.getUuid()),
                        r.getCvFilename(),
                        r.getCvContentType(),
                        r.getCvSizeBytes()))
                .toList();
        return new PendingReferralsResponse(rows, rows.size());
    }

    // ---- Pending-row AI suggestions (P9, contract §6.3) -------------------------

    /** Bounded scan over the latest referral-variant AI events (newest first). */
    static final int AI_SUGGESTION_SCAN_CAP = 500;

    /**
     * The re-validated AI triage suggestions for ONE pending referral —
     * the P14 Slack triage modal's prefill source, sharing
     * {@link #pendingAiSuggestions} with the web queue so both surfaces
     * always show the same (re-validated) values. Null when the
     * referral-triage toggle is off or no valid suggestions exist.
     */
    public PendingReferralAiSuggestions aiSuggestionsForPending(String referralUuid) {
        if (referralUuid == null || referralUuid.isBlank()) {
            return null;
        }
        return pendingAiSuggestions(List.of(referralUuid)).get(referralUuid);
    }

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
        return triage(referralUuid, request, actor, ORIGIN_WEB);
    }

    /**
     * {@link #triage(UUID, ReferralTriageRequest, UUID)} with an explicit
     * event origin — the P14 Slack triage buttons pass
     * {@link #ORIGIN_SLACK}. Identical authorization (recruiter tier,
     * decision rights), validation, idempotency and side effects.
     */
    @Transactional
    public ReferralTriageResponse triage(UUID referralUuid, ReferralTriageRequest request,
                                         UUID actor, String origin) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireInboxTier(actor);
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
        String eventOrigin = ORIGIN_SLACK.equals(origin) ? ORIGIN_SLACK : ORIGIN_WEB;
        return switch (action) {
            case "CREATE_CANDIDATE" -> triageCreate(referral, request, actor, eventOrigin);
            case "DISMISS" -> triageDismiss(referral, request, actor, eventOrigin);
            default -> throw badRequest("action is required: CREATE_CANDIDATE or DISMISS");
        };
    }

    private ReferralTriageResponse triageCreate(RecruitmentReferral referral,
                                                ReferralTriageRequest request, UUID actor,
                                                String origin) {
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
                null, null,
                // positionUuid stays null here: this leg attaches explicitly
                // below (requireDecidablePosition first), so the atomic
                // create-with-position overload would double-attach.
                null), actor);

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
                .payload("outcome", "CANDIDATE_CREATED")
                .payload("origin", origin));

        handoverCvToCandidate(referral, candidate.uuid(), actor);

        log.infof("Referral %s triaged into candidate %s (source=%s, attached=%s) by actor=%s",
                referral.getUuid(), candidate.uuid(), source, attached, actor);
        return new ReferralTriageResponse(referral.getUuid(), referral.getStatus(), candidate.uuid());
    }

    private ReferralTriageResponse triageDismiss(RecruitmentReferral referral,
                                                 ReferralTriageRequest request, UUID actor,
                                                 String origin) {
        RecruitmentReferralClosedReason reason = parseEnum(RecruitmentReferralClosedReason.class,
                request.dismissReason(), "dismissReason");
        referral.dismiss(reason, actor);
        flushOrTriagedConcurrently(referral);
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.REFERRAL_TRIAGED)
                .actorUser(actor.toString())
                .payload("referral_uuid", referral.getUuid())
                .payload("outcome", "DISMISSED")
                .payload("dismiss_reason", reason.name())
                .payload("origin", origin));

        discardCvOnDismiss(referral);

        log.infof("Referral %s dismissed (%s) by actor=%s", referral.getUuid(), reason, actor);
        return new ReferralTriageResponse(referral.getUuid(), referral.getStatus(), null);
    }

    // ---- CV hand-over at triage -------------------------------------------------

    /**
     * Hand the referrer's CV to the candidate the triage just created: the
     * {@code files} row is re-pointed at the candidate and a
     * {@code DOCUMENT_UPLOADED} is appended so the P8 Documents tab lists it
     * as a CV, exactly like one that arrived through the public apply form.
     * <p>
     * Re-pointing rather than copying is what puts the file inside the GDPR
     * anonymizer's {@code deleteAllCandidateFiles} reach and inside the
     * conversion promotion — both of which address files by
     * {@code relateduuid}. The referral row keeps its {@code cv_*} columns as
     * provenance ("this referral came with a CV"); the bytes are the
     * candidate's from here on.
     * <p>
     * The uploader — not the triaging recruiter — is the event's actor: the
     * referrer is who produced this document.
     */
    private void handoverCvToCandidate(RecruitmentReferral referral, String candidateUuid, UUID actor) {
        if (!referral.hasCv()) {
            return;
        }
        String fileUuid = referral.getCvFileUuid();
        UUID candidate = UUID.fromString(candidateUuid);
        if (!storageService.relinkFileToCandidate(fileUuid, candidate)) {
            // The referral row pointed at a files row that is gone. Nothing to
            // hand over and nothing the triage can do about it — the candidate
            // was still created correctly, which is what the recruiter asked
            // for. Logged by the storage service.
            return;
        }
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.DOCUMENT_UPLOADED)
                .candidate(candidateUuid)
                .actorUser(referral.getReferrerUuid())
                .payload("file_uuid", fileUuid)
                .payload("kind", CandidateDocumentClassifier.KIND_CV)
                .payload("content_type", referral.getCvContentType())
                .payload("size_bytes", referral.getCvSizeBytes() == null ? 0 : referral.getCvSizeBytes())
                .payload("origin", ORIGIN_REFERRAL)
                .payload("referral_uuid", referral.getUuid())
                .pii("filename", referral.getCvFilename()));
        log.infof("Referral %s handed its CV (fileUuid=%s) to candidate %s at triage by actor=%s",
                referral.getUuid(), fileUuid, candidateUuid, actor);
    }

    /**
     * Delete the attached CV when a referral is dismissed. No candidate will
     * ever exist, so there is no basis to keep a third party's CV — and no
     * retention sweep watches referral rows that never became candidates.
     * <p>
     * Deliberately best-effort: a failed S3 delete must not roll back the
     * recruiter's decision. The row's {@code cv_*} columns are cleared either
     * way, so the file stops being reachable through any endpoint.
     */
    private void discardCvOnDismiss(RecruitmentReferral referral) {
        if (!referral.hasCv()) {
            return;
        }
        String fileUuid = referral.getCvFileUuid();
        referral.clearCv();
        try {
            storageService.deleteReferralCv(fileUuid);
        } catch (RuntimeException e) {
            log.errorf(e, "Could not delete the CV of dismissed referral %s (fileUuid=%s) — "
                            + "the row no longer references it, so it is unreachable but not yet erased",
                    referral.getUuid(), fileUuid);
        }
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
        return RecruitmentPositionAccess.requireDecidablePosition(
                visibility, actor.toString(), positionUuid);
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
        requireInboxTier(actor);
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
     * The Inbox-tier gate (decisions 12/13, 2026-08-23: ADMIN, HR,
     * RECRUITMENT or TEAMLEAD), enforced at the service so no future caller
     * (the P14 Slack twin included) can reach an intake surface without it.
     * The resource keeps its own check — it answers first with the
     * friendlier message.
     */
    private void requireInboxTier(UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (!visibility.isInboxTier(actor.toString())) {
            throw new WebApplicationException(
                    "Reserved for the recruitment team and team leads", Response.Status.FORBIDDEN);
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
