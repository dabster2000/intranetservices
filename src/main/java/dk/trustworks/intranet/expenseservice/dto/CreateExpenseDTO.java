package dk.trustworks.intranet.expenseservice.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.expenseservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateSerializer;
import lombok.Data;

import java.time.LocalDate;

/**
 * Wire DTO for {@code POST /expenses}.
 *
 * <p>Contains ONLY the client-writable fields from the {@code Expense} entity. All
 * server-managed fields (uuid, status, state, AI verdicts, voucher refs, etc.) are
 * structurally absent so they cannot be injected via the request body.
 *
 * <p>JSON property names are identical to those on the entity so the wire contract
 * (including the external mobile app) is byte-for-byte unchanged.
 */
@Data
public class CreateExpenseDTO {

    private String useruuid;

    private Double amount;

    private String account;

    private String accountname;

    private String description;

    private String accountantNotes;

    private String projectuuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate datecreated;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate expensedate;

    private boolean customerexpense;

    /**
     * Base64-encoded receipt file. Input-only — never returned in responses.
     * Maps to the transient {@code expensefile} field on the entity.
     */
    private String expensefile;

    /**
     * Classification decision from the frontend wizard.
     * Input-only — maps to the transient {@code classification} field on the entity.
     */
    private ExpenseClassificationDTOs.Submission classification;
}
