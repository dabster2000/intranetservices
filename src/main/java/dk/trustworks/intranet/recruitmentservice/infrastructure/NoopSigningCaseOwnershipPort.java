package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.SigningCaseOwnershipPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopSigningCaseOwnershipPort implements SigningCaseOwnershipPort {
}
