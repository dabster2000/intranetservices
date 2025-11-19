package dk.trustworks.intranet.expenseservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for UserAccount REST API responses.
 * <p>
 * This DTO includes the {@code danlon} field which is stored in the {@code user_danlon_history} table,
 * not in the {@code user_ext_account} table. The REST resource populates this field by querying
 * the current danlon number from the history table via {@link dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService}.
 * </p>
 * <p>
 * This abstraction allows the REST API to continue exposing {@code danlon} as a simple field
 * while the backend uses a temporal history table for storage. Frontend clients remain unaware
 * of the history table complexity.
 * </p>
 *
 * @see UserAccount The underlying entity that maps to the database table
 * @see dk.trustworks.intranet.domain.user.entity.UserDanlonHistory The history table for danlon numbers
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAccountDTO {

    /**
     * User UUID (primary key).
     */
    private String useruuid;

    /**
     * e-conomic customer/supplier number.
     */
    private Integer economics;

    /**
     * Username for external systems.
     */
    private String username;

    /**
     * Current Danløn employee number.
     * <p>
     * This field is populated from the {@code user_danlon_history} table by querying
     * the most recent active danlon number as of today's date.
     * </p>
     * <p>
     * When setting this field via REST API, the backend creates a new history entry
     * with active_date set to the 1st of the current month.
     * </p>
     */
    private String danlon;

    /**
     * Create DTO from entity without danlon.
     * Danlon must be set separately by calling UserDanlonHistoryService.
     */
    public UserAccountDTO(UserAccount entity) {
        this.useruuid = entity.getUseruuid();
        this.economics = entity.getEconomics();
        this.username = entity.getUsername();
        this.danlon = null; // Must be populated by service layer
    }

    /**
     * Convert DTO to entity (danlon field is ignored - stored separately).
     */
    public UserAccount toEntity() {
        return new UserAccount(useruuid, economics, username);
    }
}
