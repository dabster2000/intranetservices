package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Typed projection of {@code AiArtifact.output} for {@code INTERVIEW_KIT} artifacts.
 *
 * <p>Mirrors {@code META-INF/recruitment/schemas/interview-kit-v1.json}. The
 * frontend deserialises the artifact's raw {@code output} string into this
 * shape via the BFF route. Backend handlers do NOT need to project through
 * this DTO — the apply-handler ({@link
 * dk.trustworks.intranet.recruitmentservice.application.handlers.InterviewKitApplyHandler})
 * only stamps the artifact pointer onto the Interview row; the JSON payload
 * stays opaque on the backend and is rendered client-side.
 */
public class InterviewKitOutput {
    public String objective;
    public List<String> focusAreas;
    public List<KitQuestion> questions;
    public Map<String, BigDecimal> scorecardWeights;
    public List<String> redFlags;

    public static class KitQuestion {
        public String category;
        public String question;
        public String rubric;
    }
}
