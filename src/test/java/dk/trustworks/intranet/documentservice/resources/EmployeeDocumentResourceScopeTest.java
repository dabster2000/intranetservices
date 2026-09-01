package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.dto.EmployeeDocumentDTO;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.TestScopeGuards;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of Phase 10.6 enforcement on the employee-document
 * endpoints (access-intent Decision 13: self + HR/ADMIN, team leads
 * deliberately excluded). The file-by-UUID serving routes are the phase
 * file's named highest-risk item: the subject check must key on the row
 * actually served, before any bytes move.
 */
class EmployeeDocumentResourceScopeTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OTHER = "bbbbbbbb-0000-0000-0000-000000000002";

    private EmployeeDocumentResource resource;
    private UserEmployeeDocumentResource userResource;
    private EmployeeDocumentService documentService;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        documentService = mock(EmployeeDocumentService.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);
        ScopeGuard guard = TestScopeGuards.wired(authorizationService, headers);

        resource = new EmployeeDocumentResource();
        resource.employeeDocumentService = documentService;
        resource.requestHeaderHolder = headers;
        resource.scope = guard;

        userResource = new UserEmployeeDocumentResource();
        userResource.employeeDocumentService = documentService;
        userResource.requestHeaderHolder = headers;
        userResource.scope = guard;
    }

    private void actorIs(String actor) {
        when(headers.getUserUuid()).thenReturn(actor);
    }

    private void subjectDecision(String permission, String subject, boolean allowed) {
        when(authorizationService.decideSubjectAccess(eq(ACTOR), eq(permission), eq(subject), any(), anySet()))
                .thenReturn(new AuthorizationService.AccessDecision(
                        allowed, allowed ? DataScope.ALL : DataScope.OWN, allowed, 0,
                        allowed ? "reach" : "subject outside reach"));
    }

    private EmployeeDocument docOwnedBy(String owner) {
        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid("doc-uuid-1");
        doc.setUserUuid(owner);
        return doc;
    }

    // ---- The named highest-risk path: file serving by UUID ------------------

    @Test
    void contentOfAnotherUsersDocumentIs403ForABoundedActor() {
        actorIs(ACTOR);
        when(documentService.get("doc-uuid-1")).thenReturn(docOwnedBy(OTHER));
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> resource.content("doc-uuid-1"));
        verify(documentService, never()).download(any(), any());
    }

    @Test
    void contentOfOwnDocumentPasses() {
        actorIs(ACTOR);
        when(documentService.get("doc-uuid-1")).thenReturn(docOwnedBy(ACTOR));
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, ACTOR, true);
        when(documentService.download(eq("doc-uuid-1"), eq(ACTOR)))
                .thenReturn(new EmployeeDocumentService.DocumentContent(
                        new byte[]{1}, "application/pdf", "contract.pdf"));
        resource.content("doc-uuid-1");
        verify(documentService).download(eq("doc-uuid-1"), eq(ACTOR));
    }

    @Test
    void deleteOfAnotherUsersDocumentIs403ForABoundedActor() {
        actorIs(ACTOR);
        when(documentService.get("doc-uuid-1")).thenReturn(docOwnedBy(OTHER));
        subjectDecision(EmployeeDocumentResource.WRITE_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> resource.delete("doc-uuid-1"));
        verify(documentService, never()).delete(any(), any());
    }

    // ---- List + upload: the path's useruuid is the subject ------------------

    @Test
    void listAnotherUsersDocumentsIs403ForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> userResource.list(OTHER, false, false));
        verify(documentService, never()).list(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void listOwnDocumentsPassesForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, ACTOR, true);
        when(documentService.list(eq(ACTOR), anyBoolean(), anyBoolean())).thenReturn(List.of());
        userResource.list(ACTOR, false, false);
        verify(documentService).list(eq(ACTOR), eq(false), eq(false));
    }

    @Test
    void headerlessCallerIsUntouched() {
        actorIs(null);
        when(documentService.list(eq(OTHER), anyBoolean(), anyBoolean())).thenReturn(List.of());
        userResource.list(OTHER, true, true);
        verify(documentService).list(eq(OTHER), eq(true), eq(true));
        verify(authorizationService, never()).decideSubjectAccess(any(), any(), any(), any(), anySet());
    }

    // ---- Placement pins -----------------------------------------------------

    @Test
    void companyWideAndGdprSurfacesAreScopeEnforced() {
        for (String name : List.of("reviewQueue", "erase", "dsarExport", "stats", "retentionPreview")) {
            Method m = Arrays.stream(EmployeeDocumentResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must be @ScopeEnforced — no per-actor subject dimension");
        }
    }

    @Test
    void perDocumentSurfacesAreNotScopeEnforced() {
        // @ScopeEnforced would 403 an OWN-scoped employee opening their own
        // contract; the resolved-owner subject check replaces it here.
        for (String name : List.of("content", "get", "patch", "delete")) {
            Method m = Arrays.stream(EmployeeDocumentResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertFalse(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must NOT be @ScopeEnforced — it has a per-document subject");
        }
    }

    // ---- by-signing-case: the case key must not become a side door ----------

    private EmployeeDocument docOwnedBy(String owner, String uuid, boolean hrOnly, boolean archived) {
        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid(uuid);
        doc.setUserUuid(owner);
        doc.setHrOnly(hrOnly);
        doc.setArchived(archived);
        // NOT NULL in the schema, and EmployeeDocumentDTO.from dereferences both.
        doc.setCategory(EmployeeDocumentCategory.CONTRACT);
        doc.setSource(EmployeeDocumentSource.SIGNING);
        doc.setOriginalFilename(uuid + ".pdf");
        return doc;
    }

    @Test
    void bySigningCaseIs403WhenTheCaseBelongsToSomeoneOutsideTheActorsReach() {
        actorIs(ACTOR);
        when(documentService.findBySigningCase("case-1"))
                .thenReturn(List.of(docOwnedBy(OTHER, "d1", false, false)));
        subjectDecision("documents:read", OTHER, false);

        assertThrows(ForbiddenException.class,
                () -> resource.bySigningCase("case-1", false, false));
    }

    @Test
    void bySigningCaseChecksEveryDistinctOwnerNotJustTheFirst() {
        actorIs(ACTOR);
        // A case key whose rows span two people must not pass because the
        // first row happens to be reachable.
        when(documentService.findBySigningCase("case-1")).thenReturn(List.of(
                docOwnedBy(ACTOR, "mine", false, false),
                docOwnedBy(OTHER, "theirs", false, false)));
        subjectDecision("documents:read", ACTOR, true);
        subjectDecision("documents:read", OTHER, false);

        assertThrows(ForbiddenException.class,
                () -> resource.bySigningCase("case-1", false, false));
    }

    @Test
    void bySigningCaseReturnsTheCasesDocumentsForAReachableSubject() {
        actorIs(ACTOR);
        when(documentService.findBySigningCase("case-1"))
                .thenReturn(List.of(docOwnedBy(ACTOR, "d1", false, false)));
        subjectDecision("documents:read", ACTOR, true);

        assertEquals(1, resource.bySigningCase("case-1", false, false).size());
    }

    @Test
    void bySigningCaseHidesHrOnlyAndArchivedUnlessAskedFor() {
        actorIs(ACTOR);
        when(documentService.findBySigningCase("case-1")).thenReturn(List.of(
                docOwnedBy(ACTOR, "plain", false, false),
                docOwnedBy(ACTOR, "hronly", true, false),
                docOwnedBy(ACTOR, "archived", false, true)));
        subjectDecision("documents:read", ACTOR, true);

        assertEquals(List.of("plain"),
                resource.bySigningCase("case-1", false, false).stream().map(EmployeeDocumentDTO::uuid).toList());
        assertEquals(List.of("plain", "hronly"),
                resource.bySigningCase("case-1", true, false).stream().map(EmployeeDocumentDTO::uuid).toList());
        assertEquals(List.of("plain", "archived"),
                resource.bySigningCase("case-1", false, true).stream().map(EmployeeDocumentDTO::uuid).toList());
    }

    @Test
    void bySigningCaseOnAnUnarchivedCaseIsAnEmptyListNotAnError() {
        actorIs(ACTOR);
        when(documentService.findBySigningCase("case-none")).thenReturn(List.of());

        assertTrue(resource.bySigningCase("case-none", false, false).isEmpty());
    }
}
