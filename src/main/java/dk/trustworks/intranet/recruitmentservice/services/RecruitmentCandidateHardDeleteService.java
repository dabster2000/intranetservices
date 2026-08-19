package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentDiscussionThread;
import dk.trustworks.intranet.recruitmentservice.notifications.CandidateDiscussionSlackNotifier;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentSlackThread;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCardReactor;
import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingProjector;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ADMIN candidate hard delete (change C) — "undo a mis-creation", NOT
 * GDPR erasure. Anonymization ({@link RecruitmentAnonymizerService}) remains
 * the compliant erasure tool; this exists because anonymization cannot fix
 * the two numbers a mistaken candidate permanently inflates: the reports
 * Source-mix {@code candidates} series and the candidate-grid total count.
 * Landing and pipeline KPIs count applications and interviews, not
 * candidates, and are unaffected either way.
 *
 * <h3>Refusals — deliberately only two (owner decision)</h3>
 * <ul>
 *   <li>{@code HIRED_OR_CONVERTED} — {@code status == HIRED} or
 *       {@code converted_user_uuid} set. A hire has left the recruitment
 *       retention regime and the row is welded to a live {@code user} by
 *       {@code fk_recruitment_candidates_converted_user} (V489).</li>
 *   <li>{@code SIGNED} — a {@code candidate_dossier_revisions} row of kind
 *       {@code SIGNATURE} carrying a {@code signing_case_key}, or any
 *       {@code recruitment_signing_completed_cases} row. The signature is
 *       evidence of a legal act, and those rows are the only bridge to the
 *       NextSign case.</li>
 * </ul>
 * Everything else proceeds — interviews, scheduling, record checks, consents
 * and Slack cards included. That is what makes the cascade large and the
 * external cleanup real work rather than a refusal.
 *
 * <h3>Sequencing, and why this method is NOT {@code @Transactional}</h3>
 * <ol>
 *   <li><b>Preflight</b> (read-only): refusals, and the manifest of things
 *       the cascade will make unreachable (S3 file uuids, {@code mail} row
 *       uuids, SharePoint ids, Graph event handles).</li>
 *   <li><b>The ledger row is opened and COMMITTED</b>, before anything
 *       irreversible happens. See "the durable record", below.</li>
 *   <li><b>External redaction</b>: Microsoft Graph event cancellation and
 *       Slack card redaction. Both do remote I/O and both read rows the
 *       cascade deletes, so they run BEFORE it and OUTSIDE any transaction
 *       (the 2026-08-11 recruitment-reactor deadlock rule: no I/O inside a
 *       recruitment transaction).</li>
 *   <li><b>What was externally redacted is committed to the ledger</b>,
 *       still before the destructive transaction opens.</li>
 *   <li><b>The cascade</b>: {@link RecruitmentCandidateDeleteCascade}, a
 *       separate bean so the {@code @Transactional} interceptor actually
 *       fires — a self-call would silently run without a transaction. It
 *       finishes by stamping this same ledger row {@code COMPLETED} in the
 *       same transaction as the deletes.</li>
 *   <li><b>Post-commit</b>: S3 objects (a DB rollback is possible, an S3
 *       undelete is not), then
 *       {@link RecruitmentReportingProjector#rebuild()}, which opens its own
 *       {@code QuarkusTransaction.requiringNew()} and therefore must not be
 *       called from inside one.</li>
 * </ol>
 * Once the cascade commits, nothing that follows may fail the delete. A
 * rebuild that throws or times out leaves the delete committed and is
 * reported as {@code reportingRebuilt=false} plus a note; the numbers correct
 * themselves on the next rebuild.
 *
 * <h3>The durable record, and why the external work still goes first</h3>
 * Graph cancellation and Slack redaction are irreversible and they precede a
 * transaction that can still roll back. Two orderings were possible and the
 * first was rejected:
 * <ul>
 *   <li><b>External work after the commit</b> would need the rows it reads
 *       ({@code recruitment_applications}, {@code recruitment_slack_threads},
 *       {@code recruitment_interviews}) snapshotted first, and — worse — if
 *       Slack were down at that moment the channel id and root timestamp
 *       would already be deleted, so the candidate's name would stay on a
 *       live card with nothing left to retry from. Doing it first means a
 *       failure is still recoverable: the rows are all still there.</li>
 *   <li><b>External work first, with a durable record</b> — what this class
 *       does. The ledger row is opened and committed in its own transaction
 *       BEFORE the first Graph or Slack call, and the record of what was
 *       actually redacted is committed BEFORE the cascade opens. So a
 *       cascade that throws (or a process that dies mid-way) leaves a row
 *       reading {@code outcome=EXTERNAL_REDACTED}/{@code ROLLED_BACK} that
 *       names every Outlook event cancelled and every Slack card rewritten,
 *       next to a candidate who still exists. Undocumented external damage
 *       is the one outcome that cannot happen.</li>
 * </ul>
 * If the ledger row cannot be opened, the delete aborts <em>before</em> any
 * external call: no record, no irreversible work.
 * <p>
 * One window stays open, and only two-phase commit across Graph, Slack and
 * MariaDB could close it: between the first external call and the commit of
 * that record. A row left reading {@code ATTEMPTED} therefore means "external
 * redaction may have started and could not be written down" — the orchestrator
 * logs the whole record at ERROR in that case and refuses to run the cascade.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCandidateHardDeleteService {

    /** Machine-readable refusal: the candidate was hired or converted. */
    public static final String REFUSAL_HIRED_OR_CONVERTED = "HIRED_OR_CONVERTED";
    /** Machine-readable refusal: a signature exists. */
    public static final String REFUSAL_SIGNED = "SIGNED";

    /** The interview's internal Outlook event ({@code graph_event_id}, V442). */
    static final String LEG_INTERNAL = "internal";
    /** The candidate-facing invitation ({@code graph_candidate_event_id}, V493). */
    static final String LEG_CANDIDATE = "candidate";

    static final String REBUILD_FAILED_NOTE =
            "The candidate is deleted. The reporting projection could not be rebuilt now, "
                    + "so the reports Source-mix candidate count stays high until the next "
                    + "rebuild (POST /recruitment/reports/rebuild) — the delete itself is final.";

    /**
     * Per-table counts, what was irreversibly done outside the database, and
     * what is still out there.
     *
     * @param externallyRedacted what this delete actually changed in Microsoft
     *                           Graph and Slack — the record that makes a
     *                           rolled-back delete diagnosable rather than a
     *                           mystery. Also committed to the ledger row.
     */
    public record HardDeleteSummary(String candidateUuid, String ledgerUuid,
                                    Map<String, Integer> deletedCounts,
                                    Map<String, Object> externallyRedacted,
                                    Map<String, Object> residue,
                                    boolean reportingRebuilt,
                                    String note) {
    }

    /**
     * One cancellable Outlook event. {@code leg} distinguishes the internal
     * event from the candidate-facing invitation, which live in DIFFERENT
     * mailboxes — {@code ref()} is what the ledger and the response carry, and
     * it names no person.
     */
    record GraphEventHandle(String interviewUuid, String leg, String mailbox, String eventId) {
        String ref() {
            return interviewUuid + "#" + leg;
        }
    }

    /** Cancel one Outlook event, or throw. The seam the tests replace. */
    @FunctionalInterface
    interface GraphEventCanceller {
        void cancel(String mailbox, String eventId);
    }

    /** Per-event outcome of the Graph leg — never a bare "it went fine". */
    record GraphCancellation(List<String> cancelled, List<String> failed) {
    }

    /**
     * Everything read from the database before the destructive work starts.
     * Held as plain values so the orchestration below touches no Panache
     * statics and is therefore testable without a database.
     */
    record Preflight(List<String> applicationUuids, List<String> fileUuids,
                     List<String> mailUuids, List<String> sharepointDriveItemIds,
                     String sharepointFolderPath, List<GraphEventHandle> graphEventHandles,
                     boolean calendarEnabled, List<String> slackCardApplicationUuids,
                     List<String> discussionThreadUuids, boolean slackPresenceUnknown) {

        /**
         * Whether the candidate is in Slack at all. Without this check every
         * delete would carry the "replies and DMs survive" caveat, including
         * the ordinary case where nothing was ever posted — and a residue
         * field that is always set is a residue field nobody reads.
         */
        boolean hasSlackPresence() {
            return slackPresenceUnknown
                    || !slackCardApplicationUuids.isEmpty()
                    || !discussionThreadUuids.isEmpty();
        }
    }

    @Inject
    EntityManager em;

    @Inject
    RecruitmentCandidateDeleteCascade cascade;

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    SlackCardReactor slackCardReactor;

    @Inject
    CandidateDiscussionSlackNotifier discussionSlackNotifier;

    @Inject
    RecruitmentS3StorageService s3StorageService;

    @Inject
    RecruitmentReportingProjector reportingProjector;

    @Inject
    Instance<GraphApiClient> graphApiClientInstance;

    /**
     * The shared organizer mailbox events are created under, read from the
     * SAME config key {@code RecruitmentCalendarService} uses.
     * {@code Optional<String>} deliberately — a plain String with
     * {@code defaultValue = ""} makes the property REQUIRED and fails every
     * boot without the env var (SRCFG00014).
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.graph.calendar.organizer")
    Optional<String> configuredOrganizerValue;

    /**
     * The actual Graph DELETE. A field, not a direct call, so the per-event
     * outcome logic can be exercised without a Graph tenant.
     */
    GraphEventCanceller graphEventCanceller =
            (mailbox, eventId) -> graphApiClientInstance.get().deleteCalendarEvent(mailbox, eventId);

    // ------------------------------------------------------------------
    // Refusals — the decision half, Panache-free and unit-testable
    // ------------------------------------------------------------------

    /**
     * The refusal rule as a pure function of four facts, so it can be tested
     * without a database. Returns the machine-readable code, or {@code null}
     * when the delete may proceed.
     *
     * <p>Deliberately short. The owner's decision is that a hard delete
     * refuses on hired-or-signed and on nothing else: interviews, scheduling,
     * record checks, consents, open DSARs and posted Slack cards all proceed.
     * Do not add cases here without that decision being revisited.</p>
     */
    public static String refusalCodeFor(CandidateStatus status, String convertedUserUuid,
                                        long signedRevisions, long signingCompletedCases) {
        if (status == CandidateStatus.HIRED
                || (convertedUserUuid != null && !convertedUserUuid.isBlank())) {
            return REFUSAL_HIRED_OR_CONVERTED;
        }
        if (signedRevisions > 0 || signingCompletedCases > 0) {
            return REFUSAL_SIGNED;
        }
        return null;
    }

    /** Human-readable text for a refusal code. */
    public static String refusalMessageFor(String code) {
        return switch (code) {
            case REFUSAL_HIRED_OR_CONVERTED -> "This candidate was hired — their file left the "
                    + "recruitment retention regime and is welded to a live employee record. "
                    + "Hard delete is for undoing a mis-creation, not for closing an employment.";
            case REFUSAL_SIGNED -> "A signature exists for this candidate. The signed revision is "
                    + "the evidence of a legal act and the only bridge to the NextSign case — it "
                    + "cannot be deleted here.";
            default -> code;
        };
    }

    // ------------------------------------------------------------------
    // The orchestration
    // ------------------------------------------------------------------

    /**
     * Delete a candidate irreversibly. The caller has already proven the
     * actor's authorization and the typed-name confirmation.
     *
     * @param candidate the candidate to remove (already loaded and visible)
     * @param actorUuid the acting admin's uuid
     * @param reason    the admin's stated reason, already validated non-trivial
     * @throws WebApplicationException 409 with a machine-readable code when a
     *                                 refusal applies
     */
    public HardDeleteSummary hardDelete(RecruitmentCandidate candidate, String actorUuid,
                                        String reason) {
        requireDeletable(candidate);
        return runDelete(candidate.getUuid(), actorUuid, reason, preflight(candidate));
    }

    /**
     * Everything after the database reads: ledger, external redaction,
     * cascade, post-commit. Package-private and free of Panache statics on
     * purpose — the ordering guarantees documented on this class are the kind
     * that must be provable by test, and the alternative is a database.
     */
    HardDeleteSummary runDelete(String candidateUuid, String actorUuid, String reason,
                                Preflight pre) {
        Map<String, Object> residue = new LinkedHashMap<>();
        Map<String, Object> externallyRedacted = new LinkedHashMap<>();

        // ---- The durable record, opened and committed FIRST -----------------
        // If this throws, nothing irreversible has happened yet and nothing
        // will: the delete fails with the candidate and their Outlook
        // invitations and Slack cards all intact.
        String ledgerUuid = cascade.openLedger(candidateUuid, actorUuid, reason);

        // ---- External redaction, before the rows disappear ------------------
        cancelGraphEvents(pre, residue, externallyRedacted);
        redactSlack(candidateUuid, pre, residue, externallyRedacted);
        recordUnreachable(residue, "mailRowsRetained", pre.mailUuids(),
                "the `mail` table has no candidate column at all — these rendered "
                        + "message bodies are unreachable from here and must be cleaned "
                        + "up by uuid if that matters");
        recordUnreachable(residue, "sharepointDriveItemsRetained", pre.sharepointDriveItemIds(),
                "onboarding documents stored in SharePoint; this module has no delete path for them");
        String folderPath = pre.sharepointFolderPath();
        if (folderPath != null && !folderPath.isBlank()) {
            residue.put("sharepointFolderRetained",
                    redactedFolderHandle(folderPath, candidateUuid));
        }

        // ---- Commit what was irreversibly done, BEFORE the destructive tx ----
        // This is the whole point of the ordering: from here on, a rollback
        // leaves a row that names every cancelled invitation and rewritten
        // card, next to a candidate who still exists.
        try {
            cascade.recordExternalRedaction(ledgerUuid, externallyRedacted, residue);
        } catch (RuntimeException e) {
            // The one window this design cannot close without two-phase
            // commit: the external work is done and the row does not say so.
            // Make it loud, and do NOT go on to delete anything.
            log.errorf(e, "Hard delete of candidate %s could not record its external redaction "
                            + "on ledger row %s. The following was ALREADY done and is now only "
                            + "in this log line: %s (residue: %s)",
                    candidateUuid, ledgerUuid, externallyRedacted, residue);
            throw e;
        }

        // ---- The one transaction ---------------------------------------------
        RecruitmentCandidateDeleteCascade.CascadeResult result;
        try {
            result = cascade.deleteCandidate(candidateUuid, ledgerUuid);
        } catch (RuntimeException e) {
            markRolledBack(ledgerUuid, candidateUuid, e);
            throw e;
        }

        // ---- Post-commit. Nothing below may fail the delete. ------------------
        deleteStoredFiles(candidateUuid, pre.fileUuids(), residue);
        boolean rebuilt = rebuildReporting(candidateUuid);
        if (!rebuilt) {
            residue.put("reportingProjectionRebuilt", false);
        }
        if (!residue.isEmpty()) {
            try {
                cascade.recordResidue(result.ledgerUuid(), residue);
            } catch (RuntimeException e) {
                log.warnf(e, "Could not attach post-commit residue to deletion ledger %s: %s",
                        result.ledgerUuid(), e.getMessage());
            }
        }

        return new HardDeleteSummary(candidateUuid, result.ledgerUuid(), result.deletedCounts(),
                externallyRedacted, residue, rebuilt, rebuilt ? null : REBUILD_FAILED_NOTE);
    }

    /**
     * Stamp the surviving ledger row so an operator finds it by
     * {@code outcome <> 'COMPLETED'} instead of by accident.
     *
     * <p>Only the exception's CLASS is stored. The message can quote a column
     * value, and this table may hold no candidate identifier — that rule is
     * the reason the row is safe to keep forever. The full stack trace goes
     * to the log, which is not this table.</p>
     *
     * <p>Never rethrows: a bookkeeping failure must not replace the real
     * cause of the rollback in the caller's 500.</p>
     */
    private void markRolledBack(String ledgerUuid, String candidateUuid, RuntimeException cause) {
        log.errorf(cause, "Hard delete of candidate %s rolled back AFTER external redaction — "
                        + "ledger row %s records what was already cancelled/redacted: %s",
                candidateUuid, ledgerUuid, cause.getMessage());
        try {
            cascade.markRolledBack(ledgerUuid, cause.getClass().getName());
        } catch (RuntimeException e) {
            log.errorf(e, "Deletion ledger %s could not be marked ROLLED_BACK; it still holds "
                    + "the external-redaction record written before the cascade: %s",
                    ledgerUuid, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Preflight
    // ------------------------------------------------------------------

    private void requireDeletable(RecruitmentCandidate candidate) {
        List<String> dossierUuids = CandidateDossier
                .<CandidateDossier>list("candidateUuid = ?1", candidate.getUuid()).stream()
                .map(CandidateDossier::getUuid).toList();
        long signedRevisions = dossierUuids.isEmpty() ? 0
                : CandidateDossierRevision.count(
                        "dossierUuid in ?1 and kind = ?2 and signingCaseKey is not null",
                        dossierUuids, RevisionKind.SIGNATURE);
        long signingCases = signingCompletedCaseCount(candidate.getUuid());

        String code = refusalCodeFor(candidate.getStatus(), candidate.getConvertedUserUuid(),
                signedRevisions, signingCases);
        if (code != null) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", code, "message", refusalMessageFor(code)))
                    .build());
        }
    }

    /** Everything the cascade is about to make unreachable, read while it still is. */
    private Preflight preflight(RecruitmentCandidate candidate) {
        String candidateUuid = candidate.getUuid();
        List<String> applicationUuids = RecruitmentApplication
                .<RecruitmentApplication>list("candidateUuid = ?1", candidateUuid).stream()
                .map(RecruitmentApplication::getUuid).toList();
        List<String> fileUuids = File.<File>list("relateduuid", candidateUuid).stream()
                .map(File::getUuid).toList();
        List<String> sharepointDriveItemIds = OnboardingUploadSubmission
                .<OnboardingUploadSubmission>list(
                        "candidateUuid = ?1 and sharepointDriveItemId is not null", candidateUuid)
                .stream().map(OnboardingUploadSubmission::getSharepointDriveItemId).toList();

        List<String> slackCardApplicationUuids = List.of();
        List<String> discussionThreadUuids = List.of();
        boolean slackPresenceUnknown = false;
        try {
            slackCardApplicationUuids = applicationUuids.isEmpty() ? List.of()
                    : RecruitmentSlackThread.<RecruitmentSlackThread>list(
                            "applicationUuid in ?1", applicationUuids).stream()
                    .map(RecruitmentSlackThread::getApplicationUuid).toList();
            discussionThreadUuids = RecruitmentDiscussionThread
                    .<RecruitmentDiscussionThread>list("candidateUuid = ?1", candidateUuid).stream()
                    .map(RecruitmentDiscussionThread::getUuid).toList();
        } catch (RuntimeException e) {
            // Cannot prove absence — keep the "replies and DMs survive" caveat.
            log.warnf(e, "Could not resolve Slack presence for candidate %s: %s",
                    candidateUuid, e.getMessage());
            slackPresenceUnknown = true;
        }

        return new Preflight(applicationUuids, fileUuids, orphanedMailUuids(candidateUuid),
                sharepointDriveItemIds, candidate.getSharepointFolderPath(),
                graphEventHandles(applicationUuids), calendarService.isEnabled(),
                slackCardApplicationUuids, discussionThreadUuids, slackPresenceUnknown);
    }

    /**
     * {@code recruitment_signing_completed_cases} has no JPA entity (V441 is a
     * pure claims table, written with {@code INSERT IGNORE} by
     * {@code RecruitmentOfferBridge}), hence the native count.
     */
    private long signingCompletedCaseCount(String candidateUuid) {
        Object result = em.createNativeQuery(
                        "SELECT COUNT(*) FROM recruitment_signing_completed_cases "
                                + "WHERE candidate_uuid = :candidate")
                .setParameter("candidate", candidateUuid)
                .getSingleResult();
        return result == null ? 0L : ((Number) result).longValue();
    }

    /**
     * The {@code mail} rows this candidate's emails produced. {@code mail} has
     * no candidate column ({@code RecruitmentEmailService} persists a bare
     * {@code TrustworksMail}); the only link is the {@code mail_uuid} fact on
     * the {@code EMAIL_SENT} events, which the cascade is about to delete. So
     * this must be read now or the rows become permanently unreachable.
     */
    @SuppressWarnings("unchecked")
    private List<String> orphanedMailUuids(String candidateUuid) {
        try {
            return em.createNativeQuery("""
                            SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.mail_uuid'))
                            FROM recruitment_events
                            WHERE candidate_uuid = :candidate
                              AND JSON_EXTRACT(payload, '$.mail_uuid') IS NOT NULL
                            """)
                    .setParameter("candidate", candidateUuid)
                    .getResultList();
        } catch (RuntimeException e) {
            log.warnf(e, "Could not resolve mail rows for candidate %s: %s",
                    candidateUuid, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // External legs — best effort, residue on failure, never fatal
    // ------------------------------------------------------------------

    /**
     * Every Outlook event this candidate's interviews still hold, with the
     * mailbox each one lives in.
     *
     * <p>The selector takes an interview that carries <b>either</b> event id.
     * Keying on {@code graph_event_id is not null} alone missed the interview
     * whose internal event was already cancelled or never created but whose
     * candidate-facing invitation (V493) is still live in the deleted
     * person's inbox — the one event that matters most here.</p>
     */
    private List<GraphEventHandle> graphEventHandles(List<String> applicationUuids) {
        if (applicationUuids.isEmpty()) {
            return List.of();
        }
        List<RecruitmentInterview> interviews = RecruitmentInterview.list(
                "applicationUuid in ?1 and (graphEventId is not null "
                        + "or graphCandidateEventId is not null)", applicationUuids);
        List<GraphEventHandle> handles = new ArrayList<>();
        for (RecruitmentInterview interview : interviews) {
            String internal = organizerMailbox(interview);
            handles.addAll(handlesFor(interview, internal, candidateOrganizer(internal)));
        }
        return handles;
    }

    /**
     * Split one interview into its cancellable events. Pure — the mailboxes
     * are resolved by the caller, so this is exercisable without config,
     * Graph or a database.
     */
    static List<GraphEventHandle> handlesFor(RecruitmentInterview interview,
                                             String internalMailbox, String candidateMailbox) {
        List<GraphEventHandle> handles = new ArrayList<>(2);
        if (notBlank(interview.getGraphEventId())) {
            handles.add(new GraphEventHandle(interview.getUuid(), LEG_INTERNAL,
                    internalMailbox, interview.getGraphEventId()));
        }
        if (notBlank(interview.getGraphCandidateEventId())) {
            handles.add(new GraphEventHandle(interview.getUuid(), LEG_CANDIDATE,
                    candidateMailbox, interview.getGraphCandidateEventId()));
        }
        return handles;
    }

    /**
     * Cancel every Outlook event, recording each one's real outcome.
     *
     * <h3>Why this does not call {@code RecruitmentCalendarService.cancelEvent}</h3>
     * That method returns {@code void} and swallows everything — a missing
     * organizer mailbox, a disabled toggle, and any non-404 Graph error all
     * look identical to success from the outside. Used here it produced a
     * ledger and a response claiming a clean cancellation while the
     * candidate's invitations were still sitting in their inbox, which is
     * exactly the leg most likely to fail. It also returns early when
     * {@code graph_event_id} is null, so it can never cancel a
     * candidate-only invitation at all. A delete that reports what it did
     * needs per-event answers, so the two events are cancelled here and each
     * is recorded as cancelled or not.
     *
     * <p>A Graph 404 counts as cancelled: the event is not on anyone's
     * calendar, which is the outcome being asked for
     * ({@code GraphApiClient.deleteCalendarEvent} documents 404 as
     * idempotent).</p>
     */
    private void cancelGraphEvents(Preflight pre, Map<String, Object> residue,
                                   Map<String, Object> externallyRedacted) {
        List<GraphEventHandle> handles = pre.graphEventHandles();
        if (handles.isEmpty()) {
            return;
        }
        if (!pre.calendarEnabled()) {
            residue.put("graphEventsNotCancelled", handles.stream().map(GraphEventHandle::ref).toList());
            residue.put("graphEventsNotCancelledReason",
                    "calendar sync is disabled in this environment — the Outlook events, "
                            + "including any candidate-facing invitation, are still live");
            return;
        }
        GraphCancellation outcome = cancelEach(handles, graphEventCanceller);
        if (!outcome.cancelled().isEmpty()) {
            externallyRedacted.put("graphEventsCancelled", outcome.cancelled());
        }
        if (!outcome.failed().isEmpty()) {
            residue.put("graphEventsNotCancelled", outcome.failed());
            residue.put("graphEventsNotCancelledReason",
                    "Microsoft Graph refused the cancellation (or the organizer mailbox could "
                            + "not be resolved) — these Outlook events are still live and must "
                            + "be cancelled by hand");
        }
    }

    /**
     * The per-event loop, pure given a canceller. An event with no resolvable
     * mailbox is a FAILURE, not a skip: nobody cancelled it.
     */
    static GraphCancellation cancelEach(List<GraphEventHandle> handles,
                                        GraphEventCanceller canceller) {
        List<String> cancelled = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (GraphEventHandle handle : handles) {
            if (!notBlank(handle.mailbox())) {
                log.warnf("No organizer mailbox for %s — its Outlook event cannot be cancelled",
                        handle.ref());
                failed.add(handle.ref());
                continue;
            }
            try {
                canceller.cancel(handle.mailbox(), handle.eventId());
                cancelled.add(handle.ref());
            } catch (Exception e) {
                if (RecruitmentCalendarService.isGraphNotFound(e)) {
                    // Already gone: the invitation is off the calendar, which
                    // is what "cancelled" means here.
                    cancelled.add(handle.ref());
                    continue;
                }
                log.warnf(e, "Graph cancellation failed for %s: %s", handle.ref(), e.getMessage());
                failed.add(handle.ref());
            }
        }
        return new GraphCancellation(cancelled, failed);
    }

    /**
     * The mailbox the interview's INTERNAL event lives under: the organizer
     * persisted on the row when the event was created (V492), else the
     * configured shared mailbox.
     *
     * <p>Deliberately NOT falling back to the first interviewer's address the
     * way {@code RecruitmentCalendarService} does, for two reasons. It would
     * be a <em>guess</em> at which mailbox holds the event, and a wrong guess
     * comes back as a Graph 404 — which this leg reads as "already cancelled".
     * Manufacturing a false clean is the exact failure being fixed here. And
     * reading a user's mail address in this class would breach the ledger PII
     * ratchet ({@code RecruitmentDeletionLedgerNoPiiTest}), which bans those
     * accessors outright so no address can drift into a permanent,
     * never-cleaned audit row.</p>
     *
     * <p>{@code null} means "we do not know where this event lives", which
     * {@link #cancelEach} records as a FAILURE, so the admin is told to
     * cancel it by hand instead of being told it is gone.</p>
     */
    private String organizerMailbox(RecruitmentInterview interview) {
        if (notBlank(interview.getGraphOrganizer())) {
            return interview.getGraphOrganizer();
        }
        return configuredOrganizer();
    }

    /**
     * The mailbox the CANDIDATE invitation lives under: the shared organizer
     * (decision D1, {@code career@trustworks.dk}), falling back to the
     * internal organizer while the config is empty — the same fallback the
     * event was created with.
     */
    private String candidateOrganizer(String internalMailbox) {
        String configured = configuredOrganizer();
        return configured != null ? configured : internalMailbox;
    }

    /** Null-tolerant on the FIELD too: unit tests build this bean with bare {@code new}. */
    private String configuredOrganizer() {
        if (configuredOrganizerValue == null) {
            return null;
        }
        return configuredOrganizerValue.filter(v -> !v.isBlank()).map(String::trim).orElse(null);
    }

    /**
     * Rewrite the Slack messages that carry the candidate's name and can still
     * be addressed: the per-application living root cards and the
     * per-candidate discussion roots.
     *
     * <p>Both entry points force the redacted rendering rather than
     * re-deriving it from the candidate row — see
     * {@code SlackCardReactor.redactRootCardsForHardDelete}. The reactor's
     * own {@code redactRootCards} would have rendered the REAL name here,
     * because it only substitutes the placeholder once the row is already
     * ANONYMIZED, which a hard-deleted candidate never is.</p>
     *
     * <p>What cannot be reached, and is recorded as residue rather than
     * claimed: in-thread replies and mention DMs. Their message timestamps
     * are not stored anywhere, so the Slack API has no handle on them. This
     * is the same residual the anonymizer documents.</p>
     */
    private void redactSlack(String candidateUuid, Preflight pre, Map<String, Object> residue,
                             Map<String, Object> externallyRedacted) {
        try {
            List<String> failedCards = slackCardReactor.redactRootCardsForHardDelete(candidateUuid);
            recordSlackLeg(pre.slackCardApplicationUuids(), failedCards, externallyRedacted, residue,
                    "slackRootCardsRedacted", "slackRootCardsNotRedacted");
        } catch (RuntimeException e) {
            log.warnf(e, "Slack card redaction failed wholesale for candidate %s: %s",
                    candidateUuid, e.getMessage());
            residue.put("slackRootCardsNotRedacted", List.of("<all>"));
        }
        try {
            List<String> failedRoots =
                    discussionSlackNotifier.redactDiscussionRootsForHardDelete(candidateUuid);
            recordSlackLeg(pre.discussionThreadUuids(), failedRoots, externallyRedacted, residue,
                    "slackDiscussionRootsRedacted", "slackDiscussionRootsNotRedacted");
        } catch (RuntimeException e) {
            log.warnf(e, "Slack discussion redaction failed wholesale for candidate %s: %s",
                    candidateUuid, e.getMessage());
            residue.put("slackDiscussionRootsNotRedacted", List.of("<all>"));
        }
        if (pre.hasSlackPresence()) {
            residue.put("slackThreadRepliesAndDmsRetained",
                    "Slack thread replies and mention DMs keep the candidate's name — their "
                            + "message timestamps are not stored, so they cannot be addressed. "
                            + "Same documented residual as GDPR anonymization.");
        }
    }

    /**
     * Split a Slack leg into the two halves the ledger needs: what was
     * rewritten (irreversible — must be recorded even if the cascade then
     * rolls back) and what was not (residue).
     *
     * <p>{@code attempted} is the exact row set the reactor iterates, read in
     * the preflight, so "attempted minus failed" is the truth rather than an
     * estimate. Both reactors also answer with a sentinel string when their
     * own lookup failed; a failure that is not one of the attempted rows means
     * the leg's outcome is unknown, and an unknown leg claims <b>nothing</b>
     * as redacted.</p>
     */
    private static void recordSlackLeg(List<String> attempted, List<String> failed,
                                       Map<String, Object> externallyRedacted,
                                       Map<String, Object> residue,
                                       String redactedKey, String failedKey) {
        if (!failed.isEmpty()) {
            residue.put(failedKey, failed);
        }
        if (failed.stream().anyMatch(id -> !attempted.contains(id))) {
            return;
        }
        List<String> redacted = attempted.stream().filter(id -> !failed.contains(id)).toList();
        if (!redacted.isEmpty()) {
            externallyRedacted.put(redactedKey, redacted);
        }
    }

    /**
     * S3 objects go AFTER the DB commit: a DB rollback is possible, an S3
     * undelete is not ({@code RecruitmentAnonymizerService} ordering
     * rationale). Reuses the anonymizer's own bulk delete, which removes both
     * the S3 object and its {@code files} row (the object key IS the file
     * uuid — {@code S3FileService.delete}). Whatever is left afterwards is
     * residue: the row survives, so a retry is possible.
     */
    private void deleteStoredFiles(String candidateUuid, List<String> fileUuids,
                                   Map<String, Object> residue) {
        if (fileUuids.isEmpty()) {
            return;
        }
        try {
            s3StorageService.deleteAllCandidateFiles(UUID.fromString(candidateUuid));
        } catch (RuntimeException e) {
            log.errorf(e, "S3 cleanup failed for deleted candidate %s: %s",
                    candidateUuid, e.getMessage());
        }
        List<String> remaining;
        try {
            remaining = File.<File>list("relateduuid", candidateUuid).stream()
                    .map(File::getUuid).toList();
        } catch (RuntimeException e) {
            remaining = fileUuids;
        }
        if (!remaining.isEmpty()) {
            residue.put("storedFilesNotDeleted", remaining);
        }
    }

    /**
     * The only mechanism in the codebase that can un-count a candidate: the
     * projection is rebuilt from the surviving event stream, and the deleted
     * {@code CANDIDATE_CREATED} event is simply no longer there. Runs after
     * the commit and opens its own transaction; a failure is reported, never
     * propagated.
     */
    private boolean rebuildReporting(String candidateUuid) {
        try {
            RecruitmentReportingProjector.RebuildSummary summary = reportingProjector.rebuild();
            if (summary.blocked()) {
                log.warnf("Reporting rebuild after deleting candidate %s finished BLOCKED: %s",
                        candidateUuid, summary);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.errorf(e, "Reporting rebuild after deleting candidate %s failed — the delete "
                    + "stands, the numbers correct on the next rebuild: %s",
                    candidateUuid, e.getMessage());
            return false;
        }
    }

    /**
     * A non-identifying handle for the SharePoint folder that survives the
     * delete. The raw {@code sharepoint_folder_path} may NOT be stored:
     * {@code RecruitmentCandidate.anonymize} nulls that exact column with the
     * comment "contains the candidate's name", and residue is persisted
     * verbatim into {@code recruitment_candidate_deletions.residue} — a row
     * with no FK, excluded from the prod → staging sync, never cleaned. Its
     * migration header states the rule outright: the ledger must hold no
     * identifier beyond the (now meaningless) uuid, because storing the name
     * there would defeat the deletion it records.
     *
     * <p>What is stored instead is a SHA-256 over the candidate uuid and the
     * path. It is a <em>verifier</em>, not a lookup key: an operator holding
     * a candidate folder path can prove it belongs to this ledger row by
     * recomputing the digest, and nobody reading the ledger learns a name.
     * Salting with the candidate uuid is what stops one rainbow table of
     * plausible names covering every row at once — the digest is only
     * checkable by someone who already has this row.</p>
     *
     * <p>The path is deliberately not returned to the client either. The
     * deleting admin just typed the candidate's full name into the confirm
     * box, so it would leak nothing to them — but the dialog renders residue
     * by <em>key</em>, never by value, so the raw path would buy the operator
     * nothing while adding a second copy to log at every hop.</p>
     */
    static Map<String, Object> redactedFolderHandle(String path, String candidateUuid) {
        Map<String, Object> handle = new LinkedHashMap<>();
        handle.put("pathSha256", saltedDigest(candidateUuid, path));
        handle.put("pathLength", path.length());
        handle.put("note", "The SharePoint folder still exists and this module has no delete "
                + "path for it. Its path is withheld on purpose — it embeds the candidate's "
                + "name, and this ledger may not carry one. To confirm a folder is the one "
                + "this row means, recompute sha256(candidate_uuid + 0x00 + path) and compare.");
        return handle;
    }

    /** SHA-256 of {@code salt + NUL + value}, lower-case hex. */
    private static String saltedDigest(String salt, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS platform spec; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void recordUnreachable(Map<String, Object> residue, String key,
                                          List<String> values, String why) {
        if (values.isEmpty()) {
            return;
        }
        residue.put(key, values);
        residue.put(key + "Reason", why);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
