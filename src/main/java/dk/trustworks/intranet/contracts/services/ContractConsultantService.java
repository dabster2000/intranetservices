package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.ContractConsultant;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.PathParam;

@JBossLog
@ApplicationScoped
public class ContractConsultantService {

    public ContractConsultant findByUUID(@PathParam("consultantuuid") String consultantuuid) {
        return ContractConsultant.findById(consultantuuid);
    }
}
