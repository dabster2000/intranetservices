package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.NextSignPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopNextSignPort implements NextSignPort {
}
