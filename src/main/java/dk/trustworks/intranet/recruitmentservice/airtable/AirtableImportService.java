package dk.trustworks.intranet.recruitmentservice.airtable;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentPositionDefaults;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The P21 Airtable migration orchestrator (spec §10, plan §P21): export →
 * map (Appendix A) → reconcile → import, with a persisted run row and a
 * per-record ledger making every run idempotent and every outcome
 * verifiable in the database (never in memory — deploys kill in-flight
 * jobs silently).
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li><b>Dry-run</b> — synchronous; maps everything, persists the
 *       reconciliation report, writes NOTHING else. Repeatable at will.</li>
 *   <li><b>Import</b> — refuses to start while any blocker exists
 *       (unmapped faglighed/pipeline values, unknown statuses — run ends
 *       BLOCKED with the same report a dry-run gives) or while another
 *       import is running. Otherwise the per-record work happens on a
 *       background thread, each record in its own transaction, so one bad
 *       record never rolls back the batch and progress is readable from
 *       the ledger at any time.</li>
 * </ul>
 *
 * <h3>What one imported record produces</h3>
 * Candidate row (+ pool/terminal state per A.2), application row on the
 * table's synthetic position (except Backlog → pool, no application),
 * answers, interview rows from the date columns, one
 * {@code CANDIDATE_CREATED {migrated_from:'airtable'}} event, one
 * {@code NOTE_ADDED} carrying the verbatim Airtable snapshot in {@code pii}
 * (anonymizable), CV/cover-letter/contract attachments to S3 (each with
 * its {@code DOCUMENT_UPLOADED} event), consent rows when Airtable's GDPR
 * checkbox was ticked, and the ledger row. Pre-migration history is
 * deliberately NOT replayed as events — the snapshot note is the history
 * (spec §10 step 3).
 *
 * <h3>Retention triage (spec §10 step 4)</h3>
 * Terminal/pooled candidates without consent whose last activity is more
 * than {@link #TRIAGE_MONTHS} months old get NO retention deadline — they
 * surface in the DPO queue's migration-triage section (via the ledger
 * join) for an explicit human anonymize-or-consent decision; nothing is
 * auto-deleted behind the DPO's back. Recent ones get the normal
 * activity + 6 months deadline and flow through the standard machinery.
 * HIRED candidates leave the retention regime entirely (spec §5.5).
 */
@JBossLog
@ApplicationScoped
public class AirtableImportService {

    /** recruitment_candidates.created_by_useruuid marker (PublicApplyService idiom). */
    public static final String AIRTABLE_CREATOR = "airtable-import";

    /** payload.origin on every event the importer emits. */
    public static final String ORIGIN_AIRTABLE = "airtable_import";

    /** Spec §10 step 4: process ended &gt; 6 months ago + no consent → DPO triage. */
    public static final int TRIAGE_MONTHS = 6;

    /** Airtable consent checkbox → 12-month pool-retention consent (spec §4.1). */
    static final int POOL_CONSENT_MONTHS = 12;

    @Inject
    AirtableExportService exportService;

    @Inject
    RecruitmentS3StorageService storageService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    AirtableReferrerMatcher referrerMatcher;

    @Inject
    ObjectMapper objectMapper;

    private final AtomicBoolean importRunning = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Dry-run
    // ------------------------------------------------------------------

