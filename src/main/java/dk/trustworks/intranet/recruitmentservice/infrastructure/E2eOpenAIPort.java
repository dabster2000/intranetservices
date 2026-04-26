package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Map;

/**
 * Deterministic {@link OpenAIPort} for Playwright end-to-end tests. Active only
 * under {@code -Dquarkus.profile=dev-e2e}. Returns hand-crafted JSON payloads
 * matching the recruitment AI schemas so the golden-path spec (TAM uploads CV →
 * accepts extracted fields → generates summary; TAM generates role brief) can
 * assert on stable text without burning OpenAI tokens or introducing flakiness.
 *
 * <p>Resolution order at runtime:
 * <ul>
 *   <li>{@code NoopOpenAIPort} {@code @Priority(1)}</li>
 *   <li>{@code OpenAIPortImpl} {@code @Priority(10)} — beats Noop when AI is enabled</li>
 *   <li>{@code E2eOpenAIPort} {@code @Priority(100)} — beats both, but only built into
 *       the {@code dev-e2e} profile thanks to {@link IfBuildProfile}.</li>
 * </ul>
 */
@IfBuildProfile("dev-e2e")
@ApplicationScoped
@Alternative
@Priority(100)
public class E2eOpenAIPort implements OpenAIPort {

    @Override
    public GenerateResult generate(String promptId, String promptVersion,
                                   Map<String, Object> inputs, String outputSchema) {
        if (promptId.startsWith("cv-extraction")) {
            return new GenerateResult(
                "{\"firstName\":\"Alice\",\"lastName\":\"Example\",\"email\":\"alice@example.com\",\"phone\":\"+45 12345678\",\"yearsOfExperience\":5,\"skills\":[\"Java\",\"AWS\"],\"languages\":[\"en\",\"da\"],\"workHistory\":[{\"company\":\"Acme Consulting\",\"title\":\"Senior Consultant\",\"startMonth\":\"2020-01\",\"endMonth\":null}],\"evidence\":[{\"field\":\"firstName\",\"snippet\":\"Alice Example\"}]}",
                "[]", "e2e-stub");
        }
        if (promptId.startsWith("role-brief")) {
            return new GenerateResult(
                "{\"responsibilities\":[\"Lead delivery for client engagements\",\"Mentor junior consultants\"],\"mustHaves\":[\"5+ yrs Java\",\"AWS\"],\"niceToHaves\":[\"Kubernetes\"],\"adCopyDraft\":\"Are you a senior Java engineer ready to lead delivery? Trustworks is hiring.\",\"risks\":[\"Tight market for senior Java in DK\"]}",
                "[]", "e2e-stub");
        }
        if (promptId.startsWith("candidate-summary")) {
            return new GenerateResult(
                "{\"summaryParagraph\":\"Solid mid-level fit; 5 yrs Java with AWS exposure aligns with DEV practice needs.\",\"practiceMatchScore\":0.85,\"levelMatchScore\":0.7,\"consultingPotential\":0.6,\"concerns\":[\"Limited client-facing exposure\"],\"evidenceCitations\":[\"cv:Java 5 yrs at Acme\"]}",
                "[]", "e2e-stub");
        }
        return new GenerateResult("{}", "[]", "e2e-stub");
    }
}
