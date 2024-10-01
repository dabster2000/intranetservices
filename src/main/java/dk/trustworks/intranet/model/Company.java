package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "companies")
public class Company extends PanacheEntityBase {
    public static String TRUSTWORKS_UUID = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    public static String TRUSTWORKS_TECHNOLOGY_UUID = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";

    @Id
    @Column(name = "uuid")
    @EqualsAndHashCode.Include
    private String uuid;
    @Basic
    @Column(name = "name")
    private String name;
    @Basic
    @Column(name = "abbreviation")
    private String abbreviation;
    @Basic
    @Column(name = "cvr")
    private String cvr;
    @Basic
    @Column(name = "address")
    private String address;
    @Basic
    @Column(name = "zipcode")
    private String zipcode;
    @Basic
    @Column(name = "city")
    private String city;
    @Basic
    @Column(name = "country")
    private String country;
    @Basic
    @Column(name = "phone")
    private String phone;
    @Basic
    @Column(name = "email")
    private String email;
    @Basic
    @Column(name = "regnr")
    private String regnr;
    @Basic
    @Column(name = "account")
    private String account;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime created;

}
