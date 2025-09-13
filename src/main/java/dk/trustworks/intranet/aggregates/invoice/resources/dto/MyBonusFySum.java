package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import java.time.LocalDate;

public record MyBonusFySum(LocalDate fyStart, double approved, double total) {}
