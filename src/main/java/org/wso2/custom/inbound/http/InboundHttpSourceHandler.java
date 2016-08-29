package org.wso2.custom.inbound.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
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
import org.wso2.custom.inbound.InboundHttp2Constants;
import org.wso2.custom.inbound.common.InboundMessageHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.wso2.custom.inbound.InboundHttp2Constants.ENDPOINT_NAME;

public class InboundHttpSourceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Log log = LogFactory.getLog(InboundHttpSourceHandler.class);
    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Hello World", CharsetUtil.UTF_8));
    private ChannelHandlerContext channelCtx;
    private boolean keepAlive;
    private InboundMessageHandler messageHandler;
    private InboundHttpResponseSender responseSender;
    public InboundHttpSourceHandler() {
        responseSender=new InboundHttpResponseSender(this);
        messageHandler=new InboundMessageHandler(responseSender);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        this.channelCtx=ctx;
        if (HttpUtil.is100ContinueExpected(req)) {
            channelCtx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
        }
        this.keepAlive = HttpUtil.isKeepAlive(req);
        String method = req != null ? req.method().toString() : "";
        if(method.equalsIgnoreCase("POST")){
            MessageContext synCtx = messageHandler.getSynapseMessageContext("carbon.super");
            InboundEndpoint endpoint = synCtx.getConfiguration().getInboundEndpoint(InboundHttp2Constants.ENDPOINT_NAME);

            if (endpoint == null) {
                log.error("Cannot find deployed inbound endpoint " + ENDPOINT_NAME + "for process request");
                return;
            }

            org.apache.axis2.context.MessageContext axis2MsgCtx =
                    ((org.apache.synapse.core.axis2.Axis2MessageContext) synCtx)
                            .getAxis2MessageContext();

            //Select the message builder
            Builder builder = null;
            String contentType =req.headers().get(CONTENT_TYPE);
            if (contentType == null) {
                log.info("No content type specified. Using SOAP builder.");
                builder = new SOAPBuilder();
            } else {
                try {
                    builder = BuilderUtil.getBuilderFromSelector(contentType, axis2MsgCtx);
                } catch (AxisFault axisFault) {
                    log.error("Error while creating message builder :: "
                            + axisFault.getMessage());
                }
                if (builder == null) {
                    log.info("No message builder found for type '" + contentType
                            + "'. Falling back to SOAP.");
                    builder = new SOAPBuilder();
                }
            }

            //Inject to the sequence
            InputStream in = new AutoCloseInputStream(new ByteArrayInputStream(ByteBufUtil.getBytes(req.content())));
            OMElement documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            synCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            messageHandler.injectToSequence(synCtx, endpoint);
        }
     //   log.info("RequestHeaders : " + (Arrays.asList(req.headers().entries().toArray())));
     //   log.info("RequestPayload : " + new String(ByteBufUtil.getBytes(req.content())));
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
}
