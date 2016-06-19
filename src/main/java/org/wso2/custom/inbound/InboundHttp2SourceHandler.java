package org.wso2.custom.inbound;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.http.HttpHeaders;
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

@Sharable
public class InboundHttp2SourceHandler extends ChannelDuplexHandler {

    private InboundHttp2ResponseSender responseSender;
    private ChannelHandlerContext channelCtx;

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

    /**
     * If receive a frame with end-of-stream set, send a pre-canned response.
     */
    public void onDataRead(ChannelHandlerContext ctx, Http2DataFrame data) throws Exception {
        String endpointName = "Http2Inbound";
        String contentType = "text/xml";
        if (data.isEndStream()) {
            System.out.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^Object Instance : " + this.hashCode());
            System.out.println("RequestPayload : " + new String(ByteBufUtil.getBytes(data.content())));


            MessageContext synCtx = getSynapseMessageContext("carbon.super");
            InboundEndpoint endpoint = synCtx.getConfiguration().getInboundEndpoint(endpointName);

            if (endpoint == null) {
                System.out.println("Cannot find deployed inbound endpoint " + endpointName + "for process request");
                return;
            }

            org.apache.axis2.context.MessageContext axis2MsgCtx =
                    ((org.apache.synapse.core.axis2.Axis2MessageContext) synCtx)
                            .getAxis2MessageContext();

            Builder builder = null;
            if (contentType == null) {
                System.out.println("No content type specified. Using SOAP builder.");
                builder = new SOAPBuilder();
            } else {
                int index = contentType.indexOf(';');
                String type = index > 0 ? contentType.substring(0, index)
                                        : contentType;
                try {
                    builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
                } catch (AxisFault axisFault) {
                    System.out.println("Error while creating message builder :: "
                              + axisFault.getMessage());
                }
                if (builder == null) {
                    System.out.println("No message builder found for type '" + type
                                  + "'. Falling back to SOAP.");
                    builder = new SOAPBuilder();
                }
            }

            OMElement documentElement = null;
            InputStream in = new AutoCloseInputStream(new ByteArrayInputStream(ByteBufUtil.getBytes(data.content())));
            documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            synCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            injectToSequence(synCtx, endpoint);

//            ByteBuf content = ctx.alloc().buffer();
//            content.writeBytes(RESPONSE_BYTES);
//            ByteBufUtil.writeAscii(content, " - via HTTP/2");
//            sendResponse(ctx, content);

        }
    }

    private void injectToSequence(org.apache.synapse.MessageContext synCtx,
                                  InboundEndpoint endpoint) {
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
        System.out.println("+++++++ injecting message to sequence : " + endpoint.getInjectingSeq());
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
        System.out.println("+++++++++ RequestHeaders " + headers.toString());
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
        ((Axis2MessageContext)synCtx).getAxis2MessageContext().setProperty(SynapseConstants.IS_INBOUND, true);
        synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
        ((Axis2MessageContext)synCtx).getAxis2MessageContext()
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

    public InboundHttp2ResponseSender getResponseSender() {
        return responseSender;
    }

    public ChannelHandlerContext getChannelCtx() {
        return channelCtx;
    }

}
