package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.model.UserAccountDTO;
import dk.trustworks.intranet.expenseservice.services.EconomicsService;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;

@JBossLog
@Path("/user-accounts")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"expenses:read"})
public class UserAccountResource {

    @Inject
    EventBus bus;

    @Inject
    EconomicsService economicsService;

    @Inject
    UserDanlonHistoryService danlonHistoryService;

    /**
     * Get user account including current Danløn number.
     * <p>
     * Returns a DTO that includes the current danlon number from the history table.
     * The danlon field represents the most recent active danlon number for this user.
     * </p>
     */
    @GET
    @Path("/{useruuid}")
    public UserAccountDTO getAccountByUser(@PathParam("useruuid") String useruuid) {
        UserAccount entity = UserAccount.findById(useruuid);
        if (entity == null) {
            return null;
        }

        // Create DTO from entity
        UserAccountDTO dto = new UserAccountDTO(entity);

        // Populate danlon from history table
        String currentDanlon = danlonHistoryService.getCurrentDanlon(useruuid).orElse(null);
        dto.setDanlon(currentDanlon);

        return dto;
    }

    @GET
    @Path("/search/findByAccountNumber")
    public UserAccount getAccount(@QueryParam("companyuuid") String companyuuid, @QueryParam("account") int account) throws Exception {
        String username = economicsService.getAccount(companyuuid, account);
        return new UserAccount(account, username);
    }

    /**
     * Save or update a user account including Danløn number.
     * <p>
     * This endpoint accepts a UserAccountDTO which includes:
     * <ul>
     *   <li>economics - saved to user_ext_account table</li>
     *   <li>username - saved to user_ext_account table</li>
     *   <li>danlon - saved to user_danlon_history table with active_date = 1st of current month</li>
     * </ul>
     * </p>
     */
    @POST
    @RolesAllowed({"expenses:write"})
    @Transactional
    public void saveAccount(@Valid UserAccountDTO dto, @Context SecurityContext securityContext) {
        String useruuid = dto.getUseruuid();
        UserAccount existing = UserAccount.findById(useruuid);

        // Save/update the account entity (economics and username)
        UserAccount entity = dto.toEntity();
        if (existing != null) {
            // Update existing account
            UserAccount.update("economics = ?1, username = ?2 WHERE useruuid like ?3",
                    entity.getEconomics(),
                    entity.getUsername(),
                    useruuid);
        } else {
            // Create new account
            entity.persist();
            log.infof("Created new UserAccount for user %s", useruuid);
        }

        // Danløn is read-only here. Minting/closing happens ONLY via DanlonAssignmentService
        // (HR-approved proposals) — the single minting authority. The incoming danlon is ignored.
    }

    /**
     * Update an existing user account including Danløn number.
     * <p>
     * This endpoint accepts a UserAccountDTO which updates:
     * <ul>
     *   <li>economics - saved to user_ext_account table</li>
     *   <li>username - saved to user_ext_account table</li>
     *   <li>danlon - saved to user_danlon_history table with active_date = 1st of current month</li>
     * </ul>
     * </p>
     */
    @PUT
    @Path("/{useruuid}")
    @RolesAllowed({"expenses:write"})
    @Transactional
    public void updateAccount(@PathParam("useruuid") String useruuid,
                              UserAccountDTO dto,
                              @Context SecurityContext securityContext) {
        // Get existing account
        UserAccount existing = UserAccount.findById(useruuid);
        if (existing == null) {
            log.warnf("Attempted to update non-existent UserAccount: %s", useruuid);
            throw new NotFoundException("UserAccount not found: " + useruuid);
        }

        // Update account (economics and username)
        UserAccount.update("economics = ?1, username = ?2 WHERE useruuid like ?3",
                dto.getEconomics(),
                dto.getUsername(),
                useruuid);

        // Danløn is read-only here. Minting/closing happens ONLY via DanlonAssignmentService
        // (HR-approved proposals) — the single minting authority. The incoming danlon is ignored.
    }

}
