package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;


@ApplicationScoped
public class UtilizationCalculatingExecutor {

    @Inject
    UserService userService;
    @Inject
    WorkService workService;

    public void createAvailabilityDocumentByUserAndDay(String useruuid, LocalDate testDay) {

    }
}
