package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Map;

@ApplicationScoped
@Alternative
@Priority(1)  // overridden by OpenAIPortImpl (higher @Priority) when ai is enabled
public class NoopOpenAIPort implements OpenAIPort {
    @Override
    public GenerateResult generate(String promptId, String promptVersion,
                                   Map<String, Object> inputs, String outputSchema) {
        throw new IllegalStateException(
            "NoopOpenAIPort hit — wire OpenAIPortImpl or a test fake. promptId=" + promptId);
    }
}
