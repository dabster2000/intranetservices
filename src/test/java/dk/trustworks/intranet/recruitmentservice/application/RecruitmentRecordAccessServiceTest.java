package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleAssignment;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringSource;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ResponsibilityKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.WorkstreamStatus;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RecruitmentRecordAccessServiceTest {

    @Inject RecruitmentRecordAccessService recordAccess;

    // ---- OpenRole visibility ----

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void plainReaderNotLeadOrAssigned_cannotSeeOrdinaryRole() {
        String actor = uuid();
        OpenRole role = persistRole(HiringCategory.PRACTICE_CONSULTANT, uuid());

        assertFalse(recordAccess.canSeeOpenRole(role, actor));
        assertFalse(recordAccess.openRolePredicate(actor).test(role));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void teamLead_canSeeOrdinaryRolesForTheirTeam() {
        String actor = uuid();
        String teamUuid = persistTeamWithLeader(actor);
        OpenRole role = persistRole(HiringCategory.PRACTICE_CONSULTANT, teamUuid);

        assertTrue(recordAccess.canSeeOpenRole(role, actor));
        assertTrue(recordAccess.openRolePredicate(actor).test(role));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void partnerOrLeadershipRole_hiddenFromTeamLead_unlessAssigned() {
        String actor = uuid();
        String teamUuid = persistTeamWithLeader(actor);
        OpenRole role = persistRole(HiringCategory.PARTNER_OR_LEADERSHIP, teamUuid);

        assertFalse(recordAccess.canSeeOpenRole(role, actor),
                "PARTNER_OR_LEADERSHIP requires explicit assignment, not team leadership");

        // Now assign and re-check
        RoleAssignment.fresh(role.uuid, actor, ResponsibilityKind.RECRUITMENT_OWNER, actor).persist();
        assertTrue(recordAccess.canSeeOpenRole(role, actor));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "offerer", roles = {"recruitment:read", "recruitment:offer"})
    void offerScope_bypassesAllRecordRestrictions() {
        OpenRole role = persistRole(HiringCategory.PARTNER_OR_LEADERSHIP, uuid());
        assertTrue(recordAccess.canSeeOpenRole(role, uuid()));
        assertTrue(recordAccess.openRolePredicate(uuid()).test(role));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "admin", roles = {"recruitment:read", "recruitment:admin"})
    void adminScope_bypassesAllRecordRestrictions() {
        OpenRole role = persistRole(HiringCategory.PARTNER_OR_LEADERSHIP, uuid());
        assertTrue(recordAccess.canSeeOpenRole(role, uuid()));
        assertTrue(recordAccess.openRolePredicate(uuid()).test(role));
    }

    // ---- Candidate visibility ----

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void candidateWithApplicationOnlyOnHiddenRole_isHidden() {
        String actor = uuid();
        OpenRole hiddenRole = persistRole(HiringCategory.PRACTICE_CONSULTANT, uuid());
        Candidate candidate = persistCandidate(null);
        persistApplication(candidate.uuid, hiddenRole.uuid);

        assertFalse(recordAccess.canSeeCandidate(candidate, actor),
                "Candidate with apps only on roles the actor cannot see should be hidden");
        assertFalse(recordAccess.candidatePredicate(actor).test(candidate));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void candidateOwnedByActor_isVisibleEvenWithoutApplications() {
        String actor = uuid();
        Candidate candidate = persistCandidate(actor);
        assertTrue(recordAccess.canSeeCandidate(candidate, actor));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void candidateWithApplicationOnVisibleRole_isVisible() {
        String actor = uuid();
        String teamUuid = persistTeamWithLeader(actor);
        OpenRole visibleRole = persistRole(HiringCategory.PRACTICE_CONSULTANT, teamUuid);
        Candidate candidate = persistCandidate(null);
        persistApplication(candidate.uuid, visibleRole.uuid);

        assertTrue(recordAccess.canSeeCandidate(candidate, actor));
    }

    // ---- Application visibility ----

    @Test
    @TestTransaction
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void applicationOnHiddenRole_isHidden() {
        String actor = uuid();
        OpenRole hiddenRole = persistRole(HiringCategory.PRACTICE_CONSULTANT, uuid());
        Candidate candidate = persistCandidate(null);
        Application app = persistApplication(candidate.uuid, hiddenRole.uuid);

        assertFalse(recordAccess.canSeeApplication(app, actor));
        assertFalse(recordAccess.applicationPredicate(actor).test(app));
    }

    @Test
    @TestTransaction
    @TestSecurity(user = "offerer", roles = {"recruitment:read", "recruitment:offer"})
    void applicationVisibility_offerScopeBypasses() {
        OpenRole role = persistRole(HiringCategory.PARTNER_OR_LEADERSHIP, uuid());
        Candidate candidate = persistCandidate(null);
        Application app = persistApplication(candidate.uuid, role.uuid);

        assertTrue(recordAccess.canSeeApplication(app, uuid()));
    }

    // ---- Helpers ----

    private static String uuid() { return UUID.randomUUID().toString(); }

    private OpenRole persistRole(HiringCategory category, String teamUuid) {
        OpenRole r = OpenRole.withFreshUuid();
        r.title = "X";
        r.hiringCategory = category;
        r.pipelineKind = category.pipelineKind();
        r.practice = Practice.DEV;
        r.teamUuid = teamUuid;
        r.hiringSource = HiringSource.CAPACITY_GAP;
        r.targetStartDate = LocalDate.now();
        r.status = RoleStatus.SOURCING;
        r.advertisingStatus = WorkstreamStatus.NOT_STARTED;
        r.searchStatus = WorkstreamStatus.NOT_STARTED;
        r.createdByUuid = uuid();
        r.persist();
        return r;
    }

    private Candidate persistCandidate(String ownerUuid) {
        Candidate c = Candidate.withFreshUuid();
        c.firstName = "Pat";
        c.lastName = "Doe";
        c.email = "pat-" + c.uuid + "@example.com";
        c.consentStatus = "GIVEN";
        c.state = CandidateState.ACTIVE;
        c.ownerUserUuid = ownerUuid;
        c.persist();
        return c;
    }

    private Application persistApplication(String candidateUuid, String roleUuid) {
        Application a = Application.withFreshUuid();
        a.candidateUuid = candidateUuid;
        a.roleUuid = roleUuid;
        a.applicationType = ApplicationType.JOB_AD;
        a.stage = ApplicationStage.SOURCED;
        a.persist();
        return a;
    }

    private String persistTeamWithLeader(String userUuid) {
        Team team = new Team();
        team.setUuid(uuid());
        team.setName("Test Team");
        team.persist();

        TeamRole tr = new TeamRole(uuid(), team.getUuid(), userUuid,
                LocalDate.now().minusMonths(1), null, TeamMemberType.LEADER);
        tr.persist();
        return team.getUuid();
    }
}
