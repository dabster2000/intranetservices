package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleAssignment;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringSource;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ResponsibilityKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the Slice 3a auto-assign-creator wiring on POST /roles and POST /candidates.
 *
 * <p>Sandbox-blocked Quarkus tests — test-compile is the bar in CI; the in-DB
 * behavior is exercised by the Phase G end-to-end suite.
 */
@QuarkusTest
class AutoAssignCreatorTest {

    @Inject OpenRoleService openRoleService;
    @Inject CandidateService candidateService;

    @Test
    @TestTransaction
    void create_role_alsoInsertsRecruitmentOwnerAssignment() {
        String actor = UUID.randomUUID().toString();
        OpenRole input = baseDraft();
        OpenRole role = openRoleService.create(input, actor);

        long count = RoleAssignment.count(
                "roleUuid = ?1 and userUuid = ?2 and responsibilityKind = ?3",
                role.uuid, actor, ResponsibilityKind.RECRUITMENT_OWNER);
        assertEquals(1, count, "creator should be auto-assigned as RECRUITMENT_OWNER");
    }

    @Test
    @TestTransaction
    void create_candidate_defaultsOwnerUserUuidToActor_whenBlank() {
        String actor = UUID.randomUUID().toString();
        Candidate input = Candidate.withFreshUuid();
        input.firstName = "Auto";
        input.lastName = "Owner";
        input.email = "auto-owner-" + input.uuid + "@example.com";
        input.consentStatus = "GIVEN";
        // ownerUserUuid intentionally null

        Candidate created = candidateService.create(input, actor);

        assertNotNull(created.ownerUserUuid);
        assertEquals(actor, created.ownerUserUuid);
    }

    @Test
    @TestTransaction
    void create_candidate_preservesExplicitOwnerUserUuid() {
        String actor = UUID.randomUUID().toString();
        String explicitOwner = UUID.randomUUID().toString();
        Candidate input = Candidate.withFreshUuid();
        input.firstName = "Explicit";
        input.lastName = "Owner";
        input.email = "explicit-owner-" + input.uuid + "@example.com";
        input.consentStatus = "GIVEN";
        input.ownerUserUuid = explicitOwner;

        Candidate created = candidateService.create(input, actor);

        assertEquals(explicitOwner, created.ownerUserUuid,
                "an explicit owner must NOT be overwritten by the actor");
    }

    private OpenRole baseDraft() {
        OpenRole r = OpenRole.withFreshUuid();
        r.title = "Auto-assign role";
        r.hiringCategory = HiringCategory.PRACTICE_CONSULTANT;
        r.pipelineKind = PipelineKind.CONSULTANT;
        r.practice = Practice.DEV;
        r.teamUuid = UUID.randomUUID().toString();
        r.hiringSource = HiringSource.CAPACITY_GAP;
        r.hiringReason = "Capacity gap";
        r.targetStartDate = LocalDate.now().plusMonths(2);
        r.status = RoleStatus.DRAFT;
        r.createdByUuid = UUID.randomUUID().toString();
        return r;
    }
}
