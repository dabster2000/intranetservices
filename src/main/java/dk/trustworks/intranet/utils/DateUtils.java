package dk.trustworks.intranet.utils;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MONTHS;

public final class DateUtils {

    private DateUtils() {
    }

    /**
     * Counts vacation days in period, weekends not included.
     *
     * @param dateFrom inclusive
     * @param dateTo exclusive
     * @return number of vacation days
     */
    public static int getVacationDaysInPeriod(LocalDate dateFrom, LocalDate dateTo) {

        List<LocalDate> vacationDaysYear = getVacationDayArray(dateFrom.getYear());
        if(dateFrom.getYear() != dateTo.getYear()) vacationDaysYear.addAll(getVacationDayArray(dateTo.getYear()));

        int countVacationDays = 0;
        for (LocalDate localDate : vacationDaysYear) {
            if (isWeekendDay(localDate)) continue;
            if(localDate.isAfter(dateFrom) && localDate.isBefore(dateTo)) countVacationDays++;
            if(localDate.isEqual(dateFrom)) countVacationDays++;
        }

        return countVacationDays;
    }

    /**
     * Both date inclusive
     * @param testDate
     * @param from
     * @param to
     * @return
     */
    public static boolean isBetweenBothIncluded(LocalDate testDate, LocalDate from, LocalDate to) {
        if(testDate.isEqual(from)) return true;
        if(testDate.isEqual(to)) return true;
        return testDate.isAfter(from) && testDate.isBefore(to);
    }

    /**
     * First date inclusive
     * @param testDate
     * @param from
     * @param to
     * @return
     */
    public static boolean isBetween(LocalDate testDate, LocalDate from, LocalDate to) {
        if(testDate.isEqual(from)) return true;
        return testDate.isAfter(from) && testDate.isBefore(to);
    }

    /**
     * Counts vacation days in period, weekends not included.
     *
     * @param dateFrom inclusive
     * @param dateTo exclusive
     * @return number of vacation days
     */
    public static int getWeekdaysInPeriod(LocalDate dateFrom, LocalDate dateTo) {
        int weekDays = 0;

        LocalDate localDate = dateFrom;
        do {
            if(!isWeekendDay(localDate)) weekDays++;
            localDate = localDate.plusDays(1);
        } while (localDate.isBefore(dateTo));
        return weekDays - getVacationDaysInPeriod(dateFrom, dateTo);
    }

    public static int getWeeksInMonth(LocalDate date) {
        return (int) Math.ceil((double) date.lengthOfMonth() / 7.0);
    }

    public static boolean isWeekendDay(LocalDate localDate) {
        return localDate.getDayOfWeek().equals(DayOfWeek.SATURDAY) || localDate.getDayOfWeek().equals(DayOfWeek.SUNDAY);
    }

    public static boolean isFriday(LocalDate localDate) {
        return localDate.getDayOfWeek().equals(DayOfWeek.FRIDAY);
    }


    public static LocalDate getNextBirthday(LocalDate birthDate) {
        // Calculate the next occurrence of the birthday
        LocalDate today = LocalDate.now();
        LocalDate birthdayThisYear = birthDate.withYear(today.getYear());

        if (birthdayThisYear.isBefore(today) || birthdayThisYear.isEqual(today)) {
            // Birthday has already occurred this year, so take next year's date
            birthdayThisYear = birthdayThisYear.plusYears(1);
        }
        return birthdayThisYear;
    }

    /**
     * @param dateFrom inclusive
     * @param dateTo exclusive
     * @return number of weekday occurances days
     */
    public static int countWeekdayOccurances(DayOfWeek dayOfWeek, LocalDate dateFrom, LocalDate dateTo) {
        int weekDayOccurances = 0;

        LocalDate localDate = dateFrom;
        do {
            if(localDate.getDayOfWeek().getValue()==dayOfWeek.getValue()) weekDayOccurances++;
            localDate = localDate.plusDays(1);
        } while (localDate.isBefore(dateTo));
        return weekDayOccurances;
    }

    public static boolean isWorkday(LocalDate localDate) {
        if(isWeekendDay(localDate)) return false;
        for (LocalDate vacationDate : getVacationDayArray(localDate.getYear())) {
            if(localDate.isEqual(vacationDate)) return false;
        }
        return true;
    }

    public static List<LocalDate> getVacationDayArray(int year) {
        int a = year % 19;
        int b = (int) Math.round(year/100.0);
        int c = year % 100;
        int d = (int) Math.round(b/4.0);
        int e = b % 4;
        int f = (int) Math.floor((b+8.0)/25.0);
        int g = (int) Math.floor((b-f+1.0)/3.0);
        int h = (19*a+b-d-g+15) % 30;
        int j = (int) Math.floor(c/4.0);
        int k = c % 4;
        int l = (32+2*e+2*j-h-k) % 7;
        int m = (int) Math.floor((a+11.0*h+22.0*l)/451.0);
        int n = (int) Math.floor((h+l-7.0*m+114.0)/31.0);
        int p = (h+l-7*m+114) % 31;
        int q = (n-3)*31+p-20;

        int day = p+1;

        LocalDate newYearsDay = LocalDate.of(year, 1, 1);
        LocalDate easterDay = LocalDate.of(year, n, day);
        LocalDate easterFriday = easterDay.minusDays(2);
        LocalDate easterThursday = easterDay.minusDays(3);
        LocalDate secondEasterday = easterDay.plusDays(1);
        LocalDate prayerday = easterDay.plusDays(26);
        LocalDate assendenceDay = easterDay.plusDays(39);
        LocalDate whitSun = easterDay.plusDays(49);
        LocalDate whitMon = easterDay.plusDays(50);
        LocalDate grundlovsDay = LocalDate.of(year, 6, 5);
        LocalDate christmasEve = LocalDate.of(year, 12, 24);
        LocalDate christmasDay = christmasEve.plusDays(1);
        LocalDate secondChristmasDay = christmasEve.plusDays(2);
        LocalDate newYearsEve = LocalDate.of(year, 12, 31);

        ArrayList<LocalDate> vacationDayList = new ArrayList<>();
        vacationDayList.add(newYearsDay);
        vacationDayList.add(easterThursday);
        vacationDayList.add(easterFriday);
        vacationDayList.add(easterDay);
        vacationDayList.add(secondEasterday);
        if(year < 2024) vacationDayList.add(prayerday);
        vacationDayList.add(assendenceDay);
        vacationDayList.add(whitSun);
        vacationDayList.add(whitMon);
        //vacationDayList.add(grundlovsDay);
        vacationDayList.add(christmasEve);
        vacationDayList.add(christmasDay);
        vacationDayList.add(secondChristmasDay);
        vacationDayList.add(newYearsEve);

        return vacationDayList;
    }

