package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;

/**
 * DTO for the accounting ledger grid. Contains all fields needed by the frontend
 * including resolved account manager info and upload error status.
 */
@RegisterForReflection
public record InvoiceLedgerDTO(
        String uuid,
        int invoicenumber,
        String type,
        String accountManagerUuid,
        String accountManagerName,
        String companyUuid,
        String companyName,
        String clientname,
        String projectname,
        @JsonSerialize(using = LocalDateSerializer.class)
        @JsonDeserialize(using = LocalDateDeserializer.class)
        LocalDate invoicedate,
        @JsonSerialize(using = LocalDateSerializer.class)
        @JsonDeserialize(using = LocalDateDeserializer.class)
        LocalDate duedate,
        String currency,
        double sumNoTax,
        double sumWithTax,
        String status,
        String economicsStatus,
        boolean hasUploadError,
        String lastUploadError
) {}
