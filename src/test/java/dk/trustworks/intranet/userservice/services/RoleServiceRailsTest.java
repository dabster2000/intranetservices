package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import dk.trustworks.intranet.security.AuthzAuditService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of the role-assignment removal rails (Phase 7, task 7.9):
 * cannot strip your own admin access, cannot remove the last holder of a system
 * role — and every allowed removal is audited (which also bumps authz_version in
 * the same transaction, task 7.2).
 */
class RoleServiceRailsTest {

    private static final String ACTOR = "actor-uuid";
    private static final String OTHER_USER = "other-user-uuid";
    private static final String ROLE_UUID = "role-row-uuid";

    private RoleService service;
    private AuthzAuditService audit;
    private Query adminViaRoleQuery;
    private Query adminViaOtherRolesQuery;

    @BeforeEach
    void setUp() {
        service = new RoleService();
        audit = mock(AuthzAuditService.class);
        RequestHeaderHolder headers = mock(RequestHeaderHolder.class);
        when(headers.getUserUuid()).thenReturn(ACTOR);
        EntityManager em = mock(EntityManager.class);

        // The rail's two native queries, distinguished by shape: the role-grants-admin
        // query filters on rp.role, the other-roles query on r.useruuid.
        adminViaRoleQuery = mock(Query.class);
        when(adminViaRoleQuery.setParameter(anyString(), any())).thenReturn(adminViaRoleQuery);
        adminViaOtherRolesQuery = mock(Query.class);
        when(adminViaOtherRolesQuery.setParameter(anyString(), any())).thenReturn(adminViaOtherRolesQuery);
        when(em.createNativeQuery(contains("rp.role = :role"))).thenReturn(adminViaRoleQuery);
        when(em.createNativeQuery(contains("r.useruuid = :useruuid"))).thenReturn(adminViaOtherRolesQuery);

        service.authzAuditService = audit;
        service.requestHeaderHolder = headers;
        service.em = em;
    }

    private void stubRoleRow(MockedStatic<PanacheEntityBase> panache, String useruuid, String roleName) {
        Role row = new Role(ROLE_UUID, roleName, useruuid);
        panache.when(() -> PanacheEntityBase.findById(ROLE_UUID)).thenReturn(row);
    }

    @SuppressWarnings("unchecked")
    private void stubRoleDefinition(MockedStatic<PanacheEntityBase> panache, String name, boolean isSystem) {
        RoleDefinition definition = mock(RoleDefinition.class);
        when(definition.isSystem()).thenReturn(isSystem);
        PanacheQuery<RoleDefinition> query = mock(PanacheQuery.class);
        when(query.firstResultOptional()).thenReturn(Optional.of(definition));
        panache.when(() -> PanacheEntityBase.find(eq("name"), eq(name))).thenReturn(query);
    }

    @Test
    void removingYourOwnOnlyAdminRoleIsRefused() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleRow(panache, ACTOR, "ADMIN");
            stubRoleDefinition(panache, "ADMIN", true);
            when(adminViaRoleQuery.getSingleResult()).thenReturn(1L);       // ADMIN grants admin:*
            when(adminViaOtherRolesQuery.getSingleResult()).thenReturn(0L); // no other admin path
            panache.when(() -> PanacheEntityBase.count(eq("role"), eq("ADMIN"))).thenReturn(3L);

            assertThrows(RoleService.RoleAssignmentRailException.class,
                    () -> service.deleteByRoleUuid(ACTOR, ROLE_UUID));
            verifyNoInteractions(audit);
        }
    }

    @Test
    void removingYourOwnAdminRoleIsAllowedWhenAnotherRoleStillGrantsAdmin() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleRow(panache, ACTOR, "ADMIN");
            stubRoleDefinition(panache, "ADMIN", true);
            when(adminViaRoleQuery.getSingleResult()).thenReturn(1L);
            when(adminViaOtherRolesQuery.getSingleResult()).thenReturn(1L); // second admin path
            panache.when(() -> PanacheEntityBase.count(eq("role"), eq("ADMIN"))).thenReturn(3L);
            panache.when(() -> PanacheEntityBase.deleteById(ROLE_UUID)).thenReturn(true);

            service.deleteByRoleUuid(ACTOR, ROLE_UUID);

            panache.verify(() -> PanacheEntityBase.deleteById(ROLE_UUID));
            verify(audit).record(eq("USER_ROLE_REMOVED"), eq("user_role"), eq(ACTOR),
                    any(), isNull());
        }
    }

    @Test
    void removingTheLastHolderOfASystemRoleIsRefused() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleRow(panache, OTHER_USER, "HR");
            stubRoleDefinition(panache, "HR", true);
            when(adminViaRoleQuery.getSingleResult()).thenReturn(0L); // HR grants no admin:*
            panache.when(() -> PanacheEntityBase.count(eq("role"), eq("HR"))).thenReturn(1L);

            assertThrows(RoleService.RoleAssignmentRailException.class,
                    () -> service.deleteByRoleUuid(OTHER_USER, ROLE_UUID));
            verifyNoInteractions(audit);
        }
    }

    @Test
    void removingANonSystemRoleFromSomeoneElseIsAllowedAndAudited() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRoleRow(panache, OTHER_USER, "SALES");
            stubRoleDefinition(panache, "SALES", false);
            when(adminViaRoleQuery.getSingleResult()).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.count(eq("role"), eq("SALES"))).thenReturn(5L);
            panache.when(() -> PanacheEntityBase.deleteById(ROLE_UUID)).thenReturn(true);

            service.deleteByRoleUuid(OTHER_USER, ROLE_UUID);

            panache.verify(() -> PanacheEntityBase.deleteById(ROLE_UUID));
            verify(audit).record(eq("USER_ROLE_REMOVED"), eq("user_role"), eq(OTHER_USER),
                    any(), isNull());
        }
    }

    @Test
    void removingAnUnknownAssignmentIs404() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(ROLE_UUID)).thenReturn(null);

            assertThrows(NotFoundException.class,
                    () -> service.deleteByRoleUuid(OTHER_USER, ROLE_UUID));
            verifyNoInteractions(audit);
        }
    }
}
