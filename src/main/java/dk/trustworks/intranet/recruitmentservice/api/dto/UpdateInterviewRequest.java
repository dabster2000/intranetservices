package dk.trustworks.intranet.recruitmentservice.api.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public record UpdateInterviewRequest(
    @Future LocalDateTime scheduledAt,
    @Min(15) @Max(240) Integer durationMinutes,
    String prepNotes,
    Boolean markHeld,
    String cancelReason
) {}
