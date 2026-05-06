package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertResponse;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use case orchestrating the full "candidate -> employee" conversion. All
 * steps execute inside a single {@link Transactional} boundary so any
 * failure (e.g. signing-case ownership transfer) rolls the entire
 * conversion back — no partial hires.
 * <p>
 * The SharePoint folder copy now runs synchronously inside this transaction.
 * On {@link SharePointMoveStatus#COMPLETED}, every revision and appendix
 * with S3-stored bytes is stamped with {@code s3_retention_until = NOW + 30 days}
 * so the {@code S3RetentionCleanupBatchlet} can reap the originals after the
 * retention window. On {@link SharePointMoveStatus#PARTIAL} or
 * {@link SharePointMoveStatus#FAILED}, retention stays NULL and the batchlet
 * (Task 9) retries the move.
 *
 * <h3>Steps</h3>
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
 *   <li>Resolve template's sharepoint_folder; PENDING if blank.</li>
 *   <li>Synchronous SharePoint copy via SharePointEmployeeFolderService.</li>
 *   <li>On COMPLETED, stamp s3_retention_until on revisions and appendices.</li>
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

        // (i) Resolve the destination folder. The template's sharepoint_folder
        //     is required at promote time; if blank, leave PENDING so an
        //     operator can fix the template and retry via the batchlet.
        CandidateDossier promoteDossier = CandidateDossier
                .<CandidateDossier>find("candidateUuid", candidate.getUuid())
                .firstResult();
        DocumentTemplateEntity template = promoteDossier != null
                ? DocumentTemplateEntity.<DocumentTemplateEntity>findById(promoteDossier.getTemplateUuid())
                : null;
        String baseFolder = template != null ? template.getSharepointFolder() : null;

        if (baseFolder == null || baseFolder.isBlank()) {
            log.warnf("Template sharepoint_folder is blank for candidate=%s template=%s — " +
                            "leaving sharepoint_move_status=PENDING for operator follow-up",
                    candidate.getUuid(),
                    promoteDossier != null ? promoteDossier.getTemplateUuid() : "null");
            candidate.setSharepointMoveStatus(SharePointMoveStatus.PENDING);
            return ConvertResponse.hired(user.uuid, candidate.getUuid(), caseKeys.size());
        }

        // (j) Synchronous SharePoint copy attempt. The batchlet retries
        //     PENDING / FAILED / PARTIAL rows on a schedule.
        SharePointMoveStatus moveStatus;
        try {
            moveStatus = sharePointEmployeeFolderService.copyToEmployeeFolder(
                    candidate, user.getUsername(), baseFolder);
        } catch (RuntimeException e) {
            log.warnf(e, "SharePoint copy threw for candidate=%s — marking PENDING for batchlet retry",
                    candidate.getUuid());
            moveStatus = SharePointMoveStatus.PENDING;
        }
        candidate.setSharepointMoveStatus(moveStatus);

        // (k) On full COMPLETED only: stamp s3_retention_until on every
        //     revision and appendix referencing S3 storage. PARTIAL leaves
        //     retention NULL so the reaper does not strand a row that the
        //     batchlet may still need to upload.
        if (moveStatus == SharePointMoveStatus.COMPLETED) {
            LocalDateTime retentionUntil = LocalDateTime.now().plusDays(30);
            CandidateDossierRevision.update(
                    "s3RetentionUntil = ?1 WHERE dossierUuid IN " +
                    "(SELECT d.uuid FROM CandidateDossier d WHERE d.candidateUuid = ?2) " +
                    "AND generatedPdfsSnapshot IS NOT NULL",
                    retentionUntil, candidate.getUuid());
            CandidateDossierAppendix.update(
                    "s3RetentionUntil = ?1 WHERE dossierUuid IN " +
                    "(SELECT d.uuid FROM CandidateDossier d WHERE d.candidateUuid = ?2) " +
                    "AND fileUuid IS NOT NULL",
                    retentionUntil, candidate.getUuid());
            log.infof("Stamped s3_retention_until=%s on all S3-backed rows for candidate=%s",
                    retentionUntil, candidate.getUuid());
        }

        log.infof("Converted candidate uuid=%s -> user uuid=%s by actor=%s (signing cases transferred=%d)",
                candidate.getUuid(), user.uuid, actor, caseKeys.size());

        return ConvertResponse.hired(user.uuid, candidate.getUuid(), caseKeys.size());
    }

    /**
     * Pull the distinct {@code signing_case_key} values from every revision
     * across every dossier belonging to the candidate. Returns an ordered
     * list (Set iteration order is undefined; we want stability for the
     * audit log).
     */
    private List<String> collectSigningCaseKeys(String candidateUuid) {
        return CandidateDossierRevision
                .<CandidateDossierRevision>find(
                        "dossierUuid IN (SELECT d.uuid FROM CandidateDossier d WHERE d.candidateUuid = ?1) " +
                        "AND signingCaseKey IS NOT NULL",
                        candidateUuid)
                .stream()
                .map(CandidateDossierRevision::getSigningCaseKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }
}
