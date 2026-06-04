package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Upsert for one phantom label -> client mapping. {@code excluded=true} marks
 * the label a known non-client (e.g. canteen); {@code clientUuid} resolves it.
 * {@code confirmed_by} is taken from the X-Requested-By header, never the body.
 */
@RegisterForReflection
public record PhantomClientMapRequest(
        String clientname,
        String clientUuid,
        Boolean excluded,
        String note
) {}
