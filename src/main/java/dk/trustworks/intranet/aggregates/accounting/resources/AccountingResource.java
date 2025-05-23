package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.accounting.model.CompanyAccountingRecord;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.dto.DateAccountCategoriesDTO;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.resources.ExpenseResource;
import dk.trustworks.intranet.expenseservice.resources.UserAccountResource;
import dk.trustworks.intranet.financeservice.model.AccountLumpSum;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.Data;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@Tag(name = "accounting")
@JBossLog
@RequestScoped
@Path("/accounting")
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
public class AccountingResource {

    @Inject
    ExpenseResource expenseAPI;

    @Inject
    UserAccountResource userAccountAPI;

    @Inject
    AvailabilityService availabilityService;

    @GET
    @Path("/categories/csv")
    public List<DateAccountCategoriesDTO> findAllAccountingCategoriesCSV(@QueryParam("companyuuid") String companyuuid, @QueryParam("fromdate") Optional<String> strFromdate, @QueryParam("todate") Optional<String> strTodate) {

        LocalDate datefrom = strFromdate.map(DateUtils::dateIt).orElse(LocalDate.of(2017, 1, 1));
        LocalDate dateto = strTodate.map(DateUtils::dateIt).orElse(LocalDate.now());

        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);
        Company company = Company.findById(companyuuid);

