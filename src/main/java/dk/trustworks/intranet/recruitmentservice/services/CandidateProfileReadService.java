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
 * the one authorization rule this service owns is the download IDOR guard:
 * a file whose {@code relateduuid} does not match the candidate in the URL
 * answers the same 404 as a nonexistent one.
 */
@JBossLog
@ApplicationScoped
public class CandidateProfileReadService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    /** The document kinds emitted by P5; anything else renders as OTHER. */
    private static final Set<String> KNOWN_DOCUMENT_KINDS = Set.of("CV", "COVER_LETTER");

    static final String KIND_OTHER = "OTHER";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    S3FileService s3FileService;

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
    public CandidateDocumentsResponse documents(String candidateUuid) {
        List<File> files = File.list("relateduuid", candidateUuid);
        Map<String, DocumentEventFacts> facts = documentEventFacts(candidateUuid);
        List<CandidateDocument> documents = files.stream()
                .map(file -> toDocument(file, facts.get(file.getUuid())))
                .sorted(Comparator.comparing(CandidateDocument::uploadedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CandidateDocument::fileUuid))
                .toList();
        return new CandidateDocumentsResponse(documents);
    }

    /**
     * Stream one candidate document. The IDOR guard runs on the local
     * {@code files} row BEFORE any S3 round-trip: a mismatching or missing
     * {@code relateduuid} answers 404, never 403 — URL-guessed file uuids
     * cannot leak another candidate's documents.
     */
    public DocumentDownload download(String candidateUuid, String fileUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        File meta = File.findById(fileUuid);
        if (meta == null || !candidateUuid.equals(meta.getRelateduuid())) {
            throw new NotFoundException("Document not found: " + fileUuid);
        }
        File withBytes = s3FileService.findOne(fileUuid);
        if (withBytes == null || withBytes.getFile() == null || withBytes.getFile().length == 0) {
            // Metadata row without a retrievable S3 object — not downloadable.
            throw new NotFoundException("Document not found: " + fileUuid);
        }
        DocumentEventFacts facts = documentEventFacts(candidateUuid).get(fileUuid);
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

    private CandidateDocument toDocument(File file, DocumentEventFacts facts) {
        LocalDateTime uploadedAt = facts != null
                ? facts.occurredAt()
                : (file.getUploaddate() != null ? file.getUploaddate().atStartOfDay() : null);
        return new CandidateDocument(
                file.getUuid(),
                file.getFilename(),
                facts != null ? facts.contentType() : null,
                facts != null ? facts.sizeBytes() : null,
                uploadedAt,
                facts != null ? facts.kind() : KIND_OTHER,
                facts != null ? facts.origin() : null,
                facts != null ? facts.reason() : null);
    }

    /** Enrichment facts parsed from one {@code DOCUMENT_UPLOADED} payload. */
    private record DocumentEventFacts(String kind, String origin, String reason,
                                      String contentType, Long sizeBytes,
                                      LocalDateTime occurredAt) {
    }

    /** All the candidate's DOCUMENT_UPLOADED payloads, keyed by {@code file_uuid} (one query). */
    private Map<String, DocumentEventFacts> documentEventFacts(String candidateUuid) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq",
                candidateUuid, RecruitmentEventType.DOCUMENT_UPLOADED);
        Map<String, DocumentEventFacts> facts = new HashMap<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parsePayload(event.getPayload());
            Object fileUuid = payload.get("file_uuid");
            if (!(fileUuid instanceof String key) || key.isBlank()) {
                continue;
            }
            String kind = payload.get("kind") instanceof String k && KNOWN_DOCUMENT_KINDS.contains(k)
                    ? k
                    : KIND_OTHER;
            // First event wins — each upload emits exactly one event.
            facts.putIfAbsent(key, new DocumentEventFacts(
                    kind,
                    payload.get("origin") instanceof String o ? o : null,
                    payload.get("reason") instanceof String r ? r : null,
                    payload.get("content_type") instanceof String c ? c : null,
                    payload.get("size_bytes") instanceof Number n ? n.longValue() : null,
                    event.getOccurredAt()));
        }
        return facts;
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            log.warn("Unparseable DOCUMENT_UPLOADED payload — skipping enrichment for one event");
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
