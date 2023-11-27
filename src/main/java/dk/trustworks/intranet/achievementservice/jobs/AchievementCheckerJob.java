package dk.trustworks.intranet.achievementservice.jobs;

import dk.trustworks.intranet.achievementservice.AchievementPersistenceManager;
import dk.trustworks.intranet.achievementservice.model.Achievement;
import dk.trustworks.intranet.achievementservice.model.enums.AchievementType;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.BudgetService;
import dk.trustworks.intranet.aggregateservices.RevenueService;
import dk.trustworks.intranet.dao.bubbleservice.services.BubbleService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.knowledgeservice.model.CKOExpense;
import dk.trustworks.intranet.knowledgeservice.services.CkoExpenseService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class AchievementCheckerJob {

    @Inject
    CkoExpenseService ckoExpenseService;

    @Inject
    UserService userResource;

    @Inject
    BubbleService bubbleService;

    @Inject
    WorkService workService;

    @Inject
    StatusService statusService;

    @Inject
    BudgetService budgetService;

    @Inject
    RevenueService revenueService;

    @Inject
    AchievementPersistenceManager persistenceManager;

    //@Scheduled(every="24h")
    void checkAchievements() {
        log.debug("AchievementCheckerJob.checkAchievements");
        for (User user : userResource.findCurrentlyEmployedUsers(ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT)) {
            log.debug("Checking achievement for user = " + user);
            List<Achievement> achievementList = Achievement.find("useruuid like ?1", user.getUuid()).list();


            testAchievement(user, achievementList, AchievementType.WORKWEEK40, isWortyOfWorkWeekAchievement(user, 40));
            testAchievement(user, achievementList, AchievementType.WORKWEEK50, isWortyOfWorkWeekAchievement(user, 50));
            testAchievement(user, achievementList, AchievementType.WORKWEEK60, isWortyOfWorkWeekAchievement(user, 60));

            testAchievement(user, achievementList, AchievementType.VACATION3, isWorthyOfVacationAchievement(user, 3));
            testAchievement(user, achievementList, AchievementType.VACATION4, isWorthyOfVacationAchievement(user, 4));
            testAchievement(user, achievementList, AchievementType.VACATION5, isWorthyOfVacationAchievement(user, 5));
/*
            testAchievement(user, achievementList, AchievementType.INTRALOGIN14, isWorthyOfIntraLoginAchievement(user, 14));
            testAchievement(user, achievementList, AchievementType.INTRALOGIN21, isWorthyOfIntraLoginAchievement(user, 21));
            testAchievement(user, achievementList, AchievementType.INTRALOGIN28, isWorthyOfIntraLoginAchievement(user, 28));
*/
            /*
            testAchievement(user, achievementList, AchievementType.SPEEDDATES10, isWorthyOfSpeedDateAchievement(user, 10));
            testAchievement(user, achievementList, AchievementType.SPEEDDATES20, isWorthyOfSpeedDateAchievement(user, 20));
            testAchievement(user, achievementList, AchievementType.SPEEDDATES30, isWorthyOfSpeedDateAchievement(user, 30));
            */

            testAchievement(user, achievementList, AchievementType.WEEKVACATION, isWorthyOfVacationAllWeeksAchievement(user));
            testAchievement(user, achievementList, AchievementType.MONTHVACATION, isWorthyOfVacationAllMonthsAchievement(user));

            testAchievement(user, achievementList, AchievementType.AMBITIONENTERED, isWorthyOfAmbitionCompleted(user));

            testAchievement(user, achievementList, AchievementType.CKOEXPENSE1, isWorthyOfCkoExpenseAchievement(user, 1));
            testAchievement(user, achievementList, AchievementType.CKOEXPENSE2, isWorthyOfCkoExpenseAchievement(user, 2));
            testAchievement(user, achievementList, AchievementType.CKOEXPENSE3, isWorthyOfCkoExpenseAchievement(user, 3));

            testAchievement(user, achievementList, AchievementType.ANNIVERSARY3, isWorthyOfAnniversary(user, 3));
            testAchievement(user, achievementList, AchievementType.ANNIVERSARY5, isWorthyOfAnniversary(user, 5));
            testAchievement(user, achievementList, AchievementType.ANNIVERSARY10, isWorthyOfAnniversary(user, 10));

            testAchievement(user, achievementList, AchievementType.BUDGETBEATER5, isWorthyOfBudgetBeatersAchievement(user, 5));
            testAchievement(user, achievementList, AchievementType.BUDGETBEATER15, isWorthyOfBudgetBeatersAchievement(user, 15));
            testAchievement(user, achievementList, AchievementType.BUDGETBEATER30, isWorthyOfBudgetBeatersAchievement(user, 30));

            testAchievement(user, achievementList, AchievementType.BUBBLES3, isWorthyOfBubbleMemberAchievement(user, 3));
            testAchievement(user, achievementList, AchievementType.BUBBLES6, isWorthyOfBubbleMemberAchievement(user, 6));
            testAchievement(user, achievementList, AchievementType.BUBBLES9, isWorthyOfBubbleMemberAchievement(user, 9));

            testAchievement(user, achievementList, AchievementType.BUBBLELEADER, isWorthyOfBubbleLeaderAchievement(user));
        }
    }

    private void testAchievement(User user, List<Achievement> achievementList, AchievementType achievementType, boolean worthyOfVacationAchievement) {
        if (achievementList.stream().noneMatch(achievement -> (achievement.getAchievement().equals(achievementType)))) {
            if (worthyOfVacationAchievement) {
                log.debug("user = " + user.getUsername() + ": achivement = " + achievementType);
                //notificationRepository.save(new Notification(user, LocalDate.now(), LocalDate.now().plusMonths(3), "New Achievement", "You've made it: "+achievementType.toString(), achievementType.getDescription(), achievementType.getNumber()+"", NotificationType.ACHIEVEMENT));
                persistenceManager.persistAchievement(new Achievement(user.getUuid(), LocalDate.now(), achievementType));
            }
        }
    }

    private boolean isWorthyOfCkoExpenseAchievement(User user, int years) {
        List<CKOExpense> ckoExpenseList = ckoExpenseService.findExpensesByUseruuid(user.getUuid());
        Map<String, Double> expensePerYearMap = new HashMap<>();
        for (CKOExpense ckoExpense : ckoExpenseList) {
            LocalDate localDate = ckoExpense.getEventdate();
            String key = localDate.getYear() + "";
            expensePerYearMap.putIfAbsent(key, 0.0);
            double expense = expensePerYearMap.get(key);
            expense += ckoExpense.getPrice();
            expensePerYearMap.replace(key, expense);
        }

        if(expensePerYearMap.size()==0) return false;

        int length = 0;
        boolean found = false;
        for (String key : expensePerYearMap.keySet().stream().sorted().collect(Collectors.toList())) {
            if(expensePerYearMap.get(key) >= 20400 && expensePerYearMap.get(key) <= 27600) {
                length++;
            } else {
                length = 0;
            }
            if(length >= years) found = true;
        }

        return found;
    }

    private boolean isWorthyOfBubbleMemberAchievement(User user, int minBubbles) {
        return (bubbleService.findActiveBubblesByUseruuid(user.getUuid()).size()>=minBubbles);
    }

    private boolean isWorthyOfBubbleLeaderAchievement(User user) {
        return bubbleService.findAll(user.getUuid()).size()>0;
    }

    private boolean isWorthyOfVacationAllMonthsAchievement(User user) {
        List<WorkFull> workList = workService.findVacationByUser(user.getUuid());
        int[] months = new int[12];
        for (WorkFull work : workList) {
            if(work.getWorkduration() >= 7.4) months[work.getRegistered().getMonthValue()-1] += 1;
        }
        for (int month : months) {
            if(month == 0) return false;
        }
        return true;
    }

    private boolean isWorthyOfBudgetBeatersAchievement(User user, int minMonths) {
        LocalDate employedDate = statusService.getLatestEmploymentStatus(user.getUuid()).getStatusdate();
        int count = 0;
        do {
            // TODO: Transaction timeout
            double budgetHoursByMonth = 0;//budgetService.getConsultantBudgetHoursByMonth(user.getUuid(), employedDate);
            double revenueHoursByMonth = 0;//revenueService.getRegisteredHoursForSingleMonthAndSingleConsultant(user.getUuid(), employedDate);
            if(revenueHoursByMonth>budgetHoursByMonth) count++;
            employedDate = employedDate.plusMonths(1);
        } while (employedDate.isBefore(LocalDate.now().withDayOfMonth(1)));
        return count >= minMonths;
    }

    private boolean isWorthyOfAnniversary(User user, int years) {
        UserStatus status = statusService.getLatestEmploymentStatus(user.getUuid());
        if(status==null) {
            log.warn("No latest status: "+user.getUsername());
            return false;
        }
        return status.getStatusdate().isBefore(LocalDate.now().minusYears(years));
    }

    private boolean isWorthyOfAmbitionCompleted(User user) {
        //int ambitionCount = ambitionRepository.findAmbitionByActiveIsTrue().size();
        //int userAmbitionCount = userAmbitionDTORepository.findUserAmbitionByUseruuidAndActiveTrue(user.getUuid()).size();
        //return userAmbitionCount>=ambitionCount;
        return false;
    }
/*
    private boolean isWorthyOfIntraLoginAchievement(User user, int daysInARow) {
        int consequtiveDays = 0;
        LocalDate foundDate = LocalDate.now().withYear(2014);
        for (LogEvent logEvent : logEventRepository.findByType(LogType.LOGIN)) {
            LocalDate localDate = Instant.ofEpochMilli(logEvent.getDateTime()).atZone(ZoneId.systemDefault()).toLocalDate();
            if(!localDate.minusDays(1).isEqual(foundDate)) {
                consequtiveDays = 0;
            } else {
                consequtiveDays++;
            }
            if(consequtiveDays>=daysInARow) return true;
            foundDate = localDate;
        }
        return false;
    }

 */

    private boolean isWorthyOfVacationAllWeeksAchievement(User user) {
        List<WorkFull> workList = workService.findVacationByUser(user.getUuid());
        int[] weeks = new int[53];
        for (WorkFull work : workList) {
            int week = work.getRegistered().get(ChronoField.ALIGNED_WEEK_OF_YEAR);
            if(work.getWorkduration() > 7.4) weeks[week-1] += 1;
        }
        for (int week : weeks) {
            if(week == 0) return false;
        }
        return true;
    }
/*
    private boolean isWorthyOfSpeedDateAchievement(User user, int minDates) {
        int count = 0;
        List<ReminderHistory> others = reminderHistoryRepository.findByTargetuuidAndType(user.getUuid(), ReminderType.SPEEDDATE);
        for (ReminderHistory reminderHistory : reminderHistoryRepository.findByTypeAndUseruuidOrderByTransmissionDateDesc(ReminderType.SPEEDDATE, user.getUuid())) {
            if(others.stream().anyMatch(reminderHistory1 -> reminderHistory1.getUser().getUuid().equals(reminderHistory.getTargetuuid()))) count++;
        }
        return count >= minDates;
    }

 */

    private boolean isWorthyOfVacationAchievement(User user, int minWork) {
        List<WorkFull> workList = workService.findVacationByUser(user.getUuid());
        Map<String, Double> hoursPerWeekMap = getHoursPerWeek(workList);
        if(hoursPerWeekMap.size()==0) return false;

        int length = 0;
        boolean found = false;
        for (String key : hoursPerWeekMap.keySet().stream().sorted().collect(Collectors.toList())) {
            if(hoursPerWeekMap.get(key) >= 37) {
                length++;
            } else {
                length = 0;
            }
            if(length>=minWork) found = true;
        }

        return found;
    }

    private boolean isWortyOfWorkWeekAchievement(User user, int minWork) {
        List<WorkFull> workList = workService.findBillableWorkByUser(user.getUuid());
        Map<String, Double> hoursPerWeekMap = getHoursPerWeek(workList);
        if(hoursPerWeekMap.size()==0) return false;

        Double aDouble = hoursPerWeekMap.values().stream().max(Double::compareTo).get();
        return aDouble >= minWork;
    }



    private Map<String, Double> getHoursPerWeek(List<WorkFull> workList) {
        Map<String, Double> hoursPerWeekMap = new HashMap<>();
        for (WorkFull work : workList) {
            String key = work.getRegistered().getYear() + "" + work.getRegistered().get(ChronoField.ALIGNED_WEEK_OF_YEAR);
            hoursPerWeekMap.putIfAbsent(key, 0.0);
            double hours = hoursPerWeekMap.get(key);
            hours += work.getWorkduration();
            hoursPerWeekMap.replace(key, hours);
        }
        return hoursPerWeekMap;
    }
}
