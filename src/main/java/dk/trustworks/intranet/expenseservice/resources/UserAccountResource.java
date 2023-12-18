package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.services.EconomicsService;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;
import javax.ws.rs.*;
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
    public UserAccount getAccount(@QueryParam("account") int account) throws IOException {
        String username = economicsService.getAccount(account);
        return new UserAccount(account, username);
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
        UserAccount.update("account = ?1, " +
                        "username = ?2" +
                        "WHERE useruuid like ?3 ",
                userAccount.getAccount(),
                userAccount.getUsername(),
                useruuid);
    }
}
