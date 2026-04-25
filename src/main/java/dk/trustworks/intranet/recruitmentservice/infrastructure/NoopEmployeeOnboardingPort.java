package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.EmployeeOnboardingPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopEmployeeOnboardingPort implements EmployeeOnboardingPort {
}
