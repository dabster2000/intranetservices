package dk.trustworks.intranet.vacationservice.model.enums;

/**
 * The two day pools per vacation year. FERIE is statutory (ferieloven:
 * 2.08 days/month, Dec 31 deadline, 5th-week March payout); FERIEFRIDAGE is
 * the contractual 6th week (0.42 days/month, carries over by agreement, no
 * statutory deadlines).
 */
public enum VacationPoolType {
    FERIE,
    FERIEFRIDAGE
}