        List<AccountingCategory> allAccountingCategories = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));
        List<FinanceDetails> allFinanceDetails = FinanceDetails.list("expensedate >= ?1 and expensedate < ?2", datefrom, dateto);

        List<FinanceDetails> primaryCompanyFinanceDetails = allFinanceDetails.stream().filter(fd -> fd.getCompany().equals(company)).toList();
        List<FinanceDetails> secondaryCompaniesFinanceDetails = allFinanceDetails.stream().filter(fd -> !fd.getCompany().equals(company)).toList();


        LocalDate date = datefrom;

        Map<LocalDate, DateAccountCategoriesDTO> result = new HashMap<>();
        do {
            // For use ind streams
            LocalDate finalDate = date;

            result.putIfAbsent(finalDate, new DateAccountCategoriesDTO(finalDate, new ArrayList<>()));

            // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
            double primaryCompanySalarySum = availabilityService.calculateSalarySum(company, finalDate, employeeAvailabilityPerMonthList);
            System.out.println("primaryCompanySalarySum = " + primaryCompanySalarySum);

            // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
            double primaryCompanyConsultant = availabilityService.calculateConsultantCount(company, finalDate, employeeAvailabilityPerMonthList);
            System.out.println("primaryCompanyConsultant = " + primaryCompanyConsultant);

            // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
            AtomicReference<Double> secondaryCompanyConsultant = new AtomicReference<>(0.0);
            AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
            Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).forEach(secondaryCompany -> {
                secondaryCompanySalarySum.updateAndGet(v -> v + availabilityService.calculateSalarySum(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
                secondaryCompanyConsultant.updateAndGet(v -> v + availabilityService.calculateConsultantCount(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
            });

            double totalNumberOfConsultants = primaryCompanyConsultant + secondaryCompanyConsultant.get();
            System.out.println("totalNumberOfConsultants = " + totalNumberOfConsultants);

            for (AccountingCategory ac : allAccountingCategories) {
                AccountingCategory accountingCategory = new AccountingCategory(ac.getAccountCode(), ac.getAccountname());
                result.get(finalDate).getAccountingCategories().add(accountingCategory);

                for (AccountingAccount aa : ac.getAccounts()) {
                    // Find existing account in accountingCategory or create a new one
                    Optional<AccountingAccount> existingAccount = accountingCategory.getAccounts().stream().filter(eaa -> eaa.getAccountCode() == aa.getAccountCode() && eaa.getCompany().equals(aa.getCompany())).findAny();
                    AccountingAccount accountingAccount;
                    if (existingAccount.isPresent()) accountingAccount = existingAccount.get();
                    else {
                        accountingAccount = new AccountingAccount(aa.getCompany(), ac, aa.getAccountCode(), aa.getAccountDescription(), aa.isShared(), aa.isSalary());
                        accountingCategory.getAccounts().add(accountingAccount);
                    }

                    if(accountingAccount.getCompany().equals(company)) {
                        double fullExpenses = primaryCompanyFinanceDetails.stream()
                                .filter(fd -> fd.getAccountnumber() == aa.getAccountCode() && fd.getExpensedate().equals(finalDate))
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum();
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("fullExpenses = " + fullExpenses);
                        accountingAccount.addSum(fullExpenses);
                        accountingCategory.addPrimarySum(fullExpenses);
                        if(!aa.isShared()) continue;

                        // Check and skip if expenses are negative, since they are not relevant for the calculation
                        if(fullExpenses <= 0) continue;

                        // Start by making the partial expenses equal fullExpenses
                        double partialExpenses = fullExpenses;
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("partialExpenses1 = " + partialExpenses);

                        // Remove lump sums from the expenses
                        double lumpSum = AccountLumpSum.<AccountLumpSum>list("accountingAccount = ?1 and registeredDate = ?2", aa, finalDate.withDayOfMonth(1)).stream().mapToDouble(AccountLumpSum::getAmount).sum();
                        partialExpenses -= lumpSum;
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("lumpSum = " + lumpSum);

                        // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                        if (aa.isSalary() && accountingAccount.isShared()) {
                            //partialExpenses = (Math.max(0, partialExpenses - primaryCompanySalarySum));
                            AtomicReference<Double> otherSalarySources = new AtomicReference<>(0.0);
                            ac.getAccounts().stream()
                                    .filter(value -> value.getCompany().equals(company) && !value.isShared() && value.isSalary())
                                    .forEach(value -> {
                                        otherSalarySources.updateAndGet(v -> v + primaryCompanyFinanceDetails.stream()
                                                .filter(fd -> fd.getAccountnumber() == value.getAccountCode() && fd.getExpensedate().withDayOfMonth(1).equals(finalDate))
                                                .mapToDouble(FinanceDetails::getAmount)
                                                .sum());
                                    });
                            if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("otherSalarySources.get() = " + otherSalarySources.get());
                            partialExpenses += otherSalarySources.get();
                            if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("partialExpenses2 = " + partialExpenses);
                            partialExpenses = (Math.max(0, partialExpenses - (primaryCompanySalarySum * 1.02)));
                            System.out.println("partialExpenses3 = " + partialExpenses);
                        }
                        if(partialExpenses <= 0) continue;

                        if (accountingAccount.isShared())
                            // partial fullExpenses should only account for the part of the fullExpenses equal to the share of consultants in the primary company
                            partialExpenses *= (secondaryCompanyConsultant.get() / totalNumberOfConsultants);
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("partialExpenses4 = " + partialExpenses);

                        // The loan is the difference between the fullExpenses and the partialExpenses
                        accountingAccount.addLoan(Math.max(0, partialExpenses));
                    } else {
                        if(!aa.isShared()) continue;
                        double fullExpenses = secondaryCompaniesFinanceDetails.stream()
                                .filter(fd -> fd.getAccountnumber() == aa.getAccountCode() && fd.getExpensedate().equals(finalDate))
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum();
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("fullExpenses = " + fullExpenses);
                        accountingCategory.addSecondarySum(fullExpenses);

                        // Check and skip if expenses are negative, since they are not relevant for the calculation
                        if(fullExpenses <= 0) continue;

                        // Start by making the partial expenses equal fullExpenses
                        double partialExpenses = fullExpenses;
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("partialExpenses1 = " + partialExpenses);

                        // Remove lump sums from the expenses
                        double lumpSum = AccountLumpSum.<AccountLumpSum>list("accountingAccount = ?1 and registeredDate = ?2", aa, finalDate.withDayOfMonth(1)).stream().mapToDouble(AccountLumpSum::getAmount).sum();
                        partialExpenses -= lumpSum;
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("lumpSum = " + lumpSum);

                        // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                        if (aa.isSalary() && accountingAccount.isShared()) {
                            //partialExpenses = (Math.max(0, partialExpenses - primaryCompanySalarySum));
                            AtomicReference<Double> otherSalarySources = new AtomicReference<>(0.0);
                            ac.getAccounts().stream()
                                    .filter(value -> !value.getCompany().equals(company) && !value.isShared() && value.isSalary())
                                    .forEach(value -> {
                                        otherSalarySources.updateAndGet(v -> v + secondaryCompaniesFinanceDetails.stream()
                                                .filter(fd -> fd.getAccountnumber() == value.getAccountCode() && fd.getExpensedate().withDayOfMonth(1).equals(finalDate))
                                                .mapToDouble(FinanceDetails::getAmount)
                                                .sum());
                                    });
                            if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("otherSalarySources.get() = " + otherSalarySources.get());
                            partialExpenses += otherSalarySources.get();
                            if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("partialExpenses2 = " + partialExpenses);
                            partialExpenses = (Math.max(0, partialExpenses - (secondaryCompanySalarySum.get() * 1.02)));
                            System.out.println("partialExpenses3 = " + partialExpenses);
                        }
                        if(partialExpenses <= 0) continue;

                        if (accountingAccount.isShared())
                            // partial fullExpenses should only account for the part of the fullExpenses equal to the share of consultants in the primary company
                            partialExpenses *= (primaryCompanyConsultant / totalNumberOfConsultants);
                        if(aa.getAccountCode()==3502 && finalDate.isEqual(LocalDate.of(2024,10,1))) System.out.println("partialExpenses4 = " + partialExpenses);

                        // The loan is the difference between the fullExpenses and the partialExpenses
                        accountingAccount.addDebt(Math.max(0, partialExpenses));
                    }
                }
            }
            date = date.plusMonths(1);
        } while (date.isBefore(dateto));

        return result.values().stream().toList();
    }

    @GET
    @Path("/categories/v2")
    public List<AccountingCategory> findAllAccountingCategoriesV2(@QueryParam("companyuuid") String companyuuid, @QueryParam("fromdate") Optional<String> strFromdate, @QueryParam("todate") Optional<String> strTodate) {
        LocalDate datefrom = strFromdate.map(DateUtils::dateIt).orElse(LocalDate.of(2017, 1, 1));
        LocalDate dateto = strTodate.map(DateUtils::dateIt).orElse(LocalDate.now());

        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);
        Company company = Company.findById(companyuuid);

        List<AccountingCategory> list = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        LocalDate date = datefrom;
        do {
            LocalDate finalDate = date;
            // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
            double primaryCompanySalarySum = availabilityService.calculateSalarySum(company, date, employeeAvailabilityPerMonthList);

            // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
            double primaryCompanyConsultantAvg = availabilityService.calculateConsultantCount(company, date, employeeAvailabilityPerMonthList);

            // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
            //AtomicReference<Double> secondaryCompanyConsultantAvg = new AtomicReference<>(0.0);
            //AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
            Map<Company, CompanyAccountingRecord> accountingRecordMap = new HashMap<>();
            Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).forEach(secondaryCompany -> {
                accountingRecordMap.put(secondaryCompany, new CompanyAccountingRecord(
                        availabilityService.calculateSalarySum(secondaryCompany, finalDate, employeeAvailabilityPerMonthList),
                        availabilityService.calculateConsultantCount(secondaryCompany, finalDate, employeeAvailabilityPerMonthList)
                ));
                //secondaryCompanySalarySum.updateAndGet(v -> v + calculateSalarySum(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
                //secondaryCompanyConsultantAvg.updateAndGet(v -> v + calculateConsultantCount(secondaryCompany, finalDate, employeeAvailabilityPerMonthList));
            });

            double totalNumberOfConsultants = primaryCompanyConsultantAvg + accountingRecordMap.values().stream().mapToDouble(CompanyAccountingRecord::consultantCount).sum();//secondaryCompanyConsultantAvg.get();

            // Beregn omkostningsfordeling for hver kategori og account for den primære virksomhed.
            //AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2", accountingCategory, company)
            list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(company)).forEach(accountingAccount -> {
                accountingAccount.setSum(
                        FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), company, finalDate, finalDate.plusMonths(1))
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum());
                accountingCategory.setPrimarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

                accountingAccount.setAdjustedSum(accountingAccount.getSum());

                // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                if(accountingAccount.isSalary()) {
                    accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getSum() - primaryCompanySalarySum));
                }

                if(accountingAccount.isShared()) accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (primaryCompanyConsultantAvg / totalNumberOfConsultants));

                if(accountingAccount.isSalary()) {
                    accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() + primaryCompanySalarySum);
                }

                accountingCategory.setAdjustedPrimarySum(accountingCategory.getAdjustedPrimarySum() + accountingAccount.getAdjustedSum());
            }));

            // Beregn omkostningsfordeling for hver kategori og account for alle andre virksomheder end den primære.
            // AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2 and shared is true", accountingCategory, secondaryCompany)
            Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).forEach(secondaryCompany -> {
                list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(secondaryCompany) && aa.isShared()).forEach(accountingAccount -> {
                    accountingAccount.setSum(
                            FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), secondaryCompany, finalDate, finalDate.plusMonths(1))
                                    .mapToDouble(FinanceDetails::getAmount)
                                    .sum());

                    accountingCategory.setSecondarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

                    accountingAccount.setAdjustedSum(accountingAccount.getSum());

                    // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                    if(accountingAccount.isSalary()) {
                        accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getAdjustedSum() - accountingRecordMap.values().stream().mapToDouble(CompanyAccountingRecord::salarySum).sum()));
                    }

                    accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (accountingRecordMap.get(secondaryCompany).consultantCount() / totalNumberOfConsultants));

                    accountingCategory.setAdjustedSecondarySum(accountingCategory.getAdjustedSecondarySum() + accountingAccount.getAdjustedSum());
                }));
            });

            date = date.plusMonths(1);
        } while (date.isBefore(dateto));

        return list;
    }

    @GET
    @Path("/categories")
    public List<AccountingCategory> findAllAccountingCategories(@QueryParam("companyuuid") String companyuuid, @QueryParam("fromdate") Optional<String> strFromdate, @QueryParam("todate") Optional<String> strTodate) {
        LocalDate datefrom = strFromdate.map(DateUtils::dateIt).orElse(LocalDate.of(2017, 1, 1));
        LocalDate dateto = strTodate.map(DateUtils::dateIt).orElse(LocalDate.now());
        int monthsBetween = DateUtils.countMonthsBetween(datefrom, dateto);

        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(datefrom, dateto);
        Company company = Company.findById(companyuuid);

        // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
        double primaryCompanySalarySum = availabilityService.calculateSalarySum(company, employeeAvailabilityPerMonthList.stream().filter(e -> e.getDate().isBefore(LocalDate.now().withDayOfMonth(1))).toList());
        System.out.println("primaryCompanySalarySum = " + primaryCompanySalarySum);

        // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
        double primaryCompanyConsultantAvg = availabilityService.calculateConsultantCount(company, employeeAvailabilityPerMonthList) / monthsBetween;
        System.out.println("primaryCompanyConsultantAvg = " + primaryCompanyConsultantAvg);

        // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
        AtomicReference<Double> secondaryCompanyConsultantAvg = new AtomicReference<>(0.0);
        AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
        Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).forEach(secondaryCompany -> {
            secondaryCompanySalarySum.updateAndGet(v -> v + availabilityService.calculateSalarySum(secondaryCompany, employeeAvailabilityPerMonthList.stream().filter(e -> e.getDate().isBefore(LocalDate.now().withDayOfMonth(1))).toList()));
            secondaryCompanyConsultantAvg.updateAndGet(v -> v + availabilityService.calculateConsultantCount(secondaryCompany, employeeAvailabilityPerMonthList) / monthsBetween);
        });
        System.out.println("secondaryCompanyConsultantAvg = " + secondaryCompanyConsultantAvg.get());

        double totalNumberOfConsultants = primaryCompanyConsultantAvg + secondaryCompanyConsultantAvg.get();

        List<AccountingCategory> list = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));

        // Beregn omkostningsfordeling for hver kategori og account for den primære virksomhed.
        //AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2", accountingCategory, company)
        list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(company)).forEach(accountingAccount -> {
            accountingAccount.setSum(
                    FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), company, datefrom, dateto)
                            .mapToDouble(FinanceDetails::getAmount)
                            .sum());
            accountingCategory.setPrimarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

            accountingAccount.setAdjustedSum(accountingAccount.getSum());

            // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
            if(accountingAccount.isSalary()) {
                accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getSum() - primaryCompanySalarySum));
            }

            if(accountingAccount.isShared()) accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (primaryCompanyConsultantAvg / totalNumberOfConsultants));

            if(accountingAccount.isSalary()) {
                accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() + primaryCompanySalarySum);
            }

            accountingCategory.setAdjustedPrimarySum(accountingCategory.getAdjustedPrimarySum() + accountingAccount.getAdjustedSum());
        }));

        // Beregn omkostningsfordeling for hver kategori og account for alle andre virksomheder end den primære.
        // AccountingAccount.<AccountingAccount>list("accountingCategory = ?1 and company = ?2 and shared is true", accountingCategory, secondaryCompany)
        Company.<Company>listAll().stream().filter(c -> !c.getUuid().equals(companyuuid)).forEach(secondaryCompany -> {
            list.forEach(accountingCategory -> accountingCategory.getAccounts().stream().filter(aa -> aa.getCompany().equals(secondaryCompany) && aa.isShared()).forEach(accountingAccount -> {
                accountingAccount.setSum(
                        FinanceDetails.<FinanceDetails>stream("accountnumber = ?1 and company = ?2 and expensedate >= ?3 and expensedate < ?4", accountingAccount.getAccountCode(), secondaryCompany, datefrom, dateto)
                                .mapToDouble(FinanceDetails::getAmount)
                                .sum());

                accountingCategory.setSecondarySum(accountingCategory.getPrimarySum() + accountingAccount.getSum());

                accountingAccount.setAdjustedSum(accountingAccount.getSum());

                // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                if(accountingAccount.isSalary()) {
                    accountingAccount.setAdjustedSum(Math.max(0, accountingAccount.getAdjustedSum() - secondaryCompanySalarySum.get()));
                }

                accountingAccount.setAdjustedSum(accountingAccount.getAdjustedSum() * (primaryCompanyConsultantAvg / totalNumberOfConsultants));

                accountingCategory.setAdjustedSecondarySum(accountingCategory.getAdjustedSecondarySum() + accountingAccount.getAdjustedSum());
            }));
        });

        return list;
    }


    @GET
    @Path("/categories/{uuid}")
    public AccountingCategory findAccountingCategoryByUuid(@PathParam("uuid") String uuid) {
        return AccountingCategory.findById(uuid);
    }

    @GET
    @Path("/categories/{uuid}/expenses/sum")
    public String getExpenses(@QueryParam("companyuuid") String companyuuid, @PathParam("uuid") String uuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate date1 = DateUtils.dateIt(fromdate);
        LocalDate date2 = DateUtils.dateIt(todate);
        Company company = Company.findById(companyuuid);
        AccountingCategory category = AccountingCategory.findById(uuid);
        List<FinanceDetails> financeDetails = FinanceDetails.list("expensedate between ?1 and ?2", date1, date2);
        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(date1, date2);
        AtomicInteger sum = new AtomicInteger();
        LocalDate date = date1;
        while(date.isBefore(date2)) {
            LocalDate finalDate = date;
            System.out.println("Calculating date: " + stringIt(finalDate));

            // count sum of salary per month and number of consultants
            AtomicInteger salarySum = new AtomicInteger();
            AtomicInteger totalNumberOfConsultants = new AtomicInteger();
            Map<Company, Integer> numberOfConsultantsPerCompany = new HashMap<>();
            for (Company c : Company.<Company>listAll()) {
                numberOfConsultantsPerCompany.put(c, 0);
                employeeAvailabilityPerMonthList.stream()
                        .filter(e -> LocalDate.of(e.getYear(), e.getMonth(), 1).equals(finalDate) &&
                                !e.getStatus().equals(StatusType.TERMINATED) &&
                                !e.getStatus().equals(StatusType.NON_PAY_LEAVE) &&
                                e.getCompany()!=null &&
                                e.getCompany().equals(c) &&
                                e.getConsultantType().equals(ConsultantType.CONSULTANT)).forEach(employeeDataPerMonth -> {
                            salarySum.addAndGet(employeeDataPerMonth.getAvgSalary().intValue());
                            totalNumberOfConsultants.getAndIncrement();
                            numberOfConsultantsPerCompany.merge(employeeDataPerMonth.getCompany(), 1, Integer::sum);
                        });
            }
            System.out.println("totalNumberOfConsultants = " + totalNumberOfConsultants.get());
            System.out.println("salarySum = " + salarySum.get());
            System.out.println("numberOfConsultantsPerCompany.get(company).intValue() = " + numberOfConsultantsPerCompany.get(company));

            // Go through each account type and sum up the expenses. Include all company account and those which are shared
            AccountingAccount.<AccountingAccount>list("accountingCategory", category).stream().filter(a -> (a.isShared() || a.getCompany().equals(company))).forEach(accountingAccount -> {
                // Calculate the raw sum of expenses for the given account type and month. Only include which are shared or the correct company
                // DONE: Filter by company and shared. Waiting for the new accounting system to be implemented.
                AccountCodeResult accountCodeResult = new AccountCodeResult();

                double partialSum = financeDetails.stream()
                        .filter(fd -> fd.getExpensedate().equals(finalDate) &&
                                fd.getAccountnumber() == accountingAccount.getAccountCode() &&
                                (accountingAccount.isShared() || accountingAccount.getCompany().equals(company)))
                        .mapToDouble(FinanceDetails::getAmount)
                        .sum();
                System.out.println("Unadjusted sum for "+accountingAccount.getAccountCode()+": " + partialSum);

                if(accountingAccount.isSalary()) {
                    partialSum -= salarySum.get();
                }
                System.out.println("Salary adjusted sum: " + partialSum);

                if(accountingAccount.isShared()) {
                    partialSum = (partialSum / totalNumberOfConsultants.get()) * numberOfConsultantsPerCompany.get(company);
                }
                System.out.println("Sum adjusted for number of employees without salary: " + partialSum);

                if(accountingAccount.isSalary()) {
                    partialSum += salarySum.get();
                }
                System.out.println("Resulting sum: " + partialSum);

                sum.addAndGet((int) partialSum);
            });
            date = date.plusMonths(1);
        }
        return null; //TODO replace this stub to something useful
    }

    @Data
    static class MonthResult {
        private LocalDate date;
        private int totalConsultants;
        private int companyConsultants;
        private int companySalary;
        private List<AccountCodeResult> accountCodeResults = new ArrayList<>();
    }

    @Data
    static class AccountCodeResult {
        private int accountCode;
        private int totalSum;
        private int companySum;
    }

    @GET
    @Path("/receipts/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        return expenseAPI.findByUuid(uuid);
    }

    @GET
    @Path("/receipts/file/{uuid}")
    public ExpenseFile getFileById(@PathParam("uuid") String uuid) {
        return expenseAPI.getFileById(uuid);
    }

    @GET
    @Path("/receipts/user/{useruuid}")
    public List<Expense> findByUser(@PathParam("useruuid") String useruuid, @QueryParam("limit") Optional<String> limit, @QueryParam("page") Optional<String> page) {
        if(limit.isPresent() && page.isPresent())
            return expenseAPI.findByUser(useruuid, limit.get(), page.get());
        else
            return expenseAPI.findByUser(useruuid);
    }

    @GET
    @Path("/receipts/project/{projectuuid}/search/period")
    public List<Expense> findByProjectAndPeriod(@PathParam("projectuuid") String projectuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByProjectAndPeriod(projectuuid, fromdate, todate);
    }

    @GET
    @Path("/receipts/user/{useruuid}/search/period")
    public List<Expense> findByUserAndPeriod(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByUserAndPeriod(useruuid, fromdate, todate);
    }

    @GET
    @Path("/receipts/search/period")
    public List<Expense> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByPeriod(fromdate, todate);
    }

    @POST
    @Path("/receipts")
    public void save(Expense expense) throws IOException, InterruptedException {
        if(expense.getUseruuid().equals("173ee0b6-4ee5-11e7-b114-b2f933d5fe66")) return;
        expenseAPI.saveExpense(expense);
    }

    @PUT
    @Path("/receipts/{uuid}")
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        expenseAPI.updateOne(uuid, expense);
    }

    @DELETE
    @Path("/receipts/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        expenseAPI.delete(uuid);
    }

    // UserAccount Resource

    @GET
    @Path("/user-accounts/{useruuid}")
    public UserAccount getUserAccountByUser(@PathParam("useruuid") String useruuid) {
        return userAccountAPI.getAccountByUser(useruuid);
    }

    @GET
    @Path("/user-accounts/search/findByAccountNumber")
    public UserAccount getAccount(@QueryParam("companyuuid") String companyuuid, @QueryParam("account") int account) throws IOException {
        if(account<=0) return new UserAccount(0, "", "No account found");
        return userAccountAPI.getAccount(companyuuid, account);
    }

    @POST
    @Path("/user-accounts")
    public void saveUserAccount(UserAccount userAccount) {
        userAccountAPI.saveAccount(userAccount);
    }

    @PUT
    @Path("/user-accounts/{useruuid}")
    public void updateUserAccount(@PathParam("useruuid") String useruuid, UserAccount userAccount) {
        userAccountAPI.updateAccount(useruuid, userAccount);
    }

    // AccountPlan Resource
