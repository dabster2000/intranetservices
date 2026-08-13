package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of GET /users/{uuid}. A missing user must answer 404 —
 * returning the service's null used to become an empty 204, which backendFetch
 * in the BFF resolves to a "successful" null payload (prod incident 2026-08-12).
 */
class UserResourceFindByIdTest {

    private static final String USER_UUID = "65557592-7c20-42c3-b103-687a705ad761";

    private UserResource resource;
    private UserService userService;

    @BeforeEach
    void setUp() {
        resource = new UserResource();
        userService = mock(UserService.class);
        resource.userAPI = userService;
    }

    @Test
    void missingUserIs404() {
        when(userService.findById(USER_UUID, false)).thenReturn(null);

        assertThrows(NotFoundException.class,
                () -> resource.findById(USER_UUID, Optional.empty()));
    }

    @Test
    void existingUserIsReturned() {
        User user = new User();
        user.uuid = USER_UUID;
        when(userService.findById(USER_UUID, false)).thenReturn(user);

        assertSame(user, resource.findById(USER_UUID, Optional.of("false")));

        verify(userService).findById(USER_UUID, false);
    }

    @Test
    void shallowFlagIsPassedThrough() {
        User user = new User();
        when(userService.findById(USER_UUID, true)).thenReturn(user);

        assertSame(user, resource.findById(USER_UUID, Optional.of("true")));

        verify(userService).findById(USER_UUID, true);
    }
}
