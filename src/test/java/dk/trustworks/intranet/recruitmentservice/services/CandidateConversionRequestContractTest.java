package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link ConvertRequest} and the request-derived defaults in
 * {@link CandidateConversionUseCase}.
 *
 * <p><b>The defect.</b> The conversion hardcoded
 * {@code salary.setType(SalaryType.NORMAL)} and {@code salaryType} was absent
 * from the request, so an operator could not express an hourly hire at all.
 * {@code thomas.rask} was therefore booked as {@code 195 NORMAL} while every
 * other student in production is {@code HOURLY} — the flow silently produced
 * the wrong salary type rather than refusing the input.</p>
 *
 * <p>The parameter is optional on purpose: callers written against the
 * pre-{@code salaryType} contract (and the in-flight frontend) must keep
 * working, so absence has to resolve to {@code NORMAL} — the monthly salary
 * the vast majority of hires get.</p>
 *
 * <p>The remaining cases pin the rest of the input boundary: the column widths
 * the DTO has to respect, the {@code username} shape Azure requires, and the
 * {@code user.phone} width guard on the value copied off the candidate.</p>
 */
class CandidateConversionRequestContractTest {

    @Test
    void absentSalaryType_defaultsToNormal() {
        assertEquals(SalaryType.NORMAL, CandidateConversionUseCase.resolveSalaryType(null));
    }

    /** The student case that the hardcoded NORMAL made unreachable. */
    @Test
    void hourlySalaryType_isHonoured() {
        assertEquals(SalaryType.HOURLY, CandidateConversionUseCase.resolveSalaryType(SalaryType.HOURLY));
    }

    @ParameterizedTest
    @EnumSource(SalaryType.class)
    void everyRequestedSalaryType_isPassedThroughUnchanged(SalaryType requested) {
        assertEquals(requested, CandidateConversionUseCase.resolveSalaryType(requested));
    }

    /**
     * The source-compatible 10-argument constructor exists for callers written
     * before {@code salaryType}; it must leave the component null so the use
     * case applies the NORMAL default rather than any other value.
     */
    @Test
    void legacyArityConstructor_leavesSalaryTypeNullSoTheDefaultApplies() {
        ConvertRequest legacy = new ConvertRequest(
                "test.user",
                "test.user@trustworks.dk",
                ConsultantType.CONSULTANT,
                CareerTrack.DELIVERY,
                CareerLevel.CONSULTANT,
                "d8894494-2fa6-11e6-9e28-0800278d0be6",
                TeamMemberType.MEMBER,
                LocalDate.of(2026, 10, 1),
                45_000,
                37);

        assertNull(legacy.salaryType(), "the legacy arity must not invent a salary type");
        assertEquals(SalaryType.NORMAL, CandidateConversionUseCase.resolveSalaryType(legacy.salaryType()));
    }

    @Test
    void salaryTypeIsCarriedByTheCanonicalConstructor() {
        ConvertRequest hourly = new ConvertRequest(
                "student.hire",
                "student.hire@trustworks.dk",
                ConsultantType.STUDENT,
                CareerTrack.DELIVERY,
                CareerLevel.JUNIOR_CONSULTANT,
                "d8894494-2fa6-11e6-9e28-0800278d0be6",
                TeamMemberType.MEMBER,
                LocalDate.of(2026, 10, 1),
                195,
                20,
                SalaryType.HOURLY);

        assertEquals(SalaryType.HOURLY, hourly.salaryType());
        assertEquals(SalaryType.HOURLY, CandidateConversionUseCase.resolveSalaryType(hourly.salaryType()));
    }

    /**
     * {@code user.username} is {@code varchar(50)}. The bound used to be 100,
     * which let an over-long handle through validation and into the database as
     * a truncation/constraint error instead of a clean 400 naming the field.
     */
    @Test
    void usernameBound_matchesTheVarchar50Column() throws NoSuchFieldException {
        Size size = ConvertRequest.class.getDeclaredField("username").getAnnotation(Size.class);

        assertNotNull(size, "username must keep its @Size bound");
        assertEquals(50, size.max(), "username must not accept more than the column holds");
    }

    /**
     * {@code user.email} is {@code varchar(150)}; the DTO used to allow 255,
     * the same over-wide bound the username carried.
     */
    @Test
    void emailBound_matchesTheVarchar150Column() throws NoSuchFieldException {
        Size size = ConvertRequest.class.getDeclaredField("email").getAnnotation(Size.class);

        assertNotNull(size, "email must keep its @Size bound");
        assertEquals(150, size.max(), "email must not accept more than the column holds");
    }

    // ------------------------------------------------------------------
    // username shape — the @Pattern constraint
    //
    // Azure resolves the login name verbatim, so a handle carrying a space or
    // an uppercase letter provisions an account nobody can sign in to. The
    // hire dialog enforces ^[a-z0-9._@-]+$, but POST
    // /recruitment/candidates/{uuid}/convert is reachable by any
    // recruitment:write client-credentials holder, so the rule has to hold at
    // the API boundary too.
    //
    // The assertions call Matcher.matches(), which is exactly what Hibernate
    // Validator's PatternValidator does with the declared regexp.
    // ------------------------------------------------------------------

    private static java.util.regex.Pattern usernameShape() throws NoSuchFieldException {
        Pattern pattern = ConvertRequest.class.getDeclaredField("username").getAnnotation(Pattern.class);

        assertNotNull(pattern, "username must carry a @Pattern shape constraint");
        return java.util.regex.Pattern.compile(pattern.regexp());
    }

