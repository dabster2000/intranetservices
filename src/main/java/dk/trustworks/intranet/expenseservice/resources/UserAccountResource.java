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
import java.time.LocalDate;

@JBossLog
@Path("/user-accounts")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"SYSTEM"})
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

        // Save danlon to history table if provided
        if (dto.getDanlon() != null && !dto.getDanlon().isEmpty()) {
            saveDanlonToHistory(useruuid, dto.getDanlon(), securityContext);
        }
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

        // Save danlon to history table if provided
        if (dto.getDanlon() != null && !dto.getDanlon().isEmpty()) {
            saveDanlonToHistory(useruuid, dto.getDanlon(), securityContext);
        }
    }

    /**
     * Helper method to save danlon number to history table.
     * <p>
     * This method checks if a danlon number already exists for the current month.
     * If it exists and differs from the new value, it updates the existing record.
     * If it doesn't exist, it creates a new history entry.
     * </p>
     *
     * @param useruuid User UUID
     * @param danlon New danlon number
     * @param securityContext Security context to get current username
     */
    private void saveDanlonToHistory(String useruuid, String danlon, SecurityContext securityContext) {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        String currentDanlon = danlonHistoryService.getDanlonAsOf(useruuid, currentMonth).orElse(null);

        // Get username for audit trail
        String createdBy = securityContext != null && securityContext.getUserPrincipal() != null
                ? securityContext.getUserPrincipal().getName()
                : "system";

        try {
            if (currentDanlon == null) {
                // No danlon for current month - create new entry
                danlonHistoryService.addDanlonHistory(useruuid, currentMonth, danlon, createdBy);
                log.infof("Created danlon history for user %s: %s (active date: %s)", useruuid, danlon, currentMonth);
            } else if (!currentDanlon.equals(danlon)) {
                // Danlon changed - update existing entry for current month
                // Note: This assumes there's only one entry per month, which is enforced by the unique constraint
                log.infof("Danlon changed for user %s from %s to %s", useruuid, currentDanlon, danlon);
                danlonHistoryService.addDanlonHistory(useruuid, currentMonth, danlon, createdBy);
            } else {
                // Danlon unchanged - no action needed
                log.debugf("Danlon unchanged for user %s: %s", useruuid, danlon);
            }
        } catch (IllegalArgumentException e) {
            // Duplicate entry - this is OK, just log it
            log.warnf("Danlon history already exists for user %s in month %s - skipping", useruuid, currentMonth);
        } catch (Exception e) {
            // Log error but don't fail the account save
            log.errorf(e, "Failed to save danlon history for user %s", useruuid);
        }
    }

}
