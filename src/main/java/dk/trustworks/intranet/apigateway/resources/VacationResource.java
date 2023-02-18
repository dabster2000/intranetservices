package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.PermitAll;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "vacation")
@Path("/vacation")
@JBossLog
@RequestScoped
//@SecurityRequirement(name = "jwt")
//@ClientHeaderParam(name="Authorization", value="{generateRequestId}")
//@RolesAllowed({"SYSTEM", "USER", "EXTERNAL", "EDITOR", "CXO", "SALES", "VTV", "ACCOUNTING", "MANAGER", "PARTNER", "ADMIN"})
@PermitAll
public class VacationResource {

    @Inject
    WorkService workAPI;

    @Inject
    UserService userService;


    private LocalDate startDate = LocalDate.of(2020, 9,1);
    private final static double VacationPerDay = 0.07;
    private final static double VacationPerMonth = 2.08;

    @GET
    @Path("/user/{useruuid}")
    public List<Work> getVacation(@PathParam("useruuid") String useruuid) {
        System.out.println("VacationResource.listAll");
        System.out.println("useruuid = " + useruuid);

        User user = userService.findUserByUuid(useruuid, false);
        List<WorkFull> vacation = workAPI.findVacationByUser(useruuid);
        LocalDate employedDate = user.getEmployedDate();
        System.out.println("employedDate = " + employedDate);

        if(employedDate!= null && employedDate.isAfter(startDate)) startDate = employedDate.withDayOfMonth(1);
        System.out.println("startDate = " + startDate);

        Map<LocalDate, VacationMonth> vacationMonths = new HashMap<>();

        //Map<LocalDate, Double> vacationEarnedByMonth = new HashMap<>();
        //Map<LocalDate, Double> vacationUsedByMonth = new HashMap<>();

        Map<LocalDate, Double> vacationByYear = new HashMap<>();

        LocalDate testDate = startDate;
        System.out.println("User: "+user.getUsername());
        StringBuilder strMonth =    new StringBuilder("MONTH,");
        StringBuilder strEarned =   new StringBuilder("EARNED,");
        StringBuilder strUsed =     new StringBuilder("USED,");
        while((user.getUserStatus(testDate).getStatus().equals(StatusType.ACTIVE) || user.getUserStatus(testDate).getStatus().equals(StatusType.NON_PAY_LEAVE)) && testDate.isBefore(LocalDate.now())) {
            vacationMonths.putIfAbsent(testDate, new VacationMonth());
            vacationMonths.putIfAbsent(testDate.plusMonths(1), new VacationMonth());
            strMonth.append(DateUtils.stringIt(testDate, "MM/yy")).append(";");
            vacationMonths.get(testDate.plusMonths(1)).saved += VacationPerMonth;
            //strEarned.append(String.format("%02.2f", VacationPerMonth)).append(";");
            LocalDate finalTestDate = testDate;
            double vacationUsedTestMonth = vacation.stream().filter(work -> work.getRegistered().withDayOfMonth(1).isEqual(finalTestDate)).mapToDouble(WorkFull::getWorkduration).sum() / 7.4;
            vacationMonths.get(testDate).used += vacationUsedTestMonth;
            //strUsed.append(String.format("%02.2f", vacationUsedTestMonth)).append(";");

            testDate = testDate.plusMonths(1);
        }
        strMonth.append("]");
        strEarned.append("]");
        strUsed.append("]");

        vacationMonths.keySet().stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());

        //vacationByYear.put(convertToVacationEarnedYear(testDate), VacationPerDay);
        //if(vacationByYear.containsKey(convertToVacationUsedYear(testDate))) vacationByYear.put(convertToVacationUsedYear(testDate), vacationByYear.get(convertToVacationUsedYear(testDate))-vacationUsedTestMonth);


        System.out.println("vacationByYear = " + convertWithStream(vacationByYear));

        //System.out.println("convertWithStream(vacationEarnedByMonth) = " + convertWithStream(vacationEarnedByMonth));
        //System.out.println("convertWithStream(vacationEarnedByMonth) = " + convertWithStream(vacationUsedByMonth));

        System.out.println(strMonth);
        System.out.println(strEarned);
        System.out.println(strUsed);

        return null;
    }

    private LocalDate convertToVacationEarnedYear(LocalDate date) {
        //System.out.println("VacationResource.convertToVacationYear");
        //System.out.println("date = " + date);
        if(date.withDayOfMonth(1).getMonthValue()>=9) return LocalDate.of(date.getYear(), 9,1);
        LocalDate year = LocalDate.of(date.minusYears(1).getYear(), 9,1);
        //System.out.println("year = " + year);
        return year;
    }

    private LocalDate convertToVacationUsedYear(LocalDate date) {
        //System.out.println("VacationResource.convertToVacationYear");
        //System.out.println("date = " + date);
        return date.minusYears(1).withMonth(9).withDayOfMonth(1);
    }

    public String convertWithStream(Map<LocalDate, ?> map) {
        return map.keySet().stream()
                .sorted(LocalDate::compareTo)
                .map(key -> key + "=" + map.get(key))
                .collect(Collectors.joining(", ", "", ""));
    }
}

class VacationMonth {
    LocalDate month;
    double saved;
    double used;
}
