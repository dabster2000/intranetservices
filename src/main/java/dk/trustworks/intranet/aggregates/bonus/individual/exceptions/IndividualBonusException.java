package dk.trustworks.intranet.aggregates.bonus.individual.exceptions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Safe, stable domain failure for Individual Bonus API operations. */
public class IndividualBonusException extends RuntimeException {

    private final int status;
    private final String code;
    private final String field;
    private final LocalDate earningMonth;
    private final LocalDate payMonth;
    private final Boolean manualAction;
    private final BigDecimal expected;
    private final BigDecimal actual;
    private final List<String> violations;

    public IndividualBonusException(int status, String code, String message) {
        this(status, code, message, null, null, null, null, null, null, List.of());
    }

    public IndividualBonusException(int status, String code, String message, String field) {
        this(status, code, message, field, null, null, null, null, null, List.of());
    }

    public IndividualBonusException(int status, String code, String message, String field,
                                    LocalDate earningMonth, LocalDate payMonth, Boolean manualAction,
                                    BigDecimal expected, BigDecimal actual, List<String> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.earningMonth = earningMonth;
        this.payMonth = payMonth;
        this.manualAction = manualAction;
        this.expected = expected;
        this.actual = actual;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public int status() { return status; }
    public String code() { return code; }
    public String field() { return field; }
    public LocalDate earningMonth() { return earningMonth; }
    public LocalDate payMonth() { return payMonth; }
    public Boolean manualAction() { return manualAction; }
    public BigDecimal expected() { return expected; }
    public BigDecimal actual() { return actual; }
    public List<String> violations() { return violations; }
}
