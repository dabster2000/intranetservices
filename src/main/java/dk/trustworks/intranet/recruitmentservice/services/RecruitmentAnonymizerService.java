package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentPiiState;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * GDPR anonymization — the single, reviewed mutation path for candidate
 * personal data (ATS P19, spec §5.5). One service handles both modes:
 * the nightly sweep's automatic deadline erasure ({@code AUTO}) and the
 * DPO's on-request erasure ({@code ON_REQUEST}, typed confirmation in
 * the UI). Irreversible by design.
 *
 * <h3>The anonymization contract (spec §4.1 — four enumerated targets)</h3>
 * <ol>
 *   <li><b>Candidate row PII columns</b> — nulled / fixed placeholders via
 *       {@link RecruitmentCandidate#anonymize()}; the structural skeleton
 *       (source, stages, dates, levels, reason codes) survives so
 *       statistics keep working.</li>
 *   <li><b>Event {@code pii} sections</b> — rewritten to
 *       <code>{"anonymized": true}</code> with {@code pii_state='ANONYMIZED'}.
 *       Two legs: every event with the candidate as subject, plus the
 *       referral-era events that predate the candidate row (REFERRAL_SUBMITTED
 *       and the referral-variant AI_SUGGESTIONS_GENERATED carry no candidate
 *       subject — they are found via {@code payload.referral_uuid}, findings
 *       §P6/§P9). This is the ONLY place outside
 *       {@code RecruitmentEventRecorder} that touches the event store — the
 *       {@code RecruitmentEventSingleWriterArchTest} append-only rule
 *       exempts exactly this class.</li>
 *   <li><b>Application answers</b> — the answer text is nulled on BOTH
 *       ownership legs (application- and candidate-scoped, V437); the rows
 *       and their {@code question_key}s survive for counting.</li>
 *   <li><b>S3 documents</b> — every {@code files} row with
 *       {@code relateduuid = candidateUuid} (CVs, cover letters, generated
 *       dossier PDFs, appendices) is deleted from S3 and the DB.</li>
 * </ol>
 *
 * Plus the P6–P18 carry-over targets recorded in findings:
 * <ul>
 *   <li><b>Referral rows</b> — a referral's own PII columns
 *       (candidate_name, linkedin_url, email, why_text,
 *       external_referrer_name) are scrubbed for referrals linked to this
 *       candidate (findings §P6).</li>
 *   <li><b>Pending-email rows</b> — rendered snapshots (to_email, subject,
 *       body) are scrubbed in every status; still-PENDING rows are
 *       dismissed so nobody can approve a mail to an erased candidate
 *       (findings §P15).</li>
 *   <li><b>Dossier family</b> — the live dossier's JSON columns, every
 *       revision's immutable snapshots (native SQL — the columns are
 *       {@code updatable=false} by design) and appendix filenames are
 *       scrubbed; the dossier PDFs themselves fall under the S3 leg
 *       (P19 DoD scope decision).</li>
 *   <li><b>Consent tokens</b> — {@code token_hash}/{@code token_expires_at}
 *       are cleared so no live public link survives the erasure.</li>
 * </ul>
 *
 * Deliberately untouched (spec stance): Slack messages already posted
 * (out of controller scope), {@code recruitment_scorecards} rows (scores /
 * recommendation are pseudonymous, prose lives in event pii — findings
 * §P11), {@code recruitment_signing_completed_cases} (structural uuids,
 * findings §P10), and the module-external {@code mail} outbox (pre-existing
 * retention question, flagged in findings §P15 and again in §P19).
 *
 * <h3>Transactionality</h3>
 * One transaction per candidate. S3 deletes run LAST so a failure rolls
 * back every DB scrub and the next sweep retries the whole candidate;
 * S3 deletes are idempotent, so partially-deleted objects from a failed
 * run are harmless. The {@code CANDIDATE_ANONYMIZED} bookkeeping event is
 * appended (via the recorder, in the same transaction) BEFORE the S3 leg
 * so its own pii-free row commits atomically with the scrubs.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentAnonymizerService {

    /** What every rewritten {@code pii} section becomes (spec §3.3). */
    public static final String ANONYMIZED_PII_JSON = "{\"anonymized\": true}";

    /** Marker for NOT NULL text columns whose content had to go. */
    static final String SCRUBBED_TEXT = "[anonymized]";

    /** Placeholder for NOT NULL email columns. */
    static final String SCRUBBED_EMAIL = "anonymized@invalid";

    /** Who requested the erasure — drives the event's actor stamp. */
    public enum Mode {
        /** The nightly sweep hit the retention deadline. */
        AUTO,
        /** A DPO honored an erasure request (typed confirmation in the UI). */
        ON_REQUEST
    }

    /** Per-target counts for the bookkeeping event, logs and the DPO UI. */
    public record AnonymizationSummary(String candidateUuid, Mode mode,
                                       int eventsRewritten, int answersScrubbed,
                                       int documentsDeleted, int referralsScrubbed,
                                       int pendingEmailsScrubbed, int dossiersScrubbed,
                                       int revisionsScrubbed, int consentTokensCleared,
                                       boolean alreadyAnonymized) {
    }

    @Inject
    EntityManager em;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentS3StorageService s3StorageService;

    @Inject
    RecruitmentEmailService emailService;

    /**
     * Anonymize one candidate. Idempotent: an already-ANONYMIZED candidate
     * returns a no-op summary (the sweep may race a DPO action; neither
     * must fail). Refuses HIRED candidates — hired people leave the
     * recruitment retention regime (spec §5.5); their file is
     * access-restricted instead.
     *
     * @param candidateUuid the candidate to erase
     * @param mode          {@link Mode#AUTO} (sweep) or {@link Mode#ON_REQUEST} (DPO)
     * @param actorUserUuid the DPO's user uuid for ON_REQUEST; ignored for AUTO
     * @throws BusinessRuleViolation when the candidate does not exist or is HIRED
     */
    @Transactional
    public AnonymizationSummary anonymize(String candidateUuid, Mode mode, String actorUserUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            throw new BusinessRuleViolation("Candidate not found");
        }
        if (candidate.getStatus() == CandidateStatus.ANONYMIZED) {
            log.infof("Anonymization skipped: candidate %s is already anonymized", candidateUuid);
            return new AnonymizationSummary(candidateUuid, mode, 0, 0, 0, 0, 0, 0, 0, 0, true);
        }
        if (candidate.getStatus() == CandidateStatus.HIRED) {
            throw new BusinessRuleViolation(
                    "Hired candidates leave the recruitment retention regime — their file is "
                            + "access-restricted, not deleted (employee data has its own basis)");
        }

        // The event's visibility must be decided BEFORE the scrub (it reads
        // application/position joins that survive, but keep the read early
        // and obvious).
        var visibility = emailService.visibilityFor(candidateUuid);

        List<String> referralUuids = RecruitmentReferral
                .<RecruitmentReferral>list("candidateUuid", candidateUuid).stream()
                .map(RecruitmentReferral::getUuid)
                .toList();

        // ---- Target 1: candidate row ------------------------------------
        candidate.anonymize();

        // ---- Target 2: event pii sections (two legs) ---------------------
        int eventsRewritten = rewriteCandidateEvents(candidateUuid);
        eventsRewritten += rewriteReferralEvents(referralUuids);

        // ---- Target 3: application answers (both ownership legs) ---------
        int answersScrubbed = scrubAnswers(candidateUuid);

        // ---- Carry-over targets ------------------------------------------
        int referralsScrubbed = scrubReferrals(candidateUuid);
        int pendingEmailsScrubbed = scrubPendingEmails(candidateUuid, mode, actorUserUuid);
        int dossiersScrubbed = scrubDossiers(candidateUuid);
        int revisionsScrubbed = scrubDossierRevisions(candidateUuid);
        int consentTokensCleared = clearConsentTokens(candidateUuid);

        // ---- Bookkeeping event (pii-free, same transaction) ---------------
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.CANDIDATE_ANONYMIZED)
                .candidate(candidateUuid)
                .visibility(visibility)
                .payload("mode", mode.name())
                .payload("events_rewritten", eventsRewritten)
                .payload("answers_scrubbed", answersScrubbed)
                .payload("referrals_scrubbed", referralsScrubbed)
                .payload("pending_emails_scrubbed", pendingEmailsScrubbed)
                .payload("dossiers_scrubbed", dossiersScrubbed)
                .payload("revisions_scrubbed", revisionsScrubbed);
        if (mode == Mode.ON_REQUEST) {
            event.actorUser(actorUserUuid);
        } else {
            event.actorScheduler();
        }

        // ---- Target 4: S3 documents (LAST — see class javadoc) ------------
        int documentsDeleted = s3StorageService.deleteAllCandidateFiles(
                UUID.fromString(candidateUuid));
        event.payload("documents_deleted", documentsDeleted);
        eventRecorder.record(event);

        log.infof("Anonymized candidate %s (mode=%s): events=%d answers=%d documents=%d "
                        + "referrals=%d pendingEmails=%d dossiers=%d revisions=%d tokens=%d",
                candidateUuid, mode, eventsRewritten, answersScrubbed, documentsDeleted,
                referralsScrubbed, pendingEmailsScrubbed, dossiersScrubbed, revisionsScrubbed,
                consentTokensCleared);
        return new AnonymizationSummary(candidateUuid, mode, eventsRewritten, answersScrubbed,
                documentsDeleted, referralsScrubbed, pendingEmailsScrubbed, dossiersScrubbed,
                revisionsScrubbed, consentTokensCleared, false);
    }

    // ------------------------------------------------------------------
    // Event store rewrite — the ArchUnit-exempted path
    // ------------------------------------------------------------------

    /**
     * Candidate-subject leg: every event carrying personal data for this
     * candidate. Bulk update on purpose — the append-only ArchUnit rule
     * ({@code nobody_deletes_or_bulk_updates_recruitment_events}) exempts
     * exactly this class as the single permitted mutation (P1 carry-over).
     */
    private int rewriteCandidateEvents(String candidateUuid) {
        return RecruitmentEvent.update(
                "pii = ?1, piiState = ?2 where candidateUuid = ?3 and piiState = ?4",
                ANONYMIZED_PII_JSON, RecruitmentPiiState.ANONYMIZED,
                candidateUuid, RecruitmentPiiState.PRESENT);
    }

    /**
     * Referral leg: REFERRAL_SUBMITTED and the referral-variant
     * AI_SUGGESTIONS_GENERATED predate the candidate row and carry no
     * candidate subject — they are addressed by {@code payload.referral_uuid}
     * (findings §P6/§P9). Native SQL because JPQL has no JSON extraction;
     * still exclusively inside this service (the reviewed mutation path).
     */
    private int rewriteReferralEvents(List<String> referralUuids) {
        int rewritten = 0;
        for (String referralUuid : referralUuids) {
            rewritten += em.createNativeQuery("""
                            UPDATE recruitment_events
                            SET pii = :piiJson, pii_state = 'ANONYMIZED'
                            WHERE candidate_uuid IS NULL
                              AND pii_state = 'PRESENT'
                              AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.referral_uuid')) = :referral
                            """)
                    .setParameter("piiJson", ANONYMIZED_PII_JSON)
                    .setParameter("referral", referralUuid)
                    .executeUpdate();
        }
        return rewritten;
    }

    // ------------------------------------------------------------------
    // Remaining scrub legs
    // ------------------------------------------------------------------

    /** Null the answer text on both ownership legs (V437); rows survive. */
    private int scrubAnswers(String candidateUuid) {
        List<String> applicationUuids = RecruitmentApplication
                .<RecruitmentApplication>list("candidateUuid", candidateUuid).stream()
                .map(RecruitmentApplication::getUuid)
                .toList();
        int scrubbed = RecruitmentApplicationAnswer.update(
                "answer = null where candidateUuid = ?1 and answer is not null", candidateUuid);
        if (!applicationUuids.isEmpty()) {
            scrubbed += RecruitmentApplicationAnswer.update(
                    "answer = null where applicationUuid in ?1 and answer is not null",
                    applicationUuids);
        }
        return scrubbed;
    }

    /** Scrub the PII columns of every referral linked to this candidate (findings §P6). */
    private int scrubReferrals(String candidateUuid) {
        List<RecruitmentReferral> referrals =
                RecruitmentReferral.list("candidateUuid", candidateUuid);
        for (RecruitmentReferral referral : referrals) {
            referral.setCandidateName("Anonymized Candidate");
            referral.setLinkedinUrl(null);
            referral.setEmail(null);
            referral.setWhyText(SCRUBBED_TEXT);
            referral.setExternalReferrerName(null);
        }
        return referrals.size();
    }

    /**
     * Scrub rendered snapshots in every status and dismiss still-PENDING
     * rows — nobody may approve a mail to an erased candidate (findings §P15).
     */
    private int scrubPendingEmails(String candidateUuid, Mode mode, String actorUserUuid) {
        List<RecruitmentPendingEmail> rows =
                RecruitmentPendingEmail.list("candidateUuid", candidateUuid);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (RecruitmentPendingEmail row : rows) {
            row.setToEmail(SCRUBBED_EMAIL);
            row.setSubject(SCRUBBED_TEXT);
            row.setBody(SCRUBBED_TEXT);
            if (row.getStatus() == RecruitmentPendingEmailStatus.PENDING) {
                row.setStatus(RecruitmentPendingEmailStatus.DISMISSED);
                row.setResolvedAt(now);
                row.setResolvedBy(mode == Mode.ON_REQUEST ? actorUserUuid : null);
            }
        }
        return rows.size();
    }

    /** Empty the live dossier's JSON columns (names, addresses, signer emails). */
    private int scrubDossiers(String candidateUuid) {
        List<CandidateDossier> dossiers = CandidateDossier.list("candidateUuid", candidateUuid);
        for (CandidateDossier dossier : dossiers) {
            dossier.setPlaceholderValuesJson(null);
            dossier.setSignersConfigJson(null);
            dossier.setAppendicesJson(null);
        }
        return dossiers.size();
    }

    /**
     * The dedicated revision scrubber (P19 DoD scope decision): revision
     * snapshots are immutable by design ({@code updatable=false}), so the
     * one permitted rewrite goes through native SQL. Snapshot columns are
     * NOT NULL — they become empty-but-parseable JSON so the revision UI
     * keeps rendering. Appendix filenames are scrubbed alongside.
     */
    private int scrubDossierRevisions(String candidateUuid) {
        int revisions = em.createNativeQuery("""
                        UPDATE candidate_dossier_revisions r
                        JOIN candidate_dossiers d ON r.dossier_uuid = d.uuid
                        SET r.placeholder_values_snapshot = '{}',
                            r.signers_config_snapshot = '[]',
                            r.appendices_snapshot = '[]',
                            r.generated_pdfs_snapshot = NULL,
                            r.recipient_email = :email,
                            r.note = NULL,
                            r.s3_retention_until = NULL
                        WHERE d.candidate_uuid = :candidate
                        """)
                .setParameter("email", SCRUBBED_EMAIL)
                .setParameter("candidate", candidateUuid)
                .executeUpdate();
        em.createNativeQuery("""
                        UPDATE candidate_dossier_appendices a
                        JOIN candidate_dossiers d ON a.dossier_uuid = d.uuid
                        SET a.original_filename = 'anonymized.pdf'
                        WHERE d.candidate_uuid = :candidate
                        """)
                .setParameter("candidate", candidateUuid)
                .executeUpdate();
        return revisions;
    }

    /** No live public consent link may survive the erasure. */
    private int clearConsentTokens(String candidateUuid) {
        return RecruitmentConsent.update(
                "tokenHash = null, tokenExpiresAt = null "
                        + "where candidateUuid = ?1 and tokenHash is not null", candidateUuid);
    }
}
