package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleAssignment;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleHistory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.InvalidTransitionException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OpenRoleServiceTest {

    @Inject OpenRoleService service;

    private OpenRole baseDraft() {
        OpenRole r = OpenRole.withFreshUuid();
        r.title = "Senior DEV consultant";
        r.hiringCategory = HiringCategory.PRACTICE_CONSULTANT;
        r.pipelineKind = PipelineKind.CONSULTANT;
        r.practice = Practice.DEV;
        r.teamUuid = UUID.randomUUID().toString();
        r.hiringSource = HiringSource.CAPACITY_GAP;
        r.hiringReason = "Capacity gap in DEV";
        r.targetStartDate = LocalDate.now().plusMonths(2);
        r.status = RoleStatus.DRAFT;
        r.createdByUuid = UUID.randomUUID().toString();
        return r;
    }

    @Test
    @TestTransaction
    void createsRoleAndStampsHistory() {
        String actor = UUID.randomUUID().toString();
        OpenRole created = service.create(baseDraft(), actor);
        assertEquals(RoleStatus.DRAFT, created.status);
        assertEquals(1, RoleHistory.find("roleUuid", created.uuid).list().size());
    }

    @Test
    @TestTransaction
    void assigningRecruitmentOwnerAdvancesDraftToSourcing() {
        OpenRole role = service.create(baseDraft(), UUID.randomUUID().toString());
        service.addAssignment(role.uuid, UUID.randomUUID().toString(),
                ResponsibilityKind.RECRUITMENT_OWNER, UUID.randomUUID().toString());

        OpenRole reloaded = OpenRole.findById(role.uuid);
        assertEquals(RoleStatus.SOURCING, reloaded.status);
    }

    @Test
    @TestTransaction
    void pausingRequiresReason() {
        OpenRole role = service.create(baseDraft(), UUID.randomUUID().toString());
        service.addAssignment(role.uuid, UUID.randomUUID().toString(),
                ResponsibilityKind.RECRUITMENT_OWNER, UUID.randomUUID().toString());

        jakarta.ws.rs.BadRequestException ex = assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> service.transition(role.uuid, RoleStatus.PAUSED, " ", UUID.randomUUID().toString()));
        assertTrue(ex.getMessage().toLowerCase().contains("reason"));
    }

    @Test
    @TestTransaction
    void illegalTransitionRaisesInvalidTransitionException() {
        OpenRole role = service.create(baseDraft(), UUID.randomUUID().toString());
        assertThrows(InvalidTransitionException.class,
                () -> service.transition(role.uuid, RoleStatus.FILLED, "skip", UUID.randomUUID().toString()));
    }
}
