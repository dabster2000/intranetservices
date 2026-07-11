package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig;
import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig.Mode;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Boots Quarkus to validate the config mapping, cache interceptors, and WorkService CDI graph. */
@QuarkusTest
class TimesheetRuleEnforcementConfigTest {

    @Inject
    TimesheetRuleEnforcementConfig config;

    @Inject
    WorkService workService;

    @Test
    void defaultsOffAndApplicationGraphBuilds() {
        assertEquals(Mode.OFF, config.mode());
        assertNotNull(workService);
    }
}
