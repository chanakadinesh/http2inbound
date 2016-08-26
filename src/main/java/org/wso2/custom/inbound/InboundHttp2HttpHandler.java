package org.wso2.custom.inbound;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2Codec;
import io.netty.handler.codec.http2.Http2MultiplexCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import org.wso2.custom.inbound.http.InboundHttpHandler;

public class InboundHttp2HttpHandler extends ApplicationProtocolNegotiationHandler {

    private static final int MAX_CONTENT_LENGTH = 2048 * 100;

    protected InboundHttp2HttpHandler() {
        super(ApplicationProtocolNames.HTTP_1_1);
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            ctx.pipeline().addLast(new Http2Codec(true, new InboundHttp2SourceHandler()));
            return;
        }

        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            ctx.pipeline().addLast(new HttpServerCodec(),
                                   new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                                   new InboundHttpHandler());
            return;
        }

        throw new IllegalStateException("unknown protocol: " + protocol);
    }
}
