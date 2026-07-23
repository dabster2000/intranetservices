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
import java.util.stream.Collectors;

/**
 * The per-viewer visibility filter for the recruitment module (plan §P2) —
 * built in P2 and reused by <em>every</em> later query path (boards, grids,
 * timelines, reports). Two orthogonal rules (spec §7.2):
 * <ul>
 *   <li><b>Circle filtering (hard):</b> a {@code PARTNER}-track position is
 *       visible only to its circle members — this filter applies to every
 *       role except ADMIN, including recruiters (HR).</li>
 *   <li><b>Practice-lead read access:</b> a <em>current</em> practice lead
 *       (temporal {@code practice_lead} row with {@code enddate IS NULL})
 *       reads their practice's non-partner positions; a former lead does
 *       not.</li>
 * </ul>
 * The viewer is always the {@code X-Requested-By} user — the BFF's system
 * JWT carries {@code admin:*}, so backend scopes cannot distinguish
 * employees; per-user rules key on the user's {@code roles} rows, team
 * leaderships, practice leads and circle memberships.
 * <p>
 * Effective tiers for positions:
 * <ol>
 *   <li>{@code ADMIN} role → everything, including partner track.</li>
 *   <li>{@code HR} / {@code CXO} role (recruiter tier) → all non-partner
 *       positions + partner positions where the viewer is in the circle.</li>
 *   <li>Everyone else → positions they own ({@code hiring_owner_uuid}),
 *       positions of teams they currently lead, non-partner positions of
 *       practices they currently lead, and circle memberships.</li>
 * </ol>
 */
@ApplicationScoped
public class RecruitmentVisibility {

    static final String ROLE_ADMIN = "ADMIN";
    /** Recruiter-tier roles: see every non-partner position. */
    static final Set<String> RECRUITER_TIER_ROLES = Set.of("HR", "CXO");
    /**
     * Profile-read tier (P8, contract §P8-Timeline): roles that read every
     * candidate profile except partner-track-only candidates outside their
     * circles. TECHPARTNER joins HR/CXO here — it is the dossier flow's
     * existing production audience (findings §P3 deviation 12).
     */
    static final Set<String> PROFILE_READ_ROLES = Set.of("HR", "CXO", "TECHPARTNER");
    /**
     * Hired-file tier (spec §7.2 field gate): once a candidate is HIRED,
     * profile access narrows to these roles (+ ADMIN) — colleagues must not
     * browse a new colleague's interview file. DPO joins for GDPR duties.
     */
    static final Set<String> HIRED_FILE_ROLES = Set.of("HR", "CXO", "TECHPARTNER", "DPO");

    @Inject
    EntityManager em;

    // ---- Viewer capability resolution --------------------------------------

    /** @return the viewer's {@code roles} rows, uppercased. */
    public Set<String> rolesOf(String userUuid) {
        return Role.<Role>list("useruuid", userUuid).stream()
                .map(r -> r.getRole() == null ? "" : r.getRole().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * Whether the viewer is a <em>current</em> lead of the given practice —
     * temporal resolution per the registry model: {@code enddate IS NULL}
     * means current, multiple concurrent leads are possible.
     */
    public boolean isCurrentPracticeLead(String userUuid, String practiceUuid) {
        if (userUuid == null || practiceUuid == null) {
            return false;
        }
        return !em.createNativeQuery("""
                        SELECT 1 FROM practice_lead
                        WHERE useruuid = :user AND practice_uuid = :practice
                          AND enddate IS NULL
                        LIMIT 1
                        """)
                .setParameter("user", userUuid)
                .setParameter("practice", practiceUuid)
                .getResultList()
                .isEmpty();
    }

    /** Practice uuids the viewer currently leads ({@code enddate IS NULL}). */
    @SuppressWarnings("unchecked")
    public List<String> currentlyLedPractices(String userUuid) {
        return em.createNativeQuery("""
                        SELECT practice_uuid FROM practice_lead
                        WHERE useruuid = :user AND enddate IS NULL
                        """)
                .setParameter("user", userUuid)
                .getResultList();
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
     * queues (spec §7.2): {@code ADMIN}, {@code HR} or {@code CXO}. The P6
     * referral triage queue and the unsolicited-applicant queue gate on
     * this — a teamlead sees their own positions' pipelines but never the
     * raw intake queues.
     */
    public boolean isRecruiterTier(String userUuid) {
        Set<String> roles = rolesOf(userUuid);
        return roles.contains(ROLE_ADMIN)
                || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains);
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
            if (roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
                // Recruiter tier: everything except partner track outside the circle.
                query.append(" and (p.hiringTrack <> :partnerTrack or ").append(circleExists).append(')');
            } else {
                // Involvement tier: (non-partner AND owned/led-team/led-practice)
                // OR circle. The circle is the ONLY grant for partner track —
                // a hard filter, deliberately not bypassed by ownership.
                List<String> ledTeams = currentlyLedTeams(viewerUuid);
                List<String> ledPractices = currentlyLedPractices(viewerUuid);
                StringBuilder involvement = new StringBuilder("(p.hiringOwnerUuid = :viewer");
                if (!ledTeams.isEmpty()) {
                    involvement.append(" or p.teamUuid in :ledTeams");
                    params.and("ledTeams", ledTeams);
                }
                if (!ledPractices.isEmpty()) {
                    involvement.append(" or p.practiceUuid in :ledPractices");
                    params.and("ledPractices", ledPractices);
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
        if (roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
            return true;
        }
        return viewerUuid.equals(position.getHiringOwnerUuid())
                || (position.getTeamUuid() != null && currentlyLedTeams(viewerUuid).contains(position.getTeamUuid()))
                || (position.getPracticeUuid() != null && isCurrentPracticeLead(viewerUuid, position.getPracticeUuid()));
    }

    /**
     * May the viewer make pipeline decisions (stage moves, terminals, team
     * assignment) on applications of this position? Spec §7.2: admin and
     * recruiter everywhere, teamlead/hiring owner on their own positions —
     * a practice lead has READ access but no decision rights, and on
     * partner track "may look" never implies "may change": only circle
     * OWNER/RECRUITER members (or HR/admin) decide, mirroring the P2
     * position-mutation rule.
     */
    public boolean canDecideOnApplication(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN)) {
            return true;
        }
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            return canManageCircle(viewerUuid, position);
        }
        if (roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
            return true;
        }
        return viewerUuid.equals(position.getHiringOwnerUuid())
                || (position.getTeamUuid() != null
                    && currentlyLedTeams(viewerUuid).contains(position.getTeamUuid()));
    }

    /**
     * Is the viewer "the recruiter or the hiring owner" for this position?
     * The elevated tier two P4 rules key on (spec §4.2): forward stage
     * <em>skips</em> and rejecting a partner-referral candidate. ADMIN and
     * the recruiter tier (HR/CXO) qualify everywhere; otherwise only the
     * position's named hiring owner.
     */
    public boolean isRecruiterOrHiringOwner(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN) || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
            return true;
        }
        return viewerUuid.equals(position.getHiringOwnerUuid());
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
     * Batch variant for list pages: the visible OPEN applications of many
     * candidates in two queries plus one viewer-context resolution. Keys
     * with no visible open application are absent from the map.
     */
    public Map<String, List<RecruitmentApplication>> filterOpenApplicationsByCandidate(
            String viewerUuid, Collection<String> candidateUuids) {
        if (candidateUuids == null || candidateUuids.isEmpty()) {
            return Map.of();
        }
        List<RecruitmentApplication> applications = RecruitmentApplication.list(
                "candidateUuid in ?1 and terminal is null order by createdAt",
                List.copyOf(candidateUuids));
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
        boolean recruiterTier = roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains);

