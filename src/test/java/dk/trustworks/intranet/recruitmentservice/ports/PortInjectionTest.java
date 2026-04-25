package dk.trustworks.intranet.recruitmentservice.ports;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class PortInjectionTest {

    @Inject EmployeeOnboardingPort employeeOnboardingPort;
    @Inject NextSignPort nextSignPort;
    @Inject SigningCaseOwnershipPort signingCaseOwnershipPort;
    @Inject SharePointPort sharePointPort;
    @Inject OutlookCalendarPort outlookCalendarPort;
    @Inject SlackPort slackPort;
    @Inject EmailPort emailPort;
    @Inject OpenAIPort openAIPort;
    @Inject CvToolPort cvToolPort;

    @Test
    void allNinePortsResolveToABean() {
        assertNotNull(employeeOnboardingPort);
        assertNotNull(nextSignPort);
        assertNotNull(signingCaseOwnershipPort);
        assertNotNull(sharePointPort);
        assertNotNull(outlookCalendarPort);
        assertNotNull(slackPort);
        assertNotNull(emailPort);
        assertNotNull(openAIPort);
        assertNotNull(cvToolPort);
    }
}
