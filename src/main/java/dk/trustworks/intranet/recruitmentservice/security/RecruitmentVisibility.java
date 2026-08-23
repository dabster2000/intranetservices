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
     * Recruiter-tier roles: candidate e-mail, the AI brief, configuration
     * surfaces, dossier-adjacent work. {@code TEAMLEAD} is deliberately NOT
     * here — since decision 1 a teamlead decides on every non-partner
     * pipeline (see {@link #canDecideOnApplication}), but the recruiter
     * tier proper stays ADMIN/HR/RECRUITMENT.
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
     * The assistant's practice route (decisions 2–5): the viewer holds
     * {@code ASSISTANT_TEAMLEAD} and the position is a <b>non-partner</b>
     * position of the practice they belong to. Partner track is excluded
     * unconditionally — the circle stays its only key, and belonging to a
     * practice must never become a back door into a confidential hire.
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
            String assistantPractice = roles.contains(ROLE_ASSISTANT_TEAMLEAD)
                    ? practiceOfUser(viewerUuid) : null;
            if (roles.stream().anyMatch(POSITION_READ_ROLES::contains)) {
                // Read tier (recruiter + teamlead): everything except partner
                // track outside the circle. Decision rights are checked
                // separately by canDecideOnApplication.
                query.append(" and (p.hiringTrack <> :partnerTrack or ").append(circleExists).append(')');
            } else if (assistantPractice != null) {
                // Assistant tier (decisions 2–5): the non-partner positions
                // of the practice they belong to, plus circles they were
                // invited onto. The circle stays partner track's only key.
                query.append(" and ((p.hiringTrack <> :partnerTrack"
                                + " and p.practiceUuid = :assistantPractice) or ")
                        .append(circleExists)
                        .append(')');
                params.and("assistantPractice", assistantPractice);
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
     * mutation (you cannot change what you cannot see).
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
        if (assistantPracticeRoute(viewerUuid, roles, position)) {
            return true;
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
     * practice they belong to. <b>Final outcomes are the exception</b>
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
        return canDecideCore(viewerUuid, roles, position)
                || assistantPracticeRoute(viewerUuid, roles, position);
    }

    /**
     * May the viewer close an outcome — <b>hire, reject, withdraw,
     * return-to-pool</b> (decision 7: all four, and hiring is a stage move
     * in the data, so a terminal-only check would silently let an assistant
     * hire)? Exactly {@link #canDecideOnApplication} minus the assistant
     * practice route: an assistant moves candidates through stages but
     * never closes an outcome either way. Everyone who decides through any
     * other route — role or involvement — keeps final outcomes.
     * <p>
     * Callers: the three terminal endpoints on
     * {@code RecruitmentApplicationResource}, the REJECT half of
     * {@code recordDecision}, and (as {@link #canWriteDossier}) the hire
     * conversion.
     */
    public boolean canDecideFinalOutcome(String viewerUuid, RecruitmentPosition position) {
        return canDecideCore(viewerUuid, rolesOf(viewerUuid), position);
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
        String assistantPractice = (!admin && !readTier
                && roles.contains(ROLE_ASSISTANT_TEAMLEAD))
                ? practiceOfUser(viewerUuid) : null;

        Set<String> circled = admin ? Set.of() : circledPositionUuids(viewerUuid);
        Set<String> ledTeams = (admin || readTier) ? Set.of()
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
            if (assistantPractice != null && assistantPractice.equals(position.getPracticeUuid())) {
                return true;
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
     *   <li>they are the named hiring owner;</li>
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
        Set<String> circled = circledPositionUuids(viewerUuid);
        String assistantPractice = rolesOf(viewerUuid).contains(ROLE_ASSISTANT_TEAMLEAD)
                ? practiceOfUser(viewerUuid) : null;

        return positions.stream()
                .filter(position -> viewerUuid.equals(position.getHiringOwnerUuid())
                        || (assistantPractice != null
                            && position.getHiringTrack() != RecruitmentHiringTrack.PARTNER
                            && assistantPractice.equals(position.getPracticeUuid()))
                        || circled.contains(position.getUuid()))
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
        String assistantPractice = (!decideTier && roles.contains(ROLE_ASSISTANT_TEAMLEAD))
                ? practiceOfUser(viewerUuid) : null;
        Set<String> ledTeams = decideTier ? Set.of()
                : new HashSet<>(currentlyLedTeams(viewerUuid));
        Map<String, RecruitmentCircleRole> circleRoles = admin ? Map.of()
                : RecruitmentCircleMember.<RecruitmentCircleMember>list("userUuid", viewerUuid)
                        .stream()
                        .collect(Collectors.toMap(RecruitmentCircleMember::getPositionUuid,
                                RecruitmentCircleMember::getRoleInCircle,
                                (first, second) -> first));

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
            if (assistantPractice != null && assistantPractice.equals(position.getPracticeUuid())) {
                return true;
            }
            // Involvement: named owner or current team lead — the
            // practice-lead routes are gone (decision 11).
            return viewerUuid != null && (viewerUuid.equals(position.getHiringOwnerUuid())
                    || (position.getTeamUuid() != null && ledTeams.contains(position.getTeamUuid())));
        }).map(RecruitmentPosition::getUuid).collect(Collectors.toSet());
    }

    /** The position uuids of every circle the viewer belongs to (one query). */
    private Set<String> circledPositionUuids(String viewerUuid) {
        if (viewerUuid == null || viewerUuid.isBlank()) {
            return Set.of();
        }
        return RecruitmentCircleMember.<RecruitmentCircleMember>list("userUuid", viewerUuid).stream()
                .map(RecruitmentCircleMember::getPositionUuid)
                .collect(Collectors.toSet());
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
            return (roles.stream().anyMatch(HIRED_FILE_ROLES::contains)
                        || holdsRecruitmentGdprGrant(viewerUuid))
                    && !isPartnerTrackOnly(viewerUuid, candidate.getUuid());
        }
        if (roles.stream().anyMatch(PROFILE_READ_ROLES::contains)) {
            return !isPartnerTrackOnly(viewerUuid, candidate.getUuid());
        }
        if (roles.contains(ROLE_ASSISTANT_TEAMLEAD)
                && hasApplicationInAssistantPractice(viewerUuid, candidate.getUuid())) {
            return true;
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
     * Batched twin of {@link #hasApplicationInAssistantPractice} for the
     * database grid and bulk actions: every candidate uuid visible through
     * the assistant practice route, in one query. Empty when the viewer has
     * no practice (fail closed — the assignment rail should have refused the
     * role, but data drifts).
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
                        WHERE p.hiring_track <> 'PARTNER'
                          AND p.practice_uuid = :practice
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
     * the candidate profile — plus any hiring owner of one of the
     * candidate's positions. Interviewers, circle-only viewers and practice
     * leads are deliberately outside: they see the event, not the amount
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
        if (roles.contains(ROLE_ASSISTANT_TEAMLEAD)) {
            String practice = practiceOfUser(viewerUuid);
            if (practice != null && candidatePositions.stream().anyMatch(p ->
                    p.getHiringTrack() != RecruitmentHiringTrack.PARTNER
                            && practice.equals(p.getPracticeUuid()))) {
                return true;
            }
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
     * Partner-track positions never qualify through the practice route; the
     * named-owner route keeps its existing reach, and the dossier caller
     * composes this with {@link #canReadCandidateProfile}, so the circle
     * filter and the HIRED cutoff still apply on top.
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
     * dossier of exactly the hires somebody named them on. Split out of
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
                        """)
                .setParameter("candidate", candidateUuid)
                .setParameter("viewer", viewerUuid)
                .getResultList();
        return creators;
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
     * {@code ADMIN} and {@code HR} only, plus <b>whoever runs the hire</b>
     * ({@link #isHiringOwnerForCandidate}: the named hiring owner, or a lead
     * of the position's practice) — a read-only view of the hire they are
     * running, so they can see that the contract went out without being able
     * to touch it. Everyone else — including {@code RECRUITMENT} and the
     * wider {@code TEAMLEAD} population outside their own practice — answers
     * 404.
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
        if (isAssistantScoped(rolesOf(viewerUuid))) {
            return false;
        }
        if (canWriteDossier(viewerUuid)) {
            return true;
        }
        return isHiringOwnerForCandidate(viewerUuid, candidate.getUuid())
                && canReadCandidateProfile(viewerUuid, candidate);
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
