
package org.wso2.custom.inbound;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.inbound.InboundProcessorParams;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericInboundListener;

public final class InboundHttp2Endpoint extends GenericInboundListener {

    private static final Log log = LogFactory.getLog(InboundHttp2Endpoint.class);

    static final boolean SSL = System.getProperty("ssl") != null;

    static final int PORT = Integer.parseInt(System.getProperty("port", SSL? "8443" : "8888"));

    /**
     * Constructor
     *
     * @param params  Parameters of the inbound endpoint
     */
    public InboundHttp2Endpoint(InboundProcessorParams params) {
        super(params);
        log.info("Initialized the custom listening inbound endpoint.");
    }

    /**
     * Initialize the listening
     */
    public void init() {
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                EventLoopGroup group = new NioEventLoopGroup();
                try {
                    ServerBootstrap b = new ServerBootstrap();
                    b.option(ChannelOption.SO_BACKLOG, 1024);
                    b.group(group)
                            .channel(NioServerSocketChannel.class)
                            .handler(new LoggingHandler(LogLevel.INFO))
                            .childHandler(new InboundHttp2ServerInitializer(null));

                    Channel ch = b.bind(PORT).sync().channel();

                    log.info("Http2 Inbound started on Port : " + PORT);

                    ch.closeFuture().sync();
                } catch (InterruptedException e) {
                    log.error("Closing Http2 Inbound...");
                } finally {
                    group.shutdownGracefully();
                }
            }
        });
        serverThread.start();
        log.info("Http2 Inbound Initialization Completed.....");
    }

    /**
     * Stopping the inbound endpoint
     */
    public void destroy() {
        log.info("Inside the destroy method, destroying the listening inbound ...");
    }

}
