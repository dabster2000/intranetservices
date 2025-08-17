package dk.trustworks.intranet.expenseservice.ai;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.YearMonth;

@Path("/expenses/insights")
@Produces(MediaType.APPLICATION_JSON)
public class ExpenseInsightResource {

    @Inject ExpenseInsightQueryService queryService;

    @GET
    @Path("/sum/beverage/{subtype}")
    public Response sumBeverage(@PathParam("subtype") String subtype,
                                @QueryParam("from") String from,
                                @QueryParam("to") String to) {
        LocalDate f = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate t = (to != null && !to.isBlank()) ? LocalDate.parse(to) : null;
        double amount = queryService.sumBeverageSubtype(subtype.toUpperCase(), f, t);
        return Response.ok(new SumResponse("beverage", subtype.toUpperCase(), amount, f, t)).build();
    }

    @GET
    @Path("/sum/lunch/{useruuid}")
    public Response sumLunchByUser(@PathParam("useruuid") String useruuid,
                                   @QueryParam("yearMonth") String yearMonth) {
        if (yearMonth == null || yearMonth.isBlank()) {
            throw new BadRequestException("yearMonth is required, format YYYY-MM");
        }
        YearMonth ym = YearMonth.parse(yearMonth);
        double amount = queryService.sumLunchByUserAndMonth(useruuid, ym);
        return Response.ok(new SumResponse("lunch_user_month", useruuid + ":" + ym, amount, ym.atDay(1), ym.atEndOfMonth())).build();
    }

    @GET
    @Path("/sum/merchant/{merchant}")
    public Response sumByMerchant(@PathParam("merchant") String merchant,
                                  @QueryParam("from") String from,
                                  @QueryParam("to") String to) {
        LocalDate f = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate t = (to != null && !to.isBlank()) ? LocalDate.parse(to) : null;
        double amount = queryService.sumByMerchant(merchant, f, t);
        return Response.ok(new SumResponse("merchant", merchant, amount, f, t)).build();
    }

    public record SumResponse(String type, String key, double amount, LocalDate from, LocalDate to) {}
}
