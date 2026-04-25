package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateNote;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CandidateNoteRequest(@NotNull CandidateNote.Visibility visibility, @NotBlank String body) {}
