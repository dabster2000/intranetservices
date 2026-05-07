package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.TemplateDocumentEntity;
import dk.trustworks.intranet.recruitmentservice.dto.AppendixDto;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse.PdfArtifactRef;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.utils.services.PlaceholderFormattingService;
import dk.trustworks.intranet.utils.services.WordDocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates the PDF artifacts that ship with a {@link CandidateDossierRevision}.
 * <p>
 * For each Word document attached to the revision's template, the service
 * loads the DOCX from S3 (via {@link WordDocumentService#generatePdfFromWordTemplate})
 * and produces a PDF using the snapshot's frozen placeholder values. The
 * resulting PDF bytes are returned in-memory together with their declared
 * filename — the caller (the Send action) decides whether to upload them to
 * S3, attach them to a {@code TrustworksMail}, or hand them off to NextSign.
 * <p>
 * Appendix files are returned as opaque references — they live in S3 already
 * and are treated as already-PDFs by downstream code.
 */
@JBossLog
@ApplicationScoped
public class DossierPdfGenerationService {

    @Inject
    WordDocumentService wordDocumentService;

    @Inject
    PlaceholderFormattingService placeholderFormattingService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * One generated PDF artifact: {@code (filename, bytes)} for documents
     * produced from the template, or {@code (filename, fileUuid, bytes=null)}
     * for appendices that should be streamed straight from S3 without
     * re-processing.
     *
     * @param filename     filename presented to the recipient (already
     *                     suffixed with {@code .pdf} for generated docs)
     * @param fileUuid     non-null for appendices already living in S3;
     *                     {@code null} for freshly generated PDFs
     * @param pdfBytes     non-null for freshly generated PDFs; {@code null}
     *                     when the caller should fetch the bytes from
     *                     {@link #fileUuid} via the file service
     * @param fromTemplate   {@code true} if produced from a template document;
     *                       {@code false} for an appendix
     * @param signObligated  {@code true} = signature required when shipped via
     *                       NextSign; {@code false} = attachment-only.
     *                       Templates are always signed; appendices reflect
     *                       the recruiter's per-appendix choice.
     */
    public record GeneratedPdf(String filename, String fileUuid, byte[] pdfBytes,
                               boolean fromTemplate, boolean signObligated) {
        public PdfArtifactRef toRef() {
            return new PdfArtifactRef(filename, fileUuid);
        }
    }

    /**
     * Generate every PDF artifact a Send action should ship with the given
     * revision: one PDF per template document populated with the revision's
     * frozen placeholder values, plus opaque references to each appendix
     * already in S3.
     *
     * @return ordered list of PDF artifacts; template-generated PDFs appear
     *         first in template's {@code displayOrder}, followed by
     *         appendices in their stored order
     */
    public List<GeneratedPdf> generatePdfsFor(CandidateDossierRevision revision, String templateUuid) {
        Objects.requireNonNull(revision, "revision must not be null");
        return generatePdfsFromValues(
                templateUuid,
                readPlaceholderSnapshot(revision),
                readAppendicesSnapshot(revision));
    }

    /**
     * Pre-resolved variant: caller supplies placeholder values and appendices
     * directly. Used by the signature-send flow so the external NextSign call
     * can run outside the snapshot transaction.
     */
    public List<GeneratedPdf> generatePdfsFromValues(
            String templateUuid,
            Map<String, String> placeholders,
            List<AppendixDto> appendices) {
        Objects.requireNonNull(templateUuid, "templateUuid must not be null");
        Objects.requireNonNull(placeholders, "placeholders must not be null");
        Objects.requireNonNull(appendices, "appendices must not be null");

        List<TemplateDocumentEntity> templateDocs =
                TemplateDocumentEntity.findByTemplateUuid(templateUuid);

        // Apply type-aware formatting (CURRENCY → "kr. 40.000,00", DECIMAL →
        // "40.000,00") so the substituted strings render the same way as in
        // the signing wizard. Mirrors SigningService.createMultiDocumentCaseFromTemplate.
        Map<String, String> effectiveValues = placeholderFormattingService
                .formatPlaceholderValues(templateUuid, placeholders);
        long nonBlank = effectiveValues.values().stream()
                .filter(v -> v != null && !v.isBlank())
                .count();
        log.infof("Effective placeholder keys for template %s: %s (non-blank=%d/%d)",
                templateUuid, effectiveValues.keySet(), nonBlank, effectiveValues.size());

        List<GeneratedPdf> out = new ArrayList<>(templateDocs.size());
        for (TemplateDocumentEntity doc : templateDocs) {
            String fileUuid = doc.getFileUuid();
            if (fileUuid == null || fileUuid.isBlank()) {
                log.warnf("Template document uuid=%s has no Word file_uuid — skipping", doc.getUuid());
                continue;
            }
            String filename = ensurePdfSuffix(safeDocName(doc.getDocumentName()));
            byte[] pdfBytes = wordDocumentService.generatePdfFromWordTemplate(
                    fileUuid, effectiveValues, filename);
            out.add(new GeneratedPdf(filename, null, pdfBytes, true, true));
        }

        for (AppendixDto appendix : appendices) {
            out.add(new GeneratedPdf(
                    appendix.originalFilename(),
                    appendix.fileUuid(),
                    null,
                    false,
                    appendix.signObligated()));
        }
        return out;
    }

    /**
     * Generate only the template-derived PDFs (no appendices). Used by the
     * "Generate review PDF" download endpoint.
     */
    public List<GeneratedPdf> generateTemplatePdfsFor(CandidateDossierRevision revision, String templateUuid) {
        return generatePdfsFor(revision, templateUuid).stream()
                .filter(GeneratedPdf::fromTemplate)
                .toList();
    }

    // ---- helpers ---------------------------------------------------------------

    private Map<String, String> readPlaceholderSnapshot(CandidateDossierRevision revision) {
        if (revision.getPlaceholderValuesSnapshot() == null
                || revision.getPlaceholderValuesSnapshot().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> v = objectMapper.readValue(
                    revision.getPlaceholderValuesSnapshot(), new TypeReference<>() {
                    });
            return v == null ? Map.of() : new HashMap<>(v);
        } catch (Exception e) {
            // Don't ship a PDF with every placeholder blank — fail loudly.
            throw new IllegalStateException(
                    "Could not parse placeholder snapshot for revision=" + revision.getUuid(), e);
        }
    }

    private List<AppendixDto> readAppendicesSnapshot(CandidateDossierRevision revision) {
        if (revision.getAppendicesSnapshot() == null
                || revision.getAppendicesSnapshot().isBlank()) {
            return List.of();
        }
        try {
            List<AppendixDto> v = objectMapper.readValue(
                    revision.getAppendicesSnapshot(), new TypeReference<>() {
                    });
            return v == null ? List.of() : v;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not parse appendices snapshot for revision=" + revision.getUuid(), e);
        }
    }

    private static String safeDocName(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
        return name;
    }

    private static String ensurePdfSuffix(String name) {
        if (name.toLowerCase().endsWith(".pdf")) {
            return name;
        }
        return name + ".pdf";
    }
}
