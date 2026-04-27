package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.userservice.model.Employee;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlackUserResolverTest {

    private static SlackUserResolver withDirectory(Map<String, Employee> directory) {
        return new SlackUserResolver() {
            @Override
            Employee findEmployee(String userUuid) {
                return directory.get(userUuid);
            }
        };
    }

    @Test
    void resolves_user_uuid_to_slackusername() {
        Employee emp = new Employee();
        emp.setUuid("u-1");
        emp.setSlackusername("alice");
        Map<String, Employee> dir = new HashMap<>();
        dir.put("u-1", emp);

        assertEquals("alice", withDirectory(dir).resolve("u-1"));
    }

    @Test
    void throws_terminal_when_slackusername_blank() {
        Employee emp = new Employee();
        emp.setUuid("u-2");
        emp.setSlackusername("");
        Map<String, Employee> dir = new HashMap<>();
        dir.put("u-2", emp);

        SlackException ex = assertThrows(SlackException.class, () -> withDirectory(dir).resolve("u-2"));
        assertFalse(ex.isRetryable());
        assertEquals("user_not_found", ex.getErrorCode());
    }

    @Test
    void throws_terminal_when_user_missing() {
        SlackException ex = assertThrows(SlackException.class,
                () -> withDirectory(new HashMap<>()).resolve("u-x"));
        assertFalse(ex.isRetryable());
    }
}
