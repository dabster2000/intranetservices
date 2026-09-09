package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.exceptions.ErrorResponse;
import dk.trustworks.intranet.exceptions.InconsistantDataException;
import dk.trustworks.intranet.exceptions.InconsistantDataExceptionMapper;
import dk.trustworks.intranet.userservice.model.enums.CareerLevel;
import dk.trustworks.intranet.userservice.model.enums.CareerTrack;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the career track/level consistency guard in
 * {@link CandidateConversionUseCase#requireConsistentCareerTrack(CareerTrack, CareerLevel)}.
 *
 * <p><b>The defect.</b> {@code POST /recruitment/candidates/{uuid}/convert}
 * persisted its {@code UserCareerLevel} row with a bare
 * {@code UserCareerLevel.persist(level)}, which made it the one writer in the
 * codebase that skipped the pairing check {@code CareerLevelService.create}
 * applies to every other career-level write. The result reached production:
 * {@code michael.strand} holds the single {@code ADVISORY /
 * THOUGHT_LEADER_PARTNER} row among all 334 career-level rows — a pairing the
 * enum itself declares impossible, since {@code THOUGHT_LEADER_PARTNER}
 * belongs to {@link CareerTrack#PARTNER}.</p>
 *
 * <p>Career level and track are not independent: every {@link CareerLevel}
 * except the entry level {@code JUNIOR_CONSULTANT} carries its owning track,
 * so a request that names both is only ever consistent or wrong.</p>
 *
 * <p>The guard is a pure function of two request fields, so it runs at the top
 * of {@code execute} beside the allocation range check — before the user, the
 * status rows and the Danløn proposal are written, like the username guard.
 * Those writes all roll back with the ambient transaction either way, so the
 * placement is about a coherent validation boundary rather than correctness.</p>
 */
class CandidateConversionUseCaseCareerLevelTest {

    @Test
    void consistentPair_passes() {
        assertDoesNotThrow(() -> CandidateConversionUseCase
                .requireConsistentCareerTrack(CareerTrack.DELIVERY, CareerLevel.CONSULTANT));
    }

    /** The production reproducer (michael.strand, converted 2026-09-01). */
    @Test
    void advisoryTrackWithPartnerLevel_throws() {
        assertThrows(InconsistantDataException.class, () -> CandidateConversionUseCase
                .requireConsistentCareerTrack(CareerTrack.ADVISORY, CareerLevel.THOUGHT_LEADER_PARTNER));
    }

    /**
     * The message must name the level, the track the operator asked for and
     * the track the level actually belongs to — otherwise the operator cannot
     * tell which of the two fields to correct.
     */
    @Test
    void mismatchMessage_namesLevelRequestedTrackAndExpectedTrack() {
        InconsistantDataException thrown = assertThrows(InconsistantDataException.class,
                () -> CandidateConversionUseCase.requireConsistentCareerTrack(
                        CareerTrack.ADVISORY, CareerLevel.THOUGHT_LEADER_PARTNER));

        assertTrue(thrown.getMessage().contains("THOUGHT_LEADER_PARTNER"),
                "message must name the level, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ADVISORY"),
                "message must name the requested track, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("PARTNER"),
                "message must name the expected track, was: " + thrown.getMessage());
    }

    /**
     * The whole point of the guard is that operators see the SAME error from
     * the conversion flow as from {@code POST /users/{uuid}/careerlevels}; the
     * message shape is copied verbatim from {@code CareerLevelService.create}.
     */
    @Test
    void mismatchMessage_matchesCareerLevelServiceWording() {
        InconsistantDataException thrown = assertThrows(InconsistantDataException.class,
                () -> CandidateConversionUseCase.requireConsistentCareerTrack(
                        CareerTrack.ADVISORY, CareerLevel.THOUGHT_LEADER_PARTNER));

        assertEquals("Career level THOUGHT_LEADER_PARTNER does not belong to track ADVISORY "
                + "(expected PARTNER)", thrown.getMessage());
    }

    /**
     * {@code JUNIOR_CONSULTANT} is the entry level and declares no track
     * ({@code CareerLevel.JUNIOR_CONSULTANT.getTrack() == null}), so it fits
     * whichever track the hire is placed on and must never be rejected.
     */
    @ParameterizedTest
    @EnumSource(CareerTrack.class)
    void nullTrackLevel_passesOnEveryTrack(CareerTrack track) {
        assertDoesNotThrow(() -> CandidateConversionUseCase
                .requireConsistentCareerTrack(track, CareerLevel.JUNIOR_CONSULTANT));
    }

    /**
     * Every level paired with its own declared track is valid by construction —
     * the guard must not reject any legitimate hire.
     */
    @ParameterizedTest
    @EnumSource(CareerLevel.class)
    void everyLevelPairedWithItsOwnTrack_passes(CareerLevel level) {
        assertDoesNotThrow(() -> CandidateConversionUseCase
                .requireConsistentCareerTrack(level.getTrack(), level));
    }

    /**
     * A null level is left to {@code @NotNull} on
     * {@link dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest#careerLevel()};
     * the guard must not turn it into a NullPointerException (a 500) for the
     * direct in-process callers that bypass Bean Validation.
     */
    @Test
    void nullLevel_passesToTheBeanValidationLayer() {
        assertDoesNotThrow(() -> CandidateConversionUseCase
                .requireConsistentCareerTrack(CareerTrack.DELIVERY, null));
        assertDoesNotThrow(() -> CandidateConversionUseCase
                .requireConsistentCareerTrack(null, null));
    }

    /**
     * Closes the loop from the guard to the wire: an inconsistent pair is bad
     * input, so it answers 400 with the message preserved — not a 500, and not
     * a silently accepted impossible row.
     */
    @Test
    void guardFailure_mapsToFourHundredWithTheMessagePreserved() {
        InconsistantDataException thrown = assertThrows(InconsistantDataException.class,
                () -> CandidateConversionUseCase.requireConsistentCareerTrack(
                        CareerTrack.ADVISORY, CareerLevel.THOUGHT_LEADER_PARTNER));

        Response response = new InconsistantDataExceptionMapper().toResponse(thrown);

        assertEquals(400, response.getStatus());
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertEquals(400, body.status());
        assertTrue(body.error().contains("THOUGHT_LEADER_PARTNER"),
                "the response body must name the offending level, was: " + body.error());
    }
}
