package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class OpenRoleEntityTest {

    @Test
    @TestTransaction
    void persistsWithDefaults() {
        OpenRole role = new OpenRole();
        role.uuid = UUID.randomUUID().toString();
        role.title = "Senior DEV consultant";
        role.hiringCategory = HiringCategory.PRACTICE_CONSULTANT;
        role.pipelineKind = PipelineKind.CONSULTANT;
        role.practice = Practice.DEV;
        role.teamUuid = UUID.randomUUID().toString();
        role.hiringSource = HiringSource.CAPACITY_GAP;
        role.hiringReason = "Two pipeline wins need delivery muscle";
        role.targetStartDate = LocalDate.now().plusMonths(3);
        role.status = RoleStatus.DRAFT;
        role.advertisingStatus = WorkstreamStatus.NOT_STARTED;
        role.searchStatus = WorkstreamStatus.NOT_STARTED;
        role.createdByUuid = UUID.randomUUID().toString();
        role.persist();

        OpenRole loaded = OpenRole.findById(role.uuid);
        assertNotNull(loaded);
        assertEquals(RoleStatus.DRAFT, loaded.status);
        assertEquals(WorkstreamStatus.NOT_STARTED, loaded.advertisingStatus);
    }
}
