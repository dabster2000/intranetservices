package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.contracts.services.ContractConsultantService;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.dto.UserBooking;
import dk.trustworks.intranet.dto.UserProjectBooking;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.utils.NumberUtils;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.getFirstDayOfMonth;
import static dk.trustworks.intranet.utils.DateUtils.getLastDayOfMonth;

@JBossLog
@ApplicationScoped
@Deprecated
public class AvailabilityService {

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    @Inject
    ContractService contractService;

    @Inject
    ContractConsultantService contractConsultantService;

    @Inject
    BudgetService budgetService;

    @Inject
    ClientService clientService;

    @Inject
    ProjectService projectService;

    @Inject
    AvailabilityServiceCache availabilityServiceCache;

    public List<AvailabilityDocument> getAvailabilityDocumentsByPeriod(LocalDate fromdate, LocalDate todate) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream().filter(availabilityDocument ->
                !availabilityDocument.getMonth().withDayOfMonth(1).isBefore(fromdate) && !availabilityDocument.getMonth().withDayOfMonth(1).isAfter(todate.minusDays(1))).collect(Collectors.toList());
    }

    public List<AvailabilityDocument> getConsultantAvailabilityByMonth(LocalDate month) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream()
                .filter(availabilityDocument -> availabilityDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .collect(Collectors.toList());
    }

    public AvailabilityDocument getConsultantAvailabilityByMonth(String useruuid, LocalDate month) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream()
                .filter(
                        availabilityDocument -> availabilityDocument.getUser().getUuid().equals(useruuid) &&
                                availabilityDocument.getMonth().isEqual(month.withDayOfMonth(1))).peek(availabilityDocument -> {
                })
                .findAny().orElse(null);
    }

    public double averageAvailableConsultantsByFiscalYear(int year) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream()
                .filter(a -> a.getMonth().isAfter(LocalDate.of(year, 6, 30)))
                .filter(a -> a.getMonth().isBefore(LocalDate.of(year+1, 7, 1)))
                .filter(a -> a.getStatusType().equals(StatusType.ACTIVE))
                .count() / 12.0;
    }

    public long countActiveEmployeeTypesByMonth(LocalDate month, ConsultantType... consultantTypes) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream()
                .filter(
                        availabilityDocument -> availabilityDocument.getMonth().isEqual(month.withDayOfMonth(1)) &&
                                availabilityDocument.getGrossAvailableHours()>0.0 &&
                                Arrays.asList(consultantTypes).contains(availabilityDocument.getConsultantType()) &&
                                availabilityDocument.getStatusType().equals(StatusType.ACTIVE))
                .count();
    }

    public long getActiveEmployeeCountByMonth(LocalDate month) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream()
                .filter(
                        availabilityDocument -> availabilityDocument.getMonth().isEqual(month.withDayOfMonth(1)) &&
                                availabilityDocument.getGrossAvailableHours()>0.0)
                .count();
    }

    public long countUsersByMonthAndStatusTypeAndConsultantType(LocalDate month, StatusType statusType, ConsultantType consultantType) {
        List<AvailabilityDocument> availabilityData = availabilityServiceCache.getAvailabilityData();
        return availabilityData.stream()
                .filter(
                        availabilityDocument -> availabilityDocument.getMonth().isEqual(month.withDayOfMonth(1)) &&
                                availabilityDocument.getGrossAvailableHours()>0.0 &&
                                availabilityDocument.getConsultantType().equals(consultantType) &&
                                availabilityDocument.getStatusType().equals(statusType))
                .count();
    }

    public List<UserBooking> getUserBooking(int monthsInPast, int monthsInFuture) {
        List<UserBooking> userBookings = new ArrayList<>();
        Map<String, UserProjectBooking> userProjectBookingMap = new HashMap<>();
        LocalDate currentDate;
        for (User user : userService.findCurrentlyEmployedUsers(ConsultantType.CONSULTANT)) {
            currentDate = LocalDate.now().withDayOfMonth(1).minusMonths(monthsInPast);
            UserBooking userBooking = new UserBooking(user.getUsername(),user.getUuid(), monthsInFuture, true);
            userBookings.add(userBooking);

            for (int i = 0; i < monthsInFuture; i++) {
                List<Contract> contracts = contractService.findActiveContractsByDate(currentDate, ContractStatus.BUDGET, ContractStatus.TIME, ContractStatus.SIGNED, ContractStatus.CLOSED);
                for (Contract contract : contracts) {
                    if(contract.getContractType().equals(ContractType.PERIOD)) {
                        for (ContractConsultant contractConsultant : contract.getContractConsultants().stream().filter(c -> userService.findUserByUuid(c.getUseruuid(), true).getUsername().equals(user.getUsername())).toList()) {
                            String key = contractConsultant.getUseruuid()+contract.getClientuuid();
                            if(!userProjectBookingMap.containsKey(key)) {
                                Client client = clientService.findByUuid(contract.getClientuuid());
                                UserProjectBooking newUserProjectBooking = new UserProjectBooking(client.getName(), client.getUuid(), monthsInFuture, false);
                                userProjectBookingMap.put(key, newUserProjectBooking);
                                userBooking.addSubProject(newUserProjectBooking);

                            }
                            UserProjectBooking userProjectBooking = userProjectBookingMap.get(key);

                            double workDaysInMonth = workService.getWorkDaysInMonth(contractConsultant.getUseruuid(), currentDate);
                            double weeks = (workDaysInMonth / 5.0);
                            double preBooking = 0.0;
                            double budget = 0.0;
                            double booking;
                            if(i < monthsInPast) {
                                budget = NumberUtils.round((contractConsultant.getHours() * weeks), 2);
                                Double preBookingObj = workService.findHoursRegisteredOnContractByPeriod(contract.getUuid(), user.getUuid(), getFirstDayOfMonth(currentDate), getLastDayOfMonth(currentDate));
                                if(preBookingObj != null) preBooking = preBookingObj;
                                booking = NumberUtils.round((preBooking / budget) * 100.0, 2);
                            } else {
                                if (contract.getStatus().equals(ContractStatus.BUDGET)) {
                                    preBooking = NumberUtils.round((contractConsultant.getHours() * weeks), 2);
                                } else {
                                    budget = NumberUtils.round((contractConsultant.getHours() * weeks), 2);
                                }
                                booking = NumberUtils.round((budget / (workDaysInMonth * 7)) * 100.0, 2);
                            }
                            userProjectBooking.setAmountItemsPerProjects(budget, i);
                            userProjectBooking.setAmountItemsPerPrebooking(preBooking, i);
                            userProjectBooking.setBookingPercentage(booking, i);
                        }
                    }
                }

                List<Budget> budgets = budgetService.findByMonthAndYear(currentDate);
                for (Budget budget : budgets) {
                    LocalDate finalCurrentDate = currentDate;
                    int finalI = i;
                    ContractConsultant consultant = contractConsultantService.findByUUID(budget.getConsultantuuid());
                    if(!consultant.getUseruuid().equals(user.getUuid())) continue;
                    Project project = projectService.findByUuid(budget.getProjectuuid());
                    Client client = clientService.findByUuid(project.getClientuuid());

                    String key = consultant.getUseruuid()+budget.getProjectuuid();
                    if(!userProjectBookingMap.containsKey(key)) {
                        UserProjectBooking newUserProjectBooking = new UserProjectBooking(project.getName() + " / " + client.getName(), client.getUuid(), monthsInFuture, false);
                        userProjectBookingMap.put(key, newUserProjectBooking);
                        userBooking.addSubProject(newUserProjectBooking);
                    }
                    UserProjectBooking userProjectBooking = userProjectBookingMap.get(key);

                    double workDaysInMonth = workService.getWorkDaysInMonth(user.getUuid(), finalCurrentDate);
                    double preBooking = 0.0;
                    double hourBudget = 0.0;
                    double booking;

                    if(finalI < monthsInPast) {
                        hourBudget = NumberUtils.round(budget.getBudget() / consultant.getRate(), 2);
                        preBooking = Optional.ofNullable(workService.findHoursRegisteredOnContractByPeriod(consultant.getContractuuid(), consultant.getUseruuid(), getFirstDayOfMonth(finalCurrentDate), getLastDayOfMonth(finalCurrentDate))).orElse(0.0);
                        booking = NumberUtils.round((preBooking / hourBudget) * 100.0, 2);
                    } else {
                        if (contractService.findByUuid(consultant.getContractuuid()).getStatus().equals(ContractStatus.BUDGET)) {
                            preBooking = NumberUtils.round(budget.getBudget() / consultant.getRate(), 2);
                        } else {
                            hourBudget = NumberUtils.round(budget.getBudget() / consultant.getRate(), 2);
                        }
                        booking = NumberUtils.round(((hourBudget) / (workDaysInMonth * 7)) * 100.0, 2);
                    }

                    userProjectBooking.setAmountItemsPerProjects(hourBudget, finalI);
                    userProjectBooking.setAmountItemsPerPrebooking(preBooking, finalI);
                    userProjectBooking.setBookingPercentage(booking, finalI);
                }

                currentDate = currentDate.plusMonths(1);
            }
        }

        for(UserBooking userBooking : userBookings) {
            if(userBooking.getSubProjects().size() == 0) continue;
            for (UserBooking subProject : userBooking.getSubProjects()) {
                currentDate = LocalDate.now().withDayOfMonth(1).minusMonths(monthsInPast);
                for (int i = 0; i < monthsInFuture; i++) {
                    userBooking.addAmountItemsPerProjects(subProject.getAmountItemsPerProjects(i), i);
                    userBooking.addAmountItemsPerPrebooking(subProject.getAmountItemsPerPrebooking(i), i);
                    int workDaysInMonth = workService.getWorkDaysInMonth(userService.findByUsername(userBooking.getUsername(), true).getUuid(), currentDate);
                    userBooking.setMonthNorm(NumberUtils.round(workDaysInMonth * 7, 2), i);
                    subProject.setMonthNorm(NumberUtils.round(workDaysInMonth * 7, 2), i);
                    currentDate = currentDate.plusMonths(1);
                }
            }

            for (int i = 0; i < monthsInFuture; i++) {
                if(i < monthsInPast) {
                    userBooking.setBookingPercentage(NumberUtils.round((userBooking.getAmountItemsPerPrebooking(i) / userBooking.getAmountItemsPerProjects(i)) * 100.0, 2), i);
                } else {
                    if (userBooking.getMonthNorm(i) > 0.0)
                        userBooking.setBookingPercentage(NumberUtils.round((userBooking.getAmountItemsPerProjects(i) / (userBooking.getMonthNorm(i))) * 100.0, 2), i);
                }
            }
        }
        return userBookings;
    }
}
