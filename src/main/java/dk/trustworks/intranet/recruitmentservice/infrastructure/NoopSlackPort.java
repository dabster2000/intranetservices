package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.SlackPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopSlackPort implements SlackPort {
}
