package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flow-derived kinds for a candidate's stored files (the document-type
 * classification feature). The signing/dossier/onboarding flows store
 * files without a {@code DOCUMENT_UPLOADED} event, so the P8 Documents
 * tab used to render them all as OTHER. This classifier derives their
 * kind from the flow tables that already reference every such file —
 * retroactively correct for files stored before events were emitted:
 *
 * <ul>
 *   <li>{@code candidate_dossier_revisions.generated_pdfs_snapshot}
 *       → {@link #KIND_CONTRACT_DRAFT} (template-rendered dossier PDFs,
 *       unsigned — review copies and signature sends alike)</li>
 *   <li>{@code candidate_dossier_revisions.signed_pdfs_snapshot}
 *       → {@link #KIND_SIGNED_DOCUMENT} (NextSign-completed PDFs archived
 *       to candidate staging, spec §6.5.2)</li>
 *   <li>{@code candidate_dossier_appendices.file_uuid}
 *       → {@link #KIND_APPENDIX}</li>
 *   <li>{@code onboarding_upload_submissions.s3_file_uuid}
 *       → {@link #KIND_ID_DOCUMENT} (candidate-flow identity documents)</li>
 * </ul>
 *
 * A file referenced by more than one source keeps the most specific
 * claim: signed &gt; appendix &gt; identity &gt; draft (in practice the
 * sources are disjoint; the order only guards against snapshot reuse).
 */
@JBossLog
@ApplicationScoped
public class CandidateDocumentClassifier {

    public static final String KIND_CV = "CV";
    public static final String KIND_COVER_LETTER = "COVER_LETTER";
    public static final String KIND_CONTRACT_DRAFT = "CONTRACT_DRAFT";
    public static final String KIND_SIGNED_DOCUMENT = "SIGNED_DOCUMENT";
    public static final String KIND_APPENDIX = "APPENDIX";
    public static final String KIND_ID_DOCUMENT = "ID_DOCUMENT";
    public static final String KIND_OTHER = "OTHER";

    /** Every kind a {@code DOCUMENT_UPLOADED} / kind-change event may carry. */
    public static final Set<String> ALL_KINDS = Set.of(
            KIND_CV, KIND_COVER_LETTER, KIND_CONTRACT_DRAFT, KIND_SIGNED_DOCUMENT,
            KIND_APPENDIX, KIND_ID_DOCUMENT, KIND_OTHER);

    /**
     * Kinds a recruiter may assign when manually re-typing an
     * unclassified file — the full set: old files predate flow tracking,
     * so any of these can be the truthful answer.
     */
    public static final Set<String> ASSIGNABLE_KINDS = ALL_KINDS;

    private static final TypeReference<List<GeneratedPdfRef>> REF_LIST =
            new TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    /**
     * Flow-derived kind per file uuid for one candidate. Files with no
     * flow reference are absent from the map (the caller falls back to
     * event kinds, then OTHER).
     */
    public Map<String, String> derivedKinds(String candidateUuid) {
        Map<String, String> kinds = new HashMap<>();

        // Weakest claim first — later puts overwrite (draft < identity
        // < appendix < signed).
        for (CandidateDossierRevision revision : CandidateDossierRevision.findByCandidate(candidateUuid)) {
            for (GeneratedPdfRef ref : parseRefs(revision.getGeneratedPdfsSnapshot(), candidateUuid)) {
                if (ref.fileUuid() != null && !ref.fileUuid().isBlank()) {
                    kinds.put(ref.fileUuid(), KIND_CONTRACT_DRAFT);
                }
            }
        }
        for (OnboardingUploadSubmission submission
                : OnboardingUploadSubmission.findS3SubmissionsByCandidate(candidateUuid)) {
            if (submission.getS3FileUuid() != null) {
                kinds.put(submission.getS3FileUuid(), KIND_ID_DOCUMENT);
            }
        }
        for (CandidateDossierAppendix appendix : CandidateDossierAppendix.findByCandidate(candidateUuid)) {
            if (appendix.getFileUuid() != null) {
                kinds.put(appendix.getFileUuid(), KIND_APPENDIX);
            }
        }
        for (CandidateDossierRevision revision : CandidateDossierRevision.findByCandidate(candidateUuid)) {
            for (GeneratedPdfRef ref : parseRefs(revision.getSignedPdfsSnapshot(), candidateUuid)) {
                if (ref.fileUuid() != null && !ref.fileUuid().isBlank()) {
                    kinds.put(ref.fileUuid(), KIND_SIGNED_DOCUMENT);
                }
            }
        }
        return kinds;
    }

    private List<GeneratedPdfRef> parseRefs(String snapshotJson, String candidateUuid) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(snapshotJson, REF_LIST);
        } catch (Exception e) {
            log.warnf("Unparseable PDF snapshot for candidate=%s — files keep their event/OTHER kind",
                    candidateUuid);
            return List.of();
        }
    }
}
