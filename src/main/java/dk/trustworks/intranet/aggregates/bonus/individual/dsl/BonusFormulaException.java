package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import jakarta.ws.rs.BadRequestException;

/**
 * Raised when a bonus {@code formula} cannot be compiled or evaluated safely: a syntax error, a
 * reference to a disallowed variable/class/method, a timeout / runaway, or a {@code null} / non-numeric
 * result.
 * <p>
 * Extends {@link BadRequestException} so the projection / preview paths surface it as a clean HTTP
 * <b>400</b> via the shared {@code WebApplicationExceptionMapper} (a bad formula is client input, never a
 * server fault). It is still an unchecked {@link RuntimeException}, so the MATERIALISATION path catches it
 * explicitly and — money-safe — SKIPS that rule's payout with a prominent WARN rather than paying a
 * guessed amount (mirroring the CLAWBACK "flag for manual handling" pattern).
 */
public class BonusFormulaException extends BadRequestException {

    public BonusFormulaException(String message) {
        super(message);
    }

    public BonusFormulaException(String message, Throwable cause) {
        super(message, cause);
    }
}
