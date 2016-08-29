package org.wso2.custom.inbound;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.wso2.custom.inbound.InboundHttp2Constants.ENDPOINT_NAME;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.*;
import io.netty.util.CharsetUtil;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.MessageContextCreatorForAxis2;
import org.apache.synapse.inbound.InboundEndpoint;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.mediators.MediatorFaultHandler;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.inbound.endpoint.osgi.service.ServiceReferenceHolder;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.custom.inbound.common.InboundMessageHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Sharable
public class InboundHttp2SourceHandler extends ChannelDuplexHandler {
    private static final Log log = LogFactory.getLog(InboundHttp2SourceHandler.class);
    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Inbound Endpoint", CharsetUtil.UTF_8));
    public enum ResponseType {RST_STREAM};
    private InboundMessageHandler messageHandler;
    private InboundHttp2ResponseSender responseSender;
    private ChannelHandlerContext channelCtx;
    private Map<String, String> headerMap
            = new TreeMap<String, String>(new Comparator<String>() {
        public int compare(String o1, String o2) {
            return o1.compareToIgnoreCase(o2);
        }
    });


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            onHeadersRead(ctx, (Http2HeadersFrame) msg);
        } else if (msg instanceof Http2DataFrame) {
            onDataRead(ctx, (Http2DataFrame) msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.channelCtx = ctx;
        this.responseSender = new InboundHttp2ResponseSender(this);
        this.messageHandler=new InboundMessageHandler(this.responseSender);
    }

    public void onDataRead(ChannelHandlerContext ctx, Http2DataFrame data) throws Exception {

        if (data.isEndStream()) {
          //  log.info("RequestPayload : " + new String(ByteBufUtil.getBytes(data.content())));

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
            String contentType = headerMap.get("content-type");
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
            InputStream in = new AutoCloseInputStream(new ByteArrayInputStream(ByteBufUtil.getBytes(data.content())));
            OMElement documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            synCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            messageHandler.injectToSequence(synCtx, endpoint);
        }
    }





    /**
     * If receive a frame with end-of-stream set, send a pre-canned response.
     */
    public void onHeadersRead(ChannelHandlerContext ctx, Http2HeadersFrame headers)
            throws Exception {
    //    log.info("Header detected"+ headers.headers());
        if (headers.isEndStream()){
            if (headers.headers().contains("http2-settings")) {
        //        log.debug("Settings Frame Headers " + headers.headers().toString());
                return;
            }
            Set<CharSequence> headerSet = headers.headers().names();
            for (CharSequence header : headerSet) {
        //        log.debug("Header " + header + " : " + headers.headers().get(header));
                if (header.charAt(0) != ':') {
                    headerMap.put(header.toString(), headers.headers().get(header).toString());
                }
            }
           if(headers.headers().get(":method").toString().equalsIgnoreCase("GET")){
        //       log.debug("Responding for a GET method");
              //  sendResponse(ctx,ResponseType.RST_STREAM,null);
               ByteBuf content = ctx.alloc().buffer();
               content.writeBytes(RESPONSE_BYTES.duplicate());
               ByteBufUtil.writeAscii(content, " - via HTTP/2");
               sendResponse(ctx, content);
           }
        }
    }
    private void sendResponse(ChannelHandlerContext ctx, ByteBuf payload) {
     //   System.out.println("sendRespond triggered");
        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
        ctx.write(new DefaultHttp2HeadersFrame(headers));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(payload, true));
    }

    public void sendResponse(MessageContext msgCtx) {
        ByteBuf content = this.getChannelCtx().alloc().buffer();
        content.writeBytes(msgCtx.getEnvelope().toString().getBytes());

        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
        this.getChannelCtx().write(new DefaultHttp2HeadersFrame(headers));
        this.getChannelCtx().writeAndFlush(new DefaultHttp2DataFrame(content, true));
    }





    public ChannelHandlerContext getChannelCtx() {
        return channelCtx;
    }

}
