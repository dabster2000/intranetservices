package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.services.EconomicsService;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import java.io.IOException;
import java.time.LocalDate;

@JBossLog
@Path("/user-accounts")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"USER", "SYSTEM"})
public class UserAccountResource {

    @Inject
    EventBus bus;

    @Inject
    EconomicsService economicsService;

    @Inject
    UserDanlonHistoryService danlonHistoryService;

    @GET
    @Path("/{useruuid}")
    public UserAccount getAccountByUser(@PathParam("useruuid") String useruuid) {
        return UserAccount.findById(useruuid);
    }

    @GET
    @Path("/search/findByAccountNumber")
    public UserAccount getAccount(@QueryParam("companyuuid") String companyuuid, @QueryParam("account") int account) throws IOException {
        String username = economicsService.getAccount(companyuuid, account);
        return new UserAccount(account, username);
    }

    /**
     * Save or update a user account.
     * <p>
     * Note: Danløn numbers are now managed separately via UserDanlonHistoryService.
     * This endpoint only handles economics account number and username.
     * </p>
     */
    @POST
    @Transactional
    public void saveAccount(@Valid UserAccount useraccount) {
        UserAccount existing = UserAccount.findById(useraccount.getUseruuid());

        if (existing != null) {
            // Update existing account
            updateAccount(useraccount.getUseruuid(), useraccount);
        } else {
            // Create new account
            useraccount.persist();
            log.infof("Created new UserAccount for user %s", useraccount.getUseruuid());
        }
    }

    /**
     * Update an existing user account.
     * <p>
     * Note: Danløn numbers are now managed separately via UserDanlonHistoryService.
     * This endpoint only handles economics account number and username.
     * </p>
     */
    @PUT
    @Path("/{useruuid}")
    @Transactional
    public void updateAccount(@PathParam("useruuid") String useruuid, UserAccount userAccount) {
        // Get existing account
        UserAccount existing = UserAccount.findById(useruuid);
        if (existing == null) {
            log.warnf("Attempted to update non-existent UserAccount: %s", useruuid);
            throw new NotFoundException("UserAccount not found: " + useruuid);
        }

        // Update account (economics and username only)
        UserAccount.update("economics = ?1, " +
                        "username = ?2 " +
                        "WHERE useruuid like ?3 ",
                userAccount.getEconomics(),
                userAccount.getUsername(),
                useruuid);
    }

}
