package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateCv;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Application service that orchestrates a candidate CV upload end-to-end:
 * <ol>
 *     <li>Persists the file via {@link CvFileStorageService} (SharePoint).</li>
 *     <li>Computes a SHA-256 digest for de-duplication.</li>
 *     <li>Extracts plain text via {@link CvFileExtractor} (PDF/DOCX).</li>
 *     <li>Demotes any prior {@code is_current = true} CV row for the candidate
 *         so the trigger-derived {@code current_for_unique} unique index
 *         (see {@link CandidateCv}) accepts the new row.</li>
 *     <li>Inserts the new {@link CandidateCv} row marked current.</li>
 *     <li>Requests a {@link AiArtifactKind#CV_EXTRACTION} artifact through
 *         {@link AiArtifactService}, which idempotently inserts the artifact
 *         row and enqueues an {@code AI_GENERATE} outbox entry within the
 *         same transaction.</li>
 *     <li>Stamps the artifact UUID back onto the CV row for traceability.</li>
 * </ol>
 *
 * <p>All work runs in a single {@code @Transactional} boundary so a SharePoint
 * upload that succeeds but a subsequent JDBC error rolls the DB back; the
 * SharePoint blob is harmless on retry because storage paths are timestamped
 * (the next attempt writes to a new path) and idempotent re-extraction is
 * handled by {@link AiArtifactService} via input-digest deduplication.</p>
 */
@ApplicationScoped
public class CvUploadService {

    @Inject CvFileStorageService storage;
    @Inject CvFileExtractor extractor;
    @Inject AiArtifactService artifacts;

    /**
     * Uploads, persists, and queues extraction for a candidate CV.
     *
     * @param candidateUuid the owning candidate aggregate root UUID; must already exist
     * @param filename      original client-supplied filename (used for extension dispatch
     *                      and SharePoint path; sanitised inside {@link CvFileStorageService})
     * @param contentType   MIME type recorded on the entity
     * @param data          raw file bytes
     * @param actorUuid     user UUID that initiated the upload
     * @return the persisted, current-marked {@link CandidateCv} with
     *         {@code extractionArtifactUuid} populated
     * @throws NotFoundException        if {@code candidateUuid} does not exist
     * @throws IllegalArgumentException if extraction rejects the bytes (size, extension,
     *                                  empty payload — see {@link CvFileExtractor#extract})
     */
    @Transactional
    public CandidateCv upload(String candidateUuid, String filename, String contentType,
                              byte[] data, String actorUuid) {

        Candidate c = Candidate.findById(candidateUuid);
        if (c == null) throw new NotFoundException("candidate not found");

        String fileUrl = storage.store(candidateUuid, filename, contentType, data);
        String sha = storage.sha256(data);
        String text = extractor.extract(data, filename);

        // Demote any existing current CV — the unique index on current_for_unique
        // enforces "at most one current per candidate", so we must zero-out the
        // prior row before inserting the new one in this same transaction.
        CandidateCv.update(
                "isCurrent = false where candidateUuid = ?1 and isCurrent = true",
                candidateUuid);

        CandidateCv cv = new CandidateCv();
        cv.uuid = UUID.randomUUID().toString();
        cv.candidateUuid = candidateUuid;
        cv.fileUrl = fileUrl;
        cv.fileSha256 = sha;
        cv.isCurrent = true;
        cv.uploadedByUuid = actorUuid;
        cv.uploadedAt = LocalDateTime.now();
        cv.persist();

        // Queue CV_EXTRACTION artifact. AiArtifactService is idempotent on
        // (subject, kind, input_digest) so retries on the same content do not
        // create duplicate artifacts or outbox entries.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("candidateUuid", candidateUuid);
        inputs.put("cvSha256", sha);
        inputs.put("cvText", text);
        AiArtifact artifact = artifacts.requestArtifact(
                AiSubjectKind.CANDIDATE, candidateUuid,
                AiArtifactKind.CV_EXTRACTION, inputs, actorUuid);
        cv.extractionArtifactUuid = artifact.uuid;
        cv.persist();

        return cv;
    }
}
