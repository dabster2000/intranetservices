package dk.trustworks.intranet.aggregates.executive.people;

import jakarta.ws.rs.QueryParam;

/** Raw JAX-RS query parameters. {@link PeopleFilterParams#from(PeopleFilterRequest)} validates them. */
public class PeopleFilterRequest {

    @QueryParam("asOfDate")
    public String asOfDate;

    @QueryParam("months")
    public String months;

    @QueryParam("horizonDays")
    public String horizonDays;

    @QueryParam("companyId")
    public String companyId;

    @QueryParam("employeeTypes")
    public String employeeTypes;

    @QueryParam("population")
    public String population;

    @QueryParam("practices")
    public String practices;

    @QueryParam("careerTracks")
    public String careerTracks;

    @QueryParam("careerLevels")
    public String careerLevels;

    @QueryParam("managementScope")
    public String managementScope;

    @QueryParam("compensationGroup")
    public String compensationGroup;

    @QueryParam("salaryType")
    public String salaryType;
}
