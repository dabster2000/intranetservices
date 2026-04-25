package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.EmailPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopEmailPort implements EmailPort {
}