    public static Date convertLocalDateToDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    public static LocalDate lastDayOfMonth(LocalDate localDate) {
        return localDate.withDayOfMonth(localDate.lengthOfMonth());
    }

    public static LocalDate getFirstDayOfMonth(LocalDate localDate) {
        YearMonth month = YearMonth.from(localDate);
        return month.atDay(1);
    }

    public static LocalDate getFirstDayOfMonth(int year, int month) {
        return getFirstDayOfMonth(LocalDate.of(year, month, 1));
    }

    public static LocalDate getLastDayOfMonth(int year, int month) {
        return getLastDayOfMonth(LocalDate.of(year, month, 1));
    }

    public static LocalDate getLastDayOfMonth(LocalDate localDate) {
        YearMonth month = YearMonth.from(localDate);
        return month.atEndOfMonth();
    }

    public static LocalDate getFirstWeekdayOfMonth(LocalDate date) {
        LocalDate firstDayOfMonth = getFirstDayOfMonth(date);
        LocalDate currentDate = firstDayOfMonth;

        while (!isWorkday(currentDate)) {
            currentDate = currentDate.plusDays(1);
        }

        return currentDate;
    }

    /**
     * Fra 1. jan til 5. jan giver 4 dage.
     *
     * @param dateBefore inclusive
     * @param dateAfter exclusive
     * @return
     */
    public static int countDaysBetween(LocalDate dateBefore, LocalDate dateAfter) {
        return Math.toIntExact(DAYS.between(dateBefore, dateAfter));
    }

    /**
     * Fra 1. jan til 5. may giver 4 måneder.
     *
     * @param dateBefore inclusive
     * @param dateAfter exclusive
     * @return
     */
    public static int countMonthsBetween(LocalDate dateBefore, LocalDate dateAfter) {
        return Math.toIntExact(MONTHS.between(dateBefore, dateAfter));
    }

    public static String[] getMonthNames(LocalDate localDateStart, LocalDate localDateEnd) {
        int monthPeriod = (int) ChronoUnit.MONTHS.between(localDateStart, localDateEnd)+1;
        String[] monthNames = new String[monthPeriod];
        for (int i = 0; i < monthNames.length; i++) {
            monthNames[i] = localDateStart.plusMonths(i).getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        }
        return monthNames;
    }

    public static String stringIt(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public static String stringIt(LocalDate date, String format) {
        return date.format(DateTimeFormatter.ofPattern(format));
    }

    public static LocalDate dateIt(String date) {
        if(date==null || date.isBlank()) return null;
        return LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public static LocalDate getCurrentFiscalStartDate() {
        return (LocalDate.now().getMonthValue()>6 && LocalDate.now().getMonthValue()<13)?LocalDate.now().withMonth(7).withDayOfMonth(1):LocalDate.now().minusYears(1).withMonth(7).withDayOfMonth(1);
    }

    public static LocalDate getFiscalStartDateBasedOnDate(LocalDate date) {
        return (date.getMonthValue()>6 && date.getMonthValue()<13)?date.withMonth(7).withDayOfMonth(1):date.minusYears(1).withMonth(7).withDayOfMonth(1);
    }

    public static LocalDate getTwentieth(LocalDate date) {
        // Tjekker om den indsendte dato er den 20. eller efter i måneden
        if (date.getDayOfMonth() >= 20) {
            // Hvis det er den 20. eller senere, returner den 20. i samme måned
            return LocalDate.of(date.getYear(), date.getMonth(), 20);
        } else {
            // Hvis det er før den 20., returner den 20. i forrige måned
            return date.minusMonths(1).withDayOfMonth(20);
        }
    }

    public static LocalDate getCompanyStartDate() {
        return LocalDate.of(2014, 7, 1);
    }

    public static LocalDate ConvertInstantToLocalDate(Instant instant) {
        ZoneId zoneId = ZoneId.of("Europe/Copenhagen");
        return LocalDate.ofInstant(instant, zoneId);
    }

    public static boolean isFirstThursdayOrFridayInOctober(LocalDate date) {
        if (date.getMonth() != Month.OCTOBER) {
            return false;
        }

        LocalDate firstOfMonth = date.withDayOfMonth(1);
        LocalDate firstThursday = firstOfMonth.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        LocalDate firstFriday = firstOfMonth.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));

        return date.equals(firstThursday) || date.equals(firstFriday);
    }
}
