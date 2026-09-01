package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.agreementservice.dto.BackfillItemDTO;
import dk.trustworks.intranet.agreementservice.dto.BackfillRunDTO;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillItem;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillRun;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.EmployeeAgreement;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementSource;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementStatus;
import dk.trustworks.intranet.agreementservice.model.enums.BackfillItemStatus;
import dk.trustworks.intranet.agreementservice.model.enums.BackfillRunStatus;
import dk.trustworks.intranet.agreementservice.services.AgreementBackfillJobRunner.JobType;
import dk.trustworks.intranet.agreementservice.services.AgreementExtractionService.Proposal;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentStorageAdapter;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run management + human review for the AI backfill (template-clauses
 * spec §10, D8): starting the single-flight corpus walk, listing runs
 * and items for the console, and the row-locked one-shot
 * confirm-with-edits / reject mutations. Nothing enters
 * {@code employee_agreements} without a confirm; every confirm writes
 * {@code source=BACKFILL} + {@code confirmed_by}.
 */
@JBossLog
@ApplicationScoped
public class AgreementBackfillService {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgreementBackfillJobRunner jobRunner;

    @Inject
    AgreementBackfillWalkerService walkerService;

    @Inject
    EmployeeDocumentStorageAdapter storageAdapter;

    // ── Requests ───────────────────────────────────────────────────────────

    /** One record HR is about to confirm — the (possibly edited) proposal. */
    public record ConfirmRecord(String agreementType, String title, String summary,
                                BigDecimal amount, String currency, LocalDate validFrom,
                                LocalDate validTo, LocalDate effectiveDate) {
    }

    /**
     * Confirm-with-edits: the registry rows to write for this document.
     * {@code edited=true} records the review as EDITED rather than
     * CONFIRMED (HR changed something before accepting).
     */
    public record ConfirmRequest(List<ConfirmRecord> records, boolean edited) {
    }

    // ── Runs ───────────────────────────────────────────────────────────────

    /**
     * Start a corpus walk. Single-flight: 409 while another walk runs.
     * The run row is created first so the walker can update its counters
     * live; a racing second start marks its own row FAILED and rethrows.
     */
    public BackfillRunDTO startRun(String actor, boolean dryRun) {
        if (jobRunner.isRunning()) {
            throw new WebApplicationException("A backfill run is already in progress", 409);
        }
        reconcileInterruptedRuns();

        String runUuid = createRunRow(actor, dryRun);
        try {
            jobRunner.start(dryRun ? JobType.WALK_DRY_RUN : JobType.WALK, runUuid,
                    () -> walkerService.walk(runUuid, dryRun));
        } catch (WebApplicationException e) {
            markRunFailed(runUuid, "Another backfill run was already in progress");
            throw e;
        }
        log.infof("AUDIT: agreement backfill run %s started by=%s dryRun=%s", runUuid, actor, dryRun);
        return findRun(runUuid);
    }

    @Transactional
    String createRunRow(String actor, boolean dryRun) {
        AgreementBackfillRun run = new AgreementBackfillRun();
        run.setStartedBy(actor);
        run.setDryRun(dryRun);
        run.persist();
        return run.getUuid();
    }

    @Transactional
    void markRunFailed(String runUuid, String message) {
        AgreementBackfillRun run = AgreementBackfillRun.findById(runUuid);
        if (run != null) {
            run.setStatus(BackfillRunStatus.FAILED.name());
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage(message);
        }
    }

    /**
     * A RUNNING row with no in-process job is a walk the last deploy or
     * restart killed — reconcile it to FAILED so the console never shows
     * a phantom run as live.
     */
    @Transactional
    public void reconcileInterruptedRuns() {
        if (jobRunner.isRunning()) {
            return;
        }
        for (AgreementBackfillRun run : AgreementBackfillRun.findRunning()) {
            run.setStatus(BackfillRunStatus.FAILED.name());
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage("Interrupted — service restarted mid-run");
            log.warnf("Backfill run %s reconciled to FAILED (interrupted by restart)", run.getUuid());
        }
    }

    public List<BackfillRunDTO> findRuns(int limit) {
        reconcileInterruptedRuns();
        NameCache names = new NameCache();
        return AgreementBackfillRun.findRecent(Math.max(1, Math.min(limit, 100))).stream()
                .map(run -> toDTO(run, names))
                .toList();
    }

    public BackfillRunDTO findRun(String uuid) {
        AgreementBackfillRun run = AgreementBackfillRun.findById(uuid);
        if (run == null) {
            throw new WebApplicationException("Backfill run not found: " + uuid, 404);
        }
        return toDTO(run, new NameCache());
    }

