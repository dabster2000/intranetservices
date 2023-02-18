package dk.trustworks.intranet.apigateway.filters;

import lombok.extern.jbosslog.JBossLog;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@JBossLog
@Provider
public class CORSFilter implements ContainerResponseFilter {



    public CORSFilter() {}

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        //log.debug("Modifing response with CORSFIlter: {}" + responseContext.getHeaders());
        MultivaluedMap<String, Object> headers = responseContext.getHeaders();
        headers.putSingle("Access-Control-Allow-Origin", "*");
        headers.putSingle("Access-Control-Allow-Headers", "*");
        //log.debug("Modified to add the required header: {}"+ responseContext.getHeaders());
    }
}
