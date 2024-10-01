package dk.trustworks.intranet.aggregates.accounting.model;

import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import lombok.Getter;
import lombok.ToString;

@ToString
@Getter
public class DanlonEmployee {
    String employeeNumber; // Medarb.nr.
    String name; // Navn
    String address; // Adresse
    String address2; // Adresse 2
    String postalCode; // Postnummer
    String city; // By
    String countryCode; // Landekode
    String email; // E-mail
    String privatePhone; // Privat telefon
    String mobilePhone; // Mobiltelefon
    String cprNumber; // CPR-nummer
    String hireDate; // Ansættelsesdato
    String jobTitle; // Stillingsbetegnelse
    String bankAccount; // Bankkonto
    String accountingGroup; // Bogføringsgruppe
    String active; // Aktiv
    SalaryType type; // Hourly og monthly paid

    public DanlonEmployee(String employeeNumber, String name, String address, String address2, String postalCode,
                          String city, String countryCode, String email, String privatePhone, String mobilePhone,
                          String cprNumber, String hireDate, String jobTitle, String bankAccount, String accountingGroup, String active, SalaryType type) {
        this.employeeNumber = employeeNumber;
        this.name = name;
        this.address = address;
        this.address2 = address2;
        this.postalCode = postalCode;
        this.city = city;
        this.countryCode = countryCode;
        this.email = email;
        this.privatePhone = privatePhone;
        this.mobilePhone = mobilePhone;
        this.cprNumber = cprNumber;
        this.hireDate = hireDate;
        this.jobTitle = jobTitle;
        this.bankAccount = bankAccount;
        this.accountingGroup = accountingGroup;
        this.active = active;
        this.type = type;
    }
}