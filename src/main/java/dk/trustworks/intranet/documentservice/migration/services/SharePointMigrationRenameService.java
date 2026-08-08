package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationCategorizerService.AiVerdict;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsFeatureFlag;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;

/**
 * M6 — the standalone display-name pass (V476). Gives every migrated
 * document a standardized name
 * {@code {YYYY-MM-DD}_{CATEGORY}_{subject}.{ext}} without ever touching
 * {@code original_filename}.
 *
 * <h3>Why this is its own job and not part of categorize()</h3>
 * <p>{@link SharePointMigrationCategorizerService#applyVerdict} is
 * guarded by an idempotency check that skips any document already
 * categorized — so documents from an earlier categorize run could never
 * pick up a name there. This pass selects on
 * {@code source = MIGRATION AND displayName IS NULL} instead, which
 * also backfills the corpus already migrated on staging.</p>
 *
 * <h3>Why renaming is safe</h3>
 * <p>{@code original_filename} is immutable. The signing linkage
 * (decision A4) matches the legacy SharePoint filename byte-for-byte
 * against that column, HR compares it to SharePoint during runbook
 * verification 2a-9, and it is the value recorded in
 * {@code employee_document_audit.detail}. Nothing in this class writes
 * to it — the name lives in its own column, so the ordering between
 * categorize, rename and link stops mattering entirely.</p>
 *
 * <h3>AI is an optimization, never a dependency</h3>
 * <p>With {@code employee_documents.migration.ai.enabled} OFF the whole
 * pass runs on {@link MigrationCategorizerRules#buildDisplayName} and
 * makes zero OpenAI calls (spec decision A5). With it ON, a per-batch
 * OpenAI failure degrades that batch to the table builder and is
 * counted in the summary — it never aborts the run.</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationRenameService {

    static final int BATCH_SIZE = SharePointMigrationCategorizerService.BATCH_SIZE;

    @Inject
    EmployeeDocumentsFeatureFlag featureFlag;

    @Inject
    SharePointMigrationCategorizerService categorizerService;

    /** One document's before/after, for the dry-run report. */
    public record RenameProposal(String documentUuid, String originalFilename,
                                 String proposedName, String namedBy) { }

    public record RenameSummary(
            boolean dryRun,
            int candidates,
            int aiNamed,
            int tableNamed,
            int skipped,
            boolean aiUsed,
            List<RenameProposal> proposals,
            List<String> errors) { }

    /**
     * @param dryRun true ⇒ produce the full before/after list without a
     *               single DB write (and without an S3 write — this pass
     *               never touches bytes at all; S3 keys never change,
     *               spec §6.3)
     */
    public RenameSummary rename(boolean dryRun) {
        record DocFacts(String uuid, String path, String filename,
                        dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory category) { }

        List<String> docUuids = QuarkusTransaction.requiringNew().call(() ->
                EmployeeDocument.<EmployeeDocument>list(
                                "source = ?1 AND (displayName IS NULL OR displayName = '')",
                                EmployeeDocumentSource.MIGRATION).stream()
                        .map(EmployeeDocument::getUuid)
                        .toList());

        boolean aiEnabled = QuarkusTransaction.requiringNew().call(featureFlag::isMigrationAiEnabled);
        List<String> errors = new ArrayList<>();
        List<RenameProposal> proposals = new ArrayList<>();
        int aiNamed = 0;
        int tableNamed = 0;
        int skipped = 0;

        for (int start = 0; start < docUuids.size(); start += BATCH_SIZE) {
            List<String> batch = docUuids.subList(start, Math.min(start + BATCH_SIZE, docUuids.size()));
            List<DocFacts> facts = QuarkusTransaction.requiringNew().call(() ->
                    batch.stream()
                            .map(uuid -> (EmployeeDocument) EmployeeDocument.findById(uuid))
                            .filter(java.util.Objects::nonNull)
                            .map(d -> new DocFacts(d.getUuid(),
                                    d.getLabel() == null ? "" : d.getLabel(),
                                    d.getOriginalFilename(), d.getCategory()))
                            .toList());

            List<AiVerdict> verdicts = List.of();
            if (aiEnabled) {
                try {
                    verdicts = categorizerService.namePassVerdicts(facts.stream()
                            .map(f -> new String[]{f.path(), f.filename()})
                            .toList());
                } catch (Exception e) {
                    // Per spec A5 an OpenAI failure degrades this batch to
                    // the table builder — it never aborts the run.
                    log.errorf(e, "AI name pass failed for a rename batch — falling back to the rule table");
                    errors.add("AI name pass: " + e.getMessage());
                }
            }

            for (int i = 0; i < facts.size(); i++) {
                DocFacts doc = facts.get(i);
                AiVerdict verdict = i < verdicts.size() ? verdicts.get(i) : null;
                String aiName = verdict == null ? null : verdict.suggestedName();

                // The document already has a category (categorize ran, or
                // it stayed OTHER); the name is built from THAT category,
                // never from a fresh AI category decision — this pass does
                // not re-categorize anything.
                String name = aiName != null ? aiName
                        : MigrationCategorizerRules.buildDisplayName(
                                doc.category(), doc.filename(), doc.path(), null);

                if (name == null || name.isBlank()) {
                    skipped++;
                    continue;
                }
                if (aiName != null) aiNamed++; else tableNamed++;
                proposals.add(new RenameProposal(doc.uuid(), doc.filename(), name,
                        aiName != null ? "AI" : "TABLE"));

                if (!dryRun) {
                    try {
                        applyName(doc.uuid(), name);
                    } catch (Exception e) {
                        log.errorf(e, "Rename failed for document %s", doc.uuid());
                        errors.add("rename " + doc.uuid() + ": " + e.getMessage());
                    }
                }
            }
        }

        log.infof("Rename done (dryRun=%s): %d candidates, %d AI-named, %d table-named, %d skipped",
                dryRun, docUuids.size(), aiNamed, tableNamed, skipped);
        return new RenameSummary(dryRun, docUuids.size(), aiNamed, tableNamed, skipped,
                aiEnabled, proposals, errors);
    }

    /**
     * Narrow transaction per document. Re-reads under the transaction and
     * refuses to overwrite a name that appeared since the listing — HR's
     * manual edits are sticky, so a re-run changes 0 rows.
     */
    void applyName(String docUuid, String displayName) {
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDocument doc = EmployeeDocument.findById(docUuid);
            if (doc == null) return;
            if (doc.getDisplayName() != null && !doc.getDisplayName().isBlank()) return;
            doc.setDisplayName(displayName);
            doc.persist();
        });
    }
}
