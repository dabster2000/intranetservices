package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateConsentRow;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateConsentsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateDocument;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateDocumentsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.FormAnswer;
import dk.trustworks.intranet.recruitmentservice.dto.FormAnswersResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model for the P8 profile tabs that are not the timeline: form
 * answers (both V437 ownership legs), the documents list + download, and
 * the read-only GDPR consents. A pure query service — no mutations, no
 * events. Authorization happens in the resources <em>before</em> any call
 * lands here ({@code canReadCandidateProfile} / {@code canReadApplication});
 * the authorization rules this service owns are the download IDOR guard and
 * the capability-aware exclusion of offer/onboarding document kinds. A file
 * whose {@code relateduuid} does not match the candidate in the URL, or a
 * dossier-restricted file requested without dossier-read capability, answers
 * the same 404 as a nonexistent one.
 */
@JBossLog
@ApplicationScoped
public class CandidateProfileReadService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    /**
     * The kinds an upload/kind-change event may carry; anything else
     * renders as OTHER (shared registry on the classifier).
     */
    private static final Set<String> KNOWN_DOCUMENT_KINDS = CandidateDocumentClassifier.ALL_KINDS;

    static final String KIND_OTHER = CandidateDocumentClassifier.KIND_OTHER;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    S3FileService s3FileService;

    @Inject
    CandidateDocumentClassifier documentClassifier;

    // ---- Form answers (P8 Application tab) --------------------------------------

    /** Position-form answers of one application, labelled + display-ordered. */
    public FormAnswersResponse answersForApplication(String applicationUuid) {
        return new FormAnswersResponse(toFormAnswers(
                RecruitmentApplicationAnswer.list("applicationUuid", applicationUuid)));
    }

    /**
     * Candidate-scoped answers — the V437 leg for unsolicited applicants,
     * who have answers but no application until triage attaches one
     * (findings §P5).
     */
    public FormAnswersResponse answersForCandidate(String candidateUuid) {
        return new FormAnswersResponse(toFormAnswers(
                RecruitmentApplicationAnswer.list("candidateUuid", candidateUuid)));
    }

    /**
     * Answers in question display order, labelled from the code-defined set
     * ({@link PublicApplyQuestions}); unknown/legacy keys fall back to the
     * key itself and sort after the known questions.
     */
    private static List<FormAnswer> toFormAnswers(List<RecruitmentApplicationAnswer> answers) {
        Map<String, PublicApplyQuestions.Question> questions = PublicApplyQuestions.all().stream()
                .collect(Collectors.toMap(PublicApplyQuestions.Question::key, Function.identity()));
        List<String> displayOrder = PublicApplyQuestions.keys();
        return answers.stream()
                .sorted(Comparator.comparingInt(a -> {
                    int index = displayOrder.indexOf(a.getQuestionKey());
                    return index >= 0 ? index : displayOrder.size();
                }))
                .map(a -> new FormAnswer(
                        a.getQuestionKey(),
                        questions.containsKey(a.getQuestionKey())
                                ? questions.get(a.getQuestionKey()).label()
                                : a.getQuestionKey(),
                        a.getAnswer()))
                .toList();
    }

    // ---- Documents (P8 Documents tab) -------------------------------------------

    /**
     * The candidate's {@code files} rows enriched from their
     * {@code DOCUMENT_UPLOADED} events (joined on {@code payload.file_uuid}
     * — the CV/COVER_LETTER kind lives ONLY there, findings §P5). Two
     * queries total; newest upload first.
     */
    /**
     * Candidate documents for a profile viewer. Ordinary CV, cover-letter and
     * unclassified files remain visible without dossier access; contract
     * drafts, signed documents, appendices and identity documents do not.
     * Every caller must state the capability explicitly so a restricted
     * surface cannot accidentally inherit a privileged default.
     */
    public CandidateDocumentsResponse documents(String candidateUuid,
                                                 boolean canReadDossier) {
        List<File> files = File.list("relateduuid", candidateUuid);
        DocumentClassificationState classifications = documentClassifications(candidateUuid);
        List<CandidateDocument> documents = files.stream()
                .filter(file -> canReadDossier
                        || !classifications.isDossierRestricted(file.getUuid()))
                .map(file -> toDocument(file,
                        classifications.uploads().displayFacts().get(file.getUuid()),
                        classifications.overrides().latestKinds().get(file.getUuid()),
                        classifications.derivedKinds().get(file.getUuid())))
                .sorted(Comparator.comparing(CandidateDocument::uploadedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CandidateDocument::fileUuid))
                .toList();
        return new CandidateDocumentsResponse(documents);
    }

    /**
     * Whether one file is manually re-typeable: only files the system
     * cannot classify itself — no specific upload-event kind (missing or
     * OTHER) and no flow derivation. A manual override never revokes
     * editability, so mistakes stay correctable. Used by the kind-change
     * command as the server-side eligibility gate.
     */
    public boolean isKindEditable(String candidateUuid, String fileUuid) {
        DocumentClassificationState classifications = documentClassifications(candidateUuid);
        DocumentEventFacts facts = classifications.uploads().displayFacts().get(fileUuid);
        String derivedKind = classifications.derivedKinds().get(fileUuid);
        return systemKind(facts, derivedKind) == null;
    }

    /**
     * Whether any classification source has ever identified this candidate file
     * as offer, contract or onboarding material. This predicate is monotonic:
     * the upload event, every manual-kind override and every persisted flow
     * reference contribute, so a later ordinary display label cannot erase a
     * prior restricted classification. Commands and read filtering share this
     * method to avoid authorization drift.
     */
    public boolean isDossierRestricted(String candidateUuid, String fileUuid) {
        return documentClassifications(candidateUuid).isDossierRestricted(fileUuid);
    }

    /**
     * Stream one candidate document. The IDOR guard runs on the local
     * {@code files} row BEFORE any S3 round-trip: a mismatching or missing
     * {@code relateduuid} answers 404, never 403 — URL-guessed file uuids
     * cannot leak another candidate's documents.
     */
    public DocumentDownload download(String candidateUuid, String fileUuid,
                                     boolean canReadDossier) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        File meta = File.findById(fileUuid);
        if (meta == null || !candidateUuid.equals(meta.getRelateduuid())) {
            throw new NotFoundException("Document not found: " + fileUuid);
        }
        DocumentClassificationState classifications = documentClassifications(candidateUuid);
        if (!canReadDossier && classifications.isDossierRestricted(fileUuid)) {
            // Same status/message shape as unknown and cross-candidate UUIDs:
            // knowing the file UUID must not disclose that a dossier exists.
            throw new NotFoundException("Document not found: " + fileUuid);
        }
        File withBytes = s3FileService.findOne(fileUuid);
        if (withBytes == null || withBytes.getFile() == null || withBytes.getFile().length == 0) {
            // Metadata row without a retrievable S3 object — not downloadable.
            throw new NotFoundException("Document not found: " + fileUuid);
        }
        DocumentEventFacts facts = classifications.uploads().displayFacts().get(fileUuid);
        String filename = meta.getFilename() != null && !meta.getFilename().isBlank()
                ? meta.getFilename()
                : "document";
        String contentType = facts != null && facts.contentType() != null
                ? facts.contentType()
                : "application/octet-stream";
        return new DocumentDownload(withBytes.getFile(), filename, contentType);
    }

    /** One document's bytes plus the headers the resource needs. */
    public record DocumentDownload(byte[] bytes, String filename, String contentType) {
    }

    private CandidateDocument toDocument(File file, DocumentEventFacts facts,
                                         String overrideKind, String derivedKind) {
        LocalDateTime uploadedAt = facts != null
                ? facts.occurredAt()
                : (file.getUploaddate() != null ? file.getUploaddate().atStartOfDay() : null);
        String systemKind = systemKind(facts, derivedKind);
        String kind = overrideKind != null ? overrideKind
                : systemKind != null ? systemKind
                : KIND_OTHER;
        return new CandidateDocument(
                file.getUuid(),
                file.getFilename(),
                facts != null ? facts.contentType() : null,
                facts != null ? facts.sizeBytes() : null,
                uploadedAt,
                kind,
                facts != null ? facts.origin() : null,
                facts != null ? facts.reason() : null,
                systemKind == null);
    }

    /**
     * The system's own classification of a file: a specific (non-OTHER)
     * upload-event kind wins over the flow derivation; null when the
     * system has no specific claim (→ the file is manually re-typeable).
     */
    private static String systemKind(DocumentEventFacts facts, String derivedKind) {
        if (facts != null && facts.kind() != null && !KIND_OTHER.equals(facts.kind())) {
            return facts.kind();
        }
        return derivedKind;
    }

    /**
     * Security classification is deliberately monotonic: if any trusted
     * source identifies a file as offer/onboarding material, a later manual
     * label cannot downgrade it into the ordinary-document audience.
     */
    private DocumentClassificationState documentClassifications(String candidateUuid) {
        return new DocumentClassificationState(
                documentEventFacts(candidateUuid),
                kindOverrides(candidateUuid),
                documentClassifier.derivedKinds(candidateUuid));
    }

    /**
     * Newest manual kind override per file uuid — the
     * {@code DOCUMENT_KIND_CHANGED} events in seq order, later wins.
     * Every restricted value is retained separately for authorization even
     * when a later ordinary value wins for display.
     * Unknown kinds are ignored (defensive: the command validates on
     * write, so this only guards against future enum drift).
     */
    private DocumentOverrideState kindOverrides(String candidateUuid) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq",
                candidateUuid, RecruitmentEventType.DOCUMENT_KIND_CHANGED);
        Map<String, String> overrides = new HashMap<>();
        Set<String> restrictedFiles = new HashSet<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parsePayload(event.getPayload());
            if (payload.get("file_uuid") instanceof String key && !key.isBlank()
                    && payload.get("kind") instanceof String kind
                    && KNOWN_DOCUMENT_KINDS.contains(kind)) {
                overrides.put(key, kind);
                if (CandidateDocumentClassifier.isDossierRestricted(kind)) {
                    restrictedFiles.add(key);
                }
            }
        }
        return new DocumentOverrideState(Map.copyOf(overrides), Set.copyOf(restrictedFiles));
    }

    private record DocumentOverrideState(Map<String, String> latestKinds,
                                         Set<String> everRestrictedFiles) {
    }

    /** Enrichment facts parsed from one {@code DOCUMENT_UPLOADED} payload. */
    private record DocumentEventFacts(String kind, String origin, String reason,
                                      String contentType, Long sizeBytes,
                                      LocalDateTime occurredAt) {
    }

    /**
     * All candidate {@code DOCUMENT_UPLOADED} payloads in one query. The
     * first event supplies display metadata while every event contributes to
     * the monotonic restricted-file set.
     */
    private DocumentUploadState documentEventFacts(String candidateUuid) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq",
                candidateUuid, RecruitmentEventType.DOCUMENT_UPLOADED);
        Map<String, DocumentEventFacts> facts = new HashMap<>();
        Set<String> restrictedFiles = new HashSet<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parsePayload(event.getPayload());
            Object fileUuid = payload.get("file_uuid");
            if (!(fileUuid instanceof String key) || key.isBlank()) {
                continue;
            }
            String kind = payload.get("kind") instanceof String k && KNOWN_DOCUMENT_KINDS.contains(k)
                    ? k
                    : KIND_OTHER;
            if (CandidateDocumentClassifier.isDossierRestricted(kind)) {
                restrictedFiles.add(key);
            }
            // First event wins — each upload emits exactly one event.
            facts.putIfAbsent(key, new DocumentEventFacts(
                    kind,
                    payload.get("origin") instanceof String o ? o : null,
                    payload.get("reason") instanceof String r ? r : null,
                    payload.get("content_type") instanceof String c ? c : null,
                    payload.get("size_bytes") instanceof Number n ? n.longValue() : null,
                    event.getOccurredAt()));
        }
        return new DocumentUploadState(Map.copyOf(facts), Set.copyOf(restrictedFiles));
    }

    private record DocumentUploadState(Map<String, DocumentEventFacts> displayFacts,
                                       Set<String> everRestrictedFiles) {
    }

    private record DocumentClassificationState(
            DocumentUploadState uploads,
            DocumentOverrideState overrides,
            Map<String, String> derivedKinds) {

        boolean isDossierRestricted(String fileUuid) {
            return uploads.everRestrictedFiles().contains(fileUuid)
                    || overrides.everRestrictedFiles().contains(fileUuid)
                    || CandidateDocumentClassifier.isDossierRestricted(derivedKinds.get(fileUuid));
        }
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            log.warn("Unparseable document-classification payload — skipping one event");
            return Map.of();
        }
    }

    // ---- Consents (P8 GDPR tab) --------------------------------------------------

    /**
     * The candidate's consent rows, newest request first — read-only DTOs
     * with exactly the contract's five fields, so {@code token_hash} (P19's
     * secret) can never serialize regardless of entity annotations.
     */
    public CandidateConsentsResponse consents(String candidateUuid) {
        List<RecruitmentConsent> rows = RecruitmentConsent.list(
                "candidateUuid", Sort.descending("createdAt"), candidateUuid);
        return new CandidateConsentsResponse(rows.stream()
                .map(consent -> new CandidateConsentRow(
                        consent.getKind(),
                        consent.getStatus(),
                        consent.getCreatedAt(),
                        consent.getGrantedAt(),
                        consent.getExpiresAt()))
                .toList());
    }
}
