package dk.trustworks.intranet.appplatform.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a user membership in an application.
 */
@Data
@NoArgsConstructor
public class AppMember {
    private String userUuid;
    private AppRole role;

    public AppMember(String userUuid, AppRole role) {
        this.userUuid = userUuid;
        this.role = role;
    }
}