    /** Export + map + reconcile; persists the run + report; writes nothing else. */
    public AirtableReconciliationReport dryRun(String startedBy) {
        AirtableImportRun run = newRun(AirtableImportRun.Mode.DRY_RUN, startedBy);
        try {
            List<AirtableMappedRecord> mapped = exportAndMap();
            AirtableReconciliationReport report = inTx(() ->
                    buildReport(run.getUuid(), "DRY_RUN", mapped, 0, List.of()));
            finishRun(run, AirtableImportRun.Status.COMPLETED, report, null);
            return report;
        } catch (Exception e) {
            finishRun(run, AirtableImportRun.Status.FAILED, null, e.getMessage());
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /** Outcome of {@link #startImport}: the run + the pre-flight report. */
    public record ImportStart(String runUuid, AirtableImportRun.Status status,
                              AirtableReconciliationReport report) {
    }

    public ImportStart startImport(String startedBy) {
        return startImport(startedBy, null, false);
    }

    /**
     * Pre-flight (export, map, blocker check) synchronously; the actual
     * record import continues on a background thread. Poll the run via the
     * resource — the ledger shows live progress.
     *
     * @param onlyRecordId when non-null, import ONLY this Airtable record
     *        (the runbook's one-candidate spot check). Blockers are
     *        evaluated on the filtered set, so a sample import works even
     *        while unrelated records still lack mappings.
     * @param excludeHired when true, HIRED-disposition records are left
     *        out of this round entirely — no ledger rows are written for
     *        them, so a later run (without the flag) imports exactly the
     *        hired backlog. Owner decision 2026-08-11: the first live
     *        round covers the active pipeline + talent pool only.
     */
    public ImportStart startImport(String startedBy, String onlyRecordId, boolean excludeHired) {
        if (!importRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("An Airtable import is already running");
        }
        AirtableImportRun run;
        List<AirtableMappedRecord> mapped;
        try {
            run = newRun(AirtableImportRun.Mode.IMPORT, startedBy);
        } catch (RuntimeException e) {
            importRunning.set(false);
            throw e;
        }
        try {
            mapped = exportAndMap();
            if (onlyRecordId != null && !onlyRecordId.isBlank()) {
                String wanted = onlyRecordId.trim();
                mapped = mapped.stream()
                        .filter(record -> wanted.equals(record.airtableRecordId()))
                        .toList();
                if (mapped.isEmpty()) {
                    throw new IllegalArgumentException(
                            "No Airtable record with id '" + wanted + "' in the export");
                }
            }
            if (excludeHired) {
                int before = mapped.size();
                mapped = mapped.stream()
                        .filter(record -> record.disposition() != AirtableMappedRecord.Disposition.HIRED)
                        .toList();
                log.infof("Airtable import: excludeHired left %d of %d records for this round",
                        mapped.size(), before);
            }
            List<AirtableMappedRecord> preflightSet = mapped;
            AirtableReconciliationReport preflight = inTx(() ->
                    buildReport(run.getUuid(), "IMPORT", preflightSet, 0, List.of()));
            if (!preflight.unmappedPracticeValues().isEmpty()
                    || !preflight.unknownStatuses().isEmpty()) {
                finishRun(run, AirtableImportRun.Status.BLOCKED, preflight, null);
                importRunning.set(false);
                return new ImportStart(run.getUuid(), AirtableImportRun.Status.BLOCKED, preflight);
            }
            Thread worker = new Thread(() -> executeImport(run, preflightSet),
                    "airtable-import-" + run.getUuid().substring(0, 8));
            worker.setDaemon(true);
            worker.start();
            return new ImportStart(run.getUuid(), AirtableImportRun.Status.RUNNING, preflight);
        } catch (Exception e) {
            finishRun(run, AirtableImportRun.Status.FAILED, null, e.getMessage());
            importRunning.set(false);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private void executeImport(AirtableImportRun run, List<AirtableMappedRecord> mapped) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activated = false;
        if (!requestContext.isActive()) {
            requestContext.activate();
            activated = true;
        }
        int imported = 0;
        List<AirtableReconciliationReport.RecordIssue> attachmentFailures = new ArrayList<>();
        try {
            Map<String, String> positionCache = new HashMap<>();
            // One directory load per run — referrer matching (by name) and
            // comment-author resolution (by email) share it. NOTE: must be
            // an explicit call in a lambda — a method reference
            // (User::listAll) bypasses Panache's build-time enhancement and
            // throws "did you forget to annotate your entity with @Entity?".
            List<User> users = inTx(() -> User.<User>listAll());
            List<AirtableReferrerMatcher.DirectoryUser> directory = users.stream()
                    .map(user -> new AirtableReferrerMatcher.DirectoryUser(
                            user.getUuid(), user.getFirstname(), user.getLastname()))
                    .toList();
            Map<String, String> emailToUser = new HashMap<>();
            for (User user : users) {
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    emailToUser.putIfAbsent(
                            user.getEmail().trim().toLowerCase(Locale.ROOT), user.getUuid());
                }
            }
            for (AirtableMappedRecord record : mapped) {
                try {
                    if (importOne(record, run.getUuid(), positionCache, attachmentFailures,
                            directory, emailToUser)) {
                        imported++;
                    }
                } catch (Exception e) {
                    log.errorf(e, "Airtable import: record %s (%s) failed",
                            record.airtableRecordId(), record.airtableTable());
                    ledgerSkip(record, run.getUuid(),
                            FAILED_PREFIX + " " + truncate(e.getMessage(), 150));
                }
            }
            int importedFinal = imported;
            AirtableReconciliationReport report = inTx(() -> buildReport(
                    run.getUuid(), "IMPORT", mapped, importedFinal, attachmentFailures));
            finishRun(run, AirtableImportRun.Status.COMPLETED, report, null);
            log.infof("Airtable import %s completed: %d/%d records imported this run",
                    run.getUuid(), importedFinal, mapped.size());
        } catch (Exception e) {
            log.errorf(e, "Airtable import %s failed", run.getUuid());
            finishRun(run, AirtableImportRun.Status.FAILED, null, e.getMessage());
        } finally {
            if (activated) {
                requestContext.terminate();
            }
            importRunning.set(false);
        }
    }

    // ------------------------------------------------------------------
    // Export + map
    // ------------------------------------------------------------------

    private List<AirtableMappedRecord> exportAndMap() {
        Map<String, List<AirtableClient.AirtableRecord>> export = exportService.exportAllTables();
        Map<String, String> practiceMapping = inTx(AirtablePracticeMapping::lookupMap);
        List<AirtableMappedRecord> mapped = new ArrayList<>();
        for (Map.Entry<String, List<AirtableClient.AirtableRecord>> table : export.entrySet()) {
            for (AirtableClient.AirtableRecord record : table.getValue()) {
                mapped.add(AirtableFieldMapper.map(record, table.getKey(), practiceMapping));
            }
        }
        return mapped;
    }

    // ------------------------------------------------------------------
    // One record (own transaction; attachment downloads outside it)
    // ------------------------------------------------------------------

    /** Ledger skip-reason prefix marking a FAILURE (retryable) vs a deliberate skip. */
    static final String FAILED_PREFIX = "Import failed:";

    /** @return true when the record was imported by THIS call. */
    private boolean importOne(AirtableMappedRecord record, String runUuid,
                              Map<String, String> positionCache,
                              List<AirtableReconciliationReport.RecordIssue> attachmentFailures,
                              List<AirtableReferrerMatcher.DirectoryUser> directory,
                              Map<String, String> emailToUser) {
        // A previously FAILED record retries (its ledger row is removed);
        // imported records and deliberate skips stay settled.
        boolean settled = inTx(() -> {
            AirtableImportRecord existing = AirtableImportRecord.findById(record.airtableRecordId());
            if (existing == null) {
                return false;
            }
            boolean retryable = existing.getStatus() == AirtableImportRecord.Status.SKIPPED
                    && existing.getSkipReason() != null
                    && existing.getSkipReason().startsWith(FAILED_PREFIX);
            if (retryable) {
                existing.delete();
                return false;
            }
            return true;
        });
        if (settled) {
            return false;
        }
        if (record.skipReason() != null) {
            ledgerSkip(record, runUuid, record.skipReason());
            return false;
        }

        // Airtable attachment URLs expire — fetch bytes before the transaction.
        List<DownloadedAttachment> attachments = new ArrayList<>();
        for (AirtableMappedRecord.MappedAttachment attachment : record.attachments()) {
            try {
                attachments.add(new DownloadedAttachment(
                        attachment, exportService.download(attachment.url())));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                attachmentFailures.add(new AirtableReconciliationReport.RecordIssue(
                        record.airtableRecordId(), record.airtableTable(),
                        attachment.kind() + " " + attachment.filename(),
                        "Download failed: " + truncate(e.getMessage(), 150)));
            }
        }

        // Referrer resolution BEFORE the transaction — the AI tier calls
        // OpenAI, and OpenAI calls never run inside a transaction (M1 rule).
        AirtableReferrerMatcher.Resolution referrer =
                referrerMatcher.resolve(record.referrerName(), directory, true);

        // Record comments (the collaboration thread) — a separate Airtable
        // API, fetched outside the transaction like the attachments. A
        // failure never blocks the record; it is listed in the report.
        List<AirtableClient.AirtableComment> comments = List.of();
        try {
            comments = exportService.fetchComments(record.airtableTable(), record.airtableRecordId());
        } catch (Exception e) {
            attachmentFailures.add(new AirtableReconciliationReport.RecordIssue(
                    record.airtableRecordId(), record.airtableTable(), "comments",
                    "Comment fetch failed: " + truncate(e.getMessage(), 150)));
        }
        List<AirtableClient.AirtableComment> commentsFinal = comments;

        QuarkusTransaction.requiringNew().run(() ->
                persistRecord(record, runUuid, positionCache, attachments, referrer,
                        commentsFinal, emailToUser));
        return true;
    }

    private record DownloadedAttachment(AirtableMappedRecord.MappedAttachment attachment,
                                        byte[] bytes) {
    }

    private void persistRecord(AirtableMappedRecord record, String runUuid,
                               Map<String, String> positionCache,
                               List<DownloadedAttachment> attachments,
                               AirtableReferrerMatcher.Resolution referrer,
                               List<AirtableClient.AirtableComment> comments,
                               Map<String, String> emailToUser) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // ---- candidate ----
        RecruitmentCandidate candidate = buildCandidate(record, now, referrer);
        RecruitmentCandidate.persist(candidate);
        recordCandidateCreated(candidate, record, referrer);

        // ---- consent (Airtable GDPR checkbox → dated pool-retention consent) ----
        if (record.consentGranted()) {
            grantPoolConsent(candidate, now);
        }

        // ---- application + answers + interviews (not for pool/Backlog) ----
        RecruitmentApplication application = null;
        String positionUuid = null;
        if (record.disposition() != AirtableMappedRecord.Disposition.POOLED) {
            positionUuid = positionForPractice(record, positionCache);
            application = buildApplication(record, candidate, positionUuid, now);
            application.persist();
            persistInterviews(record, application);
        }
        persistAnswers(record, candidate, application);

        // ---- snapshot + review-task + comment notes ----
        recordSnapshotNote(candidate, record);
        if (record.needsReviewTask()) {
            recordReviewTaskNote(candidate, record);
        }
        persistCommentNotes(candidate, comments, emailToUser);

        // ---- attachments to S3 (+ DOCUMENT_UPLOADED events) ----
        for (DownloadedAttachment downloaded : attachments) {
            storeAttachment(candidate, downloaded);
        }

        // ---- ledger ----
        AirtableImportRecord ledger = new AirtableImportRecord();
        ledger.setAirtableRecordId(record.airtableRecordId());
        ledger.setAirtableTable(record.airtableTable());
        ledger.setRunUuid(runUuid);
        ledger.setCandidateUuid(candidate.getUuid());
        ledger.setApplicationUuid(application == null ? null : application.getUuid());
        ledger.setPositionUuid(positionUuid);
        ledger.setStatus(AirtableImportRecord.Status.IMPORTED);
        ledger.persist();
    }

    // ---- candidate assembly ----------------------------------------------

    private RecruitmentCandidate buildCandidate(AirtableMappedRecord record, LocalDateTime now,
                                                AirtableReferrerMatcher.Resolution referrer) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(UUID.randomUUID().toString());
        candidate.setFirstName(record.firstName());
        candidate.setLastName(record.lastName());
        candidate.setEmail(blankToNull(record.email()));
        candidate.setPhone(blankToNull(record.phone()));
        candidate.setLinkedinUrl(blankToNull(record.linkedinUrl()));
        candidate.setEducationLevel(record.educationLevel());
        candidate.setEducationOther(blankToNull(record.educationOther()));
        candidate.setExperienceLevel(record.experienceLevel());
        candidate.setSpecializations(record.specializations());
        candidate.setSecurityClearance(record.securityClearance());
        candidate.setSecurityRelevant(record.securityRelevant());
        candidate.setSource(record.source());
        candidate.setSourceDetail(record.sourceDetail());
        candidate.setLawfulBasis(CandidateLawfulBasis.LEGITIMATE_INTEREST);
        candidate.setCreatedByUseruuid(AIRTABLE_CREATOR);
        if (record.createdDate() != null) {
            candidate.setCreatedAt(record.createdDate().atStartOfDay());
        }

        if (referrer.userUuid() != null) {
            candidate.setReferredByUserUuid(referrer.userUuid());
        } else if (record.referrerName() != null && !record.referrerName().isBlank()) {
            // Real Airtable data: references are often not employees (spec §4.1).
            candidate.setExternalReferrerName(record.referrerName().trim());
        }
        resolveTeamlead(candidate, record);

        LocalDate activityDate = lastActivity(record);
        switch (record.disposition()) {
            case OPEN -> candidate.setStatus(CandidateStatus.ACTIVE);
            case HIRED -> {
                candidate.setStatus(CandidateStatus.HIRED);
                candidate.setProcessEndedAt(atNoon(activityDate));
                // HIRED leaves the recruitment retention regime (spec §5.5).
            }
            case REJECTED -> {
                candidate.setStatus(CandidateStatus.DECLINED);
                candidate.setDeclineReason("Migreret fra Airtable: No hire");
                candidate.setProcessEndedAt(atNoon(activityDate));
                stampRetention(candidate, record, activityDate, now);
            }
            case POOLED -> {
                candidate.setStatus(CandidateStatus.POOLED);
                candidate.setPoolStatus(CandidatePoolStatus.PROSPECT);
                stampRetention(candidate, record, activityDate, now);
            }
            case SKIP -> throw new IllegalStateException("SKIP records never reach persistRecord");
        }
        return candidate;
    }

