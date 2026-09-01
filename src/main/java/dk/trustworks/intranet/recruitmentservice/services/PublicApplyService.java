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
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
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

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    PublicApplyReferrerService referrerService;

    // ---- Reads -----------------------------------------------------------------

    /** Form config for a position form; uniform 404 when the slug resolves to nothing public. */
    public PublicApplyFormResponse positionForm(String slug) {
        RecruitmentPosition position = openPositionBySlug(slug);
        return new PublicApplyFormResponse(
                position.getTitle(), position.getPracticeName(),
                PublicApplyQuestions.asked(featureFlag.isApplyReferrerClaimEnabled()));
    }

    /** Form config for the unsolicited form: questions + active practices (sort order). */
    public PublicUnsolicitedFormResponse unsolicitedForm() {
        List<Practice> practices = Practice.list("active = true order by sortOrder");
        return new PublicUnsolicitedFormResponse(
                PublicApplyQuestions.asked(featureFlag.isApplyReferrerClaimEnabled()),
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
     * consent. A reused candidate who is <em>already in a process</em>
     * creates nothing new and modifies no answers — the documents still
     * land (with {@code reason=DUPLICATE_PUBLIC_SUBMISSION} on their
     * events) and the caller gets the same generic 201.
     * <p>
     * "Already in a process" means ANY open application, not just one on
     * this position: the one-open-application invariant lives in
     * {@code RecruitmentApplicationService.createCore} and would answer 409
     * here, which an anonymous caller must never see (it would turn the
     * public form into a "is this person already applying?" oracle and
     * break the uniform-201 contract). Taking the same graceful branch
     * keeps the CV — the recruiter re-files the existing application with
     * the move command if the new req is the better fit.
     */
    public void submitForPosition(String slug, PublicApplySubmission submission) {
        submitForPosition(slug, submission, resolveReferrerClaim(submission));
    }

    /**
     * The transactional core of {@link #submitForPosition(String,
     * PublicApplySubmission)} — see that method for the semantics. Split out
     * so the referrer claim can be resolved BEFORE the transaction opens
     * (its AI tier is an OpenAI round-trip; the no-OpenAI-in-tx rule). ArC
     * weaves interceptors by subclassing, so the {@code this}-call above
     * still starts this transaction.
     */
    @Transactional
    public void submitForPosition(String slug, PublicApplySubmission submission,
                                  PublicApplyReferrerService.ReferrerClaim referrerClaim) {
        RecruitmentPosition position = openPositionBySlug(slug);
        CandidateResolution resolution = resolveCandidate(submission, null, referrerClaim);
        RecruitmentCandidate candidate = resolution.candidate();

        boolean duplicateOpen = resolution.reused() && RecruitmentApplication.count(
                "candidateUuid = ?1 and terminal is null and stage <> ?2",
                candidate.getUuid(), RecruitmentStage.HIRED) > 0;

        RecruitmentApplication application = null;
        if (duplicateOpen) {
            storeDocuments(submission, candidate, position, null, REASON_DUPLICATE_SUBMISSION);
            // The submission is a fact even though no application is created
            // (the one-open-application invariant): without an event the
            // candidate mailer has nothing to acknowledge, and someone who
            // has now applied twice hears nothing (remediation F7). The
            // reactor answers it with DUPLICATE_APPLICATION_NOTICE.
            RecruitmentEventBuilder duplicate = RecruitmentEventBuilder
                    .event(RecruitmentEventType.DUPLICATE_APPLICATION_RECEIVED)
                    .candidate(candidate.getUuid())
                    .actorCandidate()
                    .payload("origin", ORIGIN_PUBLIC_FORM)
                    .payload("reason", REASON_DUPLICATE_SUBMISSION);
            subjectAndVisibility(duplicate, position, null);
            eventRecorder.record(duplicate);
        } else {
            application = applicationService.createFromPublicForm(
                    candidate, position, resolution.reused());
            persistApplicationAnswers(application, submission.answers());
            storeDocuments(submission, candidate, position, application, null);
        }
        recordSubmissionConsents(submission, candidate, position, application);
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
    public void submitUnsolicited(PublicApplySubmission submission) {
        submitUnsolicited(submission, resolveReferrerClaim(submission));
    }

    /**
     * The transactional core of {@link
     * #submitUnsolicited(PublicApplySubmission)} — see that method for the
     * semantics. Split for the same reason as the position twin: the
     * referrer claim is resolved outside the transaction.
     */
    @Transactional
    public void submitUnsolicited(PublicApplySubmission submission,
                                  PublicApplyReferrerService.ReferrerClaim referrerClaim) {
        PracticeRef practice = resolvePractice(submission.desiredPracticeUuid());
        CandidateResolution resolution = resolveCandidate(submission, practice, referrerClaim);
        RecruitmentCandidate candidate = resolution.candidate();

        persistCandidateAnswers(candidate, submission.answers(), resolution.reused());
        storeDocuments(submission, candidate, null, null, null);
        recordSubmissionConsents(submission, candidate, null, null);
        // No application means no APPLICATION_CREATED, which was the only
        // acknowledgement trigger — so an unsolicited applicant heard nothing
        // (remediation F6). This event is the receipt's fact, emitted for new
        // AND reused candidates: the submission is what happened, not the
        // candidate row's birth. The reactor answers it with
        // UNSOLICITED_ACKNOWLEDGEMENT.
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.UNSOLICITED_APPLICATION_RECEIVED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .payload("origin", ORIGIN_PUBLIC_FORM)
                .payload("candidate_reused", resolution.reused()));
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
                                                 PracticeRef practice,
                                                 PublicApplyReferrerService.ReferrerClaim claim) {
        // The UNFILTERED check on purpose: there is no viewer on a public
        // submission, and this decides whether to reuse a row, not what to
        // show anyone. Nothing from the match escapes this method.
        for (DedupeMatch match : dedupeService.checkForSystemReuse(
                submission.email(), submission.linkedinUrl()).matches()) {
            if (match.type() != DedupeMatch.MatchType.CANDIDATE
                    || match.matchedOn() != DedupeMatch.MatchedOn.EMAIL) {
                continue;
            }
            RecruitmentCandidate existing = RecruitmentCandidate.findById(match.uuid());
            // Reuse the row unless it is a HIRED or ANONYMIZED end state.
            // DECLINED/WITHDRAWN are reconsiderable: an application terminal
            // now cascades onto the candidate row, so treating those as
            // "unknown person" would mint a duplicate candidate every time a
            // previously-rejected applicant applies again. createCore reopens
            // them and records a CANDIDATE_UPDATED.
            if (existing != null && (!existing.isTerminal() || existing.isReconsiderable())) {
                return new CandidateResolution(existing, true);
            }
        }
        return new CandidateResolution(createCandidate(submission, practice, claim), false);
    }

    private RecruitmentCandidate createCandidate(PublicApplySubmission submission,
                                                 PracticeRef practice,
                                                 PublicApplyReferrerService.ReferrerClaim claim) {
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
        applyReferrerClaim(candidate, claim);
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
        recordReferrerClaim(candidate, claim);
        return candidate;
    }

    // ---- Applicant referrer claim (change request (e), 2026-09-01) --------------

    /**
     * Resolve the applicant's "Kender du nogen hos Trustworks?" answer to an
     * employee. Called from the NON-transactional entry points on purpose —
     * the matcher's AI tier is an OpenAI round-trip and must not hold a
     * pooled connection open on a public endpoint.
     */
    private PublicApplyReferrerService.ReferrerClaim resolveReferrerClaim(
            PublicApplySubmission submission) {
        Map<String, String> answers = submission.answers();
        return referrerService.resolve(
                answers == null ? null : answers.get(PublicApplyQuestions.KNOWS_SOMEONE_KEY));
    }

    /**
     * Store the claim on the NEW candidate row: a confident directory match
     * links {@code referred_by_user_uuid}; anything else — no match, an
     * ambiguous name, a non-employee — is preserved verbatim as
     * {@code external_referrer_name}. Never both, and never a guess.
     * <p>
     * Deliberately new candidates only. Reuse of an existing candidate
     * "never mutates the stored candidate" (see {@link #resolveCandidate}),
     * and a public, unauthenticated caller must not be able to attach a
     * colleague's name to a row someone else already owns. The answer itself
     * still lands in {@code recruitment_application_answers} on every path,
     * so nothing an applicant wrote is lost.
     */
    private static void applyReferrerClaim(RecruitmentCandidate candidate,
                                           PublicApplyReferrerService.ReferrerClaim claim) {
        if (claim == null || !claim.isPresent()) {
            return;
        }
        if (claim.isMatched()) {
            candidate.setReferredByUserUuid(claim.matchedUserUuid());
        } else {
            // Real data: the person named is often not an employee at all.
            candidate.setExternalReferrerName(claim.claimedName());
        }
    }

    /**
     * Append the audit fact that an applicant named a colleague. This event
     * is load-bearing, not decoration: it is the ONLY thing that tells the
     * rest of the system this link is an unverified applicant claim rather
     * than a referral the employee gave — {@code ReferrerNotificationReactor}
     * reads it to stay quiet, and {@code ApplicantReferrerNotificationReactor}
     * reads it to send the one honest notice.
     */
    private void recordReferrerClaim(RecruitmentCandidate candidate,
                                     PublicApplyReferrerService.ReferrerClaim claim) {
        if (claim == null || !claim.isPresent()) {
            return;
        }
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.APPLICANT_REFERRER_CLAIMED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .payload("origin", ORIGIN_PUBLIC_FORM)
                .payload("match_method", claim.matchMethod() == null ? "none" : claim.matchMethod())
                // The applicant's own words about another person — pii, so P19
                // anonymisation rewrites it with everything else.
                .pii("claimed_name", claim.claimedName());
        if (claim.isMatched()) {
            event.payload("matched_user_uuid", claim.matchedUserUuid());
        }
        eventRecorder.record(event);
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
     * Record the consents carried by a public submission:
     * <ul>
     *   <li>{@code APPLICATION_PROCESSING} — always (the resource rejects
     *       submissions without the mandatory storage consent). No
     *       {@code expires_at}; the retention sweep governs the deadline.</li>
     *   <li>{@code CRIMINAL_RECORD_ACKNOWLEDGED} — always (mandatory ISAE
     *       3000 acknowledgment). No {@code expires_at}.</li>
     *   <li>{@code TALENT_POOL_RETENTION} — only when ticked
     *       ({@code granted_at} now UTC, {@code expires_at} +12 months,
     *       {@code token_hash} NULL until P19).</li>
     * </ul>
     * Each grant appends {@code CONSENT_GRANTED} and is idempotent per
     * candidate: an active GRANTED consent of the same kind suppresses a
     * duplicate row (repeat submissions must not spam the consent table).
     */
    private void recordSubmissionConsents(PublicApplySubmission submission,
                                          RecruitmentCandidate candidate,
                                          RecruitmentPosition position,
                                          RecruitmentApplication application) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (submission.gdprConsent()) {
            grantConsent(RecruitmentConsentKind.APPLICATION_PROCESSING, null, now,
                    candidate, position, application);
        }
        if (submission.isaeConsent()) {
            grantConsent(RecruitmentConsentKind.CRIMINAL_RECORD_ACKNOWLEDGED, null, now,
                    candidate, position, application);
        }
        if (submission.poolConsent()) {
            grantConsent(RecruitmentConsentKind.TALENT_POOL_RETENTION,
                    now.plusMonths(POOL_CONSENT_MONTHS), now,
                    candidate, position, application);
        }
    }

    private void grantConsent(RecruitmentConsentKind kind,
                              LocalDateTime expiresAt,
                              LocalDateTime now,
                              RecruitmentCandidate candidate,
                              RecruitmentPosition position,
                              RecruitmentApplication application) {
        // Active = GRANTED and (no expiry OR not yet expired).
        long alreadyGranted = RecruitmentConsent.count(
                "candidateUuid = ?1 and kind = ?2 and status = ?3 "
                        + "and (expiresAt is null or expiresAt > ?4)",
                candidate.getUuid(), kind, RecruitmentConsentStatus.GRANTED, now);
        if (alreadyGranted > 0) {
            return;
        }
        RecruitmentConsent consent = new RecruitmentConsent();
        consent.setCandidateUuid(candidate.getUuid());
        consent.setKind(kind);
        consent.setStatus(RecruitmentConsentStatus.GRANTED);
        consent.setGrantedAt(now);
        consent.setExpiresAt(expiresAt);
        consent.persist();

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.CONSENT_GRANTED)
                .candidate(candidate.getUuid())
                .actorCandidate()
                .payload("kind", kind.name())
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
