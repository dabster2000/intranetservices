package dk.trustworks.intranet.security;

import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of the role → permission write path (Phase 7, tasks
 * 7.3 + 7.9): the protected-set rail, catalogue validation, tombstone semantics,
 * and that every real mutation is audited (the audit service also bumps
 * authz_version in the same transaction — task 7.2).
 */
class RolePermissionAdminServiceTest {

    private RolePermissionAdminService service;
    private AuthzAuditService audit;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        service = new RolePermissionAdminService();
        audit = mock(AuthzAuditService.class);
        headers = mock(RequestHeaderHolder.class);
        when(headers.getUserUuid()).thenReturn("actor-uuid");
        service.authzAuditService = audit;
        service.requestHeaderHolder = headers;
    }

    // ------------------------------------------------------------------
    // 7.9 — protected permission set: refused before any database access
    // ------------------------------------------------------------------

    @Test
    void grantingAProtectedPermissionIsRefused() {
        assertThrows(RolePermissionAdminService.ProtectedPermissionException.class,
                () -> service.grant("SALES", "admin:write"));
        assertThrows(RolePermissionAdminService.ProtectedPermissionException.class,
                () -> service.grant("SALES", "salaries:read"));
        verifyNoInteractions(audit);
    }

    @Test
    void revokingAProtectedPermissionIsRefused() {
        assertThrows(RolePermissionAdminService.ProtectedPermissionException.class,
                () -> service.revoke("ADMIN", "admin:read"));
        assertThrows(RolePermissionAdminService.ProtectedPermissionException.class,
                () -> service.revoke("HR", "salaries:write"));
        verifyNoInteractions(audit);
    }

    @Test
    void protectedRefusalIsCaseInsensitive() {
        assertThrows(RolePermissionAdminService.ProtectedPermissionException.class,
                () -> service.grant("SALES", "ADMIN:WRITE"));
    }

    @Test
    void unknownPermissionKeysAreRefused() {
        assertThrows(RolePermissionAdminService.UnknownPermissionException.class,
                () -> service.grant("SALES", "bogus:nope"));
        assertThrows(RolePermissionAdminService.UnknownPermissionException.class,
                () -> service.revoke("SALES", "bogus:nope"));
        assertThrows(RolePermissionAdminService.UnknownPermissionException.class,
                () -> service.grant("SALES", "  "));
        verifyNoInteractions(audit);
    }

    // ------------------------------------------------------------------
    // Tombstone semantics (F-13: never DELETE, never resurrect by absence)
    // ------------------------------------------------------------------

    @Test
    void grantOnActiveBindingIsANoOp_noAuditNoBump() {
        RolePermission active = new RolePermission("SALES", "invoices:write");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinition(panache, "SALES");
            stubFindBinding(panache, active);

            RolePermission result = service.grant("SALES", "invoices:write");

            assertEquals(active, result);
            verifyNoInteractions(audit); // nothing changed — nothing to audit or bump
        }
    }

    @Test
    void grantReactivatesATombstonedBinding_andAudits() {
        RolePermission tombstoned = new RolePermission("SALES", "invoices:write");
        tombstoned.setRevokedAt(LocalDateTime.now().minusDays(3));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinition(panache, "SALES");
            stubFindBinding(panache, tombstoned);

            RolePermission result = service.grant("SALES", "invoices:write");

            assertTrue(result.isActive());
            assertNull(result.getRevokedAt());
            assertEquals("actor-uuid", result.getGrantedBy());
            verify(audit).record(eq("ROLE_PERMISSION_GRANTED"), eq("role_permission"),
                    eq("SALES:invoices:write"), any(), any());
        }
    }

    @Test
    void revokeTombstonesAnActiveBinding_andAudits() {
        RolePermission active = new RolePermission("SALES", "invoices:write");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinition(panache, "SALES");
            stubFindBinding(panache, active);

            RolePermission result = service.revoke("SALES", "invoices:write");

            assertNotNull(result.getRevokedAt()); // tombstone, not delete
            verify(audit).record(eq("ROLE_PERMISSION_REVOKED"), eq("role_permission"),
                    eq("SALES:invoices:write"), any(), any());
        }
    }

    @Test
    void revokeOfAMissingBindingIs404() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinition(panache, "SALES");
            stubFindBinding(panache, null);

            assertThrows(NotFoundException.class,
                    () -> service.revoke("SALES", "invoices:write"));
            verifyNoInteractions(audit);
        }
    }

    @Test
    void revokeOfAnAlreadyRevokedBindingIsANoOp() {
        RolePermission tombstoned = new RolePermission("SALES", "invoices:write");
        tombstoned.setRevokedAt(LocalDateTime.now().minusDays(1));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinition(panache, "SALES");
            stubFindBinding(panache, tombstoned);

            RolePermission result = service.revoke("SALES", "invoices:write");

            assertNotNull(result.getRevokedAt());
            verify(audit, never()).record(anyString(), anyString(), anyString(), any(), any());
        }
    }

    @Test
    void unknownRoleIs404() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinitionMissing(panache);

            assertThrows(NotFoundException.class,
                    () -> service.grant("NOPE", "invoices:write"));
            verifyNoInteractions(audit);
        }
    }

    @Test
    void roleNameIsUppercasedBeforeLookup() {
        RolePermission active = new RolePermission("SALES", "invoices:write");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleDefinition(panache, "SALES");
            stubFindBinding(panache, active);

            RolePermission result = service.grant("sales", "INVOICES:WRITE");

            assertEquals(active, result);
        }
    }

    // ------------------------------------------------------------------
    // Stubs — Panache statics resolve to PanacheEntityBase in plain JUnit
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubRoleDefinition(MockedStatic<PanacheEntityBase> panache, String name) {
        PanacheQuery<RoleDefinition> query = mock(PanacheQuery.class);
        RoleDefinition definition = mock(RoleDefinition.class);
        when(query.firstResultOptional()).thenReturn(Optional.of(definition));
        panache.when(() -> PanacheEntityBase.find(eq("name"), eq(name))).thenReturn(query);
    }

    @SuppressWarnings("unchecked")
    private void stubRoleDefinitionMissing(MockedStatic<PanacheEntityBase> panache) {
        PanacheQuery<RoleDefinition> query = mock(PanacheQuery.class);
        when(query.firstResultOptional()).thenReturn(Optional.empty());
        panache.when(() -> PanacheEntityBase.find(eq("name"), anyString())).thenReturn(query);
    }

    @SuppressWarnings("unchecked")
    private void stubFindBinding(MockedStatic<PanacheEntityBase> panache, RolePermission binding) {
        PanacheQuery<RolePermission> query = mock(PanacheQuery.class);
        when(query.firstResultOptional()).thenReturn(Optional.ofNullable(binding));
        panache.when(() -> PanacheEntityBase.find(
                eq("role = ?1 and permissionKey = ?2"), eq("SALES"), eq("invoices:write")))
                .thenReturn(query);
    }
}
