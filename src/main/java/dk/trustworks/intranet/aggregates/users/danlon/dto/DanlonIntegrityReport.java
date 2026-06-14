package dk.trustworks.intranet.aggregates.users.danlon.dto;

import java.time.LocalDate;
import java.util.List;

public record DanlonIntegrityReport(
        List<DuplicateDanlon> duplicates,
        List<MissingIdActive> missingIdActives,
        List<NonConformingValue> nonConforming) {

    public record DuplicateDanlon(String danlon, List<Holder> holders) {}
    public record Holder(String useruuid, String fullName, String status) {}
    public record MissingIdActive(String useruuid, String fullName, String companyUuid, LocalDate activeSince) {}
    public record NonConformingValue(String useruuid, String fullName, String danlon, String reason) {}
}
