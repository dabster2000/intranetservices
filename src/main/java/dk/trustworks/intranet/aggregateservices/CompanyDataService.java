package dk.trustworks.intranet.aggregateservices;

/*
@JBossLog
@ApplicationScoped
public class CompanyDataService {

    private final LocalDate startDate = LocalDate.of(2014, 2, 1);

    @PersistenceContext
    EntityManager entityManager;

    @Inject
    WorkService workService;

    @Inject
    FinanceService financeService;

    @Inject
    UserService userService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    BudgetService budgetService;

    @Inject
    AvailabilityService availabilityService;

    private final List<LocalDate> reloadMonthRevenueDates = Collections.synchronizedList(new ArrayList<>());


    public CompanyAggregateData updateWorkData(CompanyAggregateData companyAggregateData) {
        LocalDate lookupDate = companyAggregateData.getMonth();
        companyAggregateData.setRegisteredHours(0);
        companyAggregateData.setRegisteredAmount(0);
        for (WorkFull work : workService.findByPeriod(lookupDate, lookupDate.plusMonths(1)).stream().filter(w -> w.getConsultant_company_uuid().equals(companyAggregateData.getCompany().getUuid())).toList()) {
            if(work.getRate() > 0 && work.getRegistered().getYear()!=lookupDate.getYear() && work.getRegistered().getMonthValue()!= lookupDate.getMonthValue()) continue;
            companyAggregateData.addWorkDuration(work.getWorkduration());
            companyAggregateData.addRegisteredAmount(work.getWorkduration() * work.getRate());
        }
        return companyAggregateData;
    }

    public CompanyAggregateData updateFinanceData(CompanyAggregateData companyAggregateData) {
        LocalDate lookupDate = companyAggregateData.getMonth();
        FinanceDocument financeDocument = financeService.getFinanceDocument(lookupDate);

        double consultantNetSalaries = userService.calcMonthSalaries(lookupDate, ConsultantType.CONSULTANT.toString());
        double staffNetSalaries = userService.calcMonthSalaries(lookupDate, ConsultantType.STAFF.toString());

        double totalSalaries = financeDocument.getESalaries();
        double forholdstal = totalSalaries / (consultantNetSalaries + staffNetSalaries);
        final double staffSalaries = NumberUtils.round(staffNetSalaries * forholdstal, 0);
        final double consultantSalaries = NumberUtils.round(consultantNetSalaries * forholdstal, 0);

        companyAggregateData.setConsultantSalaries((int)consultantSalaries);
        companyAggregateData.setStaffSalaries((int)staffSalaries);
        companyAggregateData.setEmployeeExpenses((int)financeDocument.getEEmployee_expenses());
        companyAggregateData.setOfficeExpenses((int)financeDocument.getEHousing());
        companyAggregateData.setSalesExpenses((int)financeDocument.getESales());
        companyAggregateData.setProductionExpenses((int)financeDocument.getEProduktion());
        companyAggregateData.setAdministrationExpenses((int)financeDocument.getEAdministration());

        return companyAggregateData;
    }

    public CompanyAggregateData updateInvoiceData(CompanyAggregateData companyAggregateData) {
        LocalDate lookupDate = companyAggregateData.getMonth();

        List<Invoice> invoices = invoiceService.findByBookingDate(lookupDate.withDayOfMonth(1), lookupDate.withDayOfMonth(1).plusMonths(1)).stream()
                .filter(invoice -> (invoice.getStatus().equals(CREATED) ||
                        invoice.getStatus().equals(CREDIT_NOTE) ||
                        invoice.getStatus().equals(SUBMITTED) ||
                        invoice.getStatus().equals(PAID)) && invoice.getCompany().equals(companyAggregateData.getCompany())).toList();

        double invoicedAmount = 0.0;
        for (Invoice invoice : invoices) {
            double sum = invoice.getInvoiceitems().stream().mapToDouble(value -> value.hours * value.rate).sum();
            sum -= sum * (invoice.discount / 100.0);
            if(invoice.getStatus().equals(CREDIT_NOTE)) invoicedAmount -= sum;
            else invoicedAmount += sum;
        }

        companyAggregateData.setInvoicedAmount((int)invoicedAmount);
        return companyAggregateData;
    }

    public CompanyAggregateData updateBudgetData(CompanyAggregateData companyAggregateData) {
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = budgetService.calcBudgets(companyAggregateData.getMonth());

        double budgetHours = employeeBudgetPerDayAggregates.stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();
        double budgetAmount = employeeBudgetPerDayAggregates.stream().mapToDouble(b -> b.getBudgetHours() * b.getRate()).sum();

        companyAggregateData.setBudgetAmount((int)budgetAmount);
        companyAggregateData.setBudgetHours((int)budgetHours);
        return companyAggregateData;
    }

    public CompanyAggregateData updateEmployeeCountData(CompanyAggregateData companyAggregateData) {
        long employees = availabilityService.countActiveEmployeeTypesByMonth(companyAggregateData.getMonth(), ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT);
        long consultants = availabilityService.countActiveEmployeeTypesByMonth(companyAggregateData.getMonth(), ConsultantType.CONSULTANT);

        companyAggregateData.setNumOfEmployees((int) employees);
        companyAggregateData.setNumOfConsultants((int) consultants);
        return companyAggregateData;
    }

    public CompanyAggregateData updateAvailabilityData(CompanyAggregateData companyAggregateData) {
        List<AvailabilityDocument> availabilityDocumentList = availabilityService.getConsultantAvailabilityByMonth(companyAggregateData.getMonth());

        companyAggregateData.setGrossAvailableHours(0);
        companyAggregateData.setNetAvailableHours(0);

        for (AvailabilityDocument availabilityDocument : availabilityDocumentList) {
            companyAggregateData.setGrossAvailableHours(companyAggregateData.getGrossAvailableHours()+availabilityDocument.getGrossAvailableHours());
            companyAggregateData.setNetAvailableHours(companyAggregateData.getNetAvailableHours()+availabilityDocument.getNetAvailableHours());
        }

        return companyAggregateData;
    }

    public CompanyAggregateData updateUtilizationData(CompanyAggregateData companyAggregateData) {
        log.debug("CompanyDataService.updateUtilizationData");
        double monthTotalNetAvailabilites = 0.0;
        double monthTotalGrossAvailabilites = 0.0;
        double monthAvailabilites = 0.0;

        List<AvailabilityDocument> availabilityDocuments = availabilityService.getConsultantAvailabilityByMonth(companyAggregateData.getMonth());
        List<EmployeeBudgetPerDayAggregate> employeeBudgetPerDayAggregates = budgetService.getBudgetDataByPeriod(companyAggregateData.getMonth());

        for (User user : userService.findWorkingUsersByDate(companyAggregateData.getMonth(), ConsultantType.CONSULTANT)) {
            if(user.getUsername().equals("hans.lassen") || user.getUsername().equals("tobias.kjoelsen") || user.getUsername().equals("lars.albert") || user.getUsername().equals("thomas.gammelvind")) continue;
            double budget = employeeBudgetPerDayAggregates.stream().filter(b -> b.getUser().getUuid().equals(user.getUuid()) && b.getDocumentDate().isEqual(companyAggregateData.getMonth().withDayOfMonth(1)))
                    .mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();
            monthAvailabilites += budget;
            Optional<AvailabilityDocument> document = availabilityDocuments.stream().filter(availabilityDocument ->
                    availabilityDocument.getMonth().isEqual(companyAggregateData.getMonth()) && availabilityDocument.getUser().getUuid().equals(user.getUuid())).findAny();
            double netAvailability = document.map(AvailabilityDocument::getNetAvailableHours).orElse(0.0);
            monthTotalNetAvailabilites += netAvailability;
            double grossAvailability = document.map(AvailabilityDocument::getGrossAvailableHours).orElse(0.0);
            monthTotalGrossAvailabilites += grossAvailability;
        }
        companyAggregateData.setNetAvailability(monthTotalNetAvailabilites);
        companyAggregateData.setGrossAvailability(monthTotalGrossAvailabilites);
        companyAggregateData.setBudgetAvailability(monthAvailabilites);
        return companyAggregateData;
    }



    @Scheduled(every = "1h", delay = 60)
    public void updateAllData() {
        log.info("CompanyDataService.updateAllData...STARTED!");
        long l = System.currentTimeMillis();
        QuarkusTransaction.requiringNew().run(() -> {
                    String sql = "TRUNCATE TABLE company_data";
                    Query query = entityManager.createNativeQuery(sql);
                    query.executeUpdate();
                });
            //CompanyAggregateData.deleteAll()});

        for (Company company : Company.<Company>listAll()) {
            for (LocalDate reloadDate : reloadMonthRevenueDates) {
                String id = UUID.randomUUID().toString();
                CompanyAggregateData data = new CompanyAggregateData(id, company, reloadDate);

                //dataMap.putIfAbsent(reloadDate, new CompanyAggregateData(reloadDate));

                updateWorkData(data);
                updateBudgetData(data);
                updateFinanceData(data);
                updateInvoiceData(data);
                updateEmployeeCountData(data);
                updateAvailabilityData(data);
                updateUtilizationData(data);
                QuarkusTransaction.requiringNew().run(data::persist);
            }
        }



        //reloadMonthRevenueDates.clear();
        log.info("CompanyDataService.updateAllData...DONE!");
        log.info("CompanyDataService Time: "+(System.currentTimeMillis()-l));
    }

    @PostConstruct
    public void init() {
        log.info("CompanyDataService.init");
        LocalDate lookupDate = startDate;
        do {
            reloadMonthRevenueDates.add(lookupDate);
            lookupDate = lookupDate.plusMonths(1);
        } while (lookupDate.isBefore(getCurrentFiscalStartDate().plusYears(2)));
        //updateAllData();
    }

    public List<CompanyAggregateData> getDataMap(LocalDate fromdate, LocalDate todate) {
        return CompanyAggregateData.find("month >= ?1 and month < ?2", fromdate, todate).list();
    }

    public void addReloadDate(LocalDate reloadDate) {
        reloadMonthRevenueDates.add(reloadDate);
    }

}

 */


