package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record MarkInternalInvoiceSkipRequest(String note) {}
