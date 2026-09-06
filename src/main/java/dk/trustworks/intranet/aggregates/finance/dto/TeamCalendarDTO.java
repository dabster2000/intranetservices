package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Calendar tab (JK Team 2.0 WP6 §4.6.5), both kinds of team: members down, months across,
 * overlapping bars per row. Spans are aggregated server-side — contracts and internal
 * assignments are spans already; ferie and orlov are contiguous runs of fact_user_day
 * leave hours, bridged across non-working days.
 */
public record TeamCalendarDTO(
        LocalDate from,
        LocalDate to,
        List<CalendarMember> members
) {

    public record CalendarMember(
            String userId,
            String firstname,
            String lastname,
            String consultantType,
            List<CalendarSpan> spans
    ) {}

    /**
     * @param kind      {@code CONTRACT} | {@code INTERNAL} | {@code VACATION} (ferie) | {@code LEAVE} (orlov)
     * @param from      first day, clipped to the window
     * @param to        last day (inclusive), clipped to the window
     * @param label     what the bar says
     * @param leaveType for {@code LEAVE}: {@code MATERNITY} | {@code NON_PAID} | {@code PAID}
     */
    public record CalendarSpan(
            String kind,
            LocalDate from,
            LocalDate to,
            String label,
            String clientName,
            String contractName,
            String accountManagerName,
            String pricingModelCode,
            Double rate,
            Double hoursPerWeek,
            Integer allocationPercent,
            boolean zeroRate,
            String sponsorName,
            boolean strategic,
            String leaveType
    ) {
        public static CalendarSpan contract(LocalDate from, LocalDate to, String clientName, String contractName,
                                            String accountManagerName, String pricingModelCode, double rate,
                                            double hoursPerWeek, Integer allocationPercent) {
            return new CalendarSpan("CONTRACT", from, to, clientName == null ? contractName : clientName,
                    clientName, contractName, accountManagerName, pricingModelCode, rate, hoursPerWeek,
                    allocationPercent, rate == 0.0, null, false, null);
        }

        public static CalendarSpan internal(LocalDate from, LocalDate to, String title, String sponsorName,
                                            double hoursPerWeek, boolean strategic) {
            return new CalendarSpan("INTERNAL", from, to, title, null, null, null, null, null, hoursPerWeek,
                    null, false, sponsorName, strategic, null);
        }

        public static CalendarSpan vacation(LocalDate from, LocalDate to) {
            return new CalendarSpan("VACATION", from, to, "Ferie", null, null, null, null, null, null,
                    null, false, null, false, null);
        }

        public static CalendarSpan leave(LocalDate from, LocalDate to, String leaveType) {
            return new CalendarSpan("LEAVE", from, to, "Orlov", null, null, null, null, null, null,
                    null, false, null, false, leaveType);
        }
    }
}
