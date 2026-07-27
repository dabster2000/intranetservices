package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
     * Store an uploaded appendix file (typically a PDF picked by the manager
     * via the dossier UI) in S3 alongside other recruitment files. Returns
     * the new {@code fileUuid} that callers persist on the appendix row.
     */
    public String storeAppendix(byte[] bytes, String filename, UUID candidateUuid) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");

        String fileUuid = UUID.randomUUID().toString();
        File file = new File(
                fileUuid,
                candidateUuid.toString(),
                "DOCUMENT",
                filename,
                filename,
                LocalDate.now(),
                bytes);
        s3FileService.save(file);
        log.infof("Stored recruitment appendix candidate=%s fileUuid=%s size=%d filename=%s",
                candidateUuid, fileUuid, bytes.length, filename);
        return fileUuid;
    }

    /**
     * Store an identity document uploaded via the public onboarding upload
     * page (driver's license, health insurance card, or criminal record
     * certificate). Mirrors {@link #storeAppendix} — same S3 bucket, same
     * {@code File.type = "DOCUMENT"}, same {@code relateduuid =
     * candidateUuid} linkage so the retention reaper / future cleanup can
     * trace the file back to its candidate.
     *
     * @return the new {@code fileUuid} the caller persists on the
     *         {@code onboarding_upload_submissions} row.
     */
    public String storeIdentityDocument(byte[] bytes, String filename, UUID candidateUuid) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");

        String fileUuid = UUID.randomUUID().toString();
        File file = new File(
                fileUuid,
                candidateUuid.toString(),
                "DOCUMENT",
                filename,
                filename,
                LocalDate.now(),
                bytes);
        s3FileService.save(file);
        log.infof("Stored onboarding identity document candidate=%s fileUuid=%s size=%d",
                candidateUuid, fileUuid, bytes.length);
        return fileUuid;
    }

    /**
     * Store a document submitted through the P5 public application forms
     * (CV or cover letter). Mirrors {@link #storeIdentityDocument} — same
     * S3 bucket, same {@code File.type = "DOCUMENT"}, same
     * {@code relateduuid = candidateUuid} linkage so the P19 anonymizer /
     * retention reaper can trace the file back to its candidate.
     *
     * @return the new {@code fileUuid} the caller carries on the
     *         {@code DOCUMENT_UPLOADED} event payload.
     */
    public String storeApplicationDocument(byte[] bytes, String filename, UUID candidateUuid) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");

        String fileUuid = UUID.randomUUID().toString();
        File file = new File(
                fileUuid,
                candidateUuid.toString(),
                "DOCUMENT",
                filename,
                filename,
                LocalDate.now(),
                bytes);
        s3FileService.save(file);
        log.infof("Stored public application document candidate=%s fileUuid=%s size=%d",
                candidateUuid, fileUuid, bytes.length);
        return fileUuid;
    }

    /**
     * Store a SIGNED PDF downloaded from NextSign into the candidate's
     * staging space (employee-documents spec §6.5.2 — the signed document
     * becomes durable the minute the case completes, independent of
     * conversion). Same {@code trustworksfiles} bucket, same
     * {@code relateduuid = candidateUuid} linkage, so the GDPR
     * anonymizer's {@link #deleteAllCandidateFiles} automatically covers
     * it for never-hired candidates, and the conversion promotion moves
     * it to the employee store like every other staged file.
     *
     * @return the new {@code fileUuid} the caller records in the dossier
     *         revision's {@code signed_pdfs_snapshot}.
     */
    public String storeSignedDocument(byte[] bytes, String filename, UUID candidateUuid) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");

        String fileUuid = UUID.randomUUID().toString();
        File file = new File(
                fileUuid,
                candidateUuid.toString(),
                "DOCUMENT",
                filename,
                filename,
                LocalDate.now(),
                bytes);
        s3FileService.save(file);
        log.infof("Stored signed recruitment document candidate=%s fileUuid=%s size=%d",
                candidateUuid, fileUuid, bytes.length);
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
     * Delete EVERY stored file linked to a candidate — CVs, cover letters,
     * generated dossier PDFs, appendices, identity documents (all writers
     * above stamp {@code File.relateduuid = candidateUuid} for exactly this
     * moment). The GDPR anonymizer's S3 leg (ATS P19, spec §5.5 — one of
     * the four enumerated anonymization targets). Deletes both the S3
     * object and the {@code files} row per file; S3 deletes are idempotent,
     * so a retried anonymization run is harmless.
     *
     * @return how many files were deleted
     */
    public int deleteAllCandidateFiles(UUID candidateUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        List<File> files = File.list("relateduuid", candidateUuid.toString());
        for (File file : files) {
            s3FileService.delete(file.getUuid());
        }
        if (!files.isEmpty()) {
            log.infof("Deleted %d stored files for candidate=%s (GDPR anonymization)",
                    files.size(), candidateUuid);
        }
        return files.size();
    }

    /**
     * Metadata of every stored file linked to a candidate (uuid, name,
     * upload date — never the bytes). The DSAR export's document list
     * (ATS P19).
     */
    public List<File> listCandidateFiles(UUID candidateUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        return File.list("relateduuid", candidateUuid.toString());
    }

    /**
     * Persist each template-generated PDF in S3 and return the
     * {@code (filename, fileUuid)} refs for the revision snapshot. Appendix
     * PDFs (already in S3) are not duplicated — only PDFs with
     * {@link DossierPdfGenerationService.GeneratedPdf#fromTemplate()} set are
     * stored.
     *
     * <p><b>Sequential by design.</b> The previous implementation dispatched
     * each store on a {@code ManagedExecutor} for ~0.5-1.5s of parallel
     * speedup, but the workers inherited the caller's JTA transaction context
     * via MicroProfile Context Propagation and shared the parent's Hibernate
     * session. Concurrent persists on a single non-thread-safe session
     * corrupted the {@code EntityEntry} map, producing intermittent
     * {@code NullPointerException} during the next autoflush
     * ({@code prepareEntityFlushes:127}) — see incident report 2026-05-06.
     * For the typical 1-3 template PDFs per dossier the sequential cost is
     * ~150-450 ms, which is well below the user-visible threshold.
     *
     * <p><b>Order:</b> the returned list is in the same order as the input
     * {@code pdfs} (template-only filter applied).
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

        List<RevisionResponse.PdfArtifactRef> refs = new ArrayList<>(templatePdfs.size());
        for (DossierPdfGenerationService.GeneratedPdf pdf : templatePdfs) {
            String fileUuid = storeGeneratedPdf(
                    pdf.pdfBytes(), pdf.filename(), candidateUuid, kind);
            refs.add(new RevisionResponse.PdfArtifactRef(pdf.filename(), fileUuid));
        }
        return refs;
    }
}
