package org.wso2.custom.inbound;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Sharable
public class InboundHttp2SourceHandler extends ChannelDuplexHandler {
    private static final Log log = LogFactory.getLog(InboundHttp2SourceHandler.class);
    private static final String ENDPOINT_NAME = "Http2Inbound";
    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Hello World", CharsetUtil.UTF_8));

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
    }

    public void onDataRead(ChannelHandlerContext ctx, Http2DataFrame data) throws Exception {

        if (data.isEndStream()) {
            log.info("RequestPayload : " + new String(ByteBufUtil.getBytes(data.content())));

            MessageContext synCtx = getSynapseMessageContext("carbon.super");
            InboundEndpoint endpoint = synCtx.getConfiguration().getInboundEndpoint(ENDPOINT_NAME);

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
                System.out.println("No content type specified. Using SOAP builder.");
                builder = new SOAPBuilder();
            } else {
                try {
                    builder = BuilderUtil.getBuilderFromSelector(contentType, axis2MsgCtx);
                } catch (AxisFault axisFault) {
                    System.out.println("Error while creating message builder :: "
                                       + axisFault.getMessage());
                }
                if (builder == null) {
                    System.out.println("No message builder found for type '" + contentType
                                       + "'. Falling back to SOAP.");
                    builder = new SOAPBuilder();
                }
            }

            //Inject to the sequence
            InputStream in = new AutoCloseInputStream(new ByteArrayInputStream(ByteBufUtil.getBytes(data.content())));
            OMElement documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            synCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            injectToSequence(synCtx, endpoint);
        }
        if (data.isEndStream()) {
          //  sendResponse(ctx, data.content().retain());
        }
    }

    private void injectToSequence(org.apache.synapse.MessageContext synCtx,
                                  InboundEndpoint endpoint) {

        ((Axis2MessageContext) synCtx).getAxis2MessageContext().setProperty(org.apache.axis2.context.MessageContext
                                                                                    .TRANSPORT_HEADERS, headerMap);

        SequenceMediator injectingSequence = null;
        if (endpoint.getInjectingSeq() != null) {
            injectingSequence = (SequenceMediator) synCtx.getSequence(endpoint.getInjectingSeq());
        }
        if (injectingSequence == null) {
            injectingSequence = (SequenceMediator) synCtx.getMainSequence();
        }
        SequenceMediator faultSequence = getFaultSequence(synCtx, endpoint);
        MediatorFaultHandler mediatorFaultHandler = new MediatorFaultHandler(faultSequence);
        synCtx.pushFaultHandler(mediatorFaultHandler);

        log.info("Injecting message to sequence : " + endpoint.getInjectingSeq());
        synCtx.setProperty("inbound.endpoint.name", endpoint.getName());
        synCtx.getEnvironment().injectMessage(synCtx, injectingSequence);
    }

    private SequenceMediator getFaultSequence(org.apache.synapse.MessageContext synCtx,
                                              InboundEndpoint endpoint) {
        SequenceMediator faultSequence = null;
        if (endpoint.getOnErrorSeq() != null) {
            faultSequence = (SequenceMediator) synCtx.getSequence(endpoint.getOnErrorSeq());
        }
        if (faultSequence == null) {
            faultSequence = (SequenceMediator) synCtx.getFaultSequence();
        }
        return faultSequence;
    }

    /**
     * If receive a frame with end-of-stream set, send a pre-canned response.
     */
    public void onHeadersRead(ChannelHandlerContext ctx, Http2HeadersFrame headers)
            throws Exception {
        log.info("Header detected"+ headers.headers());
        if (headers.isEndStream()){
            if (headers.headers().contains("http2-settings")) {
                log.info("Settings Frame Headers " + headers.headers().toString());
                return;
            }
            Set<CharSequence> headerSet = headers.headers().names();
            for (CharSequence header : headerSet) {
                log.info("Header " + header + " : " + headers.headers().get(header));
                if (header.charAt(0) != ':') {
                    headerMap.put(header.toString(), headers.headers().get(header).toString());
                }
            }
        }
    }
    private void sendResponse(ChannelHandlerContext ctx, ByteBuf payload) {
        System.out.println("sendRespond triggered");
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

    private org.apache.synapse.MessageContext getSynapseMessageContext(String tenantDomain) throws AxisFault {
        MessageContext synCtx = createSynapseMessageContext(tenantDomain);
        synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
        ((Axis2MessageContext) synCtx).getAxis2MessageContext().setProperty(SynapseConstants.IS_INBOUND, true);
        synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
        ((Axis2MessageContext) synCtx).getAxis2MessageContext()
                .setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
        return synCtx;
    }

    private org.apache.synapse.MessageContext createSynapseMessageContext(String tenantDomain) throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MsgCtx = createAxis2MessageContext();
        ServiceContext svcCtx = new ServiceContext();
        OperationContext opCtx = new OperationContext(new InOutAxisOperation(), svcCtx);
        axis2MsgCtx.setServiceContext(svcCtx);
        axis2MsgCtx.setOperationContext(opCtx);

        if (!tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
            ConfigurationContext tenantConfigCtx =
                    TenantAxisUtils.getTenantConfigurationContext(tenantDomain,
                                                                  axis2MsgCtx.getConfigurationContext());
            axis2MsgCtx.setConfigurationContext(tenantConfigCtx);
            axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, tenantDomain);
        } else {
            axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN,
                                    MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        }
        SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
        SOAPEnvelope envelope = fac.getDefaultEnvelope();
        axis2MsgCtx.setEnvelope(envelope);
        return MessageContextCreatorForAxis2.getSynapseMessageContext(axis2MsgCtx);
    }

    private static org.apache.axis2.context.MessageContext createAxis2MessageContext() {
        org.apache.axis2.context.MessageContext axis2MsgCtx = new org.apache.axis2.context.MessageContext();
        axis2MsgCtx.setMessageID(UIDGenerator.generateURNString());
        axis2MsgCtx.setConfigurationContext(ServiceReferenceHolder.getInstance().getConfigurationContextService()
                                                    .getServerConfigContext());
        return axis2MsgCtx;
    }

    public ChannelHandlerContext getChannelCtx() {
        return channelCtx;
    }

}
