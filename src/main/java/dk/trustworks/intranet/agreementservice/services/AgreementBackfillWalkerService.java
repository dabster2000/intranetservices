package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillItem;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillRun;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.enums.BackfillRunStatus;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentStorageAdapter;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Phase-4 corpus walk over the S3 employee-documents store
 * (template-clauses spec §10, corpus reworked after the legacy
 * document store was migrated into S3): every ACTIVE employee's
 * {@code employee_documents} rows in the configured categories are
 * fetched from S3 and put through one extraction call each. New
 * documents land ONLY in this store ({@code SIGNING}/{@code MANUAL_HR}/
 * {@code ONBOARDING} sources), so it is both complete and — unlike the
 * retired remote-folder walk — pre-categorized, pre-hashed and
 * throttle-free.
 *
 * <p>Runs on the single-flight job-runner thread: narrow
 * {@code requiringNew} transactions per write, live counter updates so
 * the console shows progress. Run counters keep their V549 columns with
 * per-employee semantics: {@code folders_total/walked} = employees with
 * corpus documents / employees completed.</p>
 *
 * <p>Idempotency is unchanged: {@code (user_uuid, doc_sha256)} UNIQUE —
 * the migration copied bytes verbatim, so documents already itemized by
 * a V549-era legacy walk keep their review state, are skipped, and
 * are adopted onto their {@code employee_documents} row so the preview
 * streams from S3.</p>
 */
@JBossLog
@ApplicationScoped
public class AgreementBackfillWalkerService {

    /** Contracts are a few MB; anything larger is not a signable document. */
    static final long MAX_FILE_BYTES = 40L * 1024 * 1024;

    @Inject
    UserService userService;

    @Inject
    EmployeeDocumentStorageAdapter storageAdapter;

    @Inject
    AgreementExtractionService extractionService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Comma-separated {@code EmployeeDocumentCategory} names the walk
     * extracts from. Deliberately excludes SICKNESS/IDENTITY (GDPR data
     * minimization — they never reach the AI call), SALARY/VACATION/
     * TERMINATION (no negotiated terms to register) and OTHER (the
     * uncategorized long tail; widen deliberately, not by default).
     */
    @ConfigProperty(name = "dk.trustworks.agreements.ai.backfill-categories",
            defaultValue = "CONTRACT,ADDENDUM,DECLARATION")
    String backfillCategories;

    /** Console-facing summary; also folded into the run row. */
    public record WalkSummary(String runUuid, boolean dryRun, int employees, int employeesWithDocs,
                              int employeesWalked, int docsSeen, int docsSkipped, int documentsNew,
                              int proposalsCreated, int errors, List<String> notes) {
    }

