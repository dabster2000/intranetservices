package dk.trustworks.intranet.expenseservice.resources;

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

    @GET
    @Path("/{useruuid}")
    public UserAccount getAccountByUser(@PathParam("useruuid") String useruuid) {
        return UserAccount.findById(useruuid);
    }

    @GET
    @Path("/search/findByAccountNumber")
    public UserAccount getAccount(@QueryParam("companyuuid") String companyuuid, @QueryParam("account") int account) throws IOException {
        String username = economicsService.getAccount(companyuuid, account);
        return new UserAccount(account, "", username);
    }

    // should probably be validated against user service
    @POST
    @Transactional
    public void saveAccount(@Valid UserAccount useraccount) {
        if(UserAccount.findById(useraccount.getUseruuid())!=null) updateAccount(useraccount.getUseruuid(), useraccount);
        else useraccount.persist();
    }

    @PUT
    @Path("/{useruuid}")
    @Transactional
    public void updateAccount(@PathParam("useruuid") String useruuid, UserAccount userAccount) {
        UserAccount.update("economics = ?1, " +
                        "username = ?2" +
                        "WHERE useruuid like ?3 ",
                userAccount.getEconomics(),
                userAccount.getUsername(),
                useruuid);
    }
}
