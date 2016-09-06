
package org.wso2.custom.inbound;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.inbound.InboundProcessorParams;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericInboundListener;
import org.wso2.custom.inbound.common.InboundHttp2Constants;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.Properties;

public final class InboundHttp2Endpoint extends GenericInboundListener {

    private static final Log log = LogFactory.getLog(InboundHttp2Endpoint.class);

    private static InboundHttp2Configuration configuration;
    /**
     * Constructor
     *
     * @param params  Parameters of the inbound endpoint
     */
    public InboundHttp2Endpoint(InboundProcessorParams params) {
        super(params);
        this.configuration=(new InboundHttp2Configuration.InboundHttp2ConfigurationBuilder(this.params)).build();

        log.info("Initialized the custom listening inbound endpoint.:params:"+params);
    }

    /**
     * Initialize the listening
     */
    public void init() {
        // this.name=super.params.getName();
        //Starting HTTP
        if(configuration==null){
            this.configuration=(new InboundHttp2Configuration.InboundHttp2ConfigurationBuilder(super.params)).build();
        }

        EventLoopGroup group = new NioEventLoopGroup();
        if (!configuration.isSSL()) {
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.option(ChannelOption.SO_BACKLOG, 1024);
                b.group(group)
                        .channel(NioServerSocketChannel.class)
                        .handler(new LoggingHandler(LogLevel.INFO))
                        .childHandler(new InboundHttp2ServerInitializer(null,configuration));

                Channel ch = b.bind(configuration.getPort()).sync().channel();

                log.info("Http2 Inbound started on Port : " + configuration.getPort());

            } catch (InterruptedException e) {
                log.error("Closing Http2 Inbound on Port : " + configuration.getPort());
            }
            log.info("Http2 Inbound Initialization Completed for http.....");
        } else {
            //Starting HTTPS
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.option(ChannelOption.SO_BACKLOG, 1024);
                b.group(group)
                        .channel(NioServerSocketChannel.class)
                        .handler(new LoggingHandler(LogLevel.INFO))
                        .childHandler(new InboundHttp2ServerInitializer(getSSLContext(),configuration));

                Channel ch = b.bind(configuration.getPort()).sync().channel();

                log.info("Http2 Inbound started on Port : " + configuration.getPort());

            } catch (InterruptedException e) {
                log.error("Closing Http2 Inbound on Port : " + configuration.getPort());
            }
            log.info("Http2 Inbound Initialization Completed for https.....");
        }
    }

    /**
     * Stopping the inbound endpoint
     */
    public void destroy() {
        log.info("Inside the destroy method, destroying the listening inbound ...");
    }

    private SslContext getSSLContext() {
        SslContext sslContext = null;
        SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
        try {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                    .sslProvider(provider)
                /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                 * Please refer to the HTTP/2 specification for cipher requirements. */
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1))
                    .build();
        } catch (CertificateException e) {
            e.printStackTrace();
        } catch (SSLException e) {
            e.printStackTrace();
        }
        return sslContext;
    }

}
