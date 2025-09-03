package dk.trustworks.intranet.services;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.model.GuestRegistration;
import dk.trustworks.intranet.model.RegistrationRequest;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class GuestRegistrationService {

    @Inject
    SlackService slackService;

    @Inject
    UserService userService;

    @Transactional
    public void register(RegistrationRequest request) {
        GuestRegistration registration = new GuestRegistration();
        registration.setUuid(UUID.randomUUID().toString());
        registration.setGuestName(request.getGuestName());
        registration.setGuestCompany(request.getGuestCompany());
        registration.setEmployeeName(request.getEmployee());
        registration.setEmployeeUuid(request.getEmployeeId());
        registration.setRegistrationTime(LocalDateTime.now());
        log.debug("Persisting " + registration);
        registration.persist();

        try {
            User employee = userService.findById(registration.getEmployeeUuid(), true);
            if(employee != null) {
                String text = "Guest " + registration.getGuestName() +
                        (registration.getGuestCompany() != null && !registration.getGuestCompany().isEmpty()
                                ? " from " + registration.getGuestCompany() : "") +
                        " has arrived.";
                slackService.sendMessage(employee, text);
            }
        } catch (SlackApiException | java.io.IOException e) {
            log.error("Failed to notify employee via Slack", e);
        }
    }

    public List<GuestRegistration> listAll() {
        return GuestRegistration.listAll(Sort.by("registrationTime", Sort.Direction.Descending));
    }

    public List<GuestRegistration> findByDate(LocalDate date) {
        return GuestRegistration.find("DATE(registrationTime) = ?1", date).list();
    }

    public List<GuestRegistration> findByEmployee(String employeeUuid) {
        return GuestRegistration.find("employeeUuid", employeeUuid).list();
    }
}
