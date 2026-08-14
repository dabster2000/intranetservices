package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;

/**
 * The body of {@code PUT /admin/requirements/{uuid}/{kind}/draft} (spec §6.3).
 *
 * <h2>Why {@code payload} is a {@link JsonNode} and not a String</h2>
 *
 * <p>The editor posts a JSON object. Binding the field as a {@code String} would force the
 * client to JSON-encode the payload into a string first — the double-stringify pattern that
 * reads fine in a unit test and then produces escaped-quote soup in production, because
 * exactly one of the two encodes is easy to forget and neither end can tell which side dropped
 * it. A {@code JsonNode} accepts what the editor naturally sends.
 *
 * <p>The trade the task names — "a String keeps the codec the single parse point" — is kept
 * anyway, because nothing here inspects the node. It is re-serialised by {@link #payloadJson()}
 * and handed to {@code CompetenceContentService.upsertDraft}, which parses and validates it
 * through {@link CompetencePayloadCodec}: the same single, strict, unknown-property-rejecting
 * parse that an imported file goes through. This record never reads a field, never branches on
 * a type and never decides whether something is a course or a test. Jackson's generic tree
 * parse is not a second contract; the codec's typed parse is still the only place a payload
 * acquires meaning.
 *
 * <p>{@code contentKind} is a path segment rather than a body field, so a request cannot claim
 * to be a course while sitting at the test URL.
 *
 * @param versionLabel the label this draft will publish under, ≤ 32 chars and non-blank
 *                     (§11.1, §11.2). It is what the status rules compare, so publishing a
 *                     version whose label equals the live one leaves everyone green — see
 *                     {@link PublishPreviewDTO#sameLabel()}.
 * @param payload      the raw authored payload, shaped by {@code kind}
 */
public record DraftUpsertRequest(String versionLabel, JsonNode payload) {

    /**
     * The payload as canonical JSON for the service, or {@code null} when the body carried
     * none — which the codec answers with a {@code 400} naming the problem, a better message
     * than anything this record could compose.
     */
    public String payloadJson() {
        return payload == null ? null : CompetencePayloadCodec.write(payload);
    }
}
