package dk.trustworks.intranet.utils.dto;

public record EmploymentContractData(
        String employeeName,
        String employeeAddress,
        String companyLegalName,
        String companyCvr,
        String companyAddress,
        String employmentStartDate, // fx "1. april 2026"
        String jobTitle,
        int weeklyHours,
        String monthlySalary,       // fx "60.000"
        String contractCity,
        String contractDate         // fx "14. november 2025"
) {}
