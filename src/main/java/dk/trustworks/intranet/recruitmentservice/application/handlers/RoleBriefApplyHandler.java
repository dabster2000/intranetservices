package dk.trustworks.intranet.recruitmentservice.application.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Apply-handler for {@link AiArtifactKind#ROLE_BRIEF}: when a reviewed role-brief
 * artifact is accepted (or edited+overridden) by a recruiter, render the brief
 * sections (responsibilities, must-haves, nice-to-haves, ad copy, risks) as a
 * multi-section markdown document and write it onto {@link OpenRole#hiringReason}.
 *
 * <p>Per spec §9.2, the brief output is patched into the role's existing
 * {@code hiring_reason} TEXT column; a dedicated {@code brief_json} column is
 * deferred to a later slice. The reviewer's {@code overrideJson} (when present)
 * takes precedence over the AI output.
 */
@ApplicationScoped
public class RoleBriefApplyHandler implements AiArtifactApplyHandler {

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public boolean handles(AiArtifactKind kind) {
        return kind == AiArtifactKind.ROLE_BRIEF;
    }

    @Override
    @Transactional
    public void apply(AiArtifact artifact, String overrideJson) {
        OpenRole r = OpenRole.findById(artifact.subjectUuid);
        if (r == null) return;

        try {
            JsonNode src = json.readTree(overrideJson != null ? overrideJson : artifact.output);
            StringBuilder md = new StringBuilder();
            section(md, "Responsibilities", src.get("responsibilities"));
            section(md, "Must-haves",       src.get("mustHaves"));
            section(md, "Nice-to-haves",    src.get("niceToHaves"));
            if (src.hasNonNull("adCopyDraft")) {
                String adCopy = src.get("adCopyDraft").asText();
                if (adCopy != null && !adCopy.isBlank()) {
                    md.append("## Ad copy (draft)\n\n").append(adCopy).append("\n\n");
                }
            }
            section(md, "Risks", src.get("risks"));
            r.hiringReason = md.toString().trim();
        } catch (Exception e) {
            throw new IllegalStateException("ROLE_BRIEF apply failed for role " + r.uuid, e);
        }
    }

    private void section(StringBuilder md, String title, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return;
        md.append("## ").append(title).append("\n\n");
        arr.forEach(item -> md.append("- ").append(item.asText()).append("\n"));
        md.append("\n");
    }
}
