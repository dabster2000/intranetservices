package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertResponse;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.PromotionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import dk.trustworks.intranet.recruitmentservice.services.SharePointEmployeeFolderService.CopyResult;
import dk.trustworks.intranet.signing.ports.SigningCaseNotFoundException;
import dk.trustworks.intranet.signing.ports.SigningCaseOwnershipPort;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
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
 *   <li>Insert two {@link UserStatus} rows:
 *       (1) {@link StatusType#PREBOARDING} at
 *       {@code max(plannedStart - 2 months, today)} with {@code allocation = 0}
 *       and {@code is_tw_bonus_eligible = false}, and
 *       (2) {@link StatusType#ACTIVE} at {@code plannedStart} with the requested
 *       allocation and {@code is_tw_bonus_eligible = true}. PREBOARDING is
 *       skipped when its computed statusdate equals {@code plannedStart}
 *       (i.e. {@code plannedStart} on or before today) to honour
 *       {@code uq_userstatus_user_date(useruuid, statusdate)}.</li>
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

    @Inject
    RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;

    @Inject
    dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService danlonAssignmentService;

    @Inject
    RecruitmentOfferBridge offerBridge;

    @Inject
    dk.trustworks.intranet.documentservice.services.EmployeeDocumentsFeatureFlag employeeDocumentsFeatureFlag;

    @Inject
    S3EmployeePromotionService s3EmployeePromotionService;

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

        // (c) Two status rows: PREBOARDING (alloc=0, bonus=false) makes the
        // new user discoverable to StatusService.getLatestEmploymentStatus
        // today so the SharePoint copy resolves the user's company on
        // convert day. ACTIVE carries the requested allocation and bonus
        // eligibility. Skipping PREBOARDING when its date collapses onto
        // plannedStart honours uq_userstatus_user_date(useruuid, statusdate).
        LocalDate plannedStart = req.plannedStartDate();
        LocalDate preboardingDate = computePreboardingDate(plannedStart, LocalDate.now());
        if (preboardingDate.equals(plannedStart)) {
            log.debugf("Skipping PREBOARDING insert: user=%s plannedStart=%s equals preboardingDate", user.uuid, plannedStart);
        } else {
            UserStatus preboarding = new UserStatus(
                    req.consultantType(),
                    StatusType.PREBOARDING,
                    preboardingDate,
                    0,
                    user.uuid);
            preboarding.setUuid(UUID.randomUUID().toString());
            preboarding.setCompany(company);
            preboarding.setTwBonusEligible(false);
            UserStatus.persist(preboarding);
        }

        UserStatus active = new UserStatus(
                req.consultantType(),
                StatusType.ACTIVE,
                plannedStart,
                req.allocation(),
                user.uuid);
        active.setUuid(UUID.randomUUID().toString());
        active.setCompany(company);
        active.setTwBonusEligible(true);
        UserStatus.persist(active);

        // Danløn: raise a FIRST_EMPLOYMENT proposal for the new employee (spec §6, closes N2).
        // Propose-only — no number is minted until HR approves on the salary-payment page.
        // The reconciliation scan is the backstop if this path is ever bypassed.
        danlonAssignmentService.proposeIfNeeded(user.uuid, plannedStart,
                dk.trustworks.intranet.aggregates.users.danlon.DanlonEventType.FIRST_EMPLOYMENT, company.getUuid());

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

        // (f) Initial salary row — monthly, with standard Danish benefit defaults.
        Salary salary = new Salary(plannedStart, req.salary(), user.uuid);
        salary.setType(SalaryType.NORMAL);
        salary.setLunch(true);
        salary.setPhone(true);
        salary.setPrayerDay(true);
        salary.setInternet(false);
        Salary.persist(salary);

        // (g) Transfer signing-case ownership for every signing_case_key
        // referenced by this candidate's dossier revisions. Distinct keys
        // only — a single case may be referenced by multiple revisions
        // (e.g. resends).
        List<String> caseKeys = collectSigningCaseKeys(candidate.getUuid());
        for (String caseKey : caseKeys) {
            try {
                signingCaseOwnershipPort.transferLocalOwner(caseKey, UUID.fromString(user.uuid));
            } catch (SigningCaseNotFoundException e) {
                // Best-effort audit operation; row may not exist for in-flight candidates
                // whose Send-for-Signature predates the saveMinimalCase wiring
                // (recruitment-convert-signed-archive).
                log.warnf("Skipping ownership transfer for caseKey=%s on candidate=%s: %s",
                        caseKey, candidate.getUuid(), e.getMessage());
            }
        }

        // (h) Domain transition — guards re-checked inside the entity.
        candidate.markHired(UUID.fromString(user.uuid), actor);

        // (h2, P10 bridge) Mark the candidate's OFFER application(s) HIRED and
        // append APPLICATION_STAGE_CHANGED + CANDIDATE_HIRED to the event
        // stream — same transaction, so a bridge failure rolls the whole
        // conversion back ("no partial hires": state and events never
        // diverge). Legacy dossier-only candidates without application rows
        // still get their CANDIDATE_HIRED.
        offerBridge.onCandidateConverted(candidate, user.uuid, req.teamUuid(),
                plannedStart, actor);

        // (i) Close any still-OPEN dossiers.
        List<CandidateDossier> openDossiers = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND status = ?2",
                        candidate.getUuid(), DossierStatus.OPEN)
                .list();
        for (CandidateDossier d : openDossiers) {
            d.closeOnTerminal();
        }

        // (j) Mark the post-commit document copy as PENDING. While the
        //     employee_documents.writers.promotion toggle is ON, documents
        //     promote S3→S3 into the employee store (spec §6.5.3, re-driven
        //     by the nextsign-status-sync sweep); while OFF, the legacy
        //     SharePoint copy pipeline runs unchanged (retry batchlet
        //     SharePointEmployeeFolderMoveBatchlet). Either way the actual
        //     copy is dispatched post-commit by RecruitmentResource via
        //     runPostConversionCopy(...) on a managed executor.
        if (employeeDocumentsFeatureFlag.isPromotionWriterEnabled()) {
            candidate.setPromotionStatus(PromotionStatus.PENDING);
        } else {
            candidate.setSharepointMoveStatus(SharePointMoveStatus.PENDING);
        }

        log.infof("Converted candidate uuid=%s -> user uuid=%s by actor=%s (signing cases transferred=%d)",
                candidate.getUuid(), user.uuid, actor, caseKeys.size());

        return ConvertResponse.hired(user.uuid, candidate.getUuid(), caseKeys.size());
    }

    /**
     * Post-commit document copy dispatcher: routes to the S3→S3 promotion
     * (employee-documents spec §6.5.3) when the candidate was converted
     * with the promotion writer ON (promotion_status set), otherwise to
     * the legacy SharePoint copy. Callers dispatch this on a
     * {@code ManagedExecutor} after {@link #execute} commits.
     */
    public void runPostConversionCopy(UUID candidateUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate != null && candidate.getPromotionStatus() != null) {
            s3EmployeePromotionService.runPromotion(candidateUuid);
            return;
        }
        runSharePointCopy(candidateUuid);
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

        CopyResult result;
        try {
            result = sharePointEmployeeFolderService.copyToEmployeeFolder(
                    candidate, targetUsername, location);
        } catch (RuntimeException e) {
            log.warnf(e, "runSharePointCopy: SharePoint copy threw for candidate=%s — staying PENDING for retry",
                    candidateUuid);
            return;
        }
        UUID recruiter = parseUuidOrNull(candidate.getCreatedByUseruuid());
        applySharePointResult(candidateUuid, result, recruiter);
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * PREBOARDING statusdate = 2 months before the planned start, but never
     * in the past — clamp to {@code today} when the rule would otherwise
     * yield a date earlier than today. The result is used for the
     * PREBOARDING row inserted at conversion time. Same-date with
     * {@code plannedStart} is handled by the caller (we skip PREBOARDING
     * to honour {@code uq_userstatus_user_date}).
     *
     * <p>Package-private for unit testing.</p>
     */
    static LocalDate computePreboardingDate(LocalDate plannedStart, LocalDate today) {
        Objects.requireNonNull(plannedStart, "plannedStart must not be null");
        Objects.requireNonNull(today, "today must not be null");
        LocalDate twoMonthsBefore = plannedStart.minusMonths(2);
        return twoMonthsBefore.isBefore(today) ? today : twoMonthsBefore;
    }

    /**
     * Persist the outcome of a SharePoint copy attempt. Runs inside a short
     * transaction so the row is locked only for the status write + (on
     * COMPLETED) retention stamping — the slow Graph upload is already done
     * by the time this method is called.
     *
     * <p>On {@link SharePointMoveStatus#COMPLETED}, also fires the HR Slack
     * notification (in-memory deduped per candidate, never throws).
     *
     * @param candidateUuid candidate whose move just finished
     * @param result        copy outcome including signed-PDF filenames
     * @param recruiterUuid actor who triggered Convert (used by the Slack
     *                      notifier to resolve recruiter display name); may
     *                      be {@code null} on batchlet-driven retries
     */
    @Transactional
    public void applySharePointResult(UUID candidateUuid, CopyResult result, UUID recruiterUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        Objects.requireNonNull(result, "result must not be null");
        SharePointMoveStatus status = result.status();
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            log.warnf("applySharePointResult: candidate=%s not found, cannot update status",
                    candidateUuid);
            return;
        }
        candidate.setSharepointMoveStatus(status);
        if (status == SharePointMoveStatus.COMPLETED) {
            sharePointEmployeeFolderService.stampS3RetentionUntil(candidate);
            recruitmentHrSlackNotifier.notifyHire(candidate, recruiterUuid, result.signedFilenames());
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
