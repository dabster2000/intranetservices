package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.AppendixDto;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service that owns the immutable {@link CandidateDossierRevision}
 * read/write surface.
 * <p>
 * The {@link #snapshot} method allocates a new revision via
 * {@link CandidateDossier#allocateRevision(RevisionKind, UUID)}, freezes the
 * dossier's current placeholder values, signers config and appendix list into
 * the revision's snapshot JSON columns, populates Send-context fields
 * (recipient, sender, note, optional signing-case key, optional generated
 * artifacts) and persists. Once persisted, every snapshot field on the row
 * is {@code updatable=false} — subsequent edits to the dossier cannot
 * mutate prior revisions.
 */
@JBossLog
@ApplicationScoped
public class DossierRevisionService {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DossierService dossierService;

    /**
     * Side-channel value object that bundles the recipient + sender data the
     * revision needs without leaking jakarta.ws.rs context into the entity.
     *
     * @param recipientEmail required — review email recipient (always the
     *                       candidate's email per spec) or first signer email
     *                       for signature flow
     * @param recipientName  optional display name surfaced to the recipient
     * @param sentByUseruuid actor performing the Send (set on the revision row)
     * @param note           optional free-text note on the Send
     * @param signingCaseKey optional NextSign case key (only set for SIGNATURE)
     * @param pdfArtifacts   optional list of generated PDF artifacts produced
     *                       by the Send (each {@code (filename, fileUuid)});
     *                       persisted into the revision for download replay
     */
    public record RecipientInfo(
            String recipientEmail,
            String recipientName,
            UUID sentByUseruuid,
            String note,
            String signingCaseKey,
            List<RevisionResponse.PdfArtifactRef> pdfArtifacts
    ) {
        public RecipientInfo {
            Objects.requireNonNull(recipientEmail, "recipientEmail must not be null");
            Objects.requireNonNull(sentByUseruuid, "sentByUseruuid must not be null");
        }
    }

    /**
     * Allocate, freeze and persist a revision. Runs inside the caller's
     * {@code @Transactional} so an exception during PDF generation or mail
     * queueing rolls the revision row back together with the rest of the
     * Send action.
     *
     * @return the persisted, fully populated revision read DTO
     * @throws dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation
     *         if the dossier is CLOSED — propagated from
     *         {@link CandidateDossier#allocateRevision}
     */
    @Transactional
    public CandidateDossierRevision snapshot(
            CandidateDossier dossier, RevisionKind kind, RecipientInfo recipient, UUID actor) {
        Objects.requireNonNull(dossier, "dossier must not be null");
        Map<String, String> placeholders = dossierService.currentPlaceholderValues(dossier);
        List<SignerConfigDto> signers = dossierService.currentSignersConfig(dossier);
        List<AppendixDto> appendices = dossierService.currentAppendices(dossier.getUuid());
        return snapshotFromValues(dossier, kind, placeholders, signers, appendices, recipient, actor);
    }

    /**
     * Pre-resolved variant: caller supplies the frozen draft state explicitly.
     * Used by the signature-send flow so the snapshot persists atomically with
     * the NextSign case key (no post-persist column mutation).
     */
    @Transactional
    public CandidateDossierRevision snapshotFromValues(
            CandidateDossier dossier,
            RevisionKind kind,
            Map<String, String> placeholders,
            List<SignerConfigDto> signers,
            List<AppendixDto> appendices,
            RecipientInfo recipient,
            UUID actor) {
        Objects.requireNonNull(dossier, "dossier must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        CandidateDossierRevision revision = dossier.allocateRevision(kind, actor);

        revision.setPlaceholderValuesSnapshot(writeJson(placeholders));
        revision.setSignersConfigSnapshot(writeJson(signers));
        revision.setAppendicesSnapshot(writeJson(appendices));

        revision.setRecipientEmail(recipient.recipientEmail());
        revision.setSentByUseruuid(recipient.sentByUseruuid().toString());
        revision.setNote(recipient.note());
        revision.setSigningCaseKey(recipient.signingCaseKey());

        CandidateDossierRevision.persist(revision);
        log.infof("Persisted revision uuid=%s dossier=%s version=%d kind=%s",
                revision.getUuid(), dossier.getUuid(), revision.getVersionNumber(), kind);
        return revision;
    }

    /**
     * Revisions for a dossier in reverse-chronological order (newest first).
     * Powers the timeline UI on the dossier page.
     */
    public List<RevisionResponse> findByDossier(UUID dossierUuid) {
        return CandidateDossierRevision
                .<CandidateDossierRevision>find("dossierUuid",
                        Sort.descending("versionNumber"),
                        dossierUuid.toString())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<RevisionResponse> findById(UUID revisionUuid) {
        return Optional.ofNullable(CandidateDossierRevision
                        .<CandidateDossierRevision>findById(revisionUuid.toString()))
                .map(this::toResponse);
    }

    /**
     * Hydrate a single revision into its response DTO. Note: pdfArtifactsSnapshot
     * is not stored in the V313 schema as a column — it would be a future
     * extension if we want to retain artifact refs. For now the caller can
     * reconstruct artifacts from the appendices snapshot + a regenerated PDF.
     */
    public RevisionResponse toResponse(CandidateDossierRevision revision) {
        if (revision == null) {
            throw new NotFoundException("Revision not found");
        }
        Map<String, String> placeholders = readJson(
                revision.getPlaceholderValuesSnapshot(),
                new TypeReference<>() {
                });
        List<SignerConfigDto> signers = readJson(
                revision.getSignersConfigSnapshot(),
                new TypeReference<>() {
                });
        List<AppendixDto> appendices = readJson(
                revision.getAppendicesSnapshot(),
                new TypeReference<>() {
                });
        return new RevisionResponse(
                revision.getUuid(),
                revision.getDossierUuid(),
                revision.getVersionNumber(),
                revision.getKind(),
                placeholders != null ? placeholders : Map.of(),
                signers != null ? signers : List.of(),
                appendices != null ? appendices : List.of(),
                List.of(), // pdfArtifactsSnapshot — see note above
                revision.getSigningCaseKey(),
                revision.getRecipientEmail(),
                null, // recipientName not persisted yet
                revision.getNote(),
                revision.getSentByUseruuid(),
                revision.getCreatedAt()
        );
    }

    // ---- internal helpers ------------------------------------------------------

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw CandidateService.jsonError("write", e);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> ref) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, ref);
        } catch (JsonProcessingException e) {
            // Re-throw: a silently empty snapshot ships review/signature emails
            // with the wrong placeholders or no signers.
            throw CandidateService.jsonError("read snapshot", e);
        }
    }
}
