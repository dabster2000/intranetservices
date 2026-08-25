package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The per-viewer visibility filter for the recruitment module (plan §P2) —
 * built in P2 and reused by <em>every</em> later query path (boards, grids,
 * timelines, reports). Rules as amended by the 2026-08-23 access-model
 * redesign ({@code docs/access/recruitment-access-model-target.md}):
 * <ul>
 *   <li><b>Circle filtering (hard):</b> a {@code PARTNER}-track position is
 *       visible only to its circle members — this filter applies to every
 *       role except ADMIN, including recruiters (HR).</li>
 *   <li><b>Rights come from roles only (decision 11):</b> the former
 *       practice-lead read/decide routes ({@code practice_lead} rows, the
 *       practices of currently led teams) are gone. Practice membership
 *       matters only as the assistant's scope, read from
 *       {@code user.practice_uuid} — never from {@code practice_lead} or
 *       {@code teamroles}.</li>
 * </ul>
 * The viewer is always the {@code X-Requested-By} user — the BFF's system
 * JWT carries {@code admin:*}, so backend scopes cannot distinguish
 * employees; per-user rules key on the user's {@code roles} rows, team
 * leaderships and circle memberships.
 * <p>
 * Effective tiers for positions:
 * <ol>
 *   <li>{@code ADMIN} role → everything, including partner track.</li>
 *   <li>{@code HR} / {@code RECRUITMENT} / {@code TEAMLEAD}
 *       ({@link #POSITION_READ_ROLES}) → read <em>and decide on</em> every
 *       non-partner position (decision 1 collapsed the read/decide split),
 *       plus partner positions where the viewer is in the circle. Final
 *       outcomes stay with this tier and involvement —
 *       {@link #canDecideFinalOutcome}.</li>
 *   <li>{@code ASSISTANT_TEAMLEAD} → the same capability set scoped to the
 *       practice they are a member of ({@code user.practice_uuid}), minus
 *       final outcomes, the offer dossier, candidate creation and the
 *       intake queues (decisions 2–10).</li>
 *   <li>Everyone else → positions they own ({@code hiring_owner_uuid}),
 *       positions of teams they currently lead, and circle memberships.</li>
 * </ol>
 * {@code TECHPARTNER} and {@code PARTNER} carry no recruitment access
 * (go-live decisions D7/D9): involvement or an explicit role grant is the
 * only way in.
 */
@ApplicationScoped
public class RecruitmentVisibility {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_HR = "HR";
    static final String ROLE_TEAMLEAD = "TEAMLEAD";
    /**
     * A team lead scoped to one practice (decision 2, 2026-08-23) — not a
     * junior recruiter. The role only ever ADDS the practice-scoped route;
     * it never narrows what another role or an involvement already grants,
     * so a TEAMLEAD who also holds it is simply a team lead. Its practice
     * is {@code user.practice_uuid} (decision 3, {@link #practiceOfUser}).
     */
    static final String ROLE_ASSISTANT_TEAMLEAD = "ASSISTANT_TEAMLEAD";
    /**
     * Recruiter-tier roles: the AI brief, configuration surfaces, the
     * review-before-send email queue, dossier-adjacent work. {@code TEAMLEAD}
     * is deliberately NOT here — since decision 1 a teamlead decides on every
     * non-partner pipeline (see {@link #canDecideOnApplication}), but the
     * recruiter tier proper stays ADMIN/HR/RECRUITMENT.
     * <p>
     * Composing and sending a candidate e-mail left this tier on 2026-08-25 —
     * see {@link #canEmailCandidates}. Configuring what is sent (templates,
     * sender identity) and clearing the pending queue did not.
     */
    static final Set<String> RECRUITER_TIER_ROLES = Set.of("HR", "RECRUITMENT");
    /**
     * Inbox tier (decisions 12/13, 2026-08-23): the roles that work the raw
     * intake queues — pending referrals and unsolicited applicants — and
     * their actions (triage, pool from the queue). The recruiter tier plus
     * {@code TEAMLEAD}. {@code ASSISTANT_TEAMLEAD} is deliberately absent:
     * decision 8 makes unapplied candidates invisible to an assistant, so
     * an assistant Inbox would show nothing while its buttons still fired.
     */
    static final Set<String> INBOX_TIER_ROLES = Set.of("HR", "RECRUITMENT", ROLE_TEAMLEAD);
    /**
     * {@code AuditEntityListener}'s fallback for {@code created_by} /
     * {@code modified_by} when no {@code X-Requested-By} header is present:
     * the public {@code /apply} funnel, batch imports and startup jobs. Not a
     * user uuid, so {@link #rolesOf} can never resolve it — it is matched by
     * literal in {@link #creatorConfersHire}.
     */
    static final String SYSTEM_ACTOR = "system";
    /**
     * Roles that <em>read</em> every non-partner position without needing
     * involvement (go-live decision D3): the recruiter tier plus
     * {@code TEAMLEAD}. Acting on those positions is a separate, narrower
     * question — {@link #canDecideOnApplication}.
     */
    static final Set<String> POSITION_READ_ROLES = Set.of("HR", "RECRUITMENT", ROLE_TEAMLEAD);
    /**
     * Profile-read tier (P8, contract §P8-Timeline): roles that read every
     * candidate profile except partner-track-only candidates outside their
     * circles. {@code TEAMLEAD} joins HR/RECRUITMENT here (D3) — a teamlead
     * reads the whole non-partner candidate population, including the
     * database grid. {@code TECHPARTNER} was removed from the recruitment
     * module entirely (D7).
     */
    static final Set<String> PROFILE_READ_ROLES = Set.of("HR", "RECRUITMENT", ROLE_TEAMLEAD);
    /**
     * Hired-file tier (spec §7.2 field gate): once a candidate is HIRED,
     * profile access narrows to these roles (+ ADMIN) — colleagues must not
     * browse a new colleague's interview file.
     * {@code TEAMLEAD} is deliberately absent (D6): the wide read the
     * teamlead gets while a candidate is in play stops at the hire.
     *
     * <p>Phase 10.5: the DPO no longer appears here as a hardcoded role.
     * GDPR-duty reach is expressed as holding the {@code recruitment:gdpr}
     * permission ({@link #holdsRecruitmentGdprGrant}), so the console governs
     * it — revoking {@code (DPO, recruitment:gdpr)} removes hired-file access
     * without a code change. Equivalence for every current production user is
     * proven in {@code docs/access/phase10-golden-baseline.md}.</p>
     *
     * <p>The role membership itself is the go-live's (D3/D6/D7), not Phase
     * 10.5's original {@code HR, CXO, TECHPARTNER}: the recruitment access
     * model landed on staging after this commit was written and removed
     * {@code TECHPARTNER} from the module entirely (D7). Only the DPO
     * hardcode is re-keyed here.</p>
     */
    static final Set<String> HIRED_FILE_ROLES = Set.of("HR", "RECRUITMENT");

    @Inject
    EntityManager em;

    @Inject
    dk.trustworks.intranet.security.EffectivePermissionService effectivePermissionService;

    /**
     * Whether the viewer holds {@code recruitment:gdpr} through the permission
     * catalogue (ALL-scope grants only — the Phase 8 boolean projection).
     * Fail-closed: any resolution failure reads as "does not hold it".
     */
    boolean holdsRecruitmentGdprGrant(String viewerUuid) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return false;
        }
        try {
            return effectivePermissionService.effectivePermissions(viewerUuid)
                    .contains("recruitment:gdpr");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the viewer holds {@code recruitment:intake} through the
     * permission catalogue (ALL-scope grants only — the Phase 8 boolean
     * projection). Fail-closed: any resolution failure reads as "does not
     * hold it".
     *
     * <p>Deliberately a byte-for-byte mirror of
     * {@link #holdsRecruitmentGdprGrant}: the grant is console-governed, so
     * revoking {@code (TEAMLEAD, recruitment:intake)} removes intake without
     * a code change.</p>
     */
    boolean holdsRecruitmentIntakeGrant(String viewerUuid) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return false;
        }
        try {
            return effectivePermissionService.effectivePermissions(viewerUuid)
                    .contains("recruitment:intake");
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- Viewer capability resolution --------------------------------------

    /** @return the viewer's {@code roles} rows, uppercased. */
    public Set<String> rolesOf(String userUuid) {
        return Role.<Role>list("useruuid", userUuid).stream()
                .map(r -> r.getRole() == null ? "" : r.getRole().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * The practice the viewer's {@code ASSISTANT_TEAMLEAD} rights are scoped
     * to — the practice they are a <em>member</em> of,
     * {@code user.practice_uuid} (decision 3). Deliberately never
     * {@code practice_lead} or {@code teamroles}: decision 11 removed the
     * practice-lead routes from recruitment entirely; running a practice
     * grants no recruitment right any more.
     * <p>
     * {@code null} when the user has no practice. The assignment rail
     * ({@code RoleService}) refuses handing the role to such a user, but a
     * {@code null} here still fails closed at every consultation — an
     * assistant with no practice can act on nothing.
     */
    public String practiceOfUser(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return null;
        }
        List<?> rows = em.createNativeQuery(
                        "SELECT practice_uuid FROM user WHERE uuid = :uuid")
                .setParameter("uuid", userUuid)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0) instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Whether the viewer's recruitment standing is the assistant tier and
     * nothing wider: they hold {@code ASSISTANT_TEAMLEAD} and none of the
     * roles that carry their own recruitment reach. Every assistant-specific
     * DENY (final outcomes, the dossier, candidate creation) keys on this —
     * the role only ever adds a practice-scoped route, so a TEAMLEAD or
     * recruiter who also holds it keeps everything their wider role grants.
     */
    static boolean isAssistantScoped(Set<String> roles) {
        return roles.contains(ROLE_ASSISTANT_TEAMLEAD)
                && !roles.contains(ROLE_ADMIN)
                && !roles.contains(ROLE_TEAMLEAD)
                && roles.stream().noneMatch(RECRUITER_TIER_ROLES::contains);
    }

    /** Instance form of {@link #isAssistantScoped} for callers outside this package. */
    public boolean isAssistantScopedViewer(String viewerUuid) {
        return isAssistantScoped(rolesOf(viewerUuid));
    }

    /**
     * Whether the viewer reads the whole (non-partner) candidate population
     * — {@link #PROFILE_READ_ROLES} plus ADMIN. The batched twin question of
     * {@link #canReadCandidateProfile}'s wholesale branch, for surfaces that
     * scope many candidate-level rows at once (the landing activity feed):
     * anyone below this tier sees a candidate-level row only for candidates
     * they actually reach (the assistant's practice, an involved viewer's
     * own positions' applicants). Assistant-only standing is exclusive: it
     * never falls through to the generic owner/current-leader route.
     */
    public boolean isProfileReadTier(String userUuid) {
        Set<String> roles = rolesOf(userUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(PROFILE_READ_ROLES::contains);
    }

    /**
     * The assistant's practice route (decisions 2–5): the viewer holds
     * {@code ASSISTANT_TEAMLEAD} and the position is a <b>non-partner</b>
     * position of the practice they belong to. Partner track is excluded
     * unconditionally — the circle stays its only key, and belonging to a
     * practice must never become a back door into a confidential hire. For
     * assistant-only viewers this is also the complete non-partner answer:
     * named ownership and current team leadership cannot widen it.
     */
    private boolean assistantPracticeRoute(String viewerUuid, Set<String> roles,
                                           RecruitmentPosition position) {
        if (!roles.contains(ROLE_ASSISTANT_TEAMLEAD)
                || position.getHiringTrack() == RecruitmentHiringTrack.PARTNER
                || position.getPracticeUuid() == null) {
            return false;
        }
        String practice = practiceOfUser(viewerUuid);
        return practice != null && practice.equals(position.getPracticeUuid());
    }

    /** Team uuids the viewer currently leads (temporal {@code teamroles} LEADER rows). */
    @SuppressWarnings("unchecked")
    public List<String> currentlyLedTeams(String userUuid) {
        return em.createNativeQuery("""
                        SELECT teamuuid FROM teamroles
                        WHERE useruuid = :user AND membertype = 'LEADER'
                          AND startdate <= :today
                          AND (enddate > :today OR enddate IS NULL)
                        """)
                .setParameter("user", userUuid)
                .setParameter("today", LocalDate.now())
                .getResultList();
    }

    /**
     * Practice uuids of the teams the viewer <em>currently</em> leads — the
     * same temporal {@code teamroles} LEADER rows as
     * {@link #currentlyLedTeams}, walked on to {@code team.practice_uuid}.
     * <p>
     * <b>Not a rights method.</b> Decision 11 (2026-08-23) removed every
     * practice hop from read and decide rights and this does not put one
     * back: nothing in {@link #filterPositions}, {@link #canDecideOnApplication}
     * or {@link #decidablePositionUuids} consults it. Its only consumer is
     * the landing page's "My tasks" card, which answers the narrower
     * question <em>"is this mine to worry about?"</em> — see
     * {@code RecruitmentLandingService.taskInScope}.
     * <p>
     * Deliberately NOT {@link #practiceOfUser}: in production every one of
     * the 13 team leads has a {@code user.practice_uuid} that differs from
     * the practice of the team they lead (or none at all), so the membership
     * hop would scope the card to the wrong practice. And deliberately not
     * routed through {@code position.team_uuid} either — that column is NULL
     * for every production position, so a team hop alone reaches nothing.
     * <p>
     * Teams with no practice contribute nothing (they are reached through
     * {@link #currentlyLedTeams} instead), so the result never holds nulls.
     */
    @SuppressWarnings("unchecked")
    public Set<String> ledPracticeUuids(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return Set.of();
        }
        List<String> rows = em.createNativeQuery("""
                        SELECT DISTINCT t.practice_uuid
                        FROM teamroles tr
                        JOIN team t ON t.uuid = tr.teamuuid
                        WHERE tr.useruuid = :user AND tr.membertype = 'LEADER'
                          AND tr.startdate <= :today
                          AND (tr.enddate > :today OR tr.enddate IS NULL)
                          AND t.practice_uuid IS NOT NULL
                        """)
                .setParameter("user", userUuid)
                .setParameter("today", LocalDate.now())
                .getResultList();
        return new HashSet<>(rows);
    }

    /**
     * Whether the viewer belongs to the recruiter tier for module-wide
     * queues (spec §7.2): {@code ADMIN}, {@code HR} or {@code RECRUITMENT}.
     * The P6 referral triage queue and the unsolicited-applicant queue gate
     * on this — a teamlead sees their own positions' pipelines but never the
     * raw intake queues.
     */
    public boolean isRecruiterTier(String userUuid) {
        Set<String> roles = rolesOf(userUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains);
    }

    /**
     * Whether the viewer may work the Inbox — the pending-referral queue,
     * the unsolicited-applicant queue and their actions (decisions 12/13,
     * 2026-08-23): the recruiter tier plus {@code TEAMLEAD}. See
     * {@link #INBOX_TIER_ROLES} for why the assistant is not here.
     */
    public boolean isInboxTier(String userUuid) {
        Set<String> roles = rolesOf(userUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(INBOX_TIER_ROLES::contains);
    }

    /**
     * Whether the viewer may bulk-tag candidates (decision 15, 2026-08-23):
     * the recruiter tier, every {@code TEAMLEAD} (● — company-wide, same as
     * their candidate read), and the {@code ASSISTANT_TEAMLEAD} (◐ — the
     * per-target practice scoping happens in
     * {@code CandidateService.bulkAddTags} via
     * {@link #assistantVisibleCandidateUuids}; out-of-practice targets
     * answer 404 like any other invisible row).
     */
    public boolean canBulkTag(String userUuid) {
        Set<String> roles = rolesOf(userUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)
                || roles.contains(ROLE_TEAMLEAD)
                || roles.contains(ROLE_ASSISTANT_TEAMLEAD);
    }

    /**
     * Whether the viewer may write to a candidate — the compose dialog's
     * template list, render, AI draft, copy-options and the send itself
     * (2026-08-25). The recruiter tier, every {@code TEAMLEAD} (● —
     * company-wide, same reach as their candidate read) and the
     * {@code ASSISTANT_TEAMLEAD} (◐ — practice-scoped).
     * <p>
     * This <b>reverses</b> the 2026-08-25 line of the access-model target's
     * "deliberately left closed to TEAMLEAD" list: the person actually
     * running a hire is the one who needs to write to the candidate, and
     * routing every message through a three-person recruiter tier was the
     * bottleneck the pipeline widening was meant to remove. What did NOT
     * move: the review-before-send queue (approve/dismiss), the template
     * library's write side and the sender configuration — those stay
     * {@link #isRecruiterTier}, so a team lead composes and sends but never
     * edits the shared copy or the outbound identity.
     * <p>
     * Record-level scoping is unchanged and still authoritative: every
     * endpoint funnels the candidate through {@code canReadCandidateProfile}
     * and the application through {@code canReadPosition}, so a team lead
     * reaches their non-partner population and an assistant their practice —
     * exactly the candidates they already read.
     */
    public boolean canEmailCandidates(String userUuid) {
        Set<String> roles = rolesOf(userUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)
                || roles.contains(ROLE_TEAMLEAD)
                || roles.contains(ROLE_ASSISTANT_TEAMLEAD);
    }

    /**
     * Whether the acting person may browse the candidate database grid.
     * The BFF applies the same hiring-tier role gate, but the backend must
     * remain authoritative because its client token represents the BFF, not
     * the end user. A valid plain-employee UUID must not be enough to turn a
     * direct client call into a company-wide candidate PII export.
     */
    public boolean canBrowseCandidateGrid(String viewerUuid) {
        Set<String> roles = rolesOf(viewerUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(PROFILE_READ_ROLES::contains)
                || isAssistantScoped(roles);
    }

    /**
     * The role/grant half of the canonical hired-file rule, shared by
     * collection and mutation paths that cannot evaluate one candidate at a
     * time before pagination. ADMIN, HR, RECRUITMENT and the dedicated GDPR
     * grant retain access; TEAMLEAD and assistant involvement stop at hire.
     */
    public boolean canReadHiredCandidateFiles(String viewerUuid) {
        Set<String> roles = rolesOf(viewerUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(HIRED_FILE_ROLES::contains)
                || holdsRecruitmentGdprGrant(viewerUuid);
    }

    /**
     * Whether the viewer may create a candidate (and attach one to a
     * position) — the recruiter tier, plus anyone holding the narrower
     * {@code recruitment:intake} grant.
     *
     * <p>The recruiter-tier check stays in the OR deliberately:
     * ADMIN/HR/RECRUITMENT must never depend on a {@code role_permission}
     * row surviving a staging refresh or a console edit to keep creating
     * candidates.</p>
     *
     * <p><b>Assistants never create candidates</b> (decision 10) — the rule
     * is stated on the role, not left to the grant configuration, so a
     * console edit that hands {@code ASSISTANT_TEAMLEAD} the
     * {@code recruitment:intake} key by mistake still opens nothing. This
     * closes the create-then-invisible blind spot: a candidate an assistant
     * created would have no application and therefore be invisible to its
     * own creator (decision 8).</p>
     *
     * <p>This is the <em>per-user</em> gate. {@code @RolesAllowed} on the
     * resource gates the API client, not the person: the BFF's system token
     * carries {@code admin:*} and {@code AdminScopeAugmentor} expands it to
     * every key, so an annotation alone lets every employee request through.
     * Resources must call this explicitly.</p>
     */
    public boolean canCreateCandidate(String viewerUuid) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
            return true;
        }
        if (isAssistantScoped(roles)) {
            return false;
        }
        return holdsRecruitmentIntakeGrant(viewerUuid);
    }

    /**
     * May the viewer open a new position? Closes the pre-existing gap where
     * {@code POST /recruitment/positions} had no per-person gate at all and
     * {@code hiringTrack} came straight off the request — anyone the BFF
     * admitted could open a <em>partner-track</em> position (access-model
     * target §8, fixed 2026-08-23 while in here).
     * <ul>
     *   <li>ADMIN and the recruiter tier open anything, partner track
     *       included.</li>
     *   <li>A {@code TEAMLEAD} opens any non-partner position (target
     *       table: create ●).</li>
     *   <li>An {@code ASSISTANT_TEAMLEAD} opens non-partner positions in
     *       their own practice only (◐ practice).</li>
     *   <li>Partner-track creation stays recruiter-tier: the circle model
     *       starts at creation, and reading existing partner reqs is
     *       circle-gated for everyone below ADMIN.</li>
     * </ul>
     */
    public boolean canCreatePosition(String viewerUuid, RecruitmentHiringTrack track,
                                     String practiceUuid) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
            return true;
        }
        if (track == RecruitmentHiringTrack.PARTNER) {
            return false;
        }
        if (roles.contains(ROLE_TEAMLEAD)) {
            return true;
        }
        if (roles.contains(ROLE_ASSISTANT_TEAMLEAD)) {
            String practice = practiceOfUser(viewerUuid);
            return practice != null && practice.equals(practiceUuid);
        }
        return false;
    }

    /**
     * Whether the viewer holds {@code recruitment:admin} through the
     * permission catalogue (ALL-scope grants only — the Phase 8 boolean
     * projection). Fail-closed: any resolution failure reads as "does not
     * hold it".
     *
     * <p>Mirrors {@link #holdsRecruitmentGdprGrant} exactly.</p>
     */
    boolean holdsRecruitmentAdminGrant(String viewerUuid) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return false;
        }
        try {
            return effectivePermissionService.effectivePermissions(viewerUuid)
                    .contains("recruitment:admin");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the viewer may irreversibly hard-delete a candidate — holding
     * {@code recruitment:admin}, which V465 grants to ADMIN and to no other
     * role.
     *
     * <p>The <em>per-user</em> gate, and the only one that gates a person:
     * {@code @RolesAllowed({"recruitment:admin"})} on the endpoint gates the
     * API client, and the BFF's system token carries {@code admin:*}, which
     * {@code AdminScopeAugmentor} expands to every key.</p>
     *
     * <p><b>Known property, stated rather than hidden:</b> unlike
     * {@code isRecruiterTier}, this has no role fallback and no protection
     * against a runtime rebind. {@code recruitment:admin} does not match
     * {@code ProtectedPermissions.PROTECTED_PREFIXES}
     * ({@code admin:}, {@code salaries:}), so an admin-console user can bind
     * it to another role with no deploy and no review, and that is all it
     * takes to reach an irreversible PII delete. That is a deliberate
     * consequence of governing the key from the console; if the owner wants
     * the gate frozen, the change is to add a destructive prefix to
     * {@code ProtectedPermissions} — a decision, not an implementation
     * detail.</p>
     */
    public boolean canHardDeleteCandidate(String viewerUuid) {
        return holdsRecruitmentAdminGrant(viewerUuid);
    }

    /** Whether the viewer is a member of the position's circle (any role). */
    public boolean isCircleMember(String userUuid, String positionUuid) {
        return RecruitmentCircleMember.count(
                "positionUuid = :position and userUuid = :user",
                Parameters.with("position", positionUuid).and("user", userUuid)) > 0;
    }

    // ---- Position visibility ------------------------------------------------

    /**
     * The positions visible to the viewer, filtered <em>query-level</em>
     * (partner-track rows a non-member may not see never leave the
     * database), with optional practice/track/status filters on top.
     * Partner-track visibility is granted by circle membership ONLY —
     * being hiring owner or team lead of a partner position without a
     * circle row does not reveal it.
     */
    public List<RecruitmentPosition> filterPositions(String viewerUuid,
                                                     String practiceUuid,
                                                     RecruitmentHiringTrack track,
                                                     RecruitmentPositionStatus status) {
        StringBuilder query = new StringBuilder("from RecruitmentPosition p where 1=1");
        Parameters params = new Parameters();

        Set<String> roles = rolesOf(viewerUuid);
        if (!roles.contains(ROLE_ADMIN)) {
            String circleExists =
                    "exists (select 1 from RecruitmentCircleMember m"
                            + " where m.positionUuid = p.uuid and m.userUuid = :viewer)";
            boolean assistantScoped = isAssistantScoped(roles);
            String assistantPractice = assistantScoped ? practiceOfUser(viewerUuid) : null;
            if (roles.stream().anyMatch(POSITION_READ_ROLES::contains)) {
                // Read tier (recruiter + teamlead): everything except partner
                // track outside the circle. Decision rights are checked
                // separately by canDecideOnApplication.
                query.append(" and (p.hiringTrack <> :partnerTrack or ").append(circleExists).append(')');
            } else if (assistantScoped) {
                // Assistant-only is an exclusive practice route for
                // non-partner positions. Named ownership, stale team
                // leadership and non-partner circle rows must never widen
                // it; with no practice it therefore fails closed. An
                // explicit circle still opens partner-track visibility.
                query.append(" and ((p.hiringTrack = :partnerTrack and ")
                        .append(circleExists)
                        .append(')');
                if (assistantPractice != null) {
                    query.append(" or (p.hiringTrack <> :partnerTrack"
                                    + " and p.practiceUuid = :assistantPractice)");
                    params.and("assistantPractice", assistantPractice);
                }
                query.append(')');
            } else {
                // Involvement tier: (non-partner AND owned/led-team) OR
                // circle. The practice-lead routes are gone (decision 11);
                // the circle is the ONLY grant for partner track — a hard
                // filter, deliberately not bypassed by ownership.
                List<String> ledTeams = currentlyLedTeams(viewerUuid);
                StringBuilder involvement = new StringBuilder("(p.hiringOwnerUuid = :viewer");
                if (!ledTeams.isEmpty()) {
                    involvement.append(" or p.teamUuid in :ledTeams");
                    params.and("ledTeams", ledTeams);
                }
                involvement.append(')');
                query.append(" and ((p.hiringTrack <> :partnerTrack and ")
                        .append(involvement)
                        .append(") or ")
                        .append(circleExists)
                        .append(')');
            }
            params.and("viewer", viewerUuid).and("partnerTrack", RecruitmentHiringTrack.PARTNER);
        }

        if (practiceUuid != null && !practiceUuid.isBlank()) {
            query.append(" and p.practiceUuid = :practice");
            params.and("practice", practiceUuid);
        }
        if (track != null) {
            query.append(" and p.hiringTrack = :track");
            params.and("track", track);
        }
        if (status != null) {
            query.append(" and p.status = :status");
            params.and("status", status);
        }
        query.append(" order by p.openedAt desc");

        return RecruitmentPosition.list(query.toString(), params);
    }

    /**
     * Single-row variant of {@link #filterPositions}: may the viewer read
     * this position? Used by GET-by-uuid and as the precondition for every
     * mutation (you cannot change what you cannot see). Assistant-only
     * non-partner visibility is exclusively their own practice and fails
     * closed without one, even if they are named owner or a current lead.
     */
    public boolean canReadPosition(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN)) {
            return true;
        }
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            return isCircleMember(viewerUuid, position.getUuid());
        }
        if (roles.stream().anyMatch(POSITION_READ_ROLES::contains)) {
            return true;
        }
        if (isAssistantScoped(roles)) {
            // Assistant-only never falls through to named-owner/current-lead
            // involvement outside their practice (or with no practice).
            return assistantPracticeRoute(viewerUuid, roles, position);
        }
        // Involvement: named owner or current team lead. The practice-lead
        // route is gone (decision 11).
        return viewerUuid.equals(position.getHiringOwnerUuid())
                || (position.getTeamUuid() != null && currentlyLedTeams(viewerUuid).contains(position.getTeamUuid()));
    }

    /**
     * May the viewer make pipeline decisions (stage moves, team assignment,
     * re-filing) on applications of this position? Decision 1 (2026-08-23)
     * collapsed the read/decide split: the whole read tier —
     * {@link #POSITION_READ_ROLES}, i.e. HR, RECRUITMENT and every
     * {@code TEAMLEAD} — decides on every non-partner position, practice
     * irrelevant. Otherwise the viewer must be <em>involved</em>: the named
     * hiring owner or the current lead of the position's team. The
     * practice-run routes are gone (decision 11) — running a practice
     * grants nothing anywhere in recruitment any more.
     * <p>
     * The {@code ASSISTANT_TEAMLEAD} qualifies through
     * {@link #assistantPracticeRoute} — same capability, scoped to the
     * practice they belong to, with no owner/current-leader fallback for an
     * assistant-only viewer. <b>Final outcomes are the exception</b>
     * (decision 7): hire, reject, withdraw and return-to-pool require
     * {@link #canDecideFinalOutcome}, which the assistant route never
     * satisfies.
     * <p>
     * On partner track "may look" never implies "may change": only circle
     * OWNER/RECRUITER members (or HR/admin) decide, mirroring the P2
     * position-mutation rule. A plain employee invited onto a circle gets
     * the restricted candidate view ({@link #canReadRestrictedCandidateView})
     * and nothing else (D11).
     */
    public boolean canDecideOnApplication(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (isAssistantScoped(roles)
                && position.getHiringTrack() != RecruitmentHiringTrack.PARTNER) {
            // For non-partner recruitment assistant standing is exclusively
            // own-practice; named ownership/current leadership is not an
            // alternate route. Partner decisions retain explicit circle
            // OWNER/RECRUITER semantics through canDecideCore.
            return assistantPracticeRoute(viewerUuid, roles, position);
        }
        return canDecideCore(viewerUuid, roles, position)
                || assistantPracticeRoute(viewerUuid, roles, position);
    }

    /**
     * May the viewer close an outcome — <b>hire, reject, withdraw,
     * return-to-pool</b> (decision 7: all four, and hiring is a stage move
     * in the data, so a terminal-only check would silently let an assistant
     * hire)? Exactly {@link #canDecideOnApplication} minus an assistant-only
     * viewer, regardless of which ordinary decision route would otherwise
     * apply: practice scope, named ownership or current team leadership. An
     * assistant moves candidates through stages but never closes an outcome.
     * A simultaneous TEAMLEAD, HR, RECRUITMENT or ADMIN role is broader
     * standing and therefore keeps its final-outcome rights.
     * <p>
     * Callers: the three terminal endpoints on
     * {@code RecruitmentApplicationResource}, the REJECT half of
     * {@code recordDecision}, and (as {@link #canWriteDossier}) the hire
     * conversion.
     */
    public boolean canDecideFinalOutcome(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (isAssistantScoped(roles)) {
            return false;
        }
        return canDecideCore(viewerUuid, roles, position);
    }

    /**
     * May the viewer use one of the legacy <em>candidate-level</em> terminal
     * routes ({@code /candidates/{uuid}/decline} or {@code /withdraw})?
     * Those routes pre-date applications and close every open offer dossier,
     * so a mere candidate-profile read is never sufficient authorization.
     *
     * <p>For an ATS candidate, the viewer must both
     * {@link #canReadPosition(String, RecruitmentPosition) read} and satisfy
     * {@link #canDecideFinalOutcome(String, RecruitmentPosition)} for every
     * position the candidate has been connected to. Requiring both gates on
     * every position is deliberate: a candidate-level mutation must not use
     * access to one ordinary application to cross a practice or partner-track
     * boundary on another. A missing position row fails closed. A genuinely legacy
     * candidate with no application has no position on which to evaluate the
     * canonical predicate, so only the recruiter tier (ADMIN, HR,
     * RECRUITMENT) retains the pre-ATS route.
     *
     * <p>The assistant practice route never satisfies the position predicate,
     * while an assistant who also holds TEAMLEAD, HR, RECRUITMENT or ADMIN
     * keeps the broader role's rights through the normal additive role model.
     */
    public boolean canDecideCandidateFinalOutcome(String viewerUuid,
                                                   RecruitmentCandidate candidate) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidate == null) {
            return false;
        }
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "candidateUuid", candidate.getUuid());
        if (applications.isEmpty()) {
            return isRecruiterTier(viewerUuid);
        }

        List<String> positionUuids = applications.stream()
                .map(RecruitmentApplication::getPositionUuid)
                .distinct()
                .toList();
        List<RecruitmentPosition> positions = RecruitmentPosition.list("uuid in ?1", positionUuids);
        if (positions.size() != positionUuids.size()) {
            return false;
        }
        return positions.stream().allMatch(position ->
                // Mirror the application-level route's two independent
                // gates. In particular, HR decision standing must not use a
                // visible non-partner application to cross into a second,
                // partner-track application whose circle they cannot read.
                canReadPosition(viewerUuid, position)
                        && canDecideFinalOutcome(viewerUuid, position));
    }

    /** The shared decision predicate behind both gates above. */
    private boolean canDecideCore(String viewerUuid, Set<String> roles,
                                  RecruitmentPosition position) {
        if (roles.contains(ROLE_ADMIN)) {
            return true;
        }
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            return canManageCircle(viewerUuid, position);
        }
        if (roles.stream().anyMatch(POSITION_READ_ROLES::contains)) {
            return true;
        }
        return viewerUuid.equals(position.getHiringOwnerUuid())
                || (position.getTeamUuid() != null
                    && currentlyLedTeams(viewerUuid).contains(position.getTeamUuid()));
    }

    /**
     * May the viewer <em>change</em> this position — edit its fields or
     * close it? Go-live spec §3, row "Positions — create/edit/close":
     * ADMIN/HR/RECRUITMENT everywhere, {@code TEAMLEAD} <b>own only</b>.
     * <p>
     * Deliberately the same authority as
     * {@link #canDecideOnApplication}: "may this person act on this
     * position" is one question, whether the act is moving a candidate
     * through its pipeline or closing the pipeline outright. Delegating
     * rather than restating the predicate is on purpose — this class has
     * already been bitten by mirrored rules drifting apart.
     * <p>
     * <b>Reading is emphatically not enough.</b> {@link #POSITION_READ_ROLES}
     * shows every {@code TEAMLEAD} every non-partner position (go-live
     * decision D3), and until this gate existed that read tier was the only
     * check on {@code PUT /recruitment/positions/{uuid}} and
     * {@code POST /{uuid}/close} — so all 20 role holders could edit or
     * close any non-partner position in the company. On partner track this
     * reduces to {@link #canManageCircle}, which is what the resource
     * enforced before: a circle PARTICIPANT may look but not touch.
     */
    public boolean canMutatePosition(String viewerUuid, RecruitmentPosition position) {
        return canDecideOnApplication(viewerUuid, position);
    }

    /**
     * Is the viewer "the recruiter or the hiring owner" for this position?
     * The elevated tier two P4 rules key on (spec §4.2): forward stage
     * <em>skips</em> and rejecting a partner-referral candidate. Since
     * decision 1 the whole read tier qualifies — ADMIN, HR, RECRUITMENT and
     * every {@code TEAMLEAD} (target table: skip stages ● for TL; the
     * one-tier collapse includes the owner's elevated moves, or a team lead
     * could advance a candidate one stage at a time but never fast-track
     * one). Otherwise the position's named hiring owner, or the assistant
     * within their practice (◐ practice). The 2026-08-12 practice-run route
     * is gone (decision 11).
     */
    public boolean isRecruiterOrHiringOwner(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN) || roles.stream().anyMatch(POSITION_READ_ROLES::contains)) {
            return true;
        }
        if (isAssistantScoped(roles)
                && position.getHiringTrack() != RecruitmentHiringTrack.PARTNER) {
            return assistantPracticeRoute(viewerUuid, roles, position);
        }
        return viewerUuid.equals(position.getHiringOwnerUuid())
                || assistantPracticeRoute(viewerUuid, roles, position);
    }

    // ---- Application visibility (P4) -----------------------------------------

    /**
     * May the viewer read this application? An application is exactly as
     * visible as its position — the position rule ({@link #canReadPosition})
     * is the single source of truth; partner-track applications never leak
     * outside the circle.
     */
    public boolean canReadApplication(String viewerUuid, RecruitmentApplication application) {
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        return position != null && canReadPosition(viewerUuid, position);
    }

    /**
     * The candidate's applications the viewer may see, ordered oldest-first.
     * Same rule as {@link #canReadApplication}, evaluated with batched
     * lookups (roles, circle memberships, led teams/practices are each
     * fetched once, not per row).
     */
    public List<RecruitmentApplication> filterApplications(String viewerUuid, String candidateUuid) {
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "candidateUuid = ?1 order by createdAt", candidateUuid);
        return filterApplicationsBatch(viewerUuid, applications);
    }

    /**
     * Batch variant for list pages: the visible IN-PLAY applications of many
     * candidates in two queries plus one viewer-context resolution. Keys
     * with no visible open application are absent from the map.
     * <p>
     * "In play" excludes the HIRED stage as well as terminals — HIRED keeps
     * {@code terminal} NULL by design. This feeds {@code CandidateSummary
     * .activeApplications}, which the UI reads as a PREDICATE, not just a
     * label: an empty list means "in no pipeline" (the grid's status badge
     * derivation) and a non-empty one blocks attaching the candidate to a
     * position (the one-open-application invariant). Leaving hires in made
     * both of those lie, and disagreed with the invariant's own authority,
     * {@code RecruitmentApplicationService.openApplicationOf}, which has
     * always excluded HIRED. The cost is that a hired candidate's row no
     * longer names the position they were hired onto — the Hired status
     * badge already says the part that matters.
     */
    public Map<String, List<RecruitmentApplication>> filterOpenApplicationsByCandidate(
            String viewerUuid, Collection<String> candidateUuids) {
        if (candidateUuids == null || candidateUuids.isEmpty()) {
            return Map.of();
        }
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "candidateUuid in ?1 and terminal is null and stage <> ?2 order by createdAt",
                List.copyOf(candidateUuids), RecruitmentStage.HIRED);
        return filterApplicationsBatch(viewerUuid, applications).stream()
                .collect(Collectors.groupingBy(RecruitmentApplication::getCandidateUuid));
    }

    /**
     * Apply the position-visibility rule to a pre-fetched application list
     * with per-call (not per-row) lookups. Delegates the per-position
     * decision to {@link #readablePositionUuids} — the single batched twin
     * of {@link #canReadPosition}.
     */
    private List<RecruitmentApplication> filterApplicationsBatch(
            String viewerUuid, List<RecruitmentApplication> applications) {
        if (applications.isEmpty()) {
            return applications;
        }
        List<String> positionUuids = applications.stream()
                .map(RecruitmentApplication::getPositionUuid)
                .distinct()
                .toList();
        List<RecruitmentPosition> positions =
                RecruitmentPosition.list("uuid in ?1", positionUuids);
        Set<String> readable = readablePositionUuids(viewerUuid, positions);
        // Defensive: an application whose position row is gone (FK makes
        // this unreachable) is filtered out with the rest.
        return applications.stream()
                .filter(application -> readable.contains(application.getPositionUuid()))
                .toList();
    }

    /**
     * Batched twin of {@link #canReadPosition} over pre-fetched positions:
     * the subset of position uuids the viewer may read, resolved with ONE
     * viewer-context lookup (roles, circle memberships, led teams/practices
     * each fetched once — never per row). The decision logic mirrors
     * {@link #canReadPosition} exactly — change them together. Consumers:
     * application filtering (P4) and the P8 timeline's CIRCLE-event filter.
     */
    public Set<String> readablePositionUuids(String viewerUuid,
                                             Collection<RecruitmentPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        Set<String> roles = rolesOf(viewerUuid);
        boolean admin = roles.contains(ROLE_ADMIN);
        boolean readTier = roles.stream().anyMatch(POSITION_READ_ROLES::contains);
        boolean assistantScoped = isAssistantScoped(roles);
        String assistantPractice = assistantScoped
                ? practiceOfUser(viewerUuid) : null;

        Set<String> circled = admin ? Set.of() : circledPositionUuids(viewerUuid);
        Set<String> ledTeams = (admin || readTier || assistantScoped) ? Set.of()
                : new HashSet<>(currentlyLedTeams(viewerUuid));

        return positions.stream().filter(position -> {
            if (admin) {
                return true;
            }
            if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
                return circled.contains(position.getUuid());
            }
            if (readTier) {
                return true;
            }
            if (assistantScoped) {
                return assistantPractice != null
                        && assistantPractice.equals(position.getPracticeUuid());
            }
            // The practice-lead route is gone (decision 11).
            return viewerUuid != null && (viewerUuid.equals(position.getHiringOwnerUuid())
                    || (position.getTeamUuid() != null && ledTeams.contains(position.getTeamUuid())));
        }).map(RecruitmentPosition::getUuid).collect(Collectors.toSet());
    }

    /**
     * The positions that are <b>the viewer's own</b>, as opposed to merely
     * readable — the "Your pipelines" rule (landing page, decided
     * 2026-08-11; practice routes reworked by decisions 3 and 11,
     * 2026-08-23). A position is theirs when any of these holds:
     * <ol>
     *   <li>they are the named hiring owner (except that assistant-only
     *       standing remains exclusively practice-scoped);</li>
     *   <li>they hold {@code ASSISTANT_TEAMLEAD} and it is a non-partner
     *       position of the practice they belong to — the assistant's whole
     *       scope IS their practice, so those pipelines are "theirs";</li>
     *   <li>they were invited onto its circle.</li>
     * </ol>
     * The former practice-run routes (led teams' practices, registered
     * practice leads) are gone with decision 11: a {@code TEAMLEAD} now
     * decides on every non-partner pipeline by role, and "your pipelines"
     * narrows to the ones somebody named them on or invited them into.
     * <p>
     * Because it never widens anything, it deliberately does <em>not</em>
     * re-apply the partner-circle filter: the caller passes positions that
     * already cleared visibility, and circle membership is itself one of
     * the routes (the assistant route excludes partner track on its own).
     * <p>
     * One viewer-context resolution, never per row — the class's no-N+1 rule.
     */
    public Set<String> ownPositionUuids(String viewerUuid,
                                        Collection<RecruitmentPosition> positions) {
        if (viewerUuid == null || viewerUuid.isBlank()
                || positions == null || positions.isEmpty()) {
            return Set.of();
        }
        Set<String> roles = rolesOf(viewerUuid);
        boolean assistantScoped = isAssistantScoped(roles);
        Set<String> circled = circledPositionUuids(viewerUuid);
        String assistantPractice = roles.contains(ROLE_ASSISTANT_TEAMLEAD)
                ? practiceOfUser(viewerUuid) : null;

        return positions.stream()
                .filter(position -> {
                    boolean assistantPracticePosition = assistantPractice != null
                            && position.getHiringTrack() != RecruitmentHiringTrack.PARTNER
                            && assistantPractice.equals(position.getPracticeUuid());
                    if (assistantScoped) {
                        return assistantPracticePosition
                                || (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER
                                    && circled.contains(position.getUuid()));
                    }
                    return viewerUuid.equals(position.getHiringOwnerUuid())
                            || assistantPracticePosition
                            || circled.contains(position.getUuid());
                })
                .map(RecruitmentPosition::getUuid)
                .collect(Collectors.toSet());
    }

    /**
     * Batched twin of {@link #canDecideOnApplication} over pre-fetched
     * positions: the subset the viewer may act on, resolved with ONE
     * viewer-context lookup (roles, circle memberships + their roles, led
     * teams, own practices — each fetched once, never per row).
     * <p>
     * The single implementation of the batched form on purpose. The landing
     * page grew a private copy of this predicate whose comment said "change
     * the two together"; the moment the practice route landed, that would
     * have been two places to remember. Callers: the landing page's decision
     * tasks and the positions list's {@code viewerCanMutate} stamp — the
     * latter would otherwise re-resolve the viewer for every row on the page.
     * <p>
     * Mirrors {@link #canDecideOnApplication} exactly; change them together.
     */
    public Set<String> decidablePositionUuids(String viewerUuid,
                                              Collection<RecruitmentPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        Set<String> roles = rolesOf(viewerUuid);
        boolean admin = roles.contains(ROLE_ADMIN);
        // Decision 1: the whole read tier (HR/RECRUITMENT/TEAMLEAD) decides
        // on every non-partner position.
        boolean decideTier = admin || roles.stream().anyMatch(POSITION_READ_ROLES::contains);
        boolean assistantScoped = isAssistantScoped(roles);
        String assistantPractice = assistantScoped
                ? practiceOfUser(viewerUuid) : null;
        Set<String> ledTeams = (decideTier || assistantScoped) ? Set.of()
                : new HashSet<>(currentlyLedTeams(viewerUuid));
        Map<String, RecruitmentCircleRole> circleRoles = admin ? Map.of()
                : circleRolesFor(viewerUuid);

        return positions.stream().filter(position -> {
            if (admin) {
                return true;
            }
            if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
                // canManageCircle: plain HR always; otherwise an OWNER or
                // RECRUITER seat in this position's circle. Neither the
                // decide tier nor the assistant practice grants anything
                // here.
                if (roles.contains(ROLE_HR)) {
                    return true;
                }
                RecruitmentCircleRole role = circleRoles.get(position.getUuid());
                return role == RecruitmentCircleRole.OWNER
                        || role == RecruitmentCircleRole.RECRUITER;
            }
            if (decideTier) {
                return true;
            }
            if (assistantScoped) {
                return assistantPractice != null
                        && assistantPractice.equals(position.getPracticeUuid());
            }
            // Involvement: named owner or current team lead — the
            // practice-lead routes are gone (decision 11).
            return viewerUuid != null && (viewerUuid.equals(position.getHiringOwnerUuid())
                    || (position.getTeamUuid() != null && ledTeams.contains(position.getTeamUuid())));
        }).map(RecruitmentPosition::getUuid).collect(Collectors.toSet());
    }

    /** The position uuids of every circle the viewer belongs to (one query). */
    Set<String> circledPositionUuids(String viewerUuid) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return Set.of();
        }
        return RecruitmentCircleMember.<RecruitmentCircleMember>list("userUuid", viewerUuid).stream()
                .map(RecruitmentCircleMember::getPositionUuid)
                .collect(Collectors.toSet());
    }

    /** Circle roles keyed by position, resolved once for batched decisions. */
    Map<String, RecruitmentCircleRole> circleRolesFor(String viewerUuid) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return Map.of();
        }
        return RecruitmentCircleMember.<RecruitmentCircleMember>list("userUuid", viewerUuid)
                .stream()
                .collect(Collectors.toMap(RecruitmentCircleMember::getPositionUuid,
                        RecruitmentCircleMember::getRoleInCircle,
                        (first, second) -> first));
    }

    // ---- Candidate profile visibility (P8) -----------------------------------

    /**
     * May the viewer read this candidate's profile — timeline, form answers,
     * documents, consents (P8 contract, binding)? Tiers, in order:
     * <ol>
     *   <li>{@code ADMIN} → always, including hired files and partner
     *       track.</li>
     *   <li><b>Hired-file restriction</b> (spec §7.2 field gate): once the
     *       candidate's status is {@link CandidateStatus#HIRED} (set by the
     *       conversion flow's {@code markHired}), access narrows to
     *       {@link #HIRED_FILE_ROLES} — involvement alone (teamlead, hiring
     *       owner, practice lead, circle member) no longer grants access.</li>
     *   <li>Profile-read tier ({@link #PROFILE_READ_ROLES}) → yes, minus
     *       <em>partner-track-only</em> candidates: ALL of the candidate's
     *       applications sit on PARTNER positions and the viewer is in none
     *       of those circles (the spec §7.2 hard circle filter, applied to
     *       candidates).</li>
     *   <li>Assistant tier ({@code ASSISTANT_TEAMLEAD}, decisions 5/8):
     *       only candidates with an application on a non-partner position
     *       in the viewer's practice
     *       ({@link #hasApplicationInAssistantPractice}); an unapplied
     *       candidate is invisible.</li>
     *   <li>Involvement tier (everyone else): <b>ownership or current
     *       leadership</b> of a non-partner position the candidate applied
     *       to — the named hiring owner or the current lead of the
     *       position's team
     *       ({@link #hasOwnershipOrLeadershipInvolvement}).</li>
     * </ol>
     * <b>Interview assignment and circle membership do NOT open this
     * profile</b> (go-live decisions D10/D11). They open the far narrower
     * {@link #canReadRestrictedCandidateView} — name, links, CV, position,
     * interview details, application answers and the viewer's own scorecard.
     * That is a deliberate narrowing of the P11 interviewer grant: an
     * interviewer needs the kit, not the pipeline stage, the timeline, the
     * comp data or their colleagues' scorecards.
     * <p>
     * The partner circle stays a hard filter in every tier except ADMIN —
     * including the hired-file tier. Callers answer 404 (never 403) when
     * this returns {@code false}: existence must not leak.
     */
    public boolean canReadCandidateProfile(String viewerUuid, RecruitmentCandidate candidate) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidate == null) {
            return false;
        }
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN)) {
            return true;
        }
        if (candidate.getStatus() == CandidateStatus.HIRED) {
            // Involvement never survives HIRED: colleagues must not browse a
            // new colleague's interview file.
            // GDPR duty (formerly the hardcoded DPO role) is a permission grant.
            return canReadHiredCandidateFiles(viewerUuid)
                    && !isPartnerTrackOnly(viewerUuid, candidate.getUuid());
        }
        if (roles.stream().anyMatch(PROFILE_READ_ROLES::contains)) {
            return !isPartnerTrackOnly(viewerUuid, candidate.getUuid());
        }
        if (isAssistantScoped(roles)) {
            // Assistant-only is exclusively practice-scoped for the full
            // profile. Named-owner/current-lead involvement must not bypass
            // an out-of-practice or missing-practice denial.
            return hasApplicationInAssistantPractice(viewerUuid, candidate.getUuid());
        }
        return hasOwnershipOrLeadershipInvolvement(viewerUuid, candidate.getUuid());
    }

    /**
     * The assistant's candidate scope (decisions 5 and 8): the candidate has
     * at least one application on a <b>non-partner</b> position of the
     * practice the viewer belongs to. A candidate with no application
     * resolves to no position, hence no practice, and is therefore invisible
     * to an assistant (decision 8) — including one the assistant would have
     * created themselves, which is why they don't create (decision 10).
     * Partner positions never qualify: the circle stays their only key.
     */
    public boolean hasApplicationInAssistantPractice(String viewerUuid, String candidateUuid) {
        String practice = practiceOfUser(viewerUuid);
        if (practice == null || candidateUuid == null) {
            return false;
        }
        return !em.createNativeQuery("""
                        SELECT 1 FROM recruitment_applications a
                        JOIN recruitment_positions p ON p.uuid = a.position_uuid
                        WHERE a.candidate_uuid = :candidate
                          AND p.hiring_track <> 'PARTNER'
                          AND p.practice_uuid = :practice
                        LIMIT 1
                        """)
                .setParameter("candidate", candidateUuid)
                .setParameter("practice", practice)
                .getResultList()
                .isEmpty();
    }

    /**
     * Batched candidate-profile scope for the assistant database grid and
     * bulk actions: every candidate uuid visible through the assistant
     * practice route, in one query. Unlike the narrower
     * {@link #hasApplicationInAssistantPractice} building block, this also
     * applies the canonical hired-file cutoff from
     * {@link #canReadCandidateProfile}: an application must not keep a new
     * employee's former candidate file visible after conversion. Empty when
     * the viewer has no practice (fail closed — the assignment rail should
     * have refused the role, but data drifts).
     */
    @SuppressWarnings("unchecked")
    public List<String> assistantVisibleCandidateUuids(String viewerUuid) {
        String practice = practiceOfUser(viewerUuid);
        if (practice == null) {
            return List.of();
        }
        return em.createNativeQuery("""
                        SELECT DISTINCT a.candidate_uuid
                        FROM recruitment_applications a
                        JOIN recruitment_positions p ON p.uuid = a.position_uuid
                        JOIN recruitment_candidates c ON c.uuid = a.candidate_uuid
                        WHERE p.hiring_track <> 'PARTNER'
                          AND p.practice_uuid = :practice
                          AND c.status <> 'HIRED'
                        """)
                .setParameter("practice", practice)
                .getResultList();
    }

    /**
     * Involvement that is strong enough for the <em>full</em> candidate
     * profile without a recruitment role: the viewer owns or currently
     * leads the team of a <b>non-partner</b> position the candidate applied
     * to. The practice-lead hop is gone (decision 11). Partner-track
     * positions grant nothing here — the circle is their only key, and a
     * circle member gets {@link #canReadRestrictedCandidateView}, not this.
     */
    private boolean hasOwnershipOrLeadershipInvolvement(String viewerUuid, String candidateUuid) {
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid", candidateUuid);
        if (applications.isEmpty()) {
            return false;
        }
        List<String> positionUuids = applications.stream()
                .map(RecruitmentApplication::getPositionUuid)
                .distinct()
                .toList();
        List<RecruitmentPosition> positions =
                RecruitmentPosition.list("uuid in ?1", positionUuids);
        Set<String> ledTeams = new HashSet<>(currentlyLedTeams(viewerUuid));
        return positions.stream().anyMatch(position ->
                position.getHiringTrack() != RecruitmentHiringTrack.PARTNER
                        && (viewerUuid.equals(position.getHiringOwnerUuid())
                            || (position.getTeamUuid() != null
                                && ledTeams.contains(position.getTeamUuid()))));
    }

    /**
     * Whether the viewer is an assigned interviewer on any non-cancelled
     * interview of any of the candidate's applications (P11). Assignment is
     * the spec §7.2 "Interviewer" tier: it grants candidate-profile
     * involvement (kit access — CV, answers, timeline) for exactly this
     * candidate, nothing position-wide. Cancelled interviews grant nothing.
     */
    public boolean hasInterviewAssignment(String viewerUuid, String candidateUuid) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidateUuid == null) {
            return false;
        }
        return !em.createNativeQuery("""
                        SELECT 1 FROM recruitment_interviews i
                        JOIN recruitment_applications a ON a.uuid = i.application_uuid
                        WHERE a.candidate_uuid = :candidate
                          AND i.status <> 'CANCELLED'
                          AND JSON_CONTAINS(i.interviewer_uuids, JSON_QUOTE(:viewer))
                        LIMIT 1
                        """)
                .setParameter("candidate", candidateUuid)
                .setParameter("viewer", viewerUuid)
                .getResultList()
                .isEmpty();
    }

    /**
     * Whether the viewer is a member of the circle of at least one position
     * the candidate has applied to (one query). Circle membership is an
     * explicit, per-hire invitation — see
     * {@link #canReadRestrictedCandidateView}.
     */
    public boolean hasCircleInvolvement(String viewerUuid, String candidateUuid) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidateUuid == null) {
            return false;
        }
        return !em.createNativeQuery("""
                        SELECT 1 FROM recruitment_applications a
                        JOIN recruitment_circle_members m ON m.position_uuid = a.position_uuid
                        WHERE a.candidate_uuid = :candidate AND m.user_uuid = :viewer
                        LIMIT 1
                        """)
                .setParameter("candidate", candidateUuid)
                .setParameter("viewer", viewerUuid)
                .getResultList()
                .isEmpty();
    }

    /**
     * The <b>restricted candidate view</b> grant (go-live decisions D10–D12):
     * may this viewer open the cut-down candidate page — name, links, CV,
     * position, interview details, application answers and their own
     * scorecard, and nothing else?
     * <p>
     * Granted while the candidate is {@link CandidateStatus#ACTIVE} and the
     * viewer is either an assigned interviewer on one of the candidate's
     * applications ({@link #hasInterviewAssignment}) or a member of one of
     * those positions' circles ({@link #hasCircleInvolvement}). This is the
     * <em>only</em> recruitment surface a person with no recruitment role
     * can reach, and it is read-only: it grants no position visibility, no
     * board, no stage, no timeline, no comp data and no other candidate.
     * <p>
     * The window closes with the candidate's status (D12): once they are
     * HIRED, REJECTED, WITHDRAWN or POOLED the grant is gone, so a finished
     * process stops being readable without any revocation step.
     * <p>
     * Callers answer <b>404</b> (never 403) when this returns {@code false}:
     * existence must not leak, exactly as for the full profile.
     */
    public boolean canReadRestrictedCandidateView(String viewerUuid, RecruitmentCandidate candidate) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidate == null) {
            return false;
        }
        if (candidate.getStatus() != CandidateStatus.ACTIVE) {
            return false;
        }
        return hasInterviewAssignment(viewerUuid, candidate.getUuid())
                || hasCircleInvolvement(viewerUuid, candidate.getUuid());
    }

    /** Single-candidate variant of {@link #partnerTrackOnlyCandidateUuids}. */
    public boolean isPartnerTrackOnly(String viewerUuid, String candidateUuid) {
        return !partnerTrackOnlyCandidateUuids(viewerUuid, candidateUuid).isEmpty();
    }

    /**
     * The candidates that are <b>partner-track-only for this viewer</b>:
     * they have at least one application, ALL their applications sit on
     * {@code PARTNER}-track positions, and the viewer is in none of those
     * circles. These rows are invisible to the viewer everywhere — the P8
     * database grid excludes them query-level (the P4 carry-over "partner-row
     * gap", findings §P4), profile reads answer 404, and bulk mutations treat
     * them as nonexistent. Candidates with zero applications are never in
     * this set (they remain visible). ADMIN viewers get an empty set.
     * <p>
     * One query, evaluated in the database (no N+1): a candidate is in the
     * set when no application of theirs sits on a position that is either
     * non-partner or partner-with-the-viewer-in-the-circle. A {@code null}
     * viewer (legacy callers without {@code X-Requested-By}) is treated as
     * "no circles, not admin" — fail closed.
     *
     * @param candidateUuid optional: restrict the check to one candidate
     *                      ({@code null} = whole table, for list queries)
     */
    @SuppressWarnings("unchecked")
    public List<String> partnerTrackOnlyCandidateUuids(String viewerUuid, String candidateUuid) {
        if (viewerUuid != null && rolesOf(viewerUuid).contains(ROLE_ADMIN)) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ra.candidate_uuid
                FROM recruitment_applications ra
                WHERE NOT EXISTS (
                    SELECT 1 FROM recruitment_applications ra2
                    JOIN recruitment_positions p2 ON p2.uuid = ra2.position_uuid
                    WHERE ra2.candidate_uuid = ra.candidate_uuid
                      AND (p2.hiring_track <> 'PARTNER'
                           OR EXISTS (SELECT 1 FROM recruitment_circle_members m
                                      WHERE m.position_uuid = p2.uuid
                                        AND m.user_uuid = :viewer))
                )
                """);
        if (candidateUuid != null) {
            sql.append(" AND ra.candidate_uuid = :candidate");
        }
        var query = em.createNativeQuery(sql.toString())
                // Blank sentinel for headerless callers: matches no circle row.
                .setParameter("viewer", viewerUuid != null ? viewerUuid : "");
        if (candidateUuid != null) {
            query.setParameter("candidate", candidateUuid);
        }
        return query.getResultList();
    }

    /**
     * The comp tier for a candidate's salary-expectation data (P8 contract,
     * widened by go-live decision D6): {@code ADMIN}, recruiter tier
     * (HR/RECRUITMENT) and {@code TEAMLEAD} — the same population that reads
     * the candidate profile — plus a hiring owner/current team leader through
     * the ordinary involvement tier. Assistant-only standing is instead
     * exclusively own-practice and fails closed without a practice.
     * Interviewers, circle-only viewers and practice leads are deliberately
     * outside: they see the event, not the amount
     * (spec §7.2 {@code recruitment:comp} row).
     *
     * @param candidatePositions the (pre-fetched) positions of the
     *                           candidate's applications — batched by the
     *                           caller, never re-fetched per event
     */
    public boolean isCompTierFor(String viewerUuid, Collection<RecruitmentPosition> candidatePositions) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return false;
        }
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN) || roles.stream().anyMatch(PROFILE_READ_ROLES::contains)) {
            return true;
        }
        if (candidatePositions == null || candidatePositions.isEmpty()) {
            return false;
        }
        // The assistant sees the salary-expectation events of their
        // practice's candidates — pre-offer pipeline data, same population
        // whose profile they read. The OFFER dossier's comp is a different
        // surface and stays closed to them (decision 9, canReadDossier).
        if (isAssistantScoped(roles)) {
            String practice = practiceOfUser(viewerUuid);
            return practice != null && candidatePositions.stream().anyMatch(p ->
                    p.getHiringTrack() != RecruitmentHiringTrack.PARTNER
                            && practice.equals(p.getPracticeUuid()));
        }
        if (candidatePositions.stream().anyMatch(p -> viewerUuid.equals(p.getHiringOwnerUuid()))) {
            return true;
        }
        Set<String> ledTeams = new HashSet<>(currentlyLedTeams(viewerUuid));
        return candidatePositions.stream()
                .anyMatch(p -> p.getTeamUuid() != null && ledTeams.contains(p.getTeamUuid()));
    }

    // ---- Offer dossier / contract -------------------------------------------

    /**
     * Whether the viewer <b>runs the hire</b> for this candidate: they are
     * the <em>named</em> hiring owner of at least one position the candidate
     * applied to. (The 2026-08-12 practice-run route was removed by decision
     * 11, 2026-08-23 — running a practice grants nothing in recruitment any
     * more.)
     * <p>
     * <b>The application must be one the viewer did not file themselves</b>
     * ({@link #creatorConfersHire}, 2026-08-19). Until {@code
     * recruitment:intake} existed, attaching a candidate to a position was
     * an ADMIN/HR/RECRUITMENT act, so "an application exists on a position I
     * run" was always someone else's statement about the viewer. Intake
     * handed that write to the whole {@code TEAMLEAD} population, and
     * without this clause a team lead could manufacture their own dossier
     * read: attach any candidate they can already see — a dossier-only
     * candidate from the legacy HR flow, or one whose application went
     * terminal after an offer was drafted — to a position in their own
     * practice, and the contract PDF (salary, terms, start date) opens.
     * That is exactly what the 2026-08-11 decision recorded on
     * {@link #canReadDossier} refused to do directly.
     * <p>
     * Two clauses (see {@link #creatorConfersHire} for the exact order):
     * <ol>
     *   <li>the creator is not the viewer — no self-service;</li>
     *   <li>the creator is not <em>another</em> intake-only actor — so two
     *       team leads sharing a practice cannot file for each other and
     *       both walk in.</li>
     * </ol>
     * Clause 2 is keyed on the {@code recruitment:intake} grant rather than
     * on the {@code TEAMLEAD} role, so a future console grant to some other
     * role is covered without a code change. It is deliberately <em>not</em>
     * "the creator must be recruiter tier": that phrasing reads the same
     * until you notice it denies every row whose {@code created_by} no
     * longer resolves — {@code system}, batch imports, migration literals,
     * decommissioned accounts, {@code 'test'} — and quietly revokes the
     * 2026-08-12 practice-lead grant on real historical data. (It does: it
     * broke {@code RecruitmentDossierEndpointAuthzApiTest} the first time it
     * was written that way.) Nothing is lost by conferring there, because an
     * unresolvable creator is by definition not a live intake holder — every
     * such actor authenticates, and {@code created_by} comes from the
     * session-set {@code X-Requested-By}, never from the caller's body.
     * <p>
     * One query, plus a roles lookup and at most one grant lookup per
     * distinct creator (in practice one).
     * A partner-track application qualifies only while the viewer is also a
     * member of that position's circle. This condition belongs inside the
     * owner-application query: for a mixed candidate, a visible non-partner
     * application makes the full profile readable, so composing with
     * {@link #canReadCandidateProfile} alone cannot stop a hidden partner
     * position from conferring run-the-hire and dossier access.
     */
    public boolean isHiringOwnerForCandidate(String viewerUuid, String candidateUuid) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidateUuid == null) {
            return false;
        }
        for (String creator : hiringOwnerApplicationCreators(viewerUuid, candidateUuid)) {
            if (creatorConfersHire(creator, viewerUuid, () -> rolesOf(creator),
                    () -> holdsRecruitmentIntakeGrant(creator))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code created_by} of every application of this candidate whose
     * position names the viewer as hiring owner. The practice-run clause is
     * gone (decision 11, 2026-08-23): "whoever runs the hire" now means the
     * <em>named</em> hiring owner and nobody else — a team lead reads the
     * dossier of exactly the hires somebody named them on. Partner-track
     * rows additionally require a circle membership, including when the
     * candidate also has an ordinary visible application. Split out of
     * {@link #isHiringOwnerForCandidate} so the creator rule above it can be
     * exercised without a database (the test subclasses this).
     */
    protected List<String> hiringOwnerApplicationCreators(String viewerUuid, String candidateUuid) {
        @SuppressWarnings("unchecked")
        List<String> creators = em.createNativeQuery("""
                        SELECT DISTINCT a.created_by FROM recruitment_applications a
                        JOIN recruitment_positions p ON p.uuid = a.position_uuid
                        WHERE a.candidate_uuid = :candidate
                          AND p.hiring_owner_uuid = :viewer
                          AND (p.hiring_track <> 'PARTNER'
                               OR EXISTS (
                                   SELECT 1 FROM recruitment_circle_members m
                                   WHERE m.position_uuid = p.uuid
                                     AND m.user_uuid = :viewer
                               ))
                        """)
                .setParameter("candidate", candidateUuid)
                .setParameter("viewer", viewerUuid)
                .getResultList();
        return creators;
    }

    /** One candidate/creator pair from the batched named-owner lookup. */
    protected record HiringOwnerApplicationCreator(String candidateUuid, String creatorUuid) {}

    /** Current creator facts needed by {@link #creatorConfersHire}. */
    protected record CreatorAuthorizationStanding(Set<String> roles, boolean holdsIntake) {}

    /**
     * Batched twin of {@link #hiringOwnerApplicationCreators(String, String)}.
     * The partner-circle condition is intentionally identical to the scalar
     * query; callers must not reconstruct this relationship from a broader
     * candidate-profile grant.
     */
    @SuppressWarnings("unchecked")
    protected List<HiringOwnerApplicationCreator> hiringOwnerApplicationCreators(
            String viewerUuid, Collection<String> candidateUuids) {
        if (candidateUuids == null || candidateUuids.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT DISTINCT a.candidate_uuid, a.created_by
                        FROM recruitment_applications a
                        JOIN recruitment_positions p ON p.uuid = a.position_uuid
                        WHERE a.candidate_uuid IN (:candidates)
                          AND p.hiring_owner_uuid = :viewer
                          AND (p.hiring_track <> 'PARTNER'
                               OR EXISTS (
                                   SELECT 1 FROM recruitment_circle_members m
                                   WHERE m.position_uuid = p.uuid
                                     AND m.user_uuid = :viewer
                               ))
                        """)
                .setParameter("candidates", candidateUuids)
                .setParameter("viewer", viewerUuid)
                .getResultList();
        return rows.stream()
                .map(row -> new HiringOwnerApplicationCreator(
                        (String) row[0], (String) row[1]))
                .toList();
    }

    /**
     * Loads every creator's roles and boolean {@code recruitment:intake}
     * grant in one query. The permission subquery mirrors
     * {@code DbAuthzStore.loadEffectivePermissions}: active binding, active
     * permission and {@code data_scope = 'ALL'}. Creators without a roles row
     * are absent and therefore resolve to the scalar method's empty-role,
     * no-intake historical-actor case.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, CreatorAuthorizationStanding> creatorAuthorizationStandings(
            Collection<String> creatorUuids) {
        if (creatorUuids == null || creatorUuids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT r.useruuid, r.role,
                               CASE WHEN EXISTS (
                                   SELECT 1
                                   FROM roles intake_role
                                   JOIN role_permission rp
                                     ON rp.role = intake_role.role
                                    AND rp.revoked_at IS NULL
                                   JOIN permission permission_row
                                     ON permission_row.permission_key = rp.permission_key
                                    AND permission_row.revoked_at IS NULL
                                   WHERE intake_role.useruuid = r.useruuid
                                     AND rp.permission_key = 'recruitment:intake'
                                     AND rp.data_scope = 'ALL'
                               ) THEN 1 ELSE 0 END AS holds_intake
                        FROM roles r
                        WHERE r.useruuid IN (:creators)
                        """)
                .setParameter("creators", creatorUuids)
                .getResultList();

        Map<String, Set<String>> rolesByCreator = new HashMap<>();
        Map<String, Boolean> intakeByCreator = new HashMap<>();
        for (Object[] row : rows) {
            String creatorUuid = (String) row[0];
            String role = row[1] == null ? ""
                    : ((String) row[1]).toUpperCase(Locale.ROOT);
            rolesByCreator.computeIfAbsent(creatorUuid, ignored -> new HashSet<>()).add(role);
            boolean holdsIntake = row[2] instanceof Boolean bool
                    ? bool : row[2] instanceof Number number && number.intValue() != 0;
            intakeByCreator.merge(creatorUuid, holdsIntake, Boolean::logicalOr);
        }

        Map<String, CreatorAuthorizationStanding> result = new HashMap<>();
        rolesByCreator.forEach((creatorUuid, roles) -> result.put(creatorUuid,
                new CreatorAuthorizationStanding(Set.copyOf(roles),
                        intakeByCreator.getOrDefault(creatorUuid, false))));
        return Map.copyOf(result);
    }

    /**
     * Does an application filed by {@code creatorUuid} count as somebody
     * <em>else's</em> statement that {@code viewerUuid} runs this hire? See
     * {@link #isHiringOwnerForCandidate} for why the question is asked at
     * all.
     *
     * <p>The order matters and is the rule:</p>
     * <ol>
     *   <li>an unattributed row confers — {@code created_by} is
     *       {@code NOT NULL} in the schema, so blank means damaged data, not
     *       an actor;</li>
     *   <li>the viewer's own row never confers, whatever roles they hold:
     *       this is the self-service clause, and it must not be escapable by
     *       collecting a role;</li>
     *   <li>{@value #SYSTEM_ACTOR} confers — {@code AuditEntityListener}'s
     *       fallback for public {@code /apply}, batch imports and startup
     *       jobs;</li>
     *   <li>a recruiter-tier or ADMIN filer confers — the normal production
     *       flow, and the only one that existed before intake;</li>
     *   <li>anyone else holding {@code recruitment:intake} does NOT confer —
     *       the cross-filing clause;</li>
     *   <li>everyone else confers. Reached by rows whose creator no longer
     *       resolves to anything: departed accounts, imports, fixtures. They
     *       are not live intake holders, so conferring opens nothing, and
     *       denying would revoke the 2026-08-12 grant on historical data.</li>
     * </ol>
     *
     * <p>The two lookups are suppliers so the ordering above is also a
     * statement about cost: a self-filed row is refused without touching the
     * database, and the grant store is only asked about a filer who already
     * failed the recruiter-tier check.</p>
     *
     * @param creatorUuid  {@code recruitment_applications.created_by}: a user
     *                     uuid, or {@value #SYSTEM_ACTOR} when the row was
     *                     written without an {@code X-Requested-By} header
     * @param viewerUuid   the user asking to read the dossier
     * @param creatorRoles the filer's <em>current</em> roles, lazily
     * @param creatorHoldsIntake whether the filer currently holds
     *                     {@code recruitment:intake}, lazily
     */
    static boolean creatorConfersHire(String creatorUuid, String viewerUuid,
                                      Supplier<Set<String>> creatorRoles,
                                      BooleanSupplier creatorHoldsIntake) {
        if (creatorUuid == null || creatorUuid.isBlank()) {
            return true;
        }
        if (SYSTEM_ACTOR.equals(creatorUuid)) {
            return true;
        }
        // Recruiter tier is checked BEFORE the self clause, deliberately.
        // ADMIN/HR/RECRUITMENT could already file an application and be the
        // named hiring owner long before recruitment:intake existed, so a
        // self-filed row has always conferred for them. Denying it here would
        // not close the escalation (which is reachable only through the new
        // intake grant) — it would newly 403 a recruiter who reads that
        // dossier today under D18. That regression shape has bitten this
        // codebase before; do not "simplify" this ordering away.
        Set<String> roles = creatorRoles.get();
        if (roles != null && (roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains))) {
            return true;
        }
        if (creatorUuid.equals(viewerUuid)) {
            return false;
        }
        return !creatorHoldsIntake.getAsBoolean();
    }

    /**
     * May the viewer <em>read</em> the offer dossier — the contract drafts,
     * revisions and signing status (go-live decision, 2026-08-11)?
     * <p>
     * {@code ADMIN} and {@code HR} only, plus the named {@code TEAMLEAD}
     * returned by {@link #isHiringOwnerForCandidate} (with no practice-lead
     * fallback) — a read-only view of the hire they are running, so they can
     * see that the contract went out without being able to touch it. Everyone
     * else — including {@code RECRUITMENT}, a plain named owner, and every
     * unnamed {@code TEAMLEAD} — answers 404.
     * <p>
     * The hiring-owner grant is composed with
     * {@link #canReadCandidateProfile}, so it inherits that rule's two hard
     * limits for free: a partner-track candidate stays circle-gated, and the
     * dossier closes at {@code HIRED} along with the rest of the file (a
     * colleague must not browse a new colleague's contract).
     * <p>
     * This is deliberately NOT {@code canReadCandidateProfile} on its own.
     * That tier now includes every {@code TEAMLEAD}, so using it here — as
     * the dossier endpoints did until 2026-08-11 — would have handed the
     * whole contract flow to 20 people at the backend, with only the BFF's
     * role array standing in the way.
     * <p>
     * Nor may a viewer <em>create</em> the fact this gate reads. Since
     * {@code recruitment:intake} let team leads attach candidates,
     * {@link #isHiringOwnerForCandidate} ignores applications the viewer
     * filed themselves — otherwise one attach to a position in your own
     * practice is a self-service grant to the contract PDF. That clause is
     * the whole reason the method takes a second query; do not "simplify" it
     * back to {@code SELECT 1}.
     */
    public boolean canReadDossier(String viewerUuid, RecruitmentCandidate candidate) {
        if (viewerUuid == null || viewerUuid.isBlank() || candidate == null) {
            return false;
        }
        // Decision 9 — the one deliberate deviation from "same rights as a
        // team lead": no dossier for the assistant. No read, no signature
        // status, no comp — not even as a named hiring owner. Keyed on
        // isAssistantScoped so a TEAMLEAD or recruiter who also holds the
        // assistant role keeps whatever their wider standing grants.
        Set<String> roles = rolesOf(viewerUuid);
        if (isAssistantScoped(roles)) {
            return false;
        }
        if (roles.contains(ROLE_ADMIN) || roles.contains(ROLE_HR)) {
            return true;
        }
        return roles.contains(ROLE_TEAMLEAD)
                && isHiringOwnerForCandidate(viewerUuid, candidate.getUuid())
                && canReadCandidateProfile(viewerUuid, candidate);
    }

    /**
     * Batched, policy-equivalent form of
     * {@link #canReadDossier(String, RecruitmentCandidate)} for the board,
     * landing and candidate-list read models. Its query count is independent
     * of candidate and creator cardinality: one viewer-role lookup, one
     * named-owner query and one creator-standing query (plus at most one
     * cached GDPR-grant lookup when a named TEAMLEAD is evaluating HIRED
     * files).
     *
     * <p>A qualifying owner application itself proves the profile's partner
     * constraint: it is either non-partner, or it is partner-track and the
     * owner query found the viewer in that position's circle. The only
     * remaining profile distinction is therefore the HIRED-file cutoff.</p>
     */
    public Set<String> dossierReadableCandidateUuids(
            String viewerUuid, Collection<RecruitmentCandidate> candidates) {
        if (viewerUuid == null || viewerUuid.isBlank()
                || candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        Map<String, RecruitmentCandidate> candidatesByUuid = candidates.stream()
                .filter(candidate -> candidate != null
                        && candidate.getUuid() != null
                        && !candidate.getUuid().isBlank())
                .collect(Collectors.toMap(RecruitmentCandidate::getUuid,
                        candidate -> candidate, (first, ignored) -> first));
        if (candidatesByUuid.isEmpty()) {
            return Set.of();
        }

        Set<String> viewerRoles = rolesOf(viewerUuid);
        if (isAssistantScoped(viewerRoles)) {
            return Set.of();
        }
        if (viewerRoles.contains(ROLE_ADMIN) || viewerRoles.contains(ROLE_HR)) {
            return Set.copyOf(candidatesByUuid.keySet());
        }
        if (!viewerRoles.contains(ROLE_TEAMLEAD)) {
            return Set.of();
        }

        List<HiringOwnerApplicationCreator> ownerRows =
                hiringOwnerApplicationCreators(viewerUuid, candidatesByUuid.keySet());
        if (ownerRows.isEmpty()) {
            return Set.of();
        }
        Set<String> creatorsToResolve = ownerRows.stream()
                .map(HiringOwnerApplicationCreator::creatorUuid)
                .filter(creator -> creator != null && !creator.isBlank())
                .filter(creator -> !SYSTEM_ACTOR.equals(creator))
                .filter(creator -> !viewerUuid.equals(creator))
                .collect(Collectors.toSet());
        Map<String, CreatorAuthorizationStanding> creatorStandings =
                creatorAuthorizationStandings(creatorsToResolve);

        Set<String> ownerCandidateUuids = ownerRows.stream()
                .filter(row -> candidatesByUuid.containsKey(row.candidateUuid()))
                .filter(row -> {
                    CreatorAuthorizationStanding standing;
                    if (row.creatorUuid() == null || row.creatorUuid().isBlank()
                            || SYSTEM_ACTOR.equals(row.creatorUuid())) {
                        standing = new CreatorAuthorizationStanding(Set.of(), false);
                    } else if (viewerUuid.equals(row.creatorUuid())) {
                        standing = new CreatorAuthorizationStanding(viewerRoles, false);
                    } else {
                        standing = creatorStandings.getOrDefault(row.creatorUuid(),
                                new CreatorAuthorizationStanding(Set.of(), false));
                    }
                    return creatorConfersHire(row.creatorUuid(), viewerUuid,
                            standing::roles, standing::holdsIntake);
                })
                .map(HiringOwnerApplicationCreator::candidateUuid)
                .collect(Collectors.toSet());
        if (ownerCandidateUuids.isEmpty()) {
            return Set.of();
        }

        boolean hasHiredOwnerCandidate = ownerCandidateUuids.stream()
                .map(candidatesByUuid::get)
                .anyMatch(candidate -> candidate.getStatus() == CandidateStatus.HIRED);
        boolean canReadHiredFiles = viewerRoles.stream().anyMatch(HIRED_FILE_ROLES::contains)
                || (hasHiredOwnerCandidate && canReadHiredCandidateFiles(viewerUuid));
        return ownerCandidateUuids.stream()
                .filter(candidateUuid -> candidatesByUuid.get(candidateUuid).getStatus()
                        != CandidateStatus.HIRED || canReadHiredFiles)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Candidate-uuid form used while mapping an application response. It is
     * intentionally just a fail-closed lookup followed by the canonical
     * {@link #canReadDossier(String, RecruitmentCandidate)} predicate, so the
     * UI capability and the dossier resource cannot drift into separate role
     * rules.
     */
    public boolean canReadDossier(String viewerUuid, String candidateUuid) {
        if (candidateUuid == null || candidateUuid.isBlank()) {
            return false;
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        return canReadDossier(viewerUuid, candidate);
    }

    /**
     * May the viewer <em>change</em> the dossier — edit it, attach
     * appendices, branch a revision, send for review or signature, convert
     * the candidate? {@code ADMIN} and {@code HR} only.
     * <p>
     * Role-only by design: unlike reading, this does not soften for the
     * hiring owner. Sending a contract and creating an employee are HR acts
     * (go-live decisions D1/D2), and the recruiter is excluded from them
     * just as firmly as the team lead.
     */
    public boolean canWriteDossier(String viewerUuid) {
        Set<String> roles = rolesOf(viewerUuid);
        return roles.contains(ROLE_ADMIN) || roles.contains(ROLE_HR);
    }

    /**
     * May the viewer manage (add/remove) the position's circle? ADMIN and HR
     * always; an {@code ASSISTANT_TEAMLEAD} within their practice (target
     * table 2026-08-23: ◐ practice — non-partner only, the
     * assistant route never fires on partner track); otherwise only circle
     * {@code OWNER}s and {@code RECRUITER}s — a {@code PARTICIPANT} can see
     * the position but not widen the circle.
     */
    public boolean canManageCircle(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN) || roles.contains("HR")) {
            return true;
        }
        if (isAssistantScoped(roles)
                && position.getHiringTrack() != RecruitmentHiringTrack.PARTNER) {
            // Non-partner circle membership is not an alternate route around
            // the assistant's exclusive practice scope.
            return assistantPracticeRoute(viewerUuid, roles, position);
        }
        if (assistantPracticeRoute(viewerUuid, roles, position)) {
            return true;
        }
        RecruitmentCircleMember membership = RecruitmentCircleMember.findById(
                new RecruitmentCircleMember.Key(position.getUuid(), viewerUuid));
        return membership != null
                && (membership.getRoleInCircle() == RecruitmentCircleRole.OWNER
                || membership.getRoleInCircle() == RecruitmentCircleRole.RECRUITER);
    }
}
