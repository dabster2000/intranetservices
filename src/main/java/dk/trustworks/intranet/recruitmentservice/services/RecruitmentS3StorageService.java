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
     */
    public List<RevisionResponse.PdfArtifactRef> storeTemplatePdfs(
            List<DossierPdfGenerationService.GeneratedPdf> pdfs,
            UUID candidateUuid,
            RevisionKind kind) {
        List<RevisionResponse.PdfArtifactRef> refs = new ArrayList<>();
        for (DossierPdfGenerationService.GeneratedPdf pdf : pdfs) {
            if (pdf.fromTemplate() && pdf.pdfBytes() != null) {
                String fileUuid = storeGeneratedPdf(pdf.pdfBytes(), pdf.filename(), candidateUuid, kind);
                refs.add(new RevisionResponse.PdfArtifactRef(pdf.filename(), fileUuid));
            }
        }
        return refs;
    }
}
