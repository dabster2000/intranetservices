package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.OpenAIPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopOpenAIPort implements OpenAIPort {
}
