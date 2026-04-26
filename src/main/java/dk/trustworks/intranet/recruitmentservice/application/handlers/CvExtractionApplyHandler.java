package dk.trustworks.intranet.recruitmentservice.application.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Apply-handler for {@link AiArtifactKind#CV_EXTRACTION}: when a reviewed CV-extraction
 * artifact is accepted (or edited+overridden) by a recruiter, patch matching
 * <em>blank</em> fields on the {@link Candidate} aggregate.
 *
 * <p>Per spec §9.2, this only patches NULL/blank candidate fields. Existing recruiter
 * input is never overwritten — accept is additive, not destructive. The reviewer's
 * {@code overrideJson} (when present) takes precedence over the AI output.
 *
 * <p>Skills, languages, certifications and work history are intentionally not copied
 * onto the candidate row — they stay in {@code artifact.output} for the AI sidecar
 * and the recruiter can manually surface chosen items into {@code candidate.tags}.
 */
@ApplicationScoped
public class CvExtractionApplyHandler implements AiArtifactApplyHandler {

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public boolean handles(AiArtifactKind kind) {
        return kind == AiArtifactKind.CV_EXTRACTION;
    }

    @Override
    @Transactional
    public void apply(AiArtifact artifact, String overrideJson) {
        Candidate c = Candidate.findById(artifact.subjectUuid);
        if (c == null) return;

        try {
            JsonNode source = json.readTree(overrideJson != null ? overrideJson : artifact.output);
            patchIfBlankOn(c, source, "firstName", x -> x.firstName, (x, v) -> x.firstName = v);
            patchIfBlankOn(c, source, "lastName",  x -> x.lastName,  (x, v) -> x.lastName = v);
            patchIfBlankOn(c, source, "email",     x -> x.email,     (x, v) -> x.email = v);
            patchIfBlankOn(c, source, "phone",     x -> x.phone,     (x, v) -> x.phone = v);
        } catch (Exception e) {
            throw new IllegalStateException(
                "CV_EXTRACTION apply failed for candidate " + c.uuid, e);
        }

        // Note: skills/languages/certifications/workHistory are NOT copied to Candidate.
        // They live as artifact.output for the AI sidecar to display; recruiter can
        // separately add chips to candidate.tags if they want them surfaced on the candidate row.
    }

    private void patchIfBlankOn(Candidate c, JsonNode source, String field,
                                Function<Candidate, String> getter,
                                BiConsumer<Candidate, String> setter) {
        if (!source.has(field) || source.get(field).isNull()) return;
        String existing = getter.apply(c);
        if (existing != null && !existing.isBlank()) return;
        String value = source.get(field).asText();
        if (value == null || value.isBlank()) return;
        setter.accept(c, value);
    }
}