/*
    @GET
    @Path("/expense-accounts/{account_no}")
    public ExpenseAccount findExpenseAccountByAccountNo(@PathParam("account_no") String account_no) {
        return accountPlanAPI.findAccountByAccountNo(account_no);
    }

    @POST
    @Path("/expense-accounts")
    @Transactional
    public void saveExpenseAccount(@Valid ExpenseAccount expenseAccount) {
        accountPlanAPI.saveExpenseAccount(expenseAccount);
    }

    @PUT
    @Path("/expense-accounts/{account-no}")
    @Transactional
    public void updateExpenseAccount(@PathParam("account-no") String account_no, ExpenseAccount expenseAccount) {
        accountPlanAPI.updateExpenseAccount(account_no, expenseAccount);
    }

    @GET
    @Path("/account-categories")
    public List<ExpenseCategory> findAllExpenseCategories() {
        return accountPlanAPI.findAll();
    }

    @GET
    @Path("/expense-categories/{uuid}")
    public ExpenseCategory findExpenseCategoryByUuid(@PathParam("uuid") String uuid) {
        return accountPlanAPI.findCategoryByUuid(uuid);
    }

    @GET
    @Path("/account-categories/active")
    public List<ExpenseCategory> findAllActiveExpenseCategories() {
        return accountPlanAPI.findAllActive();
    }

    @GET
    @Path("/account-categories/inactive")
    public List<ExpenseCategory> findAllInactiveExpenseCategories() {
        return accountPlanAPI.findAllInactive();
    }

    @POST
    @Path("/expense-categories")
    @Transactional
    public void saveExpenseCategory(@Valid ExpenseCategory expenseCategory) {
        accountPlanAPI.saveExpenseCategory(expenseCategory);
    }

    @PUT
    @Path("/expense-categories/{uuid}")
    @Transactional
    public void updateExpenseCategory(@PathParam("uuid") String uuid, ExpenseCategory expenseCategory) {
        accountPlanAPI.updateExpenseCategory(uuid, expenseCategory);
    }

 */
}

