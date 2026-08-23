package dk.trustworks.intranet.hrletters.dto;

/**
 * Employee self-service vacation-transfer request. {@code days} must be a
 * positive half-day multiple, at most 10 (the 5th + 6th vacation week).
 * {@code fromYear} is the September start-year of the vacation year the
 * days are transferred from (null → the vacation year whose holding period
 * ends 31 December this calendar year). The target year is always the
 * following vacation year and is derived server-side.
 */
public record VacationTransferRequestDTO(
        double days,
        Integer fromYear) {
}
