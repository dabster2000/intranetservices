package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.userservice.model.Employee;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Maps a recruitment user UUID to the user's Slack username (used as Slack recipient
 * for {@code conversations.open}). Throws a terminal {@link SlackException} if the
 * user is missing from the directory or has no slackusername populated.
 *
 * <p>{@link #findEmployee(String)} is package-private so unit tests can override
 * it without static mocking — Mockito cannot stub Panache statics inherited from
 * {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class SlackUserResolver {

    public String resolve(String userUuid) {
        Employee emp = findEmployee(userUuid);
        if (emp == null) {
            throw new SlackException(false, "user_not_found", "user uuid not in directory: " + userUuid);
        }
        String name = emp.getSlackusername();
        if (name == null || name.isBlank()) {
            throw new SlackException(false, "user_not_found", "user has no slackusername: " + userUuid);
        }
        return name;
    }

    Employee findEmployee(String userUuid) {
        return Employee.findById(userUuid);
    }
}
