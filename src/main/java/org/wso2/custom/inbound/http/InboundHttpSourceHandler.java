package org.wso2.custom.inbound.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.util.CharsetUtil;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.inbound.InboundEndpoint;
import org.wso2.custom.inbound.HTTP2SourceRequest;
import org.wso2.custom.inbound.InboundHttp2Configuration;
import org.wso2.custom.inbound.InboundHttp2ResponseSender;
import org.wso2.custom.inbound.common.InboundHttp2Constants;
import org.wso2.custom.inbound.common.InboundMessageHandler;
import org.wso2.custom.inbound.common.SourceHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class InboundHttpSourceHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements SourceHandler{
    private static final Log log = LogFactory.getLog(InboundHttpSourceHandler.class);
    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Hello World", CharsetUtil.UTF_8));
    private ChannelHandlerContext channelCtx;
    private boolean keepAlive;
    private InboundMessageHandler messageHandler;
    private InboundHttp2ResponseSender responseSender;
    private InboundHttp2Configuration config;
    public InboundHttpSourceHandler(InboundHttp2Configuration config) {
        this.config=config;
        responseSender=new InboundHttp2ResponseSender(this);
        messageHandler=new InboundMessageHandler(responseSender,config);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        this.channelCtx=ctx;
        if (HttpUtil.is100ContinueExpected(req)) {
            channelCtx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
        }
        HTTP2SourceRequest h2Request=wrapToHttp2SourceRequest(req);
        messageHandler.processRequest(h2Request);
    }
    public void sendResponse(MessageContext msgCtx) {

        log.info("sendding http response");
        ByteBuf content =  channelCtx.alloc().buffer();
        content.writeBytes(msgCtx.getEnvelope().toString().getBytes());

        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
        response.headers().add(CONTENT_TYPE,"text/xml");
        response.headers().add(CONTENT_LENGTH,response.content().readableBytes());
        if (!keepAlive) {
            channelCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            channelCtx.writeAndFlush(response);
        }
        log.info("Http response sent successfully");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    public HTTP2SourceRequest wrapToHttp2SourceRequest(FullHttpRequest req){
        HTTP2SourceRequest http2Req=new HTTP2SourceRequest(1,channelCtx);
        List<Map.Entry<String,String>> headers=req.headers().entries();
        for (Map.Entry header:headers) {
            http2Req.setHeader(header.getKey().toString(),header.getValue().toString());
        }
        http2Req.addFrame(Http2FrameTypes.DATA,new DefaultHttp2DataFrame(req.content()));
        return http2Req;
    }

}
