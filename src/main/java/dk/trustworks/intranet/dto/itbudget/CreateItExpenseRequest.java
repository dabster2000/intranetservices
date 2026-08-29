package dk.trustworks.intranet.dto.itbudget;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;

/**
 * POST body for registering equipment. A record and not {@code ItExpenseItem}:
 * with the entity as the JAX-RS body a caller could send an {@code id} and
 * overwrite somebody else's row, or send a {@code status} and skip straight past
 * ACTIVE. {@code useruuid} comes from the path and {@code status} is set
 * server-side, so neither is reachable from the wire.
 */
@RegisterForReflection
public record CreateItExpenseRequest(
        Integer categoryId,
        String description,
        Integer price,
        @JsonDeserialize(using = LocalDateDeserializer.class) LocalDate invoicedate
) {}
