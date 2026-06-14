package dk.trustworks.intranet.aggregates.users.danlon.dto;

import dk.trustworks.intranet.aggregates.users.danlon.DanlonEventType;
import dk.trustworks.intranet.aggregates.users.danlon.ProposalIntent;
import dk.trustworks.intranet.aggregates.users.danlon.ProposalStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DanlonProposalView(
        String uuid, String useruuid, String employeeName, String companyUuid,
        LocalDate effectiveMonth, DanlonEventType eventType, ProposalIntent intent,
        String suggestedNumber, ProposalStatus status, String targetHistoryUuid,
        String currentNumber, LocalDateTime detectedDate, String detectedBy) {}
