package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.security.EffectivePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 10.5 — the DPO re-key (phase file: "Ensure the DPO's reach is
 * expressed as a permission grant rather than a hardcoded role list").
 * Hired-file access via GDPR duty is decided by holding
 * {@code recruitment:gdpr} in the catalogue, so the admin console governs it;
 * the role-tier members (HR/CXO/TECHPARTNER) are unchanged. Database-free:
 * the Panache-backed lookups are stubbed via subclass, leaving exactly the
 * decision logic under test.
 */
class RecruitmentVisibilityHiredFileTest {

    private static final String VIEWER = "aaaaaaaa-0000-0000-0000-000000000001";

    private EffectivePermissionService permissions;
    private Set<String> viewerRoles;
    private RecruitmentVisibility visibility;
    private RecruitmentCandidate hired;

    @BeforeEach
    void setUp() {
        permissions = mock(EffectivePermissionService.class);
        viewerRoles = Set.of();
        visibility = new RecruitmentVisibility() {
            @Override
            public Set<String> rolesOf(String userUuid) {
                return viewerRoles;
            }

            @Override
            public boolean isPartnerTrackOnly(String viewerUuid, String candidateUuid) {
                return false;
            }

            @Override
            public List<RecruitmentApplication> filterApplications(String viewerUuid, String candidateUuid) {
                return List.of();
            }

            @Override
            public boolean hasInterviewAssignment(String viewerUuid, String candidateUuid) {
                return false;
            }
        };
        visibility.effectivePermissionService = permissions;

        hired = new RecruitmentCandidate();
        hired.setStatus(CandidateStatus.HIRED);
    }

    private void grants(String... keys) {
        when(permissions.effectivePermissions(VIEWER)).thenReturn(Set.of(keys));
    }

    @Test
    void dpoRoleIsNoLongerHardcoded() {
        assertFalse(RecruitmentVisibility.HIRED_FILE_ROLES.contains("DPO"),
                "the DPO's hired-file access must come from the recruitment:gdpr grant, not the role name");
    }

    @Test
    void gdprGrantHolderReadsHiredFiles() {
        viewerRoles = Set.of("DPO", "USER");
        grants("recruitment:gdpr");
        assertTrue(visibility.canReadCandidateProfile(VIEWER, hired));
    }

    @Test
    void revokingTheGrantRemovesHiredFileAccessWithoutACodeChange() {
        viewerRoles = Set.of("DPO", "USER");
        grants();   // console revoke: role kept, grant gone
        assertFalse(visibility.canReadCandidateProfile(VIEWER, hired));
    }

    @Test
    void roleTierIsUnchangedByTheReKey() {
        viewerRoles = Set.of("HR", "USER");
        grants();   // HR needs no grant — the role tier decides
        assertTrue(visibility.canReadCandidateProfile(VIEWER, hired));
    }

    @Test
    void plainColleagueStaysOutOfHiredFiles() {
        viewerRoles = Set.of("USER");
        grants();
        assertFalse(visibility.canReadCandidateProfile(VIEWER, hired));
    }

    @Test
    void grantLookupFailureFailsClosed() {
        viewerRoles = Set.of("DPO", "USER");
        when(permissions.effectivePermissions(VIEWER)).thenThrow(new IllegalStateException("store down"));
        assertFalse(visibility.canReadCandidateProfile(VIEWER, hired));
    }
}
