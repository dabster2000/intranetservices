package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;

import java.util.Map;

/**
 * The validated command built by {@code PublicApplyResource} from a public
 * multipart submission (P5) and executed by {@code PublicApplyService}.
 * Every field has already passed the resource-level validation ({@code
 * @Valid} is inert in this repo — validation is explicit resource code):
 * required fields present, lengths capped, enums parsed, files
 * magic-byte-checked. The service trusts the shape and applies the domain
 * rules (dedupe, invariants, events).
 *
 * @param firstName           required, trimmed, ≤100
 * @param lastName            required, trimmed, ≤100
 * @param email               required, trimmed, valid format, ≤150
 * @param phone               optional, trimmed-to-null, ≤40
 * @param linkedinUrl         optional, trimmed-to-null, ≤300
 * @param educationLevel      optional
 * @param educationOther      optional, trimmed-to-null, ≤200
 * @param experienceLevel     optional
 * @param channel             the entry-channel source (WEBSITE default)
 * @param selfReportedSource  optional, one of the allowed self-reported
 *                            source names (validated), or {@code null}
 * @param sourceFollowUp      optional free-text follow-up, ≤200; mapped to
 *                            a source-detail key by the service
 * @param answers             non-blank answers keyed by question code, in
 *                            question order; each ≤10 000 chars
 * @param poolConsent         true iff the talent-pool checkbox was ticked
 * @param cv                  required document (PDF/JPEG/PNG, ≤10 MiB)
 * @param coverLetter         optional document, same constraints
 * @param desiredPracticeUuid unsolicited form only; validated against the
 *                            practice registry by the service (garbage is
 *                            silently dropped)
 */
public record PublicApplySubmission(
        String firstName,
        String lastName,
        String email,
        String phone,
        String linkedinUrl,
        CandidateEducationLevel educationLevel,
        String educationOther,
        CandidateExperienceLevel experienceLevel,
        CandidateSource channel,
        String selfReportedSource,
        String sourceFollowUp,
        Map<String, String> answers,
        boolean poolConsent,
        UploadedDocument cv,
        UploadedDocument coverLetter,
        String desiredPracticeUuid
) {

    /**
     * One validated uploaded file.
     *
     * @param bytes        the file bytes (≤10 MiB, magic-byte-verified)
     * @param filename     the client-supplied name (PII — event pii only),
     *                     length-capped by the resource
     * @param safeFilename the sanitised name used as the S3 object name
     * @param contentType  the normalised, allowlisted MIME type
     */
    public record UploadedDocument(byte[] bytes, String filename, String safeFilename, String contentType) {
    }
}
