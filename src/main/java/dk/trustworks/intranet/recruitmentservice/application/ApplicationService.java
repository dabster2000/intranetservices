package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.*;
import dk.trustworks.intranet.recruitmentservice.domain.enums.*;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.ApplicationStateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class ApplicationService {

    @Inject CandidateLifecycleService lifecycle;

    @Transactional
    public Application create(String candidateUuid, String roleUuid, ApplicationType type,
                              String referrerUserUuid, String actorUuid) {
        OpenRole role = OpenRole.findById(roleUuid);
        if (role == null) throw new NotFoundException("OpenRole " + roleUuid);
        Candidate candidate = Candidate.findById(candidateUuid);
        if (candidate == null) throw new NotFoundException("Candidate " + candidateUuid);
        if (role.status == RoleStatus.PAUSED || role.status == RoleStatus.CANCELLED || role.status == RoleStatus.FILLED) {
            throw new WebApplicationException(
                    "Role " + roleUuid + " is " + role.status + " — cannot accept new applications",
                    Response.Status.CONFLICT);
        }

        long existingActive = Application.count(
                "candidateUuid = ?1 and roleUuid = ?2 and stage not in (?3, ?4, ?5, ?6)",
                candidateUuid, roleUuid,
                ApplicationStage.REJECTED, ApplicationStage.WITHDRAWN, ApplicationStage.TALENT_POOL,
                ApplicationStage.CONVERTED);
        if (existingActive > 0) {
            throw new WebApplicationException(
                    "Candidate already has an active application for this role",
                    Response.Status.CONFLICT);
        }

        Application a = Application.withFreshUuid();
        a.candidateUuid = candidateUuid;
        a.roleUuid = roleUuid;
        a.applicationType = type;
        a.referrerUserUuid = referrerUserUuid;
        a.stage = ApplicationStage.SOURCED;
        a.lastStageChangeAt = LocalDateTime.now();
        a.persist();
        ApplicationStageHistory.entry(a.uuid, null, ApplicationStage.SOURCED,
                "Application created", actorUuid).persist();

        if (candidate.state == CandidateState.NEW || candidate.state == CandidateState.TALENT_POOL) {
            candidate.state = CandidateState.ACTIVE;
            candidate.addedToPoolAt = null;
            candidate.updatedAt = LocalDateTime.now();
        }
        return a;
    }

    public Application find(String uuid) {
        Application a = Application.findById(uuid);
        if (a == null) throw new NotFoundException("Application " + uuid);
        return a;
    }

    @Transactional
    public Application transition(String applicationUuid, ApplicationStage to, String reason, String actorUuid) {
        Application a = find(applicationUuid);
        OpenRole role = OpenRole.findById(a.roleUuid);
        Candidate candidate = Candidate.findById(a.candidateUuid);
        if (role == null) throw new NotFoundException("OpenRole " + a.roleUuid);
        if (candidate == null) throw new NotFoundException("Candidate " + a.candidateUuid);
        if (role.status == RoleStatus.PAUSED || role.status == RoleStatus.CANCELLED || role.status == RoleStatus.FILLED) {
            throw new WebApplicationException(
                    "Role " + role.uuid + " is " + role.status + " - cannot transition applications",
                    Response.Status.CONFLICT);
        }
        if (to == ApplicationStage.ACCEPTED || to == ApplicationStage.CONVERTED) {
            throw new WebApplicationException(
                    to + " is derived from offer acceptance/conversion, not the generic transition endpoint",
                    Response.Status.CONFLICT);
        }
        if (to == ApplicationStage.OFFER) {
            if (role.status != RoleStatus.SOURCING && role.status != RoleStatus.INTERVIEWING) {
                throw new WebApplicationException("Offers require an active sourcing/interviewing role", Response.Status.CONFLICT);
            }
            if (!"GIVEN".equals(candidate.consentStatus)) {
                throw new WebApplicationException("Candidate consent must be GIVEN before OFFER", Response.Status.CONFLICT);
            }
        }
        ApplicationStateMachine.assertTransitionAllowed(a.stage, to, role.pipelineKind);

        ApplicationStage previous = a.stage;
        a.stage = to;
        a.lastStageChangeAt = LocalDateTime.now();
        a.updatedAt = LocalDateTime.now();
        ApplicationStageHistory.entry(applicationUuid, previous, to, reason, actorUuid).persist();

        lifecycle.onApplicationStageChanged(a.candidateUuid, to);
        return a;
    }

    @Transactional
    Application markAcceptedFromOffer(String applicationUuid, String actorUuid) {
        Application a = find(applicationUuid);
        OpenRole role = OpenRole.findById(a.roleUuid);
        if (role == null) throw new NotFoundException("OpenRole " + a.roleUuid);
        if (role.status == RoleStatus.PAUSED || role.status == RoleStatus.CANCELLED || role.status == RoleStatus.FILLED) {
            throw new WebApplicationException(
                    "Role " + role.uuid + " is " + role.status + " - cannot accept offers",
                    Response.Status.CONFLICT);
        }
        ApplicationStateMachine.assertTransitionAllowed(a.stage, ApplicationStage.ACCEPTED, role.pipelineKind);
        guardNoOtherAccepted(a);
        ApplicationStage previous = a.stage;
        a.stage = ApplicationStage.ACCEPTED;
        a.acceptedAt = LocalDateTime.now();
        a.lastStageChangeAt = a.acceptedAt;
        a.updatedAt = a.acceptedAt;
        ApplicationStageHistory.entry(applicationUuid, previous, ApplicationStage.ACCEPTED,
                "Offer accepted", actorUuid).persist();
        lifecycle.onApplicationStageChanged(a.candidateUuid, ApplicationStage.ACCEPTED);
        return a;
    }

    private void guardNoOtherAccepted(Application a) {
        long otherAccepted = Application.count(
                "candidateUuid = ?1 and stage = ?2 and uuid <> ?3",
                a.candidateUuid, ApplicationStage.ACCEPTED, a.uuid);
        if (otherAccepted > 0) {
            throw new WebApplicationException(
                    "Candidate already has another accepted application - close it before accepting another",
                    Response.Status.CONFLICT);
        }
    }

    @Transactional
    public Application withdraw(String applicationUuid, String reason, String actorUuid) {
        return transition(applicationUuid, ApplicationStage.WITHDRAWN, reason, actorUuid);
    }

    @Transactional
    public Application recordScreeningDecision(String applicationUuid, String outcome,
                                               String overrideReason, String actorUuid) {
        Application a = find(applicationUuid);
        a.screeningOutcome = outcome;
        a.screeningOverrideReason = overrideReason;
        a.screeningDecidedByUuid = actorUuid;
        a.screeningDecidedAt = LocalDateTime.now();
        a.updatedAt = LocalDateTime.now();
        return a;
    }

    public List<Application> listForRole(String roleUuid) {
        return Application.find("roleUuid", roleUuid).list();
    }
}
