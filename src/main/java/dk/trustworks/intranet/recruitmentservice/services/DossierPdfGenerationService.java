package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.TemplateDocumentEntity;
import dk.trustworks.intranet.documentservice.model.TemplatePlaceholderEntity;
import dk.trustworks.intranet.documentservice.services.ClauseCompositionService;
import dk.trustworks.intranet.documentservice.services.CompanyPlaceholderResolver;
import dk.trustworks.intranet.documentservice.services.CompanyPlaceholderResolver.MissingCompanyFactException;
import dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO;
import dk.trustworks.intranet.recruitmentservice.dto.AppendixDto;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse.PdfArtifactRef;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.utils.services.PlaceholderFormattingService;
import dk.trustworks.intranet.utils.services.WordDocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    CompanyPlaceholderResolver companyPlaceholderResolver;

    @Inject
    ClauseCompositionService clauseCompositionService;

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
    public List<GeneratedPdf> generatePdfsFor(CandidateDossierRevision revision, String templateUuid,
                                              String targetCompanyUuid) {
        Objects.requireNonNull(revision, "revision must not be null");
        return generatePdfsFromValues(
                templateUuid,
                readPlaceholderSnapshot(revision),
                readAppendicesSnapshot(revision),
                readClausesSnapshot(revision),
                targetCompanyUuid);
    }

    /**
     * Pre-resolved variant: caller supplies placeholder values and appendices
     * directly. Used by the signature-send flow so the external NextSign call
     * can run outside the snapshot transaction.
     *
     * @param targetCompanyUuid the candidate's target company — COMPANY-sourced
     *                          placeholders resolve from its facts (spec §4.9);
     *                          null skips fact resolution unless the template
     *                          explicitly references facts, in which case the
     *                          generation fails closed
     */
    public List<GeneratedPdf> generatePdfsFromValues(
            String templateUuid,
            Map<String, String> placeholders,
            List<AppendixDto> appendices,
            String targetCompanyUuid) {
        return generatePdfsFromValues(templateUuid, placeholders, appendices, List.of(), targetCompanyUuid);
    }

    /**
     * Clause-composing variant (template-clauses spec §5): a non-empty
     * clause selection merges INLINE clauses into the base document at the
     * {@code {{CLAUSES}}} anchor and appends one combined tillæg for
     * ADDENDUM clauses + Individuel aftale entries. Empty keeps the
     * pre-clause path byte-identical.
     */
    public List<GeneratedPdf> generatePdfsFromValues(
            String templateUuid,
            Map<String, String> placeholders,
            List<AppendixDto> appendices,
            List<SelectedClauseDTO> clauses,
            String targetCompanyUuid) {
        return generatePdfsFromValues(templateUuid, placeholders, appendices,
                resolveClausePlan(templateUuid, clauses), targetCompanyUuid);
    }

    /**
     * Resolve a clause selection with the dossier dialogs' {@code {error,
     * message}} 400 shape on an invalid selection (retired clause, missing
     * required parameter, key collision).
     */
    public ClauseCompositionService.CompositionPlan resolveClausePlan(String templateUuid,
                                                                      List<SelectedClauseDTO> clauses) {
        try {
            return clauseCompositionService.resolveForTemplateDocuments(
                    templateUuid, clauses == null ? List.of() : clauses);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_CLAUSE_SELECTION", "message", e.getMessage()))
                    .build());
        }
    }

    /**
     * Pre-resolved plan variant — the signature-send flow resolves once,
     * renders with the plan, and records the same plan into
     * {@code signing_case_clauses} after the case is accepted.
     */
    public List<GeneratedPdf> generatePdfsFromValues(
            String templateUuid,
            Map<String, String> placeholders,
            List<AppendixDto> appendices,
            ClauseCompositionService.CompositionPlan clausePlan,
            String targetCompanyUuid) {
        Objects.requireNonNull(templateUuid, "templateUuid must not be null");
        Objects.requireNonNull(placeholders, "placeholders must not be null");
        Objects.requireNonNull(appendices, "appendices must not be null");

        // Narrowed to the candidate's target company: a merged template carries
        // every company's documents, so an unfiltered read would put another
        // company's contract in the dossier.
        List<TemplateDocumentEntity> templateDocs =
                TemplateDocumentEntity.findByTemplateUuidForCompany(templateUuid, targetCompanyUuid);
        requireDocumentsForCompany(templateDocs, templateUuid, targetCompanyUuid);

        // Company facts first (authoritative for COMPANY-sourced placeholders,
        // fail-closed on explicitly mapped facts), then type-aware formatting
        // (CURRENCY → "kr. 40.000,00") so the substituted strings render the
        // same way as in the signing wizard. Mirrors
        // SigningService.createMultiDocumentCaseFromTemplate.
        Map<String, String> withFacts = new HashMap<>(placeholders);
        var derivedCompany = companyPlaceholderResolver.deriveForCompanyUuid(targetCompanyUuid);
        try {
            companyPlaceholderResolver.applyCompanyFacts(templateUuid, withFacts, derivedCompany);
        } catch (MissingCompanyFactException e) {
            // The dossier dialogs render {error, message} bodies verbatim —
            // the message names the facts and points at Settings → Selskaber.
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "MISSING_COMPANY_FACT", "message", e.getMessage()))
                    .build());
        }
        Map<String, String> effectiveValues = placeholderFormattingService
                .formatPlaceholderValues(templateUuid, withFacts);
        long nonBlank = effectiveValues.values().stream()
                .filter(v -> v != null && !v.isBlank())
                .count();
        log.infof("Effective placeholder keys for template %s: %s (non-blank=%d/%d)",
                templateUuid, effectiveValues.keySet(), nonBlank, effectiveValues.size());
        assertKeysMatchTemplate(templateUuid, effectiveValues.keySet());

        boolean composing = clausePlan != null && !clausePlan.isEmpty();
        if (composing) {
            // Clause fragments may reference {{COMPANY_*}} tags the base
            // template does not declare — carry the derived company's facts
            // into the composition as render tokens.
            clausePlan = clausePlan.withCompanyFactTokens(
                    clauseCompositionService.companyFactTokensFor(derivedCompany));
        }

        List<GeneratedPdf> out = new ArrayList<>(templateDocs.size());
        for (TemplateDocumentEntity doc : templateDocs) {
            String fileUuid = doc.getFileUuid();
            if (fileUuid == null || fileUuid.isBlank()) {
                log.warnf("Template document uuid=%s has no Word file_uuid — skipping", doc.getUuid());
                continue;
            }
            String filename = ensurePdfSuffix(safeDocName(doc.getDocumentName()));
            byte[] pdfBytes;
            if (composing) {
                byte[] baseDocx = wordDocumentService.getWordTemplate(fileUuid);
                Map<String, Object> renderValues = clauseCompositionService
                        .renderValuesForBaseDocument(clausePlan, fileUuid, effectiveValues);
                pdfBytes = wordDocumentService.generatePdfFromDocxBytes(baseDocx, renderValues, filename);
            } else {
                pdfBytes = wordDocumentService.generatePdfFromWordTemplate(
                        fileUuid, effectiveValues, filename);
            }
            out.add(new GeneratedPdf(filename, null, pdfBytes, true, true));
        }

        if (composing) {
            byte[] addendumDocx = clauseCompositionService.buildAddendumDocx(clausePlan, effectiveValues);
            if (addendumDocx != null) {
                String addendumName = ClauseCompositionService.ADDENDUM_DOCUMENT_NAME + ".pdf";
                byte[] addendumPdf = wordDocumentService.generatePdfFromDocxBytes(
                        addendumDocx, new HashMap<>(), addendumName);
                out.add(new GeneratedPdf(addendumName, null, addendumPdf, true, true));
            }
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
    public List<GeneratedPdf> generateTemplatePdfsFor(CandidateDossierRevision revision, String templateUuid,
                                                      String targetCompanyUuid) {
        return generatePdfsFor(revision, templateUuid, targetCompanyUuid).stream()
                .filter(GeneratedPdf::fromTemplate)
                .toList();
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Guards against shipping a contract whose every merge field came out
     * blank.
     * <p>
     * poi-tl is configured with its default {@code DiscardHandler}: a
     * {@code {{TAG}}} with no matching entry in the value map is silently
     * deleted rather than left visible. So a value map keyed on anything
     * other than the template's declared placeholder keys renders a
     * perfectly formatted document with every value missing — which is
     * exactly how a blank employment contract reached a signer on
     * 2026-08-24 (the recruitment dossier BFF route stopped renaming the
     * backend's {@code placeholderKey} to the frontend's {@code key}, so the
     * form persisted synthetic {@code placeholder_&lt;section&gt;_&lt;index&gt;}
     * keys).
     * <p>
     * A total mismatch is never a legitimate state, so it fails loudly.
     * A partial mismatch is only logged: templates legitimately carry
     * optional fields, and callers may deliberately omit them.
     */
    private void assertKeysMatchTemplate(String templateUuid, Set<String> suppliedKeys) {
        if (suppliedKeys.isEmpty()) {
            return;
        }
        Set<String> declared = TemplatePlaceholderEntity
                .<TemplatePlaceholderEntity>find("template.uuid = ?1", templateUuid)
                .list()
                .stream()
                .map(TemplatePlaceholderEntity::getPlaceholderKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (declared.isEmpty()) {
            return;
        }

        Set<String> unknown = new LinkedHashSet<>(suppliedKeys);
        unknown.removeAll(declared);
        if (unknown.size() == suppliedKeys.size()) {
            throw new IllegalStateException(
                    "Refusing to render template " + templateUuid
                            + ": none of the supplied placeholder keys " + suppliedKeys
                            + " match the template's declared keys " + declared
                            + ". Every merge field would render blank.");
        }
        if (!unknown.isEmpty()) {
            log.warnf("Template %s received %d placeholder key(s) it does not declare: %s",
                    templateUuid, unknown.size(), unknown);
        }
        Set<String> missing = new LinkedHashSet<>(declared);
        missing.removeAll(suppliedKeys);
        if (!missing.isEmpty()) {
            log.warnf("Template %s has %d declared placeholder(s) with no supplied value: %s",
                    templateUuid, missing.size(), missing);
        }
    }

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

    private List<SelectedClauseDTO> readClausesSnapshot(CandidateDossierRevision revision) {
        if (revision.getClausesSnapshot() == null || revision.getClausesSnapshot().isBlank()) {
            return List.of();
        }
        try {
            List<SelectedClauseDTO> v = objectMapper.readValue(
                    revision.getClausesSnapshot(), new TypeReference<>() {
                    });
            return v == null ? List.of() : v;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not parse clauses snapshot for revision=" + revision.getUuid(), e);
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

    /**
     * A merged template must still produce documents for the company at hand.
     * If every document on it is scoped to other companies, the dossier would
     * otherwise be generated with nothing in it — refuse instead, naming the
     * company, the same way a missing company fact does.
     */
    static void requireDocumentsForCompany(List<TemplateDocumentEntity> docs,
                                           String templateUuid, String companyUuid) {
        if (docs != null && !docs.isEmpty()) {
            return;
        }
        throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "NO_DOCUMENTS_FOR_COMPANY",
                        "message", "Skabelonen har ingen dokumenter for dette selskab."
                                + " Tilføj et dokument til selskabet, eller marker et dokument"
                                + " som gældende for alle selskaber."))
                .build());
    }

}
