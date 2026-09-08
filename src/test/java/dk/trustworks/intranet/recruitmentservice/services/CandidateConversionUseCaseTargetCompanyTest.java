package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.exceptions.ErrorResponse;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolationExceptionMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the target-company guard in
 * {@link CandidateConversionUseCase#requireTargetCompanyUuid(String, String)}.
 *
 * <p><b>The defect.</b> {@code POST /recruitment/candidates/{uuid}/convert}
 * returned HTTP 500 for every candidate whose {@code target_company_uuid} was
 * NULL. The use case called {@code Company.findById(candidate.getTargetCompanyUuid())}
 * and only checked the <em>result</em> for null — but Hibernate throws
 * {@code IllegalArgumentException("Identifier may not be null")} from inside
 * {@code findById(null)}, so the {@code company == null} branch was
 * unreachable and the exception escaped to {@code GenericExceptionMapper}
 * as an "Internal server error". {@code target_company_uuid} became nullable
 * in V435 (ATS talent pool); the conversion guard was written against V311's
 * NOT NULL column and never updated.</p>
 *
 * <p>The candidate uuid must appear in the message so an operator can tell
 * <em>which</em> candidate is missing a target company, and the failure must
 * surface as a 4xx: a candidate with no target company is not ready to be
 * hired, which is a conflict of state — the same shape as the ACTIVE-status
 * guard immediately above it — not a server fault.</p>
 */
class CandidateConversionUseCaseTargetCompanyTest {

    /** One of the two production reproducers (2026-09-07, 3 failed attempts). */
    private static final String CANDIDATE_UUID = "fbf7d96d-260a-4bff-8274-c50d899d2dde";
    private static final String COMPANY_UUID = "d8894494-2fa6-11e6-9e28-0800278d0be6";

    @Test
    void nullTargetCompany_throwsBusinessRuleViolationNamingTheCandidate() {
        BusinessRuleViolation violation = assertThrows(BusinessRuleViolation.class,
                () -> CandidateConversionUseCase.requireTargetCompanyUuid(CANDIDATE_UUID, null));

        assertTrue(violation.getMessage().contains(CANDIDATE_UUID),
                "message must name the candidate uuid, was: " + violation.getMessage());
        assertTrue(violation.getMessage().contains("target company"),
                "message must say what is missing, was: " + violation.getMessage());
    }

    /**
     * The regression assertion proper: the null path must NOT produce the
     * {@code IllegalArgumentException} that {@code Company.findById(null)}
     * raised, because that is what {@code GenericExceptionMapper} turned
     * into the 500.
     */
    @Test
    void nullTargetCompany_isNotAnIllegalArgumentException() {
        Throwable thrown = assertThrows(Throwable.class,
                () -> CandidateConversionUseCase.requireTargetCompanyUuid(CANDIDATE_UUID, null));

        assertFalse(thrown instanceof IllegalArgumentException,
                "IllegalArgumentException is the 500 path (GenericExceptionMapper); "
                        + "the guard must throw a mapped domain exception instead");
    }

    @Test
    void blankTargetCompany_throwsBusinessRuleViolation() {
        assertThrows(BusinessRuleViolation.class,
                () -> CandidateConversionUseCase.requireTargetCompanyUuid(CANDIDATE_UUID, "   "));
    }

    @Test
    void emptyTargetCompany_throwsBusinessRuleViolation() {
        assertThrows(BusinessRuleViolation.class,
                () -> CandidateConversionUseCase.requireTargetCompanyUuid(CANDIDATE_UUID, ""));
    }

    @Test
    void presentTargetCompany_isReturnedUnchanged() {
        assertSame(COMPANY_UUID,
                CandidateConversionUseCase.requireTargetCompanyUuid(CANDIDATE_UUID, COMPANY_UUID));
    }

    /**
     * Closes the loop from the guard to the wire: the exception the guard
     * throws is the one {@link BusinessRuleViolationExceptionMapper} renders,
     * so a candidate with no target company answers 409, not 500.
     */
    @Test
    void guardFailure_mapsToFourXxNotFiveHundred() {
        BusinessRuleViolation violation = assertThrows(BusinessRuleViolation.class,
                () -> CandidateConversionUseCase.requireTargetCompanyUuid(CANDIDATE_UUID, null));

        Response response = new BusinessRuleViolationExceptionMapper().toResponse(violation);

        assertEquals(409, response.getStatus());
        assertTrue(response.getStatus() >= 400 && response.getStatus() < 500,
                "conversion of a candidate with no target company must be a client error");

        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertEquals(409, body.status());
        assertTrue(body.error().contains(CANDIDATE_UUID),
                "the response body must name the candidate uuid, was: " + body.error());
    }
}