    /** The shape every Trustworks handle actually has. */
    @ParameterizedTest
    @ValueSource(strings = {
            "martin.nohr",
            "thomas.rask",
            "michael.strand",
            "anna",
            "user2",
            "a.b.c"
    })
    void usernameShape_acceptsLowercaseFirstnameDotLastname(String username) throws NoSuchFieldException {
        assertTrue(usernameShape().matcher(username).matches(),
                "must accept a legitimate handle: '" + username + "'");
    }

    /**
     * The reproducer: {@code "  Martin Nohr "} was a valid request body before
     * the constraint existed, and the duplicate guard could not catch its
     * leading-space variants either — MariaDB's PAD SPACE collation ignores
     * trailing but not leading blanks, so {@code " martin.nohr"} compares as a
     * free username while Azure sees an unresolvable principal.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "  Martin Nohr ",
            " martin.nohr",
            "martin.nohr ",
            "  martin.nohr  ",
            "martin nohr"
    })
    void usernameShape_rejectsWhitespaceInAnyPosition(String username) throws NoSuchFieldException {
        assertFalse(usernameShape().matcher(username).matches(),
                "must reject a handle carrying whitespace: '" + username + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Martin.Nohr", "MARTIN.NOHR", "martin.Nohr"})
    void usernameShape_rejectsUppercase(String username) throws NoSuchFieldException {
        assertFalse(usernameShape().matcher(username).matches(),
                "Azure matches the handle verbatim, so casing is not free: '" + username + "'");
    }

    /**
     * What actually breaks an Azure login is whitespace, uppercase or a
     * non-ASCII character — not punctuation. The class is therefore wider than
     * the {@code firstname.lastname} convention: production carries one
     * EXTERNAL consultant whose username is a full email address
     * ({@code mathias.pedersen@external.dk}), and EXTERNAL is a selectable
     * consultant type on this endpoint, so rejecting {@code @} would refuse a
     * legitimate hire. The hyphen rides along for the same reason, even though
     * zero production usernames contain one today.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "martin_nohr",
            "martin-nohr",
            "anne-marie.hansen",
            "mathias.pedersen@external.dk"
    })
    void usernameShape_acceptsPunctuationThatDoesNotBreakAzure(String username) throws NoSuchFieldException {
        assertTrue(usernameShape().matcher(username).matches(),
                "must accept a handle a real Trustworks account could carry: '" + username + "'");
    }

    /**
     * Everything still outside the class is out — anything that would make the
     * stored handle differ from the principal Azure presents.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "martin.nohr!",
            "mårten.nohr",
            "martin.nøhr",
            "martin/nohr",
            "martin#nohr"
    })
    void usernameShape_rejectsAnythingOutsideTheAllowedAlphabet(String username) throws NoSuchFieldException {
        assertFalse(usernameShape().matcher(username).matches(),
                "must reject a handle outside the allowed alphabet: '" + username + "'");
    }

    /**
     * The classic {@code $} trap: {@code $} also matches before a final line
     * terminator, so {@code find()} would let a trailing newline through.
     * Bean Validation uses {@code matches()}, which requires the whole input to
     * be consumed — this pins that the constraint really is anchored.
     */
    @Test
    void usernameShape_rejectsATrailingNewline() throws NoSuchFieldException {
        assertFalse(usernameShape().matcher("martin.nohr\n").matches(),
                "the regexp must be fully anchored, not merely prefix-anchored");
    }

    /** {@code +} means at least one character; blankness is @NotBlank's job too. */
    @Test
    void usernameShape_rejectsTheEmptyString() throws NoSuchFieldException {
        assertFalse(usernameShape().matcher("").matches(), "an empty handle is not a handle");
    }

    // ------------------------------------------------------------------
    // phone copy — the user.phone width guard
    //
    // recruitment_candidates.phone is varchar(50) but user.phone is
    // varchar(20). Nothing in production exceeds 20 today (longest is 15), so
    // the mismatch is latent — but an over-long value would surface only at
    // flush, as a truncation SQLException that rolls the whole conversion back
    // and answers 500. The phone is a convenience; the hire is what matters.
    // ------------------------------------------------------------------

    private static String phoneOfLength(int length) {
        return "+".concat("4".repeat(length - 1));
    }

    @Test
    void phoneAtTheColumnWidth_isCopied() {
        String twenty = phoneOfLength(20);

        assertEquals(20, twenty.length(), "fixture must sit exactly on the boundary");
        assertEquals(twenty, CandidateConversionUseCase.resolveUserPhone("candidate-uuid", twenty));
    }

    @Test
    void phoneOneCharacterOverTheColumnWidth_isNotCopied() {
        String twentyOne = phoneOfLength(21);

        assertEquals(21, twentyOne.length(), "fixture must sit one past the boundary");
        assertNull(CandidateConversionUseCase.resolveUserPhone("candidate-uuid", twentyOne),
                "an over-long phone must be dropped, not sent to the database to fail the conversion");
    }

    @Test
    void guardWidth_tracksTheVarchar20Column() {
        assertEquals(20, CandidateConversionUseCase.MAX_USER_PHONE_LENGTH,
                "the guard must stay pinned to the width of user.phone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+4512345678", "12345678", "+45 12 34 56 78"})
    void everydayPhoneNumbers_areCopiedUnchanged(String phone) {
        assertEquals(phone, CandidateConversionUseCase.resolveUserPhone("candidate-uuid", phone));
    }

    /** Absent or blank is nothing to copy — and must not write "" over the column. */
    @Test
    void absentOrBlankPhone_isNotCopied() {
        assertNull(CandidateConversionUseCase.resolveUserPhone("candidate-uuid", null));
        assertNull(CandidateConversionUseCase.resolveUserPhone("candidate-uuid", ""));
        assertNull(CandidateConversionUseCase.resolveUserPhone("candidate-uuid", "   "));
    }
}
