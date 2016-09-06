package org.wso2.custom.inbound;


import org.apache.synapse.inbound.InboundProcessorParams;
import org.wso2.custom.inbound.common.InboundHttp2Constants;

import java.util.Properties;

/**
 * Created by chanakabalasooriya on 9/1/16.
 */
public class InboundHttp2Configuration {
    private final int port;
    private final String name;
    private final String dispatchPattern;
    private final Properties properties;
    private final boolean IsSSL;

    public Properties getProperties() {
        return properties;
    }

    public boolean isSSL() {
        return IsSSL;
    }

    private InboundHttp2Configuration(InboundHttp2Configuration.InboundHttp2ConfigurationBuilder builder) {
        this.port = builder.port;
        this.name = builder.name;
        this.properties=builder.properties;
        this.dispatchPattern = builder.dispatchPattern;
        this.IsSSL=builder.IsSSL;
    }

    public int getPort() {
        return port;
    }

    public String getName() {
        return name;
    }

    public String getDispatchPattern() {
        return dispatchPattern;
    }

    public static class InboundHttp2ConfigurationBuilder{
        private final int port;
        private final String name;
        private String dispatchPattern;
        private Properties properties;
        private boolean IsSSL;
        public InboundHttp2ConfigurationBuilder(InboundProcessorParams params) {
            properties = params.getProperties();

            this.name = params.getName();
            this.port = Integer.parseInt(properties.getProperty(InboundHttp2Constants.INBOUND_PORT));




            if(properties.getProperty(InboundHttp2Constants.IS_SSL)!=null) {
                this.IsSSL = Boolean.parseBoolean(properties.getProperty(InboundHttp2Constants.IS_SSL));
            }else{
                this.IsSSL=false;
            }

            if(properties.getProperty(InboundHttp2Constants.INBOUND_ENDPOINT_PARAMETER_DISPATCH_FILTER_PATTERN)!=null) {
                this.dispatchPattern = properties.getProperty(
                        InboundHttp2Constants.INBOUND_ENDPOINT_PARAMETER_DISPATCH_FILTER_PATTERN);
            }else{
                this.dispatchPattern=null;
            }

        }

        public InboundHttp2Configuration build() {
            return new InboundHttp2Configuration(this);
        }
        
    }

}
