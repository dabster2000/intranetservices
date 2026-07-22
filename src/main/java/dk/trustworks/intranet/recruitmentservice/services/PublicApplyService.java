package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.recruitmentservice.dto.DedupeMatch;
import dk.trustworks.intranet.recruitmentservice.dto.PublicApplyFormResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PublicApplySubmission;
import dk.trustworks.intranet.recruitmentservice.dto.PublicUnsolicitedFormResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator for the P5 public application surface ({@code /apply/*}).
 * One {@code @Transactional} command per POST: candidate + application +
 * answers + consent + file-metadata rows + events commit atomically (the
 * S3 byte upload runs through {@link RecruitmentS3StorageService} inside
 * the command, mirroring the onboarding ordering: validate → store →
 * persist).
 *
 * <h3>Anonymous-caller rules</h3>
 * <ul>
 *   <li><b>Silence:</b> every failure the caller could use to probe
 *       (unknown slug, closed position, duplicate application, existing
 *       email) answers exactly like success or a uniform
 *       {@code 404 {"error":"NOT_FOUND"}} — existence never leaks.</li>
 *   <li><b>Dedupe without poisoning:</b> an exact-email match on an
 *       existing non-terminal CANDIDATE reuses that candidate, but public
 *       input never overwrites any stored field — new info lands only in
 *       answers, documents and events, and the created application is
 *       flagged {@code dedupe_review} for recruiter attention. Employee
 *       and LinkedIn-only matches never trigger reuse.</li>
 *   <li><b>Unsolicited creates the candidate ONLY</b> — no application;
 *       recruiter triage attaches one later (deliberate spec decision).</li>
 *   <li><b>Events</b> carry {@code actor_type=CANDIDATE}
 *       ({@code RecruitmentEventBuilder.actorCandidate()}) and the
 *       payload/pii split of spec §3.3.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class PublicApplyService {

    /**
     * Value stored in {@code recruitment_candidates.created_by_useruuid}
     * (NOT NULL, soft FK — no DB constraint, nothing parses it as a UUID)
     * for candidates minted by the public forms. Distinguishable from any
     * real user UUID at a glance.
     */
    static final String PUBLIC_FORM_CREATOR = "public-form";

    static final String ORIGIN_PUBLIC_FORM = "public_form";

    /** DOCUMENT_UPLOADED reason when a duplicate open application exists. */
    static final String REASON_DUPLICATE_SUBMISSION = "DUPLICATE_PUBLIC_SUBMISSION";

    /** Pool-retention consent granted on the form runs 12 months (spec §4.1). */
    static final int POOL_CONSENT_MONTHS = 12;

    /** Allowed values of the form's {@code selfReportedSource} field. */
    public static final Set<String> SELF_REPORTED_SOURCES = Set.of(
            "NETWORK", "SOME", "CONFERENCE", "TW_EVENT", "JOB_LISTING", "LINKEDIN", "OTHER");

    @Inject
    CandidateDedupeService dedupeService;

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    RecruitmentS3StorageService storageService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    // ---- Reads -----------------------------------------------------------------

    /** Form config for a position form; uniform 404 when the slug resolves to nothing public. */
    public PublicApplyFormResponse positionForm(String slug) {
        RecruitmentPosition position = openPositionBySlug(slug);
        return new PublicApplyFormResponse(
                position.getTitle(), position.getPracticeName(), PublicApplyQuestions.all());
    }

    /** Form config for the unsolicited form: questions + active practices (sort order). */
    public PublicUnsolicitedFormResponse unsolicitedForm() {
        List<Practice> practices = Practice.list("active = true order by sortOrder");
        return new PublicUnsolicitedFormResponse(
                PublicApplyQuestions.all(),
                practices.stream()
                        .map(p -> new PublicUnsolicitedFormResponse.PracticeOption(p.getUuid(), p.getName()))
                        .toList());
    }

    /** Resolve-or-404 without building the form — the resource gates POSTs on it before validating. */
    public void requireOpenPosition(String slug) {
        openPositionBySlug(slug);
    }

    // ---- Commands --------------------------------------------------------------

    /**
     * Position-form submission: dedupe-or-create the candidate, attach an
     * application in the position's first stage, persist answers
     * (application-scoped), store documents, optionally grant pool
     * consent. A duplicate open application (same candidate + position)
     * creates nothing new and modifies no answers — the documents still
     * land (with {@code reason=DUPLICATE_PUBLIC_SUBMISSION} on their
     * events) and the caller gets the same generic 201.
     */
    @Transactional
    public void submitForPosition(String slug, PublicApplySubmission submission) {
        RecruitmentPosition position = openPositionBySlug(slug);
        CandidateResolution resolution = resolveCandidate(submission, null);
        RecruitmentCandidate candidate = resolution.candidate();

        boolean duplicateOpen = resolution.reused() && RecruitmentApplication.count(
                "candidateUuid = ?1 and positionUuid = ?2 and terminal is null",
                candidate.getUuid(), position.getUuid()) > 0;

        RecruitmentApplication application = null;
        if (duplicateOpen) {
            storeDocuments(submission, candidate, position, null, REASON_DUPLICATE_SUBMISSION);
        } else {
            application = applicationService.createFromPublicForm(
                    candidate, position, resolution.reused());
            persistApplicationAnswers(application, submission.answers());
            storeDocuments(submission, candidate, position, application, null);
        }
        grantPoolConsentIfTicked(submission, candidate, position, application);
        log.infof("Public application received: position=%s candidate=%s reused=%s duplicateOpen=%s",
                position.getUuid(), candidate.getUuid(), resolution.reused(), duplicateOpen);
    }

    /**
     * Unsolicited submission: dedupe-or-create the candidate ONLY — no
     * application (recruiter triage attaches later, deliberate spec
     * decision). Answers land candidate-scoped; on a reused candidate,
     * already-answered question keys are left untouched (public input
     * never modifies existing data). A {@code desiredPracticeUuid} that
     * matches the registry (active OR since-deactivated — a mid-flight
     * deactivation must still land) goes into {@code source_detail};
     * anything else is silently dropped.
     */
    @Transactional
    public void submitUnsolicited(PublicApplySubmission submission) {
        PracticeRef practice = resolvePractice(submission.desiredPracticeUuid());
        CandidateResolution resolution = resolveCandidate(submission, practice);
        RecruitmentCandidate candidate = resolution.candidate();

        persistCandidateAnswers(candidate, submission.answers(), resolution.reused());
        storeDocuments(submission, candidate, null, null, null);
        grantPoolConsentIfTicked(submission, candidate, null, null);
        log.infof("Public unsolicited submission received: candidate=%s reused=%s",
                candidate.getUuid(), resolution.reused());
    }

    // ---- Uniform 404 -----------------------------------------------------------

    /**
     * The uniform public 404: unknown slug, slug-less/closed position and
     * a disabled feature flag are byte-identical — an anonymous caller
     * learns nothing from the shape.
     */
    public static NotFoundException publicNotFound() {
        return new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"NOT_FOUND\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static RecruitmentPosition openPositionBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw publicNotFound();
        }
        String normalized = slug.trim().toLowerCase();
        RecruitmentPosition position = RecruitmentPosition
                .<RecruitmentPosition>find("publicSlug", normalized)
                .firstResult();
        if (position == null || position.getStatus() != RecruitmentPositionStatus.OPEN) {
            throw publicNotFound();
        }
        return position;
    }

    // ---- Candidate resolution ---------------------------------------------------

    private record CandidateResolution(RecruitmentCandidate candidate, boolean reused) {
    }

    /** Registry practice reference resolved at submit time. */
    private record PracticeRef(String uuid, String name) {
    }

    /**
     * Reuse an existing candidate on an exact-email CANDIDATE match
     * (never EMPLOYEE, never LinkedIn-only) that is not in a terminal
     * state — terminal candidates cannot re-enter the pipeline, so a
     * fresh candidate row is created instead. Reuse never mutates the
     * stored candidate.
     */
    private CandidateResolution resolveCandidate(PublicApplySubmission submission,
                                                 PracticeRef practice) {
        for (DedupeMatch match : dedupeService.check(
                submission.email(), submission.linkedinUrl()).matches()) {
            if (match.type() != DedupeMatch.MatchType.CANDIDATE
                    || match.matchedOn() != DedupeMatch.MatchedOn.EMAIL) {
                continue;
            }
            RecruitmentCandidate existing = RecruitmentCandidate.findById(match.uuid());
            if (existing != null && !existing.isTerminal()) {
                return new CandidateResolution(existing, true);
            }
        }
        return new CandidateResolution(createCandidate(submission, practice), false);
    }

    private RecruitmentCandidate createCandidate(PublicApplySubmission submission,
                                                 PracticeRef practice) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(submission.firstName());
        candidate.setLastName(submission.lastName());
        candidate.setEmail(submission.email());
        candidate.setPhone(submission.phone());
        candidate.setLinkedinUrl(submission.linkedinUrl());
        candidate.setEducationLevel(submission.educationLevel());
        candidate.setEducationOther(submission.educationOther());
        candidate.setExperienceLevel(submission.experienceLevel());
        candidate.setStatus(CandidateStatus.ACTIVE);
        candidate.setSource(submission.channel());
        Map<String, Object> sourceDetail = buildSourceDetail(
                submission.selfReportedSource(), submission.sourceFollowUp(),
                practice != null ? practice.uuid() : null,
                practice != null ? practice.name() : null);
        candidate.setSourceDetail(sourceDetail.isEmpty() ? null : sourceDetail);
        // P5 entry channels are Art. 13 (the candidate supplied the data
        // themselves — CandidateSource.requiresArt14Notice() is false for
        // WEBSITE/LINKEDIN_AD/JOBINDEX), so no Art. 14 bookkeeping here.
        candidate.setLawfulBasis(CandidateLawfulBasis.LEGITIMATE_INTEREST);
        candidate.setCreatedByUseruuid(PUBLIC_FORM_CREATOR);
        RecruitmentCandidate.persist(candidate);

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.CANDIDATE_CREATED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .payload("source", candidate.getSource().name())
                .payload("origin", ORIGIN_PUBLIC_FORM)
                .payload("education_level", name(candidate.getEducationLevel()))
                .payload("experience_level", name(candidate.getExperienceLevel()))
                .payload("lawful_basis", candidate.getLawfulBasis().name())
                .pii("first_name", candidate.getFirstName())
                .pii("last_name", candidate.getLastName())
                .pii("email", candidate.getEmail());
        piiIfPresent(event, "phone", candidate.getPhone());
        piiIfPresent(event, "linkedin_url", candidate.getLinkedinUrl());
        if (candidate.getSourceDetail() != null && !candidate.getSourceDetail().isEmpty()) {
            // May carry reference names — the whole blob is personal data (spec §4.1).
            event.pii("source_detail", candidate.getSourceDetail());
        }
        eventRecorder.record(event);
        return candidate;
    }

    /** Any registry row — active or since-deactivated; garbage → null (dropped). */
    private static PracticeRef resolvePractice(String desiredPracticeUuid) {
        if (desiredPracticeUuid == null || desiredPracticeUuid.isBlank()) {
            return null;
        }
        Practice practice = Practice.<Practice>find("uuid", desiredPracticeUuid.trim()).firstResult();
        return practice == null ? null : new PracticeRef(practice.getUuid(), practice.getName());
    }

    // ---- Answers ---------------------------------------------------------------

    private static void persistApplicationAnswers(RecruitmentApplication application,
                                                  Map<String, String> answers) {
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            RecruitmentApplicationAnswer answer = new RecruitmentApplicationAnswer();
            answer.setApplicationUuid(application.getUuid());
            answer.setQuestionKey(entry.getKey());
            answer.setAnswer(entry.getValue());
            answer.persist();
        }
    }

    /**
     * Candidate-scoped answers for the unsolicited form. On a reused
     * candidate, keys that already have a candidate-scoped answer are
     * skipped — public input never modifies existing data.
     */
    private static void persistCandidateAnswers(RecruitmentCandidate candidate,
                                                Map<String, String> answers, boolean reused) {
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            if (reused && RecruitmentApplicationAnswer.count(
                    "candidateUuid = ?1 and questionKey = ?2",
                    candidate.getUuid(), entry.getKey()) > 0) {
                continue;
            }
            RecruitmentApplicationAnswer answer = new RecruitmentApplicationAnswer();
            answer.setCandidateUuid(candidate.getUuid());
            answer.setQuestionKey(entry.getKey());
            answer.setAnswer(entry.getValue());
            answer.persist();
        }
    }

    // ---- Documents -------------------------------------------------------------

    private void storeDocuments(PublicApplySubmission submission,
                                RecruitmentCandidate candidate,
                                RecruitmentPosition position,
                                RecruitmentApplication application,
                                String reason) {
        storeDocument(submission.cv(), "CV", candidate, position, application, reason);
        if (submission.coverLetter() != null) {
            storeDocument(submission.coverLetter(), "COVER_LETTER",
                    candidate, position, application, reason);
        }
    }

    private void storeDocument(PublicApplySubmission.UploadedDocument document, String kind,
                               RecruitmentCandidate candidate,
                               RecruitmentPosition position,
                               RecruitmentApplication application,
                               String reason) {
        String fileUuid = storageService.storeApplicationDocument(
                document.bytes(), document.safeFilename(), UUID.fromString(candidate.getUuid()));

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.DOCUMENT_UPLOADED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .payload("file_uuid", fileUuid)
                .payload("kind", kind)
                .payload("content_type", document.contentType())
                .payload("size_bytes", document.bytes().length)
                .payload("origin", ORIGIN_PUBLIC_FORM)
                .pii("filename", document.filename());
        if (reason != null) {
            event.payload("reason", reason);
        }
        subjectAndVisibility(event, position, application);
        eventRecorder.record(event);
    }

    // ---- Consent ---------------------------------------------------------------

    /**
     * A ticked pool-consent checkbox creates a GRANTED
     * {@code TALENT_POOL_RETENTION} consent ({@code granted_at} now UTC,
     * {@code expires_at} +12 months, {@code token_hash} NULL until P19)
     * and appends {@code CONSENT_GRANTED}. Idempotent per candidate: an
     * unexpired GRANTED consent of the same kind suppresses a duplicate
     * row (repeat submissions must not spam the consent table).
     */
    private void grantPoolConsentIfTicked(PublicApplySubmission submission,
                                          RecruitmentCandidate candidate,
                                          RecruitmentPosition position,
                                          RecruitmentApplication application) {
        if (!submission.poolConsent()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long alreadyGranted = RecruitmentConsent.count(
                "candidateUuid = ?1 and kind = ?2 and status = ?3 and expiresAt > ?4",
                candidate.getUuid(), RecruitmentConsentKind.TALENT_POOL_RETENTION,
                RecruitmentConsentStatus.GRANTED, now);
        if (alreadyGranted > 0) {
            return;
        }
        RecruitmentConsent consent = new RecruitmentConsent();
        consent.setCandidateUuid(candidate.getUuid());
        consent.setKind(RecruitmentConsentKind.TALENT_POOL_RETENTION);
        consent.setStatus(RecruitmentConsentStatus.GRANTED);
        consent.setGrantedAt(now);
        consent.setExpiresAt(now.plusMonths(POOL_CONSENT_MONTHS));
        consent.persist();

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.CONSENT_GRANTED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .payload("kind", RecruitmentConsentKind.TALENT_POOL_RETENTION.name())
                .payload("consent_uuid", consent.getUuid());
        subjectAndVisibility(event, position, application);
        eventRecorder.record(event);
    }

    // ---- Source detail (unit-tested mapping) -----------------------------------

    /**
     * Assemble the candidate's {@code source_detail} map. The follow-up
     * text maps to a source-specific key ({@code NETWORK→referenceName},
     * {@code SOME→channel}, {@code CONFERENCE/TW_EVENT→eventName},
     * {@code JOB_LISTING→jobListingRef}; LINKEDIN/OTHER carry no
     * follow-up). The whole map is treated as personal data downstream.
     */
    static Map<String, Object> buildSourceDetail(String selfReportedSource, String sourceFollowUp,
                                                 String desiredPracticeUuid, String desiredPracticeName) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (selfReportedSource != null && !selfReportedSource.isBlank()) {
            String normalized = selfReportedSource.trim().toUpperCase(Locale.ROOT);
            detail.put("selfReportedSource", normalized);
            if (sourceFollowUp != null && !sourceFollowUp.isBlank()) {
                String key = switch (normalized) {
                    case "NETWORK" -> "referenceName";
                    case "SOME" -> "channel";
                    case "CONFERENCE", "TW_EVENT" -> "eventName";
                    case "JOB_LISTING" -> "jobListingRef";
                    default -> null; // LINKEDIN, OTHER — follow-up ignored
                };
                if (key != null) {
                    detail.put(key, sourceFollowUp.trim());
                }
            }
        }
        if (desiredPracticeUuid != null) {
            detail.put("desiredPracticeUuid", desiredPracticeUuid);
            if (desiredPracticeName != null) {
                detail.put("desiredPracticeName", desiredPracticeName);
            }
        }
        return detail;
    }

    // ---- Helpers ---------------------------------------------------------------

    /**
     * Stamp the position/application subjects when present, and CIRCLE
     * visibility on partner-track positions (P2 carry-over: the timeline
     * applies the same hard filter as the state tables).
     */
    private static void subjectAndVisibility(RecruitmentEventBuilder event,
                                             RecruitmentPosition position,
                                             RecruitmentApplication application) {
        if (position != null) {
            event.position(position.getUuid());
            if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
                event.visibility(RecruitmentEventVisibility.CIRCLE);
            }
        }
        if (application != null) {
            event.application(application.getUuid());
        }
    }

    private static void piiIfPresent(RecruitmentEventBuilder event, String key, String value) {
        if (value != null && !value.isBlank()) {
            event.pii(key, value);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