    /**
     * Retention stamping for terminal/pooled candidates: with consent the
     * deadline is the consent expiry (12 months); without consent a RECENT
     * candidate gets the normal activity + 6 months deadline, while an OLD
     * one (activity &gt; 6 months ago) gets NO deadline — it belongs to the
     * DPO triage queue and must never be auto-anonymized before that
     * explicit human decision.
     */
    private static void stampRetention(RecruitmentCandidate candidate,
                                       AirtableMappedRecord record,
                                       LocalDate activityDate, LocalDateTime now) {
        if (record.consentGranted()) {
            candidate.setRetentionDeadline(now.plusMonths(POOL_CONSENT_MONTHS));
            return;
        }
        LocalDateTime deadline = atNoon(activityDate).plusMonths(TRIAGE_MONTHS);
        if (deadline.isAfter(now)) {
            candidate.setRetentionDeadline(deadline);
        }
        // else: triage — deadline stays NULL, surfaced by the DPO queue's
        // migration section through the import ledger.
    }

    /** Is this record headed for the DPO retention-triage queue? */
    static boolean isRetentionTriage(AirtableMappedRecord record, LocalDate today) {
        if (record.disposition() != AirtableMappedRecord.Disposition.REJECTED
                && record.disposition() != AirtableMappedRecord.Disposition.POOLED) {
            return false;
        }
        if (record.consentGranted()) {
            return false;
        }
        return lastActivity(record).isBefore(today.minusMonths(TRIAGE_MONTHS));
    }

