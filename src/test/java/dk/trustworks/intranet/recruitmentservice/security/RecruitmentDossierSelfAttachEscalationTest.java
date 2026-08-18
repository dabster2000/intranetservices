package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.security.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The privilege-escalation regression that came with {@code
 * recruitment:intake} (verification finding, 2026-08-19).
 *
 * <p>The offer dossier — contract drafts, salary, terms, start date — opens
 * for ADMIN/HR, and read-only for <em>whoever runs the hire</em>: the named
 * hiring owner of one of the candidate's positions, or a lead of that
 * position's practice. That second route is derived purely from the
 * candidate's application rows, and it is exactly the set a {@code TEAMLEAD}
 * may attach to. Before intake that was safe, because attaching was an
 * ADMIN/HR/RECRUITMENT act: "an application exists on a position I run" was
 * always someone <em>else's</em> statement. Intake handed the attach to every
 * team lead, so without a creator clause a team lead could file any candidate
 * they can already see onto a position in their own practice and read the
 * contract — precisely what the 2026-08-11 decision on {@code canReadDossier}
 * refused to do directly ("would have handed the whole contract flow to 20
 * people").</p>
 *
 * <p>Database-free: the two Panache/native lookups are stubbed by subclass —
 * the same idiom as {@link RecruitmentVisibilityHiredFileTest} — leaving the
 * decision logic under test.</p>
 */
class RecruitmentDossierSelfAttachEscalationTest {

    private static final String TEAMLEAD = "aaaaaaaa-0000-0000-0000-00000000000a";
    private static final String OTHER_TEAMLEAD = "aaaaaaaa-0000-0000-0000-00000000000b";
    private static final String RECRUITER = "bbbbbbbb-0000-0000-0000-00000000000c";
    private static final String CANDIDATE = "cccccccc-0000-0000-0000-00000000000d";

    private final Map<String, Set<String>> rolesByUser = new HashMap<>();
    /** {@code created_by} of the applications that put the viewer on the position. */
    private final List<String> conferringCreators = new ArrayList<>();
    private final List<String> creatorRoleLookups = new ArrayList<>();
    private final java.util.Set<String> intakeHolders = new java.util.HashSet<>();

    private RecruitmentVisibility visibility;
    private RecruitmentCandidate candidate;

    @BeforeEach
    void setUp() {
        rolesByUser.clear();
        conferringCreators.clear();
        creatorRoleLookups.clear();
        intakeHolders.clear();

        EffectivePermissionService permissions = mock(EffectivePermissionService.class);
        // holdsRecruitmentIntakeGrant reads this. Every TEAMLEAD holds the
        // grant (V514 seeds it at data_scope ALL), and so does the recruiter
        // tier — which is exactly why the rule cannot be "does the filer hold
        // intake" on its own.
        when(permissions.effectivePermissions(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> intakeHolders.contains(inv.getArgument(0))
                        ? Set.of("recruitment:intake") : Set.<String>of());

        visibility = new RecruitmentVisibility() {
            @Override
            public Set<String> rolesOf(String userUuid) {
                if (!userUuid.equals(TEAMLEAD)) {
                    creatorRoleLookups.add(userUuid);
                }
                return rolesByUser.getOrDefault(userUuid, Set.of());
            }

            @Override
            protected List<String> hiringOwnerApplicationCreators(String viewerUuid,
                                                                  String candidateUuid) {
                return List.copyOf(conferringCreators);
            }

            @Override
            public boolean isPartnerTrackOnly(String viewerUuid, String candidateUuid) {
                return false;
            }

            @Override
            public List<RecruitmentApplication> filterApplications(String viewerUuid,
                                                                   String candidateUuid) {
                return List.of();
            }

            @Override
            public boolean hasInterviewAssignment(String viewerUuid, String candidateUuid) {
                return false;
            }
        };
        visibility.effectivePermissionService = permissions;

        rolesByUser.put(TEAMLEAD, Set.of("TEAMLEAD", "USER"));
        rolesByUser.put(OTHER_TEAMLEAD, Set.of("TEAMLEAD", "USER"));
        rolesByUser.put(RECRUITER, Set.of("RECRUITMENT", "USER"));
        intakeHolders.add(TEAMLEAD);
        intakeHolders.add(OTHER_TEAMLEAD);
        intakeHolders.add(RECRUITER);

        candidate = new RecruitmentCandidate();
        candidate.setUuid(CANDIDATE);
        candidate.setStatus(CandidateStatus.ACTIVE);
    }

    // ---- The escalation itself --------------------------------------------

    @Test
    void teamLeadWhoAttachedTheCandidateThemselvesDoesNotGetTheDossier() {
        conferringCreators.add(TEAMLEAD);   // the intake holder filed it

        assertFalse(visibility.isHiringOwnerForCandidate(TEAMLEAD, CANDIDATE),
                "an application the viewer filed themselves must not make them the hire runner");
        assertFalse(visibility.canReadDossier(TEAMLEAD, candidate),
                "attaching a candidate to a position you run must not open the contract PDF");
    }

    @Test
    void aSecondIntakeHolderCannotFileTheApplicationOnTheFirstOnesBehalf() {
        conferringCreators.add(OTHER_TEAMLEAD);

        assertFalse(visibility.canReadDossier(TEAMLEAD, candidate),
                "two intake holders in one practice must not be able to grant each other "
                        + "dossier read by cross-filing");
    }

    @Test
    void aRecruiterFiledApplicationStillOpensTheDossierForTheHireRunner() {
        conferringCreators.add(RECRUITER);

        assertTrue(visibility.isHiringOwnerForCandidate(TEAMLEAD, CANDIDATE));
        assertTrue(visibility.canReadDossier(TEAMLEAD, candidate),
                "the 2026-08-12 decision stands: a lead who runs the hire a recruiter filed "
                        + "keeps the read-only contract view");
    }

    @Test
    void publicApplyAndImportRowsStillConfer() {
        conferringCreators.add(RecruitmentVisibility.SYSTEM_ACTOR);

        assertTrue(visibility.canReadDossier(TEAMLEAD, candidate),
                "AuditEntityListener writes 'system' when there is no X-Requested-By — the "
                        + "public /apply funnel and batch imports must keep conferring");
    }

    @Test
    void oneQualifyingRecruiterApplicationIsEnoughEvenAlongsideASelfFiledOne() {
        conferringCreators.add(TEAMLEAD);
        conferringCreators.add(RECRUITER);

        assertTrue(visibility.canReadDossier(TEAMLEAD, candidate),
                "the rule is 'at least one application somebody else filed', not "
                        + "'no self-filed application anywhere'");
    }

    @Test
    void aCandidateWithNoQualifyingApplicationIsUnreachable() {
        assertFalse(visibility.isHiringOwnerForCandidate(TEAMLEAD, CANDIDATE));
        assertFalse(visibility.canReadDossier(TEAMLEAD, candidate),
                "the dossier-only candidate (zero applications, legacy HR flow) is the "
                        + "population this escalation targeted");
    }

    @Test
    void adminAndHrAreUnaffectedByTheCreatorClause() {
        rolesByUser.put(TEAMLEAD, Set.of("HR"));
        assertTrue(visibility.canReadDossier(TEAMLEAD, candidate),
                "canWriteDossier short-circuits before any application is looked at");

        rolesByUser.put(TEAMLEAD, Set.of("ADMIN"));
        assertTrue(visibility.canReadDossier(TEAMLEAD, candidate));
    }

    @Test
    void theProfileGateStillComposesOnTop() {
        conferringCreators.add(RECRUITER);
        candidate.setStatus(CandidateStatus.HIRED);

        assertFalse(visibility.canReadDossier(TEAMLEAD, candidate),
                "the HIRED cutoff on canReadCandidateProfile must keep applying — the creator "
                        + "clause narrows the gate, it must not replace the composition");
    }

    // ---- The rule itself, exhaustively ------------------------------------

    @Test
    void creatorRuleRejectsSelfForAnyoneOutsideTheRecruiterTier() {
        assertFalse(rule(TEAMLEAD, TEAMLEAD, Set.of("TEAMLEAD"), true),
                "the escalation itself: an intake holder cannot confer hiring-owner rights "
                        + "on themselves by filing the application");
        assertFalse(rule(TEAMLEAD, TEAMLEAD, Set.of(), true),
                "and it stays closed when the self-filer's roles no longer resolve, because "
                        + "the intake grant is what the clause is keyed on");
    }

    /**
     * The self clause must NOT reach the recruiter tier. ADMIN/HR/RECRUITMENT
     * could file an application and be the named hiring owner long before
     * {@code recruitment:intake} existed, so a self-filed row has always
     * conferred for them; denying it here would close nothing (the escalation
     * needs the intake grant) and would newly 403 a recruiter who reads that
     * dossier today under D18.
     */
    @Test
    void creatorRuleLetsTheRecruiterTierSelfFile() {
        assertTrue(rule(RECRUITER, RECRUITER, Set.of("RECRUITMENT"), true),
                "a RECRUITMENT recruiter who is the named hiring owner and filed the "
                        + "application reads the dossier today — intake must not revoke that");
        assertTrue(rule(RECRUITER, RECRUITER, Set.of("HR"), true));
        assertTrue(rule(RECRUITER, RECRUITER, Set.of("ADMIN"), true));
    }

    @Test
    void creatorRuleRejectsAnotherIntakeHolder() {
        assertFalse(rule(OTHER_TEAMLEAD, TEAMLEAD, Set.of("TEAMLEAD"), true),
                "cross-filing between two intake holders is the collusion route");
        assertTrue(rule(OTHER_TEAMLEAD, TEAMLEAD, Set.of("TEAMLEAD"), false),
                "and it is keyed on the GRANT, not the TEAMLEAD role — revoking the grant in "
                        + "the console must be enough to change this answer");
    }

    @Test
    void creatorRuleAcceptsTheRecruiterTierTheSystemAndAnythingUnresolvable() {
        assertTrue(rule(RECRUITER, TEAMLEAD, Set.of("HR"), true));
        assertTrue(rule(RECRUITER, TEAMLEAD, Set.of("RECRUITMENT"), true));
        assertTrue(rule(RECRUITER, TEAMLEAD, Set.of("ADMIN"), true),
                "recruiter tier is checked BEFORE the intake grant — every recruiter holds "
                        + "intake too (V514), so the other order would deny the normal flow");
        assertTrue(rule(RecruitmentVisibility.SYSTEM_ACTOR, TEAMLEAD, Set.of(), false));
        assertTrue(rule("test", TEAMLEAD, Set.of(), false),
                "a creator that resolves to nothing — a departed account, an import, a "
                        + "migration literal, a fixture — is not a live intake holder, and "
                        + "denying it would revoke the 2026-08-12 grant on historical rows");
        assertTrue(rule(null, TEAMLEAD, Set.of(), false));
        assertTrue(rule("  ", TEAMLEAD, Set.of(), false));
    }

    @Test
    void theGrantStoreIsNotConsultedForARecruiterFiledRow() {
        boolean[] asked = {false};
        assertTrue(RecruitmentVisibility.creatorConfersHire(RECRUITER, TEAMLEAD,
                () -> Set.of("HR"), () -> {
                    asked[0] = true;
                    return true;
                }));
        assertFalse(asked[0], "the ordering is also a cost statement — do not reorder it");
    }

    private static boolean rule(String creator, String viewer, Set<String> creatorRoles,
                                boolean holdsIntake) {
        return RecruitmentVisibility.creatorConfersHire(creator, viewer,
                () -> creatorRoles, () -> holdsIntake);
    }

    @Test
    void theCreatorsRolesAreActuallyConsulted() {
        conferringCreators.add(OTHER_TEAMLEAD);
        visibility.isHiringOwnerForCandidate(TEAMLEAD, CANDIDATE);

        assertEquals(List.of(OTHER_TEAMLEAD), creatorRoleLookups,
                "clause 2 must be live: the decision reads the FILER's roles, not the "
                        + "viewer's. Without this the cross-filing case above would pass "
                        + "vacuously for any implementation that only compared uuids.");
    }
}
