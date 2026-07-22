package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Result envelope for the dedupe check. An empty {@code matches} list means
 * "no known duplicate" — the UI proceeds without a confirmation step.
 */
public record DedupeCheckResponse(List<DedupeMatch> matches) {
}
