package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.SharePointPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopSharePointPort implements SharePointPort {
}
