package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Persists generated recruitment PDFs to S3 via the shared {@link S3FileService}.
 * <p>
 * Recruitment PDFs are stored alongside other application files in the same
 * S3 bucket. The {@code File.relateduuid} field is set to the candidate UUID
 * so future cleanup or auditing can trace files back to their candidate.
 * The {@code File.type} column is forced to {@code "DOCUMENT"} by
 * {@link S3FileService#save(File)} regardless of what we set here — the
 * caller-side categorisation lives on the {@code candidate_dossier_revisions}
 * row via {@code generated_pdfs_snapshot}.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentS3StorageService {

    @Inject
    S3FileService s3FileService;

    /**
     * MicroProfile {@link ManagedExecutor} used to fan out per-PDF S3 stores in
     * {@link #storeTemplatePdfs(List, UUID, RevisionKind)}. Provided by the
     * Quarkus SmallRye Context Propagation extension, this executor propagates
     * security and request context from the calling thread (necessary so the
     * downstream {@code S3FileService.save} sees the same JWT principal /
     * request headers).
     */
    @Inject
    ManagedExecutor managedExecutor;

    /**
     * Store a generated PDF in S3 and return the new file UUID.
     */
    public String storeGeneratedPdf(byte[] bytes, String filename, UUID candidateUuid, RevisionKind kind) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        Objects.requireNonNull(kind, "kind must not be null");

        String fileUuid = UUID.randomUUID().toString();
        File file = new File(
                fileUuid,
                candidateUuid.toString(),  // relateduuid — audit linkage
                "DOCUMENT",                 // type (will be re-forced by save() anyway)
                filename,                   // name
                filename,                   // filename
                LocalDate.now(),            // uploaddate
                bytes);                     // file bytes

        s3FileService.save(file);
        log.infof("Stored recruitment PDF candidate=%s kind=%s fileUuid=%s size=%d",
                candidateUuid, kind, fileUuid, bytes.length);
        return fileUuid;
    }

    /**
     * Fetch the bytes of a previously stored generated PDF.
     *
     * @throws IllegalStateException if the file is not found in S3
     */
    public byte[] fetchGeneratedPdf(String fileUuid) {
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        File file = s3FileService.findOne(fileUuid);
        if (file == null || file.getFile() == null || file.getFile().length == 0) {
            throw new IllegalStateException("Recruitment PDF not found in S3: " + fileUuid);
        }
        return file.getFile();
    }

    /**
     * Delete a stored PDF from S3. Used by the retention reaper. Idempotent
     * at the S3 layer (delete is idempotent in S3); callers decide what to
     * do with any underlying exception.
     */
    public void deleteGeneratedPdf(String fileUuid) {
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        s3FileService.delete(fileUuid);
        log.infof("Deleted recruitment PDF fileUuid=%s", fileUuid);
    }

    /**
     * Persist each template-generated PDF in S3 and return the
     * {@code (filename, fileUuid)} refs for the revision snapshot. Appendix
     * PDFs (already in S3) are not duplicated — only PDFs with
     * {@link DossierPdfGenerationService.GeneratedPdf#fromTemplate()} set are
     * stored.
     *
     * <p>Implementation: each per-PDF store is dispatched to {@link #managedExecutor}
     * and run in parallel. With 4-8 PDFs at ~50-150 ms each (DB INSERT +
     * S3 PUT), this trims ~0.5-1.5s of avoidable wall-clock latency from the
     * caller's hot path (e.g. a candidate Send action).
     *
     * <p><b>Transaction trade-off:</b> {@code S3FileService.save} is
     * {@code @Transactional(REQUIRED)}. Because each parallel store runs on a
     * different thread, each opens its own fresh JTA transaction and commits
     * independently of the outer (caller's) transaction. If a later step in
     * the caller (e.g. the Send action) fails after this method returns, the
     * parallel-saved {@code File} rows do <b>not</b> roll back. This is
     * acceptable because:
     * <ul>
     *   <li>The retention reaper batchlet already cleans up orphan S3 objects
     *       (and their {@code File} rows) within the 30-day retention window.
     *   <li>Pre-parallelisation, the S3 PUT (the network side-effect) was
     *       <i>also</i> not rolled back on caller failure — only the {@code File}
     *       row was. So this change widens the orphan window from "just the S3
     *       object" to "S3 object + File row", but the retention reaper handles
     *       both uniformly.
     *   <li>The {@code File} row orphans are bounded by the same 30-day window
     *       as the S3 objects.
     * </ul>
     *
     * <p><b>Order:</b> the returned list is in the same order as the input
     * {@code pdfs} (template-only filter applied), regardless of completion
     * order on the executor.
     */
    public List<RevisionResponse.PdfArtifactRef> storeTemplatePdfs(
            List<DossierPdfGenerationService.GeneratedPdf> pdfs,
            UUID candidateUuid,
            RevisionKind kind) {
        Objects.requireNonNull(pdfs, "pdfs must not be null");
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        Objects.requireNonNull(kind, "kind must not be null");

        // Filter to template-generated PDFs only (appendices already in S3).
        List<DossierPdfGenerationService.GeneratedPdf> templatePdfs = pdfs.stream()
                .filter(p -> p.fromTemplate() && p.pdfBytes() != null)
                .toList();

        if (templatePdfs.isEmpty()) {
            return List.of();
        }

        // Dispatch each S3 store to the managed executor. Each runs in its own
        // transaction (S3FileService.save is @Transactional REQUIRED, so on a
        // separate thread it opens a fresh tx and commits independently of the
        // caller's tx). See javadoc above for the trade-off.
        List<CompletableFuture<RevisionResponse.PdfArtifactRef>> futures =
                new ArrayList<>(templatePdfs.size());
        for (DossierPdfGenerationService.GeneratedPdf pdf : templatePdfs) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                String fileUuid = storeGeneratedPdf(
                        pdf.pdfBytes(), pdf.filename(), candidateUuid, kind);
                return new RevisionResponse.PdfArtifactRef(pdf.filename(), fileUuid);
            }, managedExecutor));
        }

        // Wait for completion and assemble results in original input order.
        // .get() after .allOf().join() is non-blocking (the future is complete);
        // a get-time exception means a different future failed first inside join().
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<RevisionResponse.PdfArtifactRef> refs = new ArrayList<>(futures.size());
            for (CompletableFuture<RevisionResponse.PdfArtifactRef> f : futures) {
                refs.add(f.get());
            }
            return refs;
        } catch (CompletionException e) {
            throw new IllegalStateException(
                    "Failed to store generated PDFs in S3", e.getCause());
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to store generated PDFs in S3", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while storing generated PDFs in S3", e);
        }
    }
}