        Set<String> circled = admin ? Set.of() : circledPositionUuids(viewerUuid);
        Set<String> ledTeams = (admin || recruiterTier) ? Set.of()
                : new HashSet<>(currentlyLedTeams(viewerUuid));
        Set<String> ledPractices = (admin || recruiterTier) ? Set.of()
                : new HashSet<>(currentlyLedPractices(viewerUuid));

        return positions.stream().filter(position -> {
            if (admin) {
                return true;
            }
            if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
                return circled.contains(position.getUuid());
            }
            if (recruiterTier) {
                return true;
            }
            return viewerUuid != null && (viewerUuid.equals(position.getHiringOwnerUuid())
                    || (position.getTeamUuid() != null && ledTeams.contains(position.getTeamUuid()))
                    || (position.getPracticeUuid() != null && ledPractices.contains(position.getPracticeUuid())));
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
     *   <li>Involvement tier (everyone else): at least one application on a
     *       position the viewer can read per {@link #canReadPosition} —
     *       covers hiring owners, current teamleads of the position's team,
     *       current practice leads (non-partner positions of their practice)
     *       and circle members.</li>
     * </ol>
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
            return roles.stream().anyMatch(HIRED_FILE_ROLES::contains)
                    && !isPartnerTrackOnly(viewerUuid, candidate.getUuid());
        }
        if (roles.stream().anyMatch(PROFILE_READ_ROLES::contains)) {
            return !isPartnerTrackOnly(viewerUuid, candidate.getUuid());
        }
        return !filterApplications(viewerUuid, candidate.getUuid()).isEmpty();
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
     * The comp tier for a candidate's salary-expectation data (P8 contract):
     * {@code ADMIN}, recruiter tier (HR/CXO), or teamlead/hiring-owner of
     * one of the candidate's positions. Interviewers, practice leads and
     * profile-read TECHPARTNERs are deliberately outside — they see the
     * event, not the amount (spec §7.2 {@code recruitment:comp} row).
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
        if (roles.contains(ROLE_ADMIN) || roles.stream().anyMatch(RECRUITER_TIER_ROLES::contains)) {
            return true;
        }
        if (candidatePositions == null || candidatePositions.isEmpty()) {
            return false;
        }
        if (candidatePositions.stream().anyMatch(p -> viewerUuid.equals(p.getHiringOwnerUuid()))) {
            return true;
        }
        Set<String> ledTeams = new HashSet<>(currentlyLedTeams(viewerUuid));
        return candidatePositions.stream()
                .anyMatch(p -> p.getTeamUuid() != null && ledTeams.contains(p.getTeamUuid()));
    }

    /**
     * May the viewer manage (add/remove) the position's circle? ADMIN and HR
     * always; otherwise only circle {@code OWNER}s and {@code RECRUITER}s —
     * a {@code PARTICIPANT} can see the position but not widen the circle.
     */
    public boolean canManageCircle(String viewerUuid, RecruitmentPosition position) {
        Set<String> roles = rolesOf(viewerUuid);
        if (roles.contains(ROLE_ADMIN) || roles.contains("HR")) {
            return true;
        }
        RecruitmentCircleMember membership = RecruitmentCircleMember.findById(
                new RecruitmentCircleMember.Key(position.getUuid(), viewerUuid));
        return membership != null
                && (membership.getRoleInCircle() == RecruitmentCircleRole.OWNER
                || membership.getRoleInCircle() == RecruitmentCircleRole.RECRUITER);
    }
}
