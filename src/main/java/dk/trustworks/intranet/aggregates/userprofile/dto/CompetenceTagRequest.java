package dk.trustworks.intranet.aggregates.userprofile.dto;

/** Body of {@code POST /users/{useruuid}/competence-tags} — one MANUAL tag. */
public record CompetenceTagRequest(String tag) {
}