    public AgreementBackfillJobRunner.JobStatus jobStatus() {
        reconcileInterruptedRuns();
        return jobRunner.status();
    }

    // ── Items (the review queue) ───────────────────────────────────────────

    public List<BackfillItemDTO> findItems(String runUuid, String status, String userUuid) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (runUuid != null && !runUuid.isBlank()) {
            query.append(" AND runUuid = :runUuid");
            params.put("runUuid", runUuid);
        }
        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.put("status", parseItemStatus(status).name());
        }
        if (userUuid != null && !userUuid.isBlank()) {
            query.append(" AND userUuid = :userUuid");
            params.put("userUuid", userUuid);
        }
        query.append(" ORDER BY createdAt DESC");
        List<AgreementBackfillItem> rows = AgreementBackfillItem.list(query.toString(), params);
        NameCache names = new NameCache();
        return rows.stream().map(item -> toDTO(item, names)).toList();
    }

    public AgreementBackfillItem requireItem(String uuid) {
        AgreementBackfillItem item = AgreementBackfillItem.findById(uuid);
        if (item == null) {
            throw new WebApplicationException("Backfill item not found: " + uuid, 404);
        }
        return item;
    }

    /**
     * The console's PDF preview. S3-sourced items (V554 corpus) stream
     * from the employee-document store; legacy V549-era items still
     * fetch live from SharePoint via Graph.
     */
    public byte[] downloadDocument(String itemUuid) {
        AgreementBackfillItem item = requireItem(itemUuid);
        if (item.getEmployeeDocumentUuid() != null) {
            EmployeeDocument doc = EmployeeDocument.findById(item.getEmployeeDocumentUuid());
            if (doc == null) {
                throw new WebApplicationException("Source document no longer exists", 404);
            }
            try {
                return storageAdapter.get(doc.getS3Key()).bytes();
            } catch (Exception e) {
                log.warnf("Backfill S3 download failed for item %s: %s", itemUuid, e.getMessage());
                throw new WebApplicationException("Document could not be fetched from storage", 502);
            }
        }
        try {
            return walkerService.downloadItemBytes(item.getDriveId(), item.getSharepointItemId());
        } catch (Exception e) {
            log.warnf("Backfill document download failed for item %s: %s", itemUuid, e.getMessage());
            throw new WebApplicationException("Document could not be fetched from SharePoint", 502);
        }
    }

    // ── Review (row-locked one-shots, spec D8) ─────────────────────────────

    /**
     * Confirm-with-edits: writes one {@code employee_agreements} row per
     * record with {@code source=BACKFILL} and {@code confirmed_by}. The
     * pessimistic lock + PROPOSED check makes the review one-shot — two
     * HR users cannot double-confirm the same document.
     */
    @Transactional
    public BackfillItemDTO confirm(String itemUuid, ConfirmRequest request, String actor) {
        AgreementBackfillItem item = lockReviewableItem(itemUuid);
        if (request == null || request.records() == null || request.records().isEmpty()) {
            throw new WebApplicationException(
                    "Confirm requires at least one record — use reject to dismiss the document", 400);
        }

        List<String> createdUuids = new ArrayList<>();
        for (ConfirmRecord record : request.records()) {
            createdUuids.add(writeAgreement(item, record, actor));
        }

        item.setStatus((request.edited() ? BackfillItemStatus.EDITED : BackfillItemStatus.CONFIRMED).name());
        item.setReviewedBy(actor);
        item.setReviewedAt(LocalDateTime.now());
        item.setCreatedAgreementsJson(writeJson(createdUuids));

        log.infof("AUDIT: backfill item %s confirmed (%s) by=%s — %d agreement(s) written for user=%s",
                itemUuid, item.getStatus(), actor, createdUuids.size(), item.getUserUuid());
        return toDTO(item, new NameCache());
    }

    /** Reject: the document holds no agreement worth registering. */
    @Transactional
    public BackfillItemDTO reject(String itemUuid, String actor) {
        AgreementBackfillItem item = lockReviewableItem(itemUuid);
        item.setStatus(BackfillItemStatus.REJECTED.name());
        item.setReviewedBy(actor);
        item.setReviewedAt(LocalDateTime.now());
        log.infof("AUDIT: backfill item %s rejected by=%s (user=%s, file=%s)",
                itemUuid, actor, item.getUserUuid(), item.getFileName());
        return toDTO(item, new NameCache());
    }

    private AgreementBackfillItem lockReviewableItem(String itemUuid) {
        AgreementBackfillItem item =
                AgreementBackfillItem.findById(itemUuid, LockModeType.PESSIMISTIC_WRITE);
        if (item == null) {
            throw new WebApplicationException("Backfill item not found: " + itemUuid, 404);
        }
        if (!BackfillItemStatus.PROPOSED.name().equals(item.getStatus())) {
            throw new WebApplicationException(
                    "Item has already been reviewed (status " + item.getStatus() + ")", 409);
        }
        return item;
    }

    private String writeAgreement(AgreementBackfillItem item, ConfirmRecord record, String actor) {
        if (record.agreementType() == null || AgreementType.findById(record.agreementType()) == null) {
            throw new WebApplicationException("Unknown agreement type: " + record.agreementType(), 400);
        }
        if (record.title() == null || record.title().isBlank()) {
            throw new WebApplicationException("title is required on every record", 400);
        }
        if (record.validFrom() != null && record.validTo() != null
                && record.validTo().isBefore(record.validFrom())) {
            throw new WebApplicationException("valid_to cannot be before valid_from", 400);
        }

        EmployeeAgreement row = new EmployeeAgreement();
        // Subject comes from the item — the request cannot re-target the
        // record at another employee.
        row.setUserUuid(item.getUserUuid());
        row.setAgreementType(record.agreementType());
        row.setTitle(record.title().trim());
        row.setSummary(record.summary());
        row.setAmount(record.amount());
        row.setCurrency(record.currency() == null || record.currency().isBlank()
                ? null : record.currency().trim().toUpperCase(java.util.Locale.ROOT));
        row.setValidFrom(record.validFrom());
        row.setValidTo(record.validTo());
        row.setEffectiveDate(record.effectiveDate());
        row.setDocumentUrl(safeDocumentUrl(item.getWebUrl()));
        row.setSource(AgreementSource.BACKFILL.name());
        // Historical terms whose window already closed enter as EXPIRED —
        // the nightly sweep would flip them anyway, but must never alert.
        row.setStatus(record.validTo() != null && record.validTo().isBefore(LocalDate.now())
                ? AgreementStatus.EXPIRED.name() : AgreementStatus.ACTIVE.name());
        row.setCreatedBy(actor);
        row.setConfirmedBy(actor);
        row.persist();
        return row.getUuid();
    }

    /** A malformed stored URL must not block a confirm — drop it instead. */
    private static String safeDocumentUrl(String webUrl) {
        try {
            return AgreementService.validateDocumentUrl(webUrl);
        } catch (WebApplicationException e) {
            return null;
        }
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private static BackfillItemStatus parseItemStatus(String raw) {
        try {
            return BackfillItemStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown item status: " + raw, 400);
        }
    }

    /** Per-call memo so a list render resolves each user once. */
    private static class NameCache {
        final Map<String, String> names = new HashMap<>();

        String userName(String uuid) {
            if (uuid == null) {
                return null;
            }
            return names.computeIfAbsent(uuid, key ->
                    User.<User>findByIdOptional(key).map(User::getFullname).orElse(null));
        }
    }

    private BackfillRunDTO toDTO(AgreementBackfillRun run, NameCache names) {
        return BackfillRunDTO.builder()
                .uuid(run.getUuid())
                .status(run.getStatus())
                .dryRun(run.isDryRun())
                .startedBy(run.getStartedBy())
                .startedByName(names.userName(run.getStartedBy()))
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .corpusSummary(run.getCorpusSummary())
                .employeesTotal(run.getEmployeesTotal())
                .foldersTotal(run.getFoldersTotal())
                .foldersWalked(run.getFoldersWalked())
                .filesSeen(run.getFilesSeen())
                .filesSkipped(run.getFilesSkipped())
                .documentsNew(run.getDocumentsNew())
                .proposalsCreated(run.getProposalsCreated())
                .errorsCount(run.getErrorsCount())
                .errorMessage(run.getErrorMessage())
                .build();
    }

    private BackfillItemDTO toDTO(AgreementBackfillItem item, NameCache names) {
        return BackfillItemDTO.builder()
                .uuid(item.getUuid())
                .runUuid(item.getRunUuid())
                .userUuid(item.getUserUuid())
                .userName(names.userName(item.getUserUuid()))
                .fileName(item.getFileName())
                .fileSize(item.getFileSize())
                .webUrl(item.getWebUrl())
                .status(item.getStatus())
                .proposals(parseProposals(item.getProposalJson()))
                .extractionNote(item.getExtractionNote())
                .reviewedBy(item.getReviewedBy())
                .reviewedByName(names.userName(item.getReviewedBy()))
                .reviewedAt(item.getReviewedAt())
                .createdAgreementUuids(parseStringList(item.getCreatedAgreementsJson()))
                .createdAt(item.getCreatedAt())
                .build();
    }

    private List<Proposal> parseProposals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Proposal>>() {
            });
        } catch (Exception e) {
            log.warnf("Unparseable proposal_json on backfill item: %s", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }
}