    /**
     * Execute the walk for an already-persisted RUNNING run row. Never
     * throws: a terminal failure marks the run FAILED and is reported in
     * the summary.
     */
    public WalkSummary walk(String runUuid, boolean dryRun) {
        Counters counters = new Counters();
        try {
            // Corpus subjects: active employees (incl. leave states —
            // people on leave still hold agreements) of every internal type.
            List<User> employees = QuarkusTransaction.requiringNew().call(() ->
                    userService.findEmployedUsersByDate(LocalDate.now(), true,
                            ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT));
            Set<String> activeUuids = employees.stream().map(User::getUuid).collect(Collectors.toSet());
            counters.employees = employees.size();

            Set<String> categories = parseCategories(backfillCategories);

            // Corpus documents: the S3 store's rows for those employees in
            // the configured categories. Enumeration is a single DB read —
            // no remote paging, no politeness delays, no folder aggregates.
            List<EmployeeDocumentCategory> categoryValues = categories.stream()
                    .map(EmployeeDocumentCategory::valueOf)
                    .toList();
            List<EmployeeDocument> corpus = QuarkusTransaction.requiringNew().call(() ->
                    EmployeeDocument.<EmployeeDocument>list(
                                    "archived = false AND category IN ?1 ORDER BY userUuid, createdAt",
                                    categoryValues)
                            .stream()
                            .filter(doc -> activeUuids.contains(doc.getUserUuid()))
                            .toList());

            Map<String, List<EmployeeDocument>> byEmployee = corpus.stream()
                    .collect(Collectors.groupingBy(EmployeeDocument::getUserUuid,
                            LinkedHashMap::new, Collectors.toList()));
            counters.employeesWithDocs = byEmployee.size();

            long uncovered = activeUuids.stream().filter(uuid -> !byEmployee.containsKey(uuid)).count();
            if (uncovered > 0) {
                // No silent caps: an employee with no corpus documents at
                // all is invisible to the walk and HR must know.
                counters.note(uncovered + " active employees have no "
                        + String.join("/", categories) + " documents in the employee-document store");
            }
            updateRunCounters(runUuid, counters);

            List<String> typeKeys = QuarkusTransaction.requiringNew().call(() ->
                    AgreementType.<AgreementType>list("active", true).stream()
                            .map(AgreementType::getTypeKey).toList());

            for (Map.Entry<String, List<EmployeeDocument>> employee : byEmployee.entrySet()) {
                for (EmployeeDocument doc : employee.getValue()) {
                    counters.docsSeen++;
                    if (!isCorpusDocument(doc)) {
                        counters.docsSkipped++;
                        continue;
                    }
                    try {
                        processDocument(runUuid, dryRun, doc, typeKeys, counters);
                    } catch (Exception e) {
                        counters.error("Document failed: " + doc.getUuid()
                                + " (" + e.getClass().getSimpleName() + ")");
                    }
                }
                counters.employeesWalked++;
                updateRunCounters(runUuid, counters);
            }

            finishRun(runUuid, counters, null);
            return counters.toSummary(runUuid, dryRun);
        } catch (Exception e) {
            log.errorf(e, "Agreement backfill run %s failed", runUuid);
            counters.error("Run failed: " + e.getMessage());
            finishRun(runUuid, counters, e.getMessage());
            return counters.toSummary(runUuid, dryRun);
        }
    }

    // ── Document filter (pure — the fast tier reaches every branch) ─────────

    /**
     * PDFs only (extraction is PDFBox + page-1 vision, spec §10.2),
     * bounded size. Category and archived are already filtered by the
     * corpus query; re-checked here so the predicate is self-contained.
     */
    static boolean isCorpusDocument(EmployeeDocument doc) {
        if (doc.isArchived()) {
            return false;
        }
        if (doc.getFileSizeBytes() <= 0 || doc.getFileSizeBytes() > MAX_FILE_BYTES) {
            return false;
        }
        String contentType = doc.getContentType() == null ? "" : doc.getContentType().toLowerCase(Locale.ROOT);
        String name = doc.getOriginalFilename() == null ? "" : doc.getOriginalFilename().toLowerCase(Locale.ROOT);
        return contentType.equals("application/pdf") || name.endsWith(".pdf");
    }

