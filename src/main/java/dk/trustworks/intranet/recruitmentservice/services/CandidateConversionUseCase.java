package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.exceptions.InconsistantDataException;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
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
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
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
 * does only DB work and exits with {@code promotion_status = PENDING}.
 * The S3→S3 document promotion (4-8 PDFs/appendices) runs <em>after</em>
 * the conversion transaction commits, via
 * {@link #runPostConversionCopy(UUID)}. Callers are expected to dispatch
 * {@code runPostConversionCopy} on a {@code ManagedExecutor} so the HTTP
 * response returns fast and DB row locks on
 * {@code recruitment_candidates / candidate_dossiers /
 * candidate_dossier_revisions / candidate_dossier_appendix /
 * users / team_role / user_status / signing_cases} are released promptly.
 * <p>
 * If the post-commit promotion fails, the nextsign-status-sync sweep
 * ({@code NextSignStatusSyncBatchlet#runPromotionRedriveSweep}) picks up
 * the still-{@code PENDING}/{@code FAILED} row on its 5-minute cadence and
 * retries — the user-facing conversion succeeds either way.
 *
 * <h3>Conversion steps (transactional)</h3>
 * <ol>
 *   <li>Validate the request on its own terms — allocation range and the
 *       {@link CareerLevel}/{@link CareerTrack} pairing — before anything is
 *       read or written.</li>
 *   <li>Load candidate; guard ACTIVE state and target company.</li>
 *   <li>Guard the requested username against the existing user population
 *       (terminated employees included) — {@link UserService#createUser}
 *       silently skips a colliding user, which would strand every row below
 *       on a dangling useruuid.</li>
 *   <li>Provision a new {@link User} via {@link UserService#createUser},
 *       carrying the candidate's phone number over to {@code user.phone}
 *       when it fits that column.</li>
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
 *   <li>Insert the initial {@link Salary} row — {@code NORMAL} (monthly) or
 *       {@code HOURLY} as requested, with the standard Danish benefit
 *       defaults.</li>
 *   <li>For each unique signing case key referenced by the candidate's
 *       dossier revisions, transfer local ownership to the new user via
 *       {@link SigningCaseOwnershipPort#transferLocalOwner}.</li>
 *   <li>Call {@link RecruitmentCandidate#markHired}.</li>
 *   <li>Close every OPEN dossier on this candidate.</li>
 *   <li>Set {@code promotion_status = PENDING}. The post-commit
 *       promotion is dispatched by the resource layer.</li>
 * </ol>
 *
 * <h3>Post-commit steps ({@link #runPostConversionCopy})</h3>
 * <ol>
 *   <li>Promote every signed PDF, appendix and identity document S3→S3
 *       into the new employee's document store
 *       ({@code S3EmployeePromotionService}).</li>
 *   <li>In a short follow-up tx, set the final promotion status.</li>
 * </ol>
 */
@JBossLog
@ApplicationScoped
public class CandidateConversionUseCase {

    /**
     * Width of {@code user.phone} ({@code varchar(20)}). The source column,
     * {@code recruitment_candidates.phone}, is {@code varchar(50)}.
     */
    static final int MAX_USER_PHONE_LENGTH = 20;

    @Inject
    UserService userService;

    @Inject
    SigningCaseOwnershipPort signingCaseOwnershipPort;

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
        // Pure input validation belongs here, beside the allocation range
        // check and ahead of the first write: the track/level pair is a
        // property of the request alone and needs neither the candidate nor
        // the company. This path used to persist its UserCareerLevel row with
        // a bare persist() and was the one writer bypassing the
        // CareerLevelService check, which is how an ADVISORY /
        // THOUGHT_LEADER_PARTNER row reached production.
        requireConsistentCareerTrack(req.careerTrack(), req.careerLevel());

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

        // Guard the company uuid BEFORE the lookup: Hibernate throws
        // IllegalArgumentException("Identifier may not be null") from inside
        // findById(null), so a null here would escape as a 500 and the
        // company == null branch below could never be reached. Nullable since
        // V435 relaxed target_company_uuid for talent-pool / LinkedIn-import
        // candidates, which the dossier flow never produced.
        String targetCompanyUuid = requireTargetCompanyUuid(
                candidate.getUuid(), candidate.getTargetCompanyUuid());

        Company company = Company.findById(targetCompanyUuid);
        if (company == null) {
            throw new NotFoundException(
                    "Target company not found: " + targetCompanyUuid);
        }

        // (b) Create User. Mirrors the existing UserService.createUser flow:
        // name + email + username + a generated UUID. The candidate's
        // first/last name come from the recruitment record itself.
        //
        // The username is checked HERE, before anything is written:
        // UserService.createUser SILENTLY returns the un-persisted argument
        // when a row with the same uuid or username already exists ("User
        // already exists, skipping creation"). The uuid minted below is
        // fresh, so the only realistic collision is the username — and an
        // unnoticed skip would leave every row below (user_status, career
        // level, team_role, salary) pointing at a useruuid that was never
        // inserted, i.e. a foreign-key violation surfacing as a 500 instead of
        // "that username is taken". The lookup deliberately spans the whole
        // user table: terminated employees keep their username and it stays
        // reserved (login handles are never recycled). The equality below is
        // only as strong as the handle is normalised — MariaDB's PAD SPACE
        // collation ignores trailing but not leading blanks, so " x" would
        // read as free — which is why ConvertRequest.username is pinned to
        // ^[a-z0-9.]+$ at the REST boundary.
        if (User.find("username", req.username()).count() > 0) {
            throw new BusinessRuleViolation(
                    ("Cannot convert candidate %s: username '%s' is already taken by another user. "
                            + "Choose a different username.")
                            .formatted(candidate.getUuid(), req.username()));
        }

        User user = new User();
        user.uuid = UUID.randomUUID().toString();
        user.setFirstname(candidate.getFirstName());
        user.setLastname(candidate.getLastName());
        user.setEmail(req.email());
        user.setUsername(req.username());
        // Carry the phone number the candidate already gave us instead of
        // making HR retype it. It belongs on user.phone, NOT on
        // user_contactinfo.phone: the Danløn employee export
        // (DanlonResource.getDanlonEmployees), the document placeholder
        // prefill (PlaceholderPrefillService, FIELD_PHONE) and the public
        // employee directory (PublicUser) all read user.phone, while nothing
        // reads user_contactinfo.phone — createUser seeds that one to "" and
        // only UserContactInfoService ever writes it. The field carries a
        // @Deprecated marker, but it is still the single column every reader
        // resolves a phone number from.
        String userPhone = resolveUserPhone(candidate.getUuid(), candidate.getPhone());
        if (userPhone != null) {
            user.setPhone(userPhone);
        }
        userService.createUser(user);

        // (c) Two status rows: PREBOARDING (alloc=0, bonus=false) makes the
        // new user discoverable to StatusService.getLatestEmploymentStatus
        // today so the document promotion resolves the user's company on
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
        // user's earliest career-level row aligns with their hire date. The
        // track/level pair was already rejected at the top of execute if
        // inconsistent, so the persist below cannot write an impossible row.
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

        // (f) Initial salary row — NORMAL (monthly) unless the request asks
        // for HOURLY (students are paid per hour), with standard Danish
        // benefit defaults.
        Salary salary = new Salary(plannedStart, req.salary(), user.uuid);
        salary.setType(resolveSalaryType(req.salaryType()));
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

        // (g2) Agreement registry re-key (template-clauses spec §8, Phase 3):
        // candidate-scoped employee_agreements rows written while signing
        // cases completed pre-hire now belong to the new user. One UPDATE
        // inside this transaction keeps the XOR subject CHECK satisfied.
        long rekeyed = dk.trustworks.intranet.agreementservice.model.EmployeeAgreement
                .rekeyCandidateToUser(candidate.getUuid(), user.uuid);
        if (rekeyed > 0) {
            log.infof("Re-keyed %d agreement registry rows candidate=%s -> user=%s",
                    rekeyed, candidate.getUuid(), user.uuid);
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

        // (j) Mark the post-commit document copy as PENDING. Documents
        //     promote S3→S3 into the employee store (spec §6.5.3, re-driven
        //     by the nextsign-status-sync sweep); the actual copy is
        //     dispatched post-commit by RecruitmentResource via
        //     runPostConversionCopy(...) on a managed executor.
        candidate.setPromotionStatus(PromotionStatus.PENDING);

        log.infof("Converted candidate uuid=%s -> user uuid=%s by actor=%s (signing cases transferred=%d)",
                candidate.getUuid(), user.uuid, actor, caseKeys.size());

        return ConvertResponse.hired(user.uuid, candidate.getUuid(), caseKeys.size());
    }

    /**
     * Post-commit document copy dispatcher: the S3→S3 promotion into the
     * employee document store (employee-documents spec §6.5.3). Callers
     * dispatch this on a {@code ManagedExecutor} after {@link #execute}
     * commits.
     */
    public void runPostConversionCopy(UUID candidateUuid) {
        Objects.requireNonNull(candidateUuid, "candidateUuid must not be null");
        s3EmployeePromotionService.runPromotion(candidateUuid);
    }

    /**
     * Resolve the candidate's target company uuid, refusing the conversion when
     * the candidate carries none.
     *
     * <p>{@code target_company_uuid} is nullable since V435 (ATS talent pool):
     * LinkedIn paste imports and pool candidates have no target company until
     * an application exists, while the dossier flow still sets one. Converting
     * such a candidate is a state problem — the candidate is not ready to be
     * hired — so it maps to {@code 409 Conflict} like the ACTIVE-status guard,
     * not to a 500. Without this guard the value reaches
     * {@code Company.findById(null)}, which throws
     * {@code IllegalArgumentException("Identifier may not be null")} from
     * inside Hibernate before any null check on the result can run.</p>
     *
     * <p>Package-private for unit testing.</p>
     *
     * @return the non-blank target company uuid
     * @throws BusinessRuleViolation when the candidate has no target company
     */
    static String requireTargetCompanyUuid(String candidateUuid, String targetCompanyUuid) {
        if (targetCompanyUuid == null || targetCompanyUuid.isBlank()) {
            throw new BusinessRuleViolation(
                    ("Cannot convert candidate %s: no target company is set on the candidate. "
                            + "Set the candidate's target company before converting.")
                            .formatted(candidateUuid));
        }
        return targetCompanyUuid;
    }

    /**
     * Decide what to write to {@code user.phone} when copying the candidate's
     * number onto the new employee.
     *
     * <p>The two columns disagree on width: {@code recruitment_candidates.phone}
     * is {@code varchar(50)}, {@code user.phone} is {@code varchar(20)}. Nothing
     * in production exceeds 20 today (the longest is 15), so this is a latent
     * mismatch rather than a live one — but a 21-character paste would only
     * surface at flush, as a truncation {@code SQLException} that rolls the
     * whole conversion back and answers 500. The phone number is a convenience
     * carried over so HR does not retype it; the hire is what matters. So an
     * over-long value is dropped and logged rather than allowed to fail the
     * conversion. Widening the column is a migration and out of scope.</p>
     *
     * <p>The number itself is PII and is deliberately kept out of the log line;
     * the candidate uuid and the length are enough to find and fix the record.</p>
     *
     * <p>Package-private for unit testing.</p>
     *
     * @return the phone number to copy, or {@code null} when there is nothing
     *         to copy (absent, blank, or wider than the target column)
     */
    static String resolveUserPhone(String candidateUuid, String candidatePhone) {
        if (candidatePhone == null || candidatePhone.isBlank()) return null;
        if (candidatePhone.length() > MAX_USER_PHONE_LENGTH) {
            log.warnf("Not copying phone for candidate=%s: %d characters exceeds the %d that user.phone holds. "
                            + "Set the phone manually on the new employee.",
                    candidateUuid, candidatePhone.length(), MAX_USER_PHONE_LENGTH);
            return null;
        }
        return candidatePhone;
    }

    /**
     * Refuse a career level that belongs to a different track than the one
     * requested.
     *
     * <p>Every {@link CareerLevel} except {@code JUNIOR_CONSULTANT} carries its
     * owning {@link CareerTrack}, and {@code CareerLevelService.create} has
     * always rejected a mismatched pair. The conversion flow persisted its
     * {@link UserCareerLevel} directly and so was the one writer that skipped
     * that check — the source of the single impossible {@code ADVISORY /
     * THOUGHT_LEADER_PARTNER} row in production.</p>
     *
     * <p>The check is duplicated here rather than delegated to
     * {@code CareerLevelService.create}: that method re-enters a <em>fresh</em>
     * transaction through a self-proxy for its duplicate-key retry, and a
     * {@link jakarta.persistence.PersistenceException} raised inside this use
     * case's single {@link Transactional} boundary would mark the ambient
     * transaction rollback-only — breaking the "no partial hires" guarantee.
     * The message shape is copied verbatim from {@code CareerLevelService} so
     * operators see one consistent error whichever writer they hit, and
     * {@link InconsistantDataException} keeps it a clean 400 (invalid input,
     * not a conflict of candidate state).</p>
     *
     * <p>A null level is tolerated: {@code @NotNull} on
     * {@link ConvertRequest#careerLevel()} is what rejects a missing level at
     * the REST boundary. A level with a null track ({@code JUNIOR_CONSULTANT},
     * the entry level) fits any track and always passes.</p>
     *
     * <p>Package-private for unit testing.</p>
     *
     * @throws InconsistantDataException when the level belongs to another track
     */
    static void requireConsistentCareerTrack(CareerTrack careerTrack, CareerLevel careerLevel) {
        if (careerLevel == null || careerLevel.getTrack() == null) return;
        if (!careerLevel.getTrack().equals(careerTrack)) {
            throw new InconsistantDataException(
                    "Career level " + careerLevel +
                    " does not belong to track " + careerTrack +
                    " (expected " + careerLevel.getTrack() + ")");
        }
    }

    /**
     * Resolve the salary type for the initial {@link Salary} row.
     * {@link ConvertRequest#salaryType()} is optional — callers written against
     * the pre-{@code salaryType} contract omit it — and absence means
     * {@link SalaryType#NORMAL}, the monthly salary the vast majority of hires
     * get. Students are hired {@link SalaryType#HOURLY} and could not be
     * expressed at all before this parameter existed.
     *
     * <p>Package-private for unit testing.</p>
     */
    static SalaryType resolveSalaryType(SalaryType requested) {
        return requested == null ? SalaryType.NORMAL : requested;
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
