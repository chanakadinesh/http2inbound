package org.wso2.custom.inbound;


/**
 * Created by chanakabalasooriya on 9/1/16.
 */
public class InboundHttp2Configuration {
    private final int port;
    private final String name;
    private final String dispatchPattern;

    private InboundHttp2Configuration(InboundHttp2Configuration.InboundHttp2ConfigurationBuilder builder) {
        this.port = builder.port;
        this.name = builder.name;
        this.dispatchPattern = builder.dispatchPattern;
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

        public InboundHttp2ConfigurationBuilder(int port,String name) {
            this.port=port;
            this.name=name;
        }

        public InboundHttp2Configuration build() {
            return new InboundHttp2Configuration(this);
        }
        
    }

}