    static LocalDate lastActivity(AirtableMappedRecord record) {
        if (record.lastStatusChange() != null) {
            return record.lastStatusChange();
        }
        if (record.createdDate() != null) {
            return record.createdDate();
        }
        return LocalDate.now(ZoneOffset.UTC);
    }

    private void resolveTeamlead(RecruitmentCandidate candidate, AirtableMappedRecord record) {
        String email = record.relevantTeamleadEmail();
        if (email == null || email.isBlank()) {
            return;
        }
        User user = User.<User>find("lower(email)", email.trim().toLowerCase(Locale.ROOT))
                .firstResult();
        if (user != null) {
            candidate.setRelevantTeamleadUuid(user.getUuid());
        }
    }

    // ---- application / interviews / answers ------------------------------

    private RecruitmentApplication buildApplication(AirtableMappedRecord record,
                                                    RecruitmentCandidate candidate,
                                                    String positionUuid, LocalDateTime now) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid(UUID.randomUUID().toString());
        application.setCandidateUuid(candidate.getUuid());
        application.setPositionUuid(positionUuid);
        application.setExpectedStartDate(record.expectedStartDate());
        // Deliberate: stage_entered_at is import time, not the Airtable
        // date — idle clocks (SLA nudges, board badges) start at cutover
        // instead of firing a nudge storm on day one. The historical date
        // is preserved in the snapshot note.
        application.setStageEnteredAt(now);
        switch (record.disposition()) {
            case OPEN -> application.setStage(record.stage());
            case HIRED -> application.setStage(RecruitmentStage.HIRED);
            case REJECTED -> {
                application.setStage(AirtableFieldMapper.stageFromInterviews(record.interviews()));
                application.setTerminal(RecruitmentApplicationTerminal.REJECTED);
                application.setRejectionReasonCode(RecruitmentRejectionReason.OTHER);
            }
            default -> throw new IllegalStateException(
                    "No application for disposition " + record.disposition());
        }
        return application;
    }

    private void persistInterviews(AirtableMappedRecord record, RecruitmentApplication application) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (AirtableMappedRecord.MappedInterview mapped : record.interviews()) {
            RecruitmentInterview interview = new RecruitmentInterview();
            interview.setUuid(UUID.randomUUID().toString());
            interview.setApplicationUuid(application.getUuid());
            interview.setKind(mapped.informal()
                    ? RecruitmentInterviewKind.INFORMAL : RecruitmentInterviewKind.ROUND);
            interview.setRound(mapped.informal() ? null : mapped.round());
            interview.setScheduledAt(mapped.date().atTime(12, 0));
            interview.setInterviewerUuids(List.of());
            interview.setStatus(mapped.date().isBefore(today)
                    ? RecruitmentInterviewStatus.HELD : RecruitmentInterviewStatus.SCHEDULED);
            interview.persist();
        }
    }

    private void persistAnswers(AirtableMappedRecord record, RecruitmentCandidate candidate,
                                RecruitmentApplication application) {
        for (Map.Entry<String, String> entry : record.answers().entrySet()) {
            RecruitmentApplicationAnswer answer = new RecruitmentApplicationAnswer();
            if (application != null) {
                answer.setApplicationUuid(application.getUuid());
            } else {
                answer.setCandidateUuid(candidate.getUuid());
            }
            answer.setQuestionKey(entry.getKey());
            answer.setAnswer(entry.getValue());
            answer.persist();
        }
    }

    // ---- synthetic positions: one per PRACTICE -----------------------------
    // The spec (§10) said one position per team-pipeline TABLE, assuming the
    // base had per-team tables. The real base has ONE candidate table
    // ("ALL DATA"), which would put every migrated candidate on a single
    // position with one arbitrary practice — breaking the board's and the
    // reports' practice grouping. One synthetic position per resolved
    // practice keeps the grouping truthful; titles are human, not table
    // names, and remain freely renamable.

    /** No-practice sentinel cache key (defensive — blockers prevent it in practice). */
    private static final String NO_PRACTICE_KEY = "(none)";

    private String positionForPractice(AirtableMappedRecord record, Map<String, String> cache) {
        String practiceUuid = record.practiceUuid();
        String key = practiceUuid == null ? NO_PRACTICE_KEY : practiceUuid;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String existing = findExistingPositionForPractice(practiceUuid);
        if (existing != null) {
            cache.put(key, existing);
            return existing;
        }

        RecruitmentPosition position = new RecruitmentPosition();
        position.setUuid(UUID.randomUUID().toString());
        position.setTitle(syntheticPositionTitle(practiceUuid));
        position.setHiringTrack(RecruitmentHiringTrack.PRACTICE_TEAM);
        position.setPracticeUuid(practiceUuid);
        position.setStageSet(RecruitmentPositionDefaults.defaultStageSet(RecruitmentHiringTrack.PRACTICE_TEAM));
        position.setScorecardTemplate(RecruitmentPositionDefaults.defaultScorecardTemplate());
        position.persist();

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.POSITION_OPENED)
                .position(position.getUuid())
                .actorSystem()
                .payload("title", position.getTitle())
                .payload("hiring_track", position.getHiringTrack().name())
                .payload("practice_uuid", position.getPracticeUuid())
                .payload("migrated_from", "airtable")
                .payload("origin", ORIGIN_AIRTABLE));

        cache.put(key, position.getUuid());
        return position.getUuid();
    }

    /**
     * Re-runs reuse the position an earlier run created for the same
     * practice — resolved through the ledger's position uuids (robust
     * against title renames), never by title.
     */
    private static String findExistingPositionForPractice(String practiceUuid) {
        List<AirtableImportRecord> priors = AirtableImportRecord.list("positionUuid is not null");
        Set<String> seen = new HashSet<>();
        for (AirtableImportRecord prior : priors) {
            if (!seen.add(prior.getPositionUuid())) {
                continue;
            }
            RecruitmentPosition position = RecruitmentPosition.findById(prior.getPositionUuid());
            if (position != null && Objects.equals(position.getPracticeUuid(), practiceUuid)) {
                return position.getUuid();
            }
        }
        return null;
    }

    private static String syntheticPositionTitle(String practiceUuid) {
        if (practiceUuid != null) {
            Practice practice = Practice.<Practice>find("uuid", practiceUuid).firstResult();
            if (practice != null) {
                return "Migreret fra Airtable — " + practice.getName();
            }
        }
        return "Migreret fra Airtable";
    }

    // ---- events ----------------------------------------------------------

    private void recordCandidateCreated(RecruitmentCandidate candidate,
                                        AirtableMappedRecord record,
                                        AirtableReferrerMatcher.Resolution referrer) {
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.CANDIDATE_CREATED)
                .candidate(candidate.getUuid())
                .actorSystem()
                .payload("migrated_from", "airtable")
                .payload("origin", ORIGIN_AIRTABLE)
                .payload("airtable_record_id", record.airtableRecordId())
                .payload("airtable_table", record.airtableTable())
                .payload("airtable_status", record.airtableStatus())
                .payload("source", name(candidate.getSource()))
                .payload("education_level", name(candidate.getEducationLevel()))
                .payload("experience_level", name(candidate.getExperienceLevel()))
                .payload("lawful_basis", name(candidate.getLawfulBasis()))
                .pii("first_name", candidate.getFirstName())
                .pii("last_name", candidate.getLastName());
        if (referrer.matchMethod() != null) {
            // Structural audit fact: how the referrer link was made (name | ai_extraction).
            event.payload("referrer_match", referrer.matchMethod());
        }
        piiIfPresent(event, "email", candidate.getEmail());
        piiIfPresent(event, "phone", candidate.getPhone());
        piiIfPresent(event, "linkedin_url", candidate.getLinkedinUrl());
        if (candidate.getSourceDetail() != null && !candidate.getSourceDetail().isEmpty()) {
            event.pii("source_detail", candidate.getSourceDetail());
        }
        eventRecorder.record(event);
    }

    /** Spec §10 step 3: pre-migration history = ONE snapshot note, pii-side. */
    private void recordSnapshotNote(RecruitmentCandidate candidate, AirtableMappedRecord record) {
        String snapshot;
        try {
            snapshot = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(record.rawFields());
        } catch (Exception e) {
            snapshot = String.valueOf(record.rawFields());
        }
        StringBuilder text = new StringBuilder()
                .append("Airtable-migrering (").append(record.airtableTable())
                .append(", status '").append(nullSafe(record.airtableStatus()))
                .append("', record ").append(record.airtableRecordId()).append(").\n");
        for (String note : record.notes()) {
            text.append('\n').append(note).append('\n');
        }
        text.append("\nAirtable-felter:\n").append(snapshot);

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.NOTE_ADDED)
                .candidate(candidate.getUuid())
                .actorSystem()
                .payload("private", false)
                .payload("origin", ORIGIN_AIRTABLE)
                .payload("migrated_from", "airtable")
                .pii("text", text.toString()));
    }

    /** A.2: Decision needed / Need review dissolve into an open recruiter task. */
    private void recordReviewTaskNote(RecruitmentCandidate candidate, AirtableMappedRecord record) {
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.NOTE_ADDED)
                .candidate(candidate.getUuid())
                .actorSystem()
                .payload("private", false)
                .payload("origin", ORIGIN_AIRTABLE)
                .payload("needs_review", true)
                .pii("text", "Airtable-status var '" + record.airtableStatus()
                        + "' — kræver manuel opfølgning af recruiter (P21-migrering)."));
    }

    /**
     * Airtable record comments → one {@code NOTE_ADDED} each, oldest
     * first so the timeline reads chronologically. The author's
     * trustworks email resolves to the real user (the timeline shows WHO
     * wrote the comment); unresolvable authors fall back to SYSTEM with
     * the name in the text. The event recorder stamps {@code occurred_at}
     * at import time, so the ORIGINAL timestamp is part of the note text.
     */
    private void persistCommentNotes(RecruitmentCandidate candidate,
                                     List<AirtableClient.AirtableComment> comments,
                                     Map<String, String> emailToUser) {
        comments.stream()
                .sorted(java.util.Comparator.comparing(
                        AirtableClient.AirtableComment::createdTime,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .forEach(comment -> {
                    RecruitmentEventBuilder event = RecruitmentEventBuilder
                            .event(RecruitmentEventType.NOTE_ADDED)
                            .candidate(candidate.getUuid())
                            .payload("private", false)
                            .payload("origin", ORIGIN_AIRTABLE)
                            .payload("migrated_from", "airtable")
                            .payload("airtable_comment_id", comment.id())
                            .pii("text", formatCommentNote(comment));
                    String authorEmail = comment.author() == null || comment.author().email() == null
                            ? null : comment.author().email().trim().toLowerCase(Locale.ROOT);
                    String authorUuid = authorEmail == null ? null : emailToUser.get(authorEmail);
                    if (authorUuid != null) {
                        event.actorUser(authorUuid);
                    } else {
                        event.actorSystem();
                    }
                    eventRecorder.record(event);
                });
    }

    /** Author + original timestamp header, @[usrXXX] mentions humanized. */
    static String formatCommentNote(AirtableClient.AirtableComment comment) {
        String text = comment.text() == null ? "" : comment.text();
        if (comment.mentioned() != null) {
            for (Map.Entry<String, AirtableClient.CommentMention> entry : comment.mentioned().entrySet()) {
                String display = entry.getValue() == null || entry.getValue().displayName() == null
                        ? "?" : entry.getValue().displayName();
                text = text.replace("@[" + entry.getKey() + "]", "@" + display);
            }
        }
        String author = comment.author() != null && comment.author().name() != null
                ? comment.author().name() : "Ukendt";
        String when = comment.createdTime() != null && comment.createdTime().length() >= 16
                ? comment.createdTime().substring(0, 16).replace("T", " ") + " UTC" : "";
        return "Airtable-kommentar fra " + author + (when.isEmpty() ? "" : " (" + when + ")")
                + ":\n" + text;
    }

    private void grantPoolConsent(RecruitmentCandidate candidate, LocalDateTime now) {
        RecruitmentConsent consent = new RecruitmentConsent();
        consent.setCandidateUuid(candidate.getUuid());
        consent.setKind(RecruitmentConsentKind.TALENT_POOL_RETENTION);
        consent.setStatus(RecruitmentConsentStatus.GRANTED);
        consent.setGrantedAt(now);
        consent.setExpiresAt(now.plusMonths(POOL_CONSENT_MONTHS));
        consent.persist();

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.CONSENT_GRANTED)
                .candidate(candidate.getUuid())
                .actorSystem()
                .payload("kind", RecruitmentConsentKind.TALENT_POOL_RETENTION.name())
                .payload("consent_uuid", consent.getUuid())
                .payload("origin", ORIGIN_AIRTABLE)
                .payload("migrated_from", "airtable"));
    }

    private void storeAttachment(RecruitmentCandidate candidate, DownloadedAttachment downloaded) {
        AirtableMappedRecord.MappedAttachment attachment = downloaded.attachment();
        String fileUuid = storageService.storeApplicationDocument(
                downloaded.bytes(), attachment.filename(),
                UUID.fromString(candidate.getUuid()));
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.DOCUMENT_UPLOADED)
                .candidate(candidate.getUuid())
                .actorSystem()
                .payload("file_uuid", fileUuid)
                .payload("kind", attachment.kind())
                .payload("size_bytes", downloaded.bytes().length)
                .payload("origin", ORIGIN_AIRTABLE)
                .pii("filename", attachment.filename()));
    }

    // ------------------------------------------------------------------
    // Reconciliation report
    // ------------------------------------------------------------------

    AirtableReconciliationReport buildReport(String runUuid, String mode,
                                             List<AirtableMappedRecord> mapped,
                                             int importedThisRun,
                                             List<AirtableReconciliationReport.RecordIssue> attachmentFailures) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        Map<String, Map<String, Long>> perTableAndStatus = new TreeMap<>();
        for (AirtableMappedRecord record : mapped) {
            perTableAndStatus
                    .computeIfAbsent(record.airtableTable(), k -> new TreeMap<>())
                    .merge(record.airtableStatus() == null || record.airtableStatus().isBlank()
                            ? "(empty)" : record.airtableStatus(), 1L, Long::sum);
        }
        Map<String, Long> perDisposition = mapped.stream().collect(Collectors.groupingBy(
                r -> r.disposition().name(), TreeMap::new, Collectors.counting()));

        List<String> unmappedPractice = mapped.stream()
                .filter(r -> r.practiceUuid() == null && r.disposition() != AirtableMappedRecord.Disposition.SKIP)
                .map(r -> r.faglighedValue() != null && !r.faglighedValue().isBlank()
                        ? r.faglighedValue() : r.airtableTable())
                .distinct().sorted().toList();
        List<String> unknownStatuses = mapped.stream()
                .filter(r -> r.disposition() == AirtableMappedRecord.Disposition.SKIP
                        && r.skipReason() == null)
                .map(r -> nullSafe(r.airtableStatus()))
                .distinct().sorted().toList();

        List<AirtableReconciliationReport.RecordIssue> skipped = mapped.stream()
                .filter(r -> r.skipReason() != null)
                .map(r -> issue(r, r.skipReason()))
                .toList();
        List<AirtableReconciliationReport.RecordIssue> warnings = mapped.stream()
                .flatMap(r -> r.warnings().stream().map(w -> issue(r, w)))
                .toList();
        List<AirtableReconciliationReport.RecordIssue> needsReview = mapped.stream()
                .filter(AirtableMappedRecord::needsReviewTask)
                .map(r -> issue(r, "Airtable-status '" + r.airtableStatus()
                        + "' bliver en åben opfølgningsopgave"))
                .toList();
        List<AirtableReconciliationReport.RecordIssue> retentionTriage = mapped.stream()
                .filter(r -> isRetentionTriage(r, today))
                .map(r -> issue(r, "Sidste aktivitet " + lastActivity(r)
                        + ", intet samtykke → DPO-triage"))
                .toList();

        Map<String, String> positionsPerPractice = new LinkedHashMap<>();
        mapped.stream()
                .filter(r -> r.disposition() == AirtableMappedRecord.Disposition.OPEN
                        || r.disposition() == AirtableMappedRecord.Disposition.HIRED
                        || r.disposition() == AirtableMappedRecord.Disposition.REJECTED)
                .map(AirtableMappedRecord::practiceUuid)
                .distinct()
                .forEach(practiceUuid -> {
                    String existing = findExistingPositionForPractice(practiceUuid);
                    positionsPerPractice.put(syntheticPositionTitle(practiceUuid),
                            existing != null ? existing : "(oprettes ved import)");
                });

        long alreadyImported = mapped.stream()
                .filter(r -> AirtableImportRecord.findById(r.airtableRecordId()) != null)
                .count();

        return new AirtableReconciliationReport(
                runUuid, mode, mapped.size(),
                perTableAndStatus, perDisposition,
                unmappedPractice, unknownStatuses,
                skipped, warnings, needsReview, retentionTriage,
                positionsPerPractice, (int) alreadyImported, importedThisRun,
                List.copyOf(attachmentFailures));
    }

    private static AirtableReconciliationReport.RecordIssue issue(AirtableMappedRecord record,
                                                                  String detail) {
        return new AirtableReconciliationReport.RecordIssue(
                record.airtableRecordId(), record.airtableTable(),
                (nullSafe(record.firstName()) + " " + nullSafe(record.lastName())).trim(),
                detail);
    }

    // ------------------------------------------------------------------
    // Run bookkeeping (each state change commits independently)
    // ------------------------------------------------------------------

    private AirtableImportRun newRun(AirtableImportRun.Mode mode, String startedBy) {
        return QuarkusTransaction.requiringNew().call(() -> {
            AirtableImportRun run = new AirtableImportRun();
            run.setMode(mode);
            run.setStartedBy(startedBy == null || startedBy.isBlank() ? "unknown" : startedBy);
            run.persist();
            return run;
        });
    }

    private void finishRun(AirtableImportRun run, AirtableImportRun.Status status,
                           AirtableReconciliationReport report, String error) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                AirtableImportRun managed = AirtableImportRun.findById(run.getUuid());
                if (managed == null) {
                    return;
                }
                managed.setStatus(status);
                managed.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
                managed.setError(error == null ? null : truncate(error, 4000));
                if (report != null) {
                    try {
                        managed.setReport(objectMapper.writeValueAsString(report));
                    } catch (Exception e) {
                        log.warnf(e, "Airtable run %s: report serialization failed", run.getUuid());
                    }
                }
            });
        } catch (Exception e) {
            log.errorf(e, "Airtable run %s: could not persist final status %s", run.getUuid(), status);
        }
    }

    private void ledgerSkip(AirtableMappedRecord record, String runUuid, String reason) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (AirtableImportRecord.findById(record.airtableRecordId()) != null) {
                    return;
                }
                AirtableImportRecord ledger = new AirtableImportRecord();
                ledger.setAirtableRecordId(record.airtableRecordId());
                ledger.setAirtableTable(record.airtableTable());
                ledger.setRunUuid(runUuid);
                ledger.setStatus(AirtableImportRecord.Status.SKIPPED);
                ledger.setSkipReason(truncate(reason, 200));
                ledger.persist();
            });
        } catch (Exception e) {
            log.errorf(e, "Airtable import: ledger skip row failed for %s", record.airtableRecordId());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private <T> T inTx(java.util.concurrent.Callable<T> work) {
        return QuarkusTransaction.requiringNew().call(work);
    }

    private static LocalDateTime atNoon(LocalDate date) {
        return date.atTime(12, 0);
    }

    private static void piiIfPresent(RecruitmentEventBuilder event, String key, String value) {
        if (value != null && !value.isBlank()) {
            event.pii(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
