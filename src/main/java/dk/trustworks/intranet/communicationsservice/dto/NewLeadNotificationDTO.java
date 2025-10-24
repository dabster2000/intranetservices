package dk.trustworks.intranet.communicationsservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Data Transfer Object for sending new sales lead notifications to Slack.
 * Contains all relevant information about a newly created lead for display
 * in a formatted Slack message using Block Kit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewLeadNotificationDTO {

    /**
     * Short description of the lead (max 255 characters)
     */
    private String description;

    /**
     * Name of the client company
     */
    private String clientName;

    /**
     * Detailed description of the lead requirements and context
     */
    private String detailedDescription;

    /**
     * Current status of the lead (e.g., DETECTED, QUALIFIED, PROPOSAL, etc.)
     */
    private String status;

    /**
     * Consultant allocation percentage (0-100)
     */
    private Integer allocation;

    /**
     * Contract period in months
     */
    private Integer period;

    /**
     * Hourly or daily rate in DKK
     */
    private Double rate;

    /**
     * Expected close date (start date) of the contract
     */
    private LocalDate closeDate;

    /**
     * Name of the lead manager responsible for this lead
     */
    private String leadManagerName;

    /**
     * Unique identifier of the lead (UUID) for deep linking to the lead detail page
     */
    private String leadUuid;
}
