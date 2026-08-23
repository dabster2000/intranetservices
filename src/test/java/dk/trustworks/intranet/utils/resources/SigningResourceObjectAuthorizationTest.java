package dk.trustworks.intranet.utils.resources;

import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeResolution;
import dk.trustworks.intranet.security.TestScopeGuards;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.dto.signing.CreateSigningCaseRequest;
import dk.trustworks.intranet.utils.dto.signing.SignerInfo;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseResponse;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.services.SigningService;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Database-free direct-resource coverage for employee signing BOLA boundaries. */
class SigningResourceObjectAuthorizationTest {

    private static final String ASSISTANT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TEAMLEAD = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String HR = "cccccccc-0000-0000-0000-000000000003";
    private static final String ADMIN = "dddddddd-0000-0000-0000-000000000004";
    private static final String EMPLOYEE = "eeeeeeee-0000-0000-0000-000000000005";
    private static final String OTHER_EMPLOYEE = "ffffffff-0000-0000-0000-000000000006";
    private static final String CASE_KEY = "case-owned-by-employee";

    private SigningResource resource;
    private SigningService signingService;
    private SigningCaseRepository signingCaseRepository;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        signingService = mock(SigningService.class);
        signingCaseRepository = mock(SigningCaseRepository.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);

        resource = new SigningResource();
        resource.signingService = signingService;
        resource.signingCaseRepository = signingCaseRepository;
        resource.authorizationService = authorizationService;
        resource.scopeGuard = TestScopeGuards.wired(authorizationService, headers);
    }

    @Test
    void missingOrMalformedHumanActorFailsClosed() {
        when(headers.getUserUuid()).thenReturn(null, "bff-client");

        assertThrows(ForbiddenException.class, () -> resource.listCases(EMPLOYEE, null));
        assertThrows(ForbiddenException.class, () -> resource.listCases(EMPLOYEE, null));

        verify(signingService, never()).listUserCases(anyString());
        verify(authorizationService, never()).decideSubjectAccess(
                anyString(), anyString(), anyString(), any(), anySet());
    }

    @Test
    void assistantCannotReadOrCreateEmployeeSigningCases() {
        actorIs(ASSISTANT);
        employeeDecision(ASSISTANT, EMPLOYEE, false, false);
        employeeDecision(ASSISTANT, EMPLOYEE, true, false);

        assertThrows(ForbiddenException.class, () -> resource.listCases(EMPLOYEE, null));
        assertThrows(ForbiddenException.class,
                () -> resource.createCase(signingRequest(), EMPLOYEE, null));

        verify(signingService, never()).listUserCases(anyString());
        verify(signingService, never()).createCase(any());
    }

    @Test
    void selfReadRemainsAvailableButSelfWriteIsDisabled() {
        actorIs(EMPLOYEE);
        employeeDecision(EMPLOYEE, EMPLOYEE, false, true);
        employeeDecision(EMPLOYEE, EMPLOYEE, true, false);
        when(signingService.listUserCases(EMPLOYEE)).thenReturn(List.of());

        try (Response response = resource.listCases(EMPLOYEE, null)) {
            assertEquals(200, response.getStatus());
        }
        assertThrows(ForbiddenException.class,
                () -> resource.createCase(signingRequest(), EMPLOYEE, null));

        verify(authorizationService).decideSubjectAccess(
                eq(EMPLOYEE), eq("salaries:read"), eq(EMPLOYEE), any(),
                argThat(Set::isEmpty));
        verify(authorizationService).decideSubjectAccess(
                eq(EMPLOYEE), eq("salaries:read"), eq(EMPLOYEE), any(),
                argThat(scopes -> scopes.equals(Set.of(DataScope.OWN))));
        verify(signingService, never()).createCase(any());
    }

    @Test
    void eligibleTeamLeadCanReadAndCreateForAnEmployeeInReach() {
        actorIs(TEAMLEAD);
        employeeDecision(TEAMLEAD, EMPLOYEE, false, true);
        employeeDecision(TEAMLEAD, EMPLOYEE, true, true);
        when(signingService.listUserCases(EMPLOYEE)).thenReturn(List.of());
        when(signingService.createCase(any())).thenReturn(
                SigningCaseResponse.created(CASE_KEY, "Salary agreement"));

        try (Response read = resource.listCases(EMPLOYEE, null);
             Response write = resource.createCase(signingRequest(), EMPLOYEE, null)) {
            assertEquals(200, read.getStatus());
            assertEquals(201, write.getStatus());
        }

        verify(signingService).createCase(any());
        verify(signingService).saveMinimalCase(
                eq(CASE_KEY), eq(EMPLOYEE), eq("Salary agreement.pdf"), eq(1), any());
    }

    @Test
    void foreignAndUnknownCaseKeysAreUniformlyHiddenBeforeProviderCalls() {
        actorIs(HR);
        employeeDecision(HR, EMPLOYEE, false, true);
        when(signingCaseRepository.findByCaseKey("foreign"))
                .thenReturn(Optional.of(signingCase("foreign", OTHER_EMPLOYEE)));
        when(signingCaseRepository.findByCaseKey("unknown")).thenReturn(Optional.empty());

        NotFoundException foreign = assertThrows(NotFoundException.class,
                () -> resource.getCaseStatus("foreign", EMPLOYEE, null));
        NotFoundException unknown = assertThrows(NotFoundException.class,
                () -> resource.getCaseStatus("unknown", EMPLOYEE, null));
        assertEquals(foreign.getMessage(), unknown.getMessage());
        assertThrows(NotFoundException.class,
                () -> resource.downloadSignedDocument("foreign", 0, EMPLOYEE, null));

        verify(signingService, never()).getStatus(anyString());
        verify(signingService, never()).downloadSignedDocument(anyString(), anyInt());
    }

    @Test
    void eligibleTeamLeadHrAndAdminCanReadOnlyCasesBoundToTheTarget() {
        when(signingCaseRepository.findByCaseKey(CASE_KEY))
                .thenReturn(Optional.of(signingCase(CASE_KEY, EMPLOYEE)));
        when(signingService.getStatus(CASE_KEY)).thenReturn(status(CASE_KEY));
        when(signingService.downloadSignedDocument(CASE_KEY, 0)).thenReturn(new byte[]{1, 2, 3});

        for (String actor : List.of(TEAMLEAD, HR, ADMIN)) {
            actorIs(actor);
            employeeDecision(actor, EMPLOYEE, false, true);
            try (Response status = resource.getCaseStatus(CASE_KEY, EMPLOYEE, null);
                 Response document = resource.downloadSignedDocument(CASE_KEY, 0, EMPLOYEE, null)) {
                assertEquals(200, status.getStatus());
                assertEquals(200, document.getStatus());
            }
        }
    }

    @Test
    void adminManagementRoutesRequireAnUnboundedHumanAdminAndKnownCase() {
        actorIs(ASSISTANT);
        when(authorizationService.resolveReach(
                eq(ASSISTANT), eq("admin:read"), any(), anySet()))
                .thenReturn(ScopeResolution.none());
        assertThrows(ForbiddenException.class, resource::listAllCasesAdmin);
        verify(signingService, never()).listAllCasesAdmin();

        actorIs(ADMIN);
        when(authorizationService.resolveReach(
                eq(ADMIN), eq("admin:read"), any(), anySet()))
                .thenReturn(ScopeResolution.unboundedAll());
        when(signingService.listAllCasesAdmin()).thenReturn(List.of());
        when(signingCaseRepository.findByCaseKey(CASE_KEY))
                .thenReturn(Optional.of(signingCase(CASE_KEY, EMPLOYEE)));
        when(signingCaseRepository.findByCaseKey("unknown")).thenReturn(Optional.empty());

        try (Response list = resource.listAllCasesAdmin();
             Response deleted = resource.deleteCase(CASE_KEY)) {
            assertEquals(200, list.getStatus());
            assertEquals(200, deleted.getStatus());
        }
        assertThrows(NotFoundException.class, () -> resource.deleteCase("unknown"));
        verify(signingService).deleteCase(CASE_KEY);
        verify(signingService, never()).deleteCase("unknown");
    }

    private void actorIs(String actor) {
        when(headers.getUserUuid()).thenReturn(actor);
    }

    private void employeeDecision(String actor, String target, boolean mutation, boolean allowed) {
        when(authorizationService.decideSubjectAccess(
                eq(actor),
                eq("salaries:read"),
                eq(target),
                any(),
                argThat(scopes -> mutation
                        ? scopes.equals(Set.of(DataScope.OWN))
                        : scopes.isEmpty())))
                .thenReturn(new AuthorizationService.AccessDecision(
                        allowed,
                        allowed ? (mutation ? DataScope.TEAM : DataScope.ALL) : null,
                        allowed && !mutation,
                        0,
                        allowed ? "in reach" : "outside reach"));
    }

    private CreateSigningCaseRequest signingRequest() {
        return new CreateSigningCaseRequest(
                "Salary agreement.pdf",
                "cGRm",
                "application/pdf",
                List.of(SignerInfo.signer(1, "Ada Example", "ada@example.com")),
                EMPLOYEE);
    }

    private SigningCase signingCase(String caseKey, String userUuid) {
        return SigningCase.builder()
                .caseKey(caseKey)
                .userUuid(userUuid)
                .documentName("Salary agreement.pdf")
                .status("pending")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private SigningCaseStatus status(String caseKey) {
        return new SigningCaseStatus(
                caseKey,
                "pending",
                "Salary agreement.pdf",
                LocalDateTime.now(),
                List.of(),
                1,
                0,
                null,
                null,
                null,
                null);
    }
}