    static Set<String> parseCategories(String raw) {
        return Arrays.stream((raw == null ? "" : raw).split(","))
                .map(String::trim)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    // ── Per-document processing ─────────────────────────────────────────────

    private void processDocument(String runUuid, boolean dryRun, EmployeeDocument doc,
                                 List<String> typeKeys, Counters counters) {
        String userUuid = doc.getUserUuid();
        String knownSha = doc.getSha256();

        // Fast path: the store's own sha (present on 99%+ of rows) makes
        // the idempotency check free — no S3 fetch for known documents.
        if (knownSha != null && !knownSha.isBlank()) {
            boolean known = QuarkusTransaction.requiringNew().call(() -> {
                var existing = AgreementBackfillItem.findByUserAndSha(userUuid, knownSha);
                existing.ifPresent(item -> {
                    // Adopt legacy (V549-era) items so their preview
                    // streams from the S3 store.
                    if (item.getEmployeeDocumentUuid() == null) {
                        item.setEmployeeDocumentUuid(doc.getUuid());
                    }
                });
                return existing.isPresent();
            });
            if (known) {
                return;
            }
        }

        if (dryRun) {
            counters.documentsNew++;
            return;
        }

        byte[] bytes = storageAdapter.get(doc.getS3Key()).bytes();
        String sha256 = knownSha != null && !knownSha.isBlank() ? knownSha : sha256Hex(bytes);

        // Sha computed post-fetch (rows copied server-side can lack one):
        // re-check before extracting.
        boolean known = QuarkusTransaction.requiringNew().call(() ->
                AgreementBackfillItem.findByUserAndSha(userUuid, sha256).isPresent());
        if (known) {
            return;
        }

        AgreementExtractionService.ExtractionResult result = extractionService.extract(bytes, typeKeys);

        QuarkusTransaction.requiringNew().run(() -> {
            AgreementBackfillItem item = new AgreementBackfillItem();
            item.setRunUuid(runUuid);
            item.setUserUuid(userUuid);
            item.setEmployeeDocumentUuid(doc.getUuid());
            item.setFileName(bounded(doc.getOriginalFilename(), 500));
            item.setFileSize(doc.getFileSizeBytes());
            item.setDocSha256(sha256);
            item.setStatus(result.status().name());
            item.setProposalJson(writeProposals(result.proposals()));
            item.setExtractionNote(result.note());
            item.persist();
        });
        counters.documentsNew++;
        counters.proposalsCreated += result.proposals().size();
    }

    private String writeProposals(List<AgreementExtractionService.Proposal> proposals) {
        if (proposals == null || proposals.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(proposals);
        } catch (Exception e) {
            log.warnf("Could not serialize %d proposals: %s", proposals.size(), e.getMessage());
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static String bounded(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Run-row bookkeeping (narrow TX each) ────────────────────────────────

    private void updateRunCounters(String runUuid, Counters counters) {
        QuarkusTransaction.requiringNew().run(() -> {
            AgreementBackfillRun run = AgreementBackfillRun.findById(runUuid);
            if (run != null) {
                counters.applyTo(run);
            }
        });
    }

    private void finishRun(String runUuid, Counters counters, String errorMessage) {
        QuarkusTransaction.requiringNew().run(() -> {
            AgreementBackfillRun run = AgreementBackfillRun.findById(runUuid);
            if (run == null) {
                return;
            }
            counters.applyTo(run);
            run.setStatus(errorMessage == null
                    ? BackfillRunStatus.COMPLETED.name() : BackfillRunStatus.FAILED.name());
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage(errorMessage);
            run.setCorpusSummary(counters.corpusSummary());
        });
    }

    private static final class Counters {
        int employees;
        int employeesWithDocs;
        int employeesWalked;
        int docsSeen;
        int docsSkipped;
        int documentsNew;
        int proposalsCreated;
        final List<String> notes = new ArrayList<>();
        int errors;

        void note(String text) {
            if (notes.size() < 50) {
                notes.add(text);
            }
        }

        void error(String text) {
            errors++;
            note(text);
        }

        void applyTo(AgreementBackfillRun run) {
            run.setEmployeesTotal(employees);
            // V549 columns, per-employee semantics since the S3 corpus
            // has no folders: total/walked = employees with docs / done.
            run.setFoldersTotal(employeesWithDocs);
            run.setFoldersWalked(employeesWalked);
            run.setFilesSeen(docsSeen);
            run.setFilesSkipped(docsSkipped);
            run.setDocumentsNew(documentsNew);
            run.setProposalsCreated(proposalsCreated);
            run.setErrorsCount(errors);
        }

        String corpusSummary() {
            String base = employees + " aktive medarbejdere, " + employeesWalked + "/" + employeesWithDocs
                    + " med dokumenter i S3-arkivet, " + docsSeen + " dokumenter, " + documentsNew + " nye";
            return notes.isEmpty() ? base
                    : shortened(base + " — " + String.join("; ", notes), 500);
        }

        static String shortened(String text, int max) {
            return text.length() <= max ? text : text.substring(0, max - 1) + "…";
        }

        WalkSummary toSummary(String runUuid, boolean dryRun) {
            return new WalkSummary(runUuid, dryRun, employees, employeesWithDocs, employeesWalked,
                    docsSeen, docsSkipped, documentsNew, proposalsCreated, errors, List.copyOf(notes));
        }
    }
}
