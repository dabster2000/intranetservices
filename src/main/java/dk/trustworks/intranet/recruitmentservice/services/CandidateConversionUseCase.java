package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertResponse;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.signing.ports.SigningCaseOwnershipPort;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use case orchestrating the full "candidate -> employee" conversion. The
 * {@link #execute(UUID, ConvertRequest, UUID)} method runs inside a single
 * {@link Transactional} boundary so any failure (e.g. signing-case ownership
 * transfer) rolls the entire conversion back — no partial hires.
 * <p>
 * <b>Two-phase design (efficiency finding H2).</b> The transactional phase
 * does only DB work and exits with {@code sharepoint_move_status = PENDING}.
 * The slow Graph API uploads (~200-500ms per PDF, 4-8 PDFs/appendices) run
 * <em>after</em> the conversion transaction commits, via
 * {@link #runSharePointCopy(UUID)}. Callers are expected to dispatch
 * {@code runSharePointCopy} on a {@code ManagedExecutor} so the HTTP response
 * returns fast and DB row locks on
 * {@code recruitment_candidates / candidate_dossiers /
 * candidate_dossier_revisions / candidate_dossier_appendix /
 * users / team_role / user_status / signing_cases} are released promptly.
 * <p>
 * If the post-commit copy fails (network blip, Graph API throttle,
 * SharePoint outage), {@link
 * dk.trustworks.intranet.recruitmentservice.jobs.SharePointEmployeeFolderMoveBatchlet}
 * picks up the still-{@code PENDING}/{@code PARTIAL}/{@code FAILED} row on
 * its 5-minute cadence and retries — the user-facing conversion succeeds
 * either way.
 *
 * <h3>Conversion steps (transactional)</h3>
 * <ol>
 *   <li>Load candidate; guard ACTIVE state.</li>
 *   <li>Provision a new {@link User} via {@link UserService#createUser}.</li>
 *   <li>Insert {@link UserStatus} with {@link StatusType#PREBOARDING}.</li>
 *   <li>Insert {@link UserCareerLevel}.</li>
 *   <li>Insert {@link TeamRole}.</li>
 *   <li>For each unique signing case key referenced by the candidate's
 *       dossier revisions, transfer local ownership to the new user via
 *       {@link SigningCaseOwnershipPort#transferLocalOwner}.</li>
 *   <li>Call {@link RecruitmentCandidate#markHired}.</li>
 *   <li>Close every OPEN dossier on this candidate.</li>
 *   <li>Set {@code sharepoint_move_status = PENDING}. The post-commit
 *       SharePoint copy is dispatched by the resource layer.</li>
 * </ol>
 *
 * <h3>Post-commit steps ({@link #runSharePointCopy})</h3>
 * <ol>
 *   <li>Resolve target username and template base folder.</li>
 *   <li>Copy every S3-backed PDF and appendix to the destination folder.</li>
 *   <li>In a short follow-up tx, set the final move status and stamp
 *       {@code s3_retention_until} on COMPLETED.</li>
 * </ol>
 */
@JBossLog
@ApplicationScoped
public class CandidateConversionUseCase {

    @Inject
    UserService userService;

    @Inject
    SigningCaseOwnershipPort signingCaseOwnershipPort;

    @Inject
    SharePointEmployeeFolderService sharePointEmployeeFolderService;

    @Transactional
    public ConvertResponse execute(UUID candidateUuid, ConvertRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (req.allocation() < 0 || req.allocation() > 100) {
            throw new IllegalArgumentException("allocation must be between 0 and 100");
        }

        // (a) Load candidate; guard ACTIVE.
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        if (candidate.getStatus() != CandidateStatus.ACTIVE) {
            throw new BusinessRuleViolation(
                    "Cannot convert candidate %s: status is %s, expected ACTIVE"
                            .formatted(candidate.getUuid(), candidate.getStatus()));
        }

        Company company = Company.findById(candidate.getTargetCompanyUuid());
        if (company == null) {
            throw new NotFoundException(
                    "Target company not found: " + candidate.getTargetCompanyUuid());
        }

        // (b) Create User. Mirrors the existing UserService.createUser flow:
        // name + email + username + a generated UUID. The candidate's
        // first/last name come from the recruitment record itself.
        User user = new User();
        user.uuid = UUID.randomUUID().toString();
        user.setFirstname(candidate.getFirstName());
        user.setLastname(candidate.getLastName());
        user.setEmail(req.email());
        user.setUsername(req.username());
        userService.createUser(user);

        // (c) PREBOARDING status row — statusdate = the planned start date
        // so reports like "starts soon" pick up the new joiner.
        UserStatus status = new UserStatus(
                req.consultantType(),
                StatusType.PREBOARDING,
                req.plannedStartDate(),
                req.allocation(),
                user.uuid);
        status.setUuid(UUID.randomUUID().toString());
        status.setCompany(company);
        UserStatus.persist(status);

        // (d) Career level — active_from is the planned start so the
        // user's earliest career-level row aligns with their hire date.
        UserCareerLevel level = new UserCareerLevel(
                user.uuid,
                req.plannedStartDate(),
                req.careerTrack(),
                req.careerLevel());
        UserCareerLevel.persist(level);

        // (e) TeamRole — start_date = planned start, end_date = null (open
        // ended). Mirrors the existing TeamRole shape.
        TeamRole teamRole = new TeamRole(
                UUID.randomUUID().toString(),
                req.teamUuid(),
                user.uuid,
                req.plannedStartDate(),
                null,
                req.teamMemberType());
        TeamRole.persist(teamRole);

        // (f) Transfer signing-case ownership for every signing_case_key
        // referenced by this candidate's dossier revisions. Distinct keys
        // only — a single case may be referenced by multiple revisions
        // (e.g. resends).
        List<String> caseKeys = collectSigningCaseKeys(candidate.getUuid());
        for (String caseKey : caseKeys) {
            signingCaseOwnershipPort.transferLocalOwner(caseKey, UUID.fromString(user.uuid));
        }

        // (g) Domain transition — guards re-checked inside the entity.
        candidate.markHired(UUID.fromString(user.uuid), actor);

        // (h) Close any still-OPEN dossiers.
        List<CandidateDossier> openDossiers = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND status = ?2",
                        candidate.getUuid(), DossierStatus.OPEN)
                .list();
        for (CandidateDossier d : openDossiers) {
            d.closeOnTerminal();
        }

        // (i) Mark SharePoint move as PENDING. The post-commit copy runs in
        //     RecruitmentResource via runSharePointCopy(...) on a managed
        //     executor, so the conversion REST call returns fast and DB locks
        //     are released promptly. The retry batchlet
        //     (SharePointEmployeeFolderMoveBatchlet) handles failures.
        candidate.setSharepointMoveStatus(SharePointMoveStatus.PENDING);

        log.infof("Converted candidate uuid=%s -> user uuid=%s by actor=%s (signing cases transferred=%d)",
                candidate.getUuid(), user.uuid, actor, caseKeys.size());

        return ConvertResponse.hired(user.uuid, candidate.getUuid(), caseKeys.size());
    }

    /**
     * Run the post-commit SharePoint copy for a hired candidate. NOT
     * transactional at the method level — the SharePoint upload runs without
     * holding DB locks. The status update + retention stamping happens in a
     * short follow-up transaction via {@link #applySharePointResult}.
     *
     * <p>Safe to call from a {@code ManagedExecutor} after {@link #execute}
     * returns. The retry batchlet
     * ({@link dk.trustworks.intranet.recruitmentservice.jobs.SharePointEmployeeFolderMoveBatchlet})
     * will pick up {@code PENDING}/{@code PARTIAL}/{@code FAILED} rows on its
     * 5-minute cadence if this method fails, so callers do not need to retry.
     */
    public void runSharePointCopy(UUID candidateUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null || candidate.getStatus() != CandidateStatus.HIRED) {
            log.warnf("runSharePointCopy: candidate=%s not HIRED, skipping", candidateUuid);
            return;
        }
        if (candidate.getSharepointMoveStatus() == SharePointMoveStatus.COMPLETED) {
            log.debugf("runSharePointCopy: candidate=%s already COMPLETED, skipping", candidateUuid);
            return;
        }

        String targetUsername = resolveTargetUsername(candidate);
        if (targetUsername == null) {
            log.warnf("runSharePointCopy: cannot resolve username for candidate=%s, leaving PENDING",
                    candidateUuid);
            return;
        }
        String userUuid = candidate.getConvertedUserUuid();
        SharePointLocationEntity location = userUuid == null
                ? null : sharePointEmployeeFolderService.resolveEmployeeLocation(userUuid);
        if (location == null) {
            log.warnf("runSharePointCopy: no active EMPLOYEE SharePointLocation for promoted user "
                    + "(candidate=%s, user=%s) — leaving PENDING. Configure (company, EMPLOYEE) "
                    + "in admin/settings SharePoint locations; the retry batchlet will pick this up.",
                    candidateUuid, userUuid);
            return;
        }

        SharePointMoveStatus moveStatus;
        try {
            moveStatus = sharePointEmployeeFolderService.copyToEmployeeFolder(
                    candidate, targetUsername, location);
        } catch (RuntimeException e) {
            log.warnf(e, "runSharePointCopy: SharePoint copy threw for candidate=%s — staying PENDING for retry",
                    candidateUuid);
            return;
        }
        applySharePointResult(candidateUuid, moveStatus);
    }

    /**
     * Persist the outcome of a SharePoint copy attempt. Runs inside a short
     * transaction so the row is locked only for the status write + (on
     * COMPLETED) retention stamping — the slow Graph upload is already done
     * by the time this method is called.
     */
    @Transactional
    public void applySharePointResult(UUID candidateUuid, SharePointMoveStatus status) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        Objects.requireNonNull(status, "status must not be null");
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            log.warnf("applySharePointResult: candidate=%s not found, cannot update status",
                    candidateUuid);
            return;
        }
        candidate.setSharepointMoveStatus(status);
        if (status == SharePointMoveStatus.COMPLETED) {
            sharePointEmployeeFolderService.stampS3RetentionUntil(candidate);
        }
    }

    private static String resolveTargetUsername(RecruitmentCandidate candidate) {
        if (candidate.getConvertedUserUuid() == null) return null;
        User user = User.findById(candidate.getConvertedUserUuid());
        return user != null ? user.getUsername() : null;
    }

    /**
     * Pull the distinct {@code signing_case_key} values from every revision
     * across every dossier belonging to the candidate. Returns an ordered
     * list (Set iteration order is undefined; we want stability for the
     * audit log).
     */
    private List<String> collectSigningCaseKeys(String candidateUuid) {
        return CandidateDossierRevision.findByCandidate(candidateUuid).stream()
                .map(CandidateDossierRevision::getSigningCaseKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }
}
