package dk.trustworks.intranet.security;

import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserBankInfo;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.TeamRole;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UserScopeResponseFilter}.
 * Tests scope-based field stripping for direct User entities, lists, and
 * embedded User objects.
 */
@ExtendWith(MockitoExtension.class)
class UserScopeResponseFilterTest {

    @Mock
    private ScopeContext scopeContext;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private ContainerResponseContext responseContext;

    @InjectMocks
    private UserScopeResponseFilter filter;

    private User userWithAllFields;

    @BeforeEach
    void setUp() {
        userWithAllFields = createPopulatedUser();
    }

    // -- Admin bypass --

    @Test
    void filter_adminScope_doesNotStripAnyFields() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertFalse(userWithAllFields.getSalaries().isEmpty());
        assertFalse(userWithAllFields.getUserBankInfos().isEmpty());
        assertFalse(userWithAllFields.getStatuses().isEmpty());
        assertFalse(userWithAllFields.getTeams().isEmpty());
        assertFalse(userWithAllFields.getCareerLevels().isEmpty());
        assertFalse(userWithAllFields.getRoleList().isEmpty());
    }

    // -- Null entity --

    @Test
    void filter_nullEntity_isNoOp() throws Exception {
        when(responseContext.getEntity()).thenReturn(null);

        filter.filter(requestContext, responseContext);

        // No exceptions, no scope checks needed beyond the null guard
    }

    // -- Salary scope --

    @Test
    void filter_missingSalaryScope_stripsSalariesAndBankInfos() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getSalaries().isEmpty(), "Salaries should be cleared");
        assertTrue(userWithAllFields.getUserBankInfos().isEmpty(), "Bank infos should be cleared");
        // Other fields should remain
        assertFalse(userWithAllFields.getStatuses().isEmpty());
        assertFalse(userWithAllFields.getTeams().isEmpty());
        assertFalse(userWithAllFields.getCareerLevels().isEmpty());
    }

    @Test
    void filter_hasSalaryScope_preservesSalariesAndBankInfos() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(true);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertFalse(userWithAllFields.getSalaries().isEmpty());
        assertFalse(userWithAllFields.getUserBankInfos().isEmpty());
    }

    // -- UserStatus scope --

    @Test
    void filter_missingUserStatusScope_stripsStatuses() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(true);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(false);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getStatuses().isEmpty(), "Statuses should be cleared");
        assertFalse(userWithAllFields.getSalaries().isEmpty());
    }

    // -- Teams scope --

    @Test
    void filter_missingTeamsScope_stripsTeams() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(true);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(false);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getTeams().isEmpty(), "Teams should be cleared");
        assertFalse(userWithAllFields.getStatuses().isEmpty());
    }

    // -- CareerLevel scope --

    @Test
    void filter_missingCareerLevelScope_stripsCareerLevels() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(true);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(false);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getCareerLevels().isEmpty(), "CareerLevels should be cleared");
        assertFalse(userWithAllFields.getTeams().isEmpty());
    }

    // -- RoleList (always stripped for non-admin) --

    @Test
    void filter_nonAdmin_alwaysStripsRoleList() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(true);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getRoleList().isEmpty(), "RoleList should be cleared for non-admin");
    }

    // -- All scopes missing --

    @Test
    void filter_noScopes_stripsAllSensitiveFields() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(false);
        when(scopeContext.hasScope("teams:read")).thenReturn(false);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(false);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getSalaries().isEmpty());
        assertTrue(userWithAllFields.getUserBankInfos().isEmpty());
        assertTrue(userWithAllFields.getStatuses().isEmpty());
        assertTrue(userWithAllFields.getTeams().isEmpty());
        assertTrue(userWithAllFields.getCareerLevels().isEmpty());
        assertTrue(userWithAllFields.getRoleList().isEmpty());
    }

    // -- List of Users --

    @Test
    void filter_listOfUsers_stripsFieldsOnEachUser() throws Exception {
        User user1 = createPopulatedUser();
        User user2 = createPopulatedUser();
        List<User> users = new ArrayList<>(List.of(user1, user2));

        when(responseContext.getEntity()).thenReturn(users);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(user1.getSalaries().isEmpty());
        assertTrue(user2.getSalaries().isEmpty());
        assertFalse(user1.getStatuses().isEmpty());
        assertFalse(user2.getStatuses().isEmpty());
    }

    // -- Embedded User via getUser() --

    @Test
    void filter_embeddedUserViaGetter_stripsFields() throws Exception {
        var wrapper = new UserWrapper(userWithAllFields);

        when(responseContext.getEntity()).thenReturn(wrapper);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getSalaries().isEmpty());
        assertTrue(userWithAllFields.getUserBankInfos().isEmpty());
    }

    // -- Embedded User via field --

    @Test
    void filter_embeddedUserViaField_stripsFields() throws Exception {
        var wrapper = new UserFieldWrapper(userWithAllFields);

        when(responseContext.getEntity()).thenReturn(wrapper);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(userWithAllFields.getSalaries().isEmpty());
    }

    // -- List of embedded User wrappers --

    @Test
    void filter_listOfEmbeddedUserWrappers_stripsFieldsOnEach() throws Exception {
        User user1 = createPopulatedUser();
        User user2 = createPopulatedUser();
        List<UserWrapper> wrappers = new ArrayList<>(List.of(
                new UserWrapper(user1), new UserWrapper(user2)));

        when(responseContext.getEntity()).thenReturn(wrappers);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(true);
        when(scopeContext.hasScope("teams:read")).thenReturn(true);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(true);

        filter.filter(requestContext, responseContext);

        assertTrue(user1.getSalaries().isEmpty());
        assertTrue(user2.getSalaries().isEmpty());
    }

    // -- Non-User entity (no embedded User) --

    @Test
    void filter_unrelatedEntity_isNoOp() throws Exception {
        String plainEntity = "just a string";
        when(responseContext.getEntity()).thenReturn(plainEntity);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);

        filter.filter(requestContext, responseContext);

        // No exception, no stripping needed
        assertEquals("just a string", plainEntity);
    }

    // -- Cleared lists are safe for .stream() --

    @Test
    void filter_clearedLists_areSafeForStreamOperations() throws Exception {
        when(responseContext.getEntity()).thenReturn(userWithAllFields);
        when(scopeContext.hasScope("admin:*")).thenReturn(false);
        when(scopeContext.hasScope("salaries:read")).thenReturn(false);
        when(scopeContext.hasScope("userstatus:read")).thenReturn(false);
        when(scopeContext.hasScope("teams:read")).thenReturn(false);
        when(scopeContext.hasScope("careerlevel:read")).thenReturn(false);

        filter.filter(requestContext, responseContext);

        // These must not throw NPE — downstream code calls .stream() on them
        assertDoesNotThrow(() -> userWithAllFields.getSalaries().stream().count());
        assertDoesNotThrow(() -> userWithAllFields.getUserBankInfos().stream().count());
        assertDoesNotThrow(() -> userWithAllFields.getStatuses().stream().count());
        assertDoesNotThrow(() -> userWithAllFields.getTeams().stream().count());
        assertDoesNotThrow(() -> userWithAllFields.getCareerLevels().stream().count());
        assertDoesNotThrow(() -> userWithAllFields.getRoleList().stream().count());
    }

    // -- Helpers --

    private User createPopulatedUser() {
        User user = new User();
        user.setFirstname("Test");
        user.setLastname("User");
        user.setUsername("testuser");
        user.setEmail("test@test.dk");

        user.setSalaries(new ArrayList<>(List.of(new Salary())));
        user.setUserBankInfos(new ArrayList<>(List.of(new UserBankInfo())));
        user.setStatuses(new ArrayList<>(List.of(new UserStatus())));
        user.setTeams(new ArrayList<>(List.of(new TeamRole())));
        user.setCareerLevels(new ArrayList<>(List.of(new UserCareerLevel())));
        user.setRoleList(new ArrayList<>(List.of(new Role())));

        return user;
    }

    /**
     * Test wrapper that exposes an embedded User via a getUser() method,
     * simulating DTOs like UserFinanceDocument.
     */
    static class UserWrapper {
        private final User user;

        UserWrapper(User user) {
            this.user = user;
        }

        public User getUser() {
            return user;
        }
    }

    /**
     * Test wrapper that exposes an embedded User via a 'user' field only
     * (no getter), simulating DTOs with direct field access.
     */
    static class UserFieldWrapper {
        @SuppressWarnings("unused") // accessed via reflection
        private final User user;

        UserFieldWrapper(User user) {
            this.user = user;
        }
    }
}
