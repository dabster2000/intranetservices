package dk.trustworks.intranet.aggregates.crm.signal.services;

import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The hand-rolled normalisation in {@link AccountSignalService}.
 *
 * <p>Written as plain Java rather than as bean-validation annotations because bean
 * validation is NOT active in this service: {@code quarkus-hibernate-validator} is absent
 * from the build, so every {@code @NotBlank} in this codebase is inert decoration. These
 * are the checks that actually run, so these are the checks that are tested — in the
 * DB-free fast tier, with no Quarkus boot.
 *
 * <p>{@code create()} itself needs a database and is covered by the API-level tests; what
 * is locked here is the normalisation that decides what reaches the columns.
 */
class AccountSignalServiceTest {

    /** A capture is never refused over a classification it could not make. */
    @Test
    void unknownAbsentAndBlankTypesAllBecomeOther() {
        assertEquals(SignalType.OTHER, AccountSignalService.parseType(null));
        assertEquals(SignalType.OTHER, AccountSignalService.parseType(""));
        assertEquals(SignalType.OTHER, AccountSignalService.parseType("   "));
        assertEquals(SignalType.OTHER, AccountSignalService.parseType("NOT_A_TYPE"));
    }

    @Test
    void knownTypesParseCaseInsensitively() {
        assertEquals(SignalType.ORG_CHANGE, AccountSignalService.parseType("ORG_CHANGE"));
        assertEquals(SignalType.ORG_CHANGE, AccountSignalService.parseType("org_change"));
        assertEquals(SignalType.COMING_PROJECT, AccountSignalService.parseType(" coming_project "));
        assertEquals(SignalType.TENDER, AccountSignalService.parseType("Tender"));
    }

    /**
     * A blank extracted field must land as NULL, not as an empty string: the read surfaces
     * will branch on null, and "" would render an empty person row on the account plan.
     */
    @Test
    void blankExtractedFieldsBecomeNull() {
        assertNull(AccountSignalService.trimToNull(null, 255));
        assertNull(AccountSignalService.trimToNull("", 255));
        assertNull(AccountSignalService.trimToNull("   ", 255));
    }

    @Test
    void extractedFieldsAreTrimmed() {
        assertEquals("Benny Hoffmann", AccountSignalService.trimToNull("  Benny Hoffmann  ", 255));
    }

    /**
     * Truncation rather than rejection. The columns are VARCHAR(255)/(500); an
     * over-long extracted value is the model's fault, not the author's, and losing the
     * whole capture over it would be the wrong trade.
     */
    @Test
    void overLongExtractedFieldsAreTruncatedToTheColumnWidth() {
        assertEquals(AccountSignalService.MAX_PERSON_NAME_CHARS,
                AccountSignalService.trimToNull("x".repeat(400), AccountSignalService.MAX_PERSON_NAME_CHARS).length());
        assertEquals(AccountSignalService.MAX_RELATION_CHARS,
                AccountSignalService.trimToNull("y".repeat(900), AccountSignalService.MAX_RELATION_CHARS).length());
    }

    /** The service cap and the extractor cap must agree, or one silently truncates the other's work. */
    @Test
    void textCapMatchesTheExtractorCap() {
        assertEquals(dk.trustworks.intranet.aggregates.crm.signal.ai.AccountSignalPrompts.MAX_TEXT_CHARS,
                AccountSignalService.MAX_TEXT_CHARS,
                "the line handed to the model and the line stored must be capped identically");
    }
}
