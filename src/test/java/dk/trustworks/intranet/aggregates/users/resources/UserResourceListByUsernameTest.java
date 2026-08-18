package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of GET /users?username=…
 *
 * An unknown username must answer an empty list. This used to answer a
 * one-element list holding {@code new User()} — which mints a random UUID — so
 * both BFF callers (login-time resolution and user-resolver) read a miss as a
 * hit and adopted a phantom identity. Live incident 2026-08-18: employees whose
 * Azure login prefix had drifted from {@code user.username} were shown the
 * alphabetically first employee's timesheet as if it were their own.
 */
class UserResourceListByUsernameTest {

    private static final String USERNAME = "nicolai.noerr";

    private UserResource resource;
    private UserService userService;

    @BeforeEach
    void setUp() {
        resource = new UserResource();
        userService = mock(UserService.class);
        resource.userAPI = userService;
    }

    @Test
    void unknownUsernameIsEmptyList() {
        when(userService.findByUsername(USERNAME, true)).thenReturn(null);

        List<User> result = resource.listAll(Optional.of(USERNAME), Optional.of("true"));

        assertTrue(result.isEmpty(), "an unknown username must not fabricate a user");
    }

    @Test
    void knownUsernameIsSingleElementList() {
        User user = new User();
        when(userService.findByUsername(USERNAME, true)).thenReturn(user);

        List<User> result = resource.listAll(Optional.of(USERNAME), Optional.of("true"));

        assertEquals(1, result.size());
        assertSame(user, result.get(0));
    }

    @Test
    void noUsernameParamListsEveryone() {
        List<User> everyone = List.of(new User(), new User());
        when(userService.listAll(false)).thenReturn(everyone);

        List<User> result = resource.listAll(Optional.empty(), Optional.empty());

        assertEquals(everyone, result);
    }
}
