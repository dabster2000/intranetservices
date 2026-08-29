package dk.trustworks.intranet.dto.itbudget;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import dk.trustworks.intranet.model.enums.ItExpenseStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;

/**
 * PUT body. <b>Every field is optional and only non-null fields are written.</b>
 * The status-change flows send nothing but a status; the previous implementation
 * answered that with a bulk
 * {@code update("description = ?1, price = ?2, invoicedate = ?3, status = ?4 ...")},
 * which NULLed the other three columns on seven live rows.
 */
@RegisterForReflection
public record UpdateItExpenseRequest(
        Integer categoryId,
        String description,
        Integer price,
        @JsonDeserialize(using = LocalDateDeserializer.class) LocalDate invoicedate,
        ItExpenseStatus status
) {}
