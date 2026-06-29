package dk.trustworks.intranet.apigateway.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for the "Your Part of Trustworks" salary refresh endpoints.
 *
 * <p>Drives recalculation of per-day salary in {@code fact_user_day} (which feeds the
 * {@code fact_tw_bonus_monthly} view consumed by the {@code /basis} endpoint) for the
 * given users over an inclusive date range.</p>
 */
@Data
@NoArgsConstructor
public class SalaryRecalcRequest {

    /**
     * Users whose salary should be recalculated. Required for the bulk endpoint; ignored by
     * the single-user endpoint, which takes the user from the path parameter.
     */
    private List<String> userUuids;

    /** Inclusive start of the window, ISO {@code yyyy-MM-dd} (e.g. fiscal year start {@code <year>-07-01}). */
    @NotNull
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be an ISO date (yyyy-MM-dd)")
    private String startDate;

    /** Inclusive end of the window, ISO {@code yyyy-MM-dd} (e.g. fiscal year end {@code <year+1>-06-30}). */
    @NotNull
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be an ISO date (yyyy-MM-dd)")
    private String endDate;

    /**
     * Optional parallelism hint, accepted for API compatibility with the client contract.
     * Recalculation currently runs sequentially on a single background worker, so this is advisory.
     */
    private Integer threads;
}
