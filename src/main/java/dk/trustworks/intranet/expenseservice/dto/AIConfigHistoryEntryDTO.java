package dk.trustworks.intranet.expenseservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record AIConfigHistoryEntryDTO(
    String uuid, String entityKind, String entityKey, String changeAction,
    JsonNode snapshot,
    OffsetDateTime changedAt, String changedBy) {}
