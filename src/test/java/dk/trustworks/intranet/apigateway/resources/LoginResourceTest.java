package dk.trustworks.intranet.apigateway.resources;

import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit test (no Quarkus, no DB). Guards the kill switch on the legacy
 * {@code GET /login} endpoint, which is {@code @PermitAll} and takes credentials as
 * query parameters — so every call leaks the plaintext password into the access log.
 *
 * <p>The endpoint must stay off unless someone deliberately flips
 * {@code login.legacy-endpoint.enabled}. {@code userAPI} is left null on purpose: if the
 * guard ever stops short-circuiting, the call reaches {@code userAPI.login(...)} and this
 * test fails with an NPE instead of silently passing.
 */
class LoginResourceTest {

    private final LoginResource resource = new LoginResource();

    @Test
    void loginIsDisabledWhenFlagIsOff() {
        resource.legacyLoginEnabled = false;

        assertThrows(NotFoundException.class, () -> resource.login("someone", "s3cret"));
    }

    @Test
    void disabledLoginRejectsNullPasswordWithoutBlowingUp() {
        resource.legacyLoginEnabled = false;

        // A null password used to hit password.equals("hul") and 500 with an NPE.
        assertThrows(NotFoundException.class, () -> resource.login(null, null));
    }

    @Test
    void devBackdoorParameterNoLongerExists() throws Exception {
        Method login = LoginResource.class.getDeclaredMethod("login", String.class, String.class);

        assertEquals(2, login.getParameterCount(),
                "login() must take only username and password — the 'dev' backdoor parameter is gone");
        assertFalse(java.util.Arrays.stream(LoginResource.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals("login") && m.getParameterCount() > 2),
                "no overload of login() may reintroduce the dev backdoor parameter");
    }
}
