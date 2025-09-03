package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateBankInfoEvent;
import dk.trustworks.intranet.aggregates.users.services.UserBankInfoService;
import dk.trustworks.intranet.domain.user.entity.UserBankInfo;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;

@Tag(name = "bankinfo")
@Path("/bankinfos")
@RequestScoped
@JBossLog
@PermitAll
public class BankInfoResource {

    @Inject
    UserBankInfoService service;

    @Inject
    AggregateEventSender aggregateEventSender;

    @POST
    @Path("/drafts")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveBankAccountInfoForm(@FormParam("full_name") String fullName, @FormParam("active_date") String activeDate, @FormParam("regnr") Optional<String> regNumber,
                                           @FormParam("account_nr") Optional<String> accountNumber, @FormParam("bic_swift") Optional<String> bicSwiftCode, @FormParam("iban") Optional<String> ibanNumber) {
        UserBankInfo userBankInfo = new UserBankInfo(
                fullName,
                DateUtils.dateIt(activeDate),
                regNumber.orElse(""),
                accountNumber.orElse(""),
                bicSwiftCode.orElse(""),
                ibanNumber.orElse(""));
        service.create(userBankInfo);
        CreateBankInfoEvent createBankInfoEvent = new CreateBankInfoEvent(userBankInfo.getUuid(), userBankInfo);
        aggregateEventSender.handleEvent(createBankInfoEvent);
    }

    @GET
    @Path("/unassigned")
    public List<UserBankInfo> findUnassignedBankInfos() {
        return service.findUnassignedBankInfos();
    }



}
