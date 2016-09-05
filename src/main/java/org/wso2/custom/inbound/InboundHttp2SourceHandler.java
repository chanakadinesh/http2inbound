package org.wso2.custom.inbound;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.wso2.custom.inbound.common.InboundHttp2Constants.ENDPOINT_NAME;
import static org.wso2.custom.inbound.common.InboundHttp2Constants.TENANT_DOMAIN;

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
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.deployment.AxisConfigBuilder;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.dispatchers.RequestURIBasedDispatcher;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPTransportUtils;
import org.apache.axis2.util.Utils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.transport.http.PatternBuilder;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundEndpoint;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.rest.RESTRequestHandler;
import org.apache.synapse.transport.nhttp.NHttpConfiguration;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.nhttp.util.RESTUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.wso2.carbon.inbound.endpoint.protocol.http.InboundHttpConstants;
import org.wso2.carbon.inbound.endpoint.protocol.http.InboundHttpResponseSender;
import org.wso2.carbon.inbound.endpoint.protocol.http.management.HTTPEndpointManager;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.custom.inbound.common.InboundHttp2Constants;
import org.wso2.custom.inbound.common.InboundMessageHandler;
import org.wso2.custom.inbound.common.SourceHandler;

import javax.xml.parsers.FactoryConfigurationError;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Sharable
public class InboundHttp2SourceHandler extends ChannelDuplexHandler implements SourceHandler {
    private static final Log log = LogFactory.getLog(InboundHttp2SourceHandler.class);
    static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Inbound Endpoint", CharsetUtil.UTF_8));
    public enum ResponseType {RST_STREAM};
    private InboundMessageHandler messageHandler;
    private InboundHttp2ResponseSender responseSender;
    private ChannelHandlerContext channelCtx;
    private HashMap<Integer,HTTP2SourceRequest> streams=new HashMap<Integer,HTTP2SourceRequest>();
    private Map<String, String> headerMap
            = new TreeMap<String, String>(new Comparator<String>() {
        public int compare(String o1, String o2) {
            return o1.compareToIgnoreCase(o2);
        }
    });
    //private InboundHttp2Configuration config=new InboundHttp2Configuration.InboundHttp2ConfigurationBuilder()
    public final Pattern dispatchPattern=Pattern.compile(".*");
    private Matcher patternMatcher;

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


      //  headerMap=request.getHeaders();
        if (data.isEndStream()) {
            int streamId=data.streamId();
            HTTP2SourceRequest request=null;
            request=streams.get(streamId);
            request.setChannel(ctx);

            request.addFrame(Http2FrameTypes.DATA,data);
            processRequest(streamId);
        }
    }
    /**
     * Headers frames sends to start a new stream
     */
    public void onHeadersRead(ChannelHandlerContext ctx, Http2HeadersFrame headers)
            throws Exception {

        int streamId=headers.streamId();
        HTTP2SourceRequest request=null;
        if(streams.containsKey(streamId)){
            request=streams.get(streamId);
        }else{
            request=new HTTP2SourceRequest(streamId,ctx);
            streams.put(streamId,request);
        }
        Map r_headers=request.getHeaders();
        Set<CharSequence> headerSet = headers.headers().names();
        for (CharSequence header : headerSet) {
            r_headers.put(header.toString(), headers.headers().get(header).toString());
        }
        if (headers.isEndStream() && !r_headers.containsKey("http2-settings")){
            //If stream ends with header frame it should be GET, DELETE call
            /*if (headers.headers().contains("http2-settings")) {
                return;
            }*/
            processRequest(streamId);
        }
    }

    //This method will be removed later
    private void sendResponse(ChannelHandlerContext ctx, ByteBuf payload) {
     //   System.out.println("sendRespond triggered");
        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
        ctx.write(new DefaultHttp2HeadersFrame(headers));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(payload, true));
    }

    public synchronized void sendResponse(MessageContext msgCtx) {
        ByteBuf content = this.getChannelCtx().alloc().buffer();
        content.writeBytes(msgCtx.getEnvelope().toString().getBytes());

        ChannelHandlerContext channel=(ChannelHandlerContext) msgCtx.getProperty("stream-channel");
        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());


        channel.write(new DefaultHttp2HeadersFrame(headers));
        channel.writeAndFlush(new DefaultHttp2DataFrame(content, true));
    }


    /**
     * processing the request at the end of the stream
     * @param streamID
     * @throws AxisFault
     */
    private void processRequest(int streamID) throws AxisFault{

            HTTP2SourceRequest request=streams.get(streamID);

            MessageContext synCtx = messageHandler.getSynapseMessageContext(TENANT_DOMAIN);

            InboundEndpoint endpoint = synCtx.getConfiguration().getInboundEndpoint(InboundHttp2Constants.ENDPOINT_NAME);
            if (endpoint == null) {
                log.error("Cannot find deployed inbound endpoint " + ENDPOINT_NAME + "for process request");
                return;
            }

            org.apache.axis2.context.MessageContext axis2MsgCtx =
                    ((org.apache.synapse.core.axis2.Axis2MessageContext) synCtx)
                            .getAxis2MessageContext();
           // axis2MsgCtx.setProperty("stream-id",streamID);
            synCtx.setProperty("stream-id",streamID);
            synCtx.setProperty("stream-channel",request.getChannel());
            axis2MsgCtx.setProperty("OutTransportInfo", this);
            axis2MsgCtx.setServerSide(true);
            axis2MsgCtx.setProperty("TransportInURL", request.getUri());
            String method=request.getMethod();
            axis2MsgCtx.setIncomingTransportName(request.getHeader(":scheme"));
            processHttpRequestUri(axis2MsgCtx,method,request);
            synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
            synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER,
                    new InboundHttp2ResponseSender(this));
            synCtx.setWSAAction(request.getHeader(InboundHttpConstants.SOAP_ACTION));

            if (!isRESTRequest(axis2MsgCtx, method)) {
                if (request.getFrame(Http2FrameTypes.DATA)!=null) {
                    processEntityEnclosingRequest(axis2MsgCtx, false,request);
                } else {
                    processNonEntityEnclosingRESTHandler(null, axis2MsgCtx, false,request);
                }
            } else {
                AxisOperation axisOperation = ((Axis2MessageContext) synCtx).getAxis2MessageContext().getAxisOperation();
                ((Axis2MessageContext) synCtx).getAxis2MessageContext().setAxisOperation(null);
                String contentTypeHeader = request.getHeader(HTTP.CONTENT_TYPE);
                SOAPEnvelope soapEnvelope = handleRESTUrlPost(contentTypeHeader,axis2MsgCtx,request);
                processNonEntityEnclosingRESTHandler(soapEnvelope, axis2MsgCtx, false,request);
                ((Axis2MessageContext) synCtx).getAxis2MessageContext().setAxisOperation(axisOperation);

            }
            boolean continueDispatch = true;
            if (dispatchPattern != null) {
                patternMatcher = dispatchPattern.matcher(request.getUri());
                if (!patternMatcher.matches()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Requested URI does not match given dispatch regular expression.");
                    }
                    continueDispatch = false;
                }
            }

            if (continueDispatch && dispatchPattern != null) {

                boolean processedByAPI = false;
                RESTRequestHandler restHandler=new RESTRequestHandler();
                // Trying to dispatch to an API
                processedByAPI = restHandler.process(synCtx);
                if (log.isDebugEnabled()) {
                    log.debug("Dispatch to API state : enabled, Message is "
                            + (!processedByAPI ? "NOT" : "") + "processed by an API");
                }

                if (!processedByAPI) {
                    //check the validity of message routing to axis2 path
                    boolean isAxis2Path = isAllowedAxis2Path(synCtx,request,axis2MsgCtx);

                    if (isAxis2Path) {
                        //create axis2 message context again to avoid settings updated above
                     //   axis2MsgCtx = messageHandler.createMessageContext(null, request);

                        //processHttpRequestUri(axis2MsgCtx, method,request);

                        //set inbound properties for axis2 context
                        //setInboundProperties(axis2MsgCtx);

                        /*if (!isRESTRequest(axis2MsgContext, method)) {
                            if (request.isEntityEnclosing()) {
                                processEntityEnclosingRequest(axis2MsgContext, isAxis2Path);
                            } else {
                                processNonEntityEnclosingRESTHandler(null, axis2MsgContext, isAxis2Path);
                            }
                        } else {
                            String contentTypeHeader = request.getHeaders().get(HTTP.CONTENT_TYPE);
                            SOAPEnvelope soapEnvelope = handleRESTUrlPost(contentTypeHeader);
                            processNonEntityEnclosingRESTHandler(soapEnvelope,axis2MsgContext,true);
                        }*/
                    } else {
                        //this case can only happen regex exists and it DOES match
                        //BUT there is no api or proxy found message to be injected
                        //should be routed to the main sequence instead inbound defined sequence
                        messageHandler.injectToMainSequence(synCtx, endpoint);
                    }
                }
            } else if (continueDispatch && dispatchPattern == null) {
                // else if for clarity compiler will optimize
                messageHandler.injectToSequence(synCtx, endpoint);
            } else {
                //this case can only happen regex exists and it DOES NOT match
                //should be routed to the main sequence instead inbound defined sequence
                messageHandler.injectToMainSequence(synCtx, endpoint);
            }

           // messageHandler.injectToSequence(synCtx, endpoint);

        streams.remove(streamID);
    }


    public void processNonEntityEnclosingRESTHandler(SOAPEnvelope soapEnvelope, org.apache.axis2.context.MessageContext msgContext, boolean injectToAxis2Engine,HTTP2SourceRequest request) {
        String soapAction = request.getHeader("soapaction");
        if(soapAction != null && soapAction.startsWith("\"") && soapAction.endsWith("\"")) {
            soapAction = soapAction.substring(1, soapAction.length() - 1);
        }

        msgContext.setSoapAction(soapAction);
        msgContext.setTo(new EndpointReference("/"+request.getHeader(":path")));
        msgContext.setServerSide(true);
        msgContext.setDoingREST(true);
        if(request.getFrame(Http2FrameTypes.DATA)==null) {
            msgContext.setProperty("NO_ENTITY_BODY", Boolean.TRUE);
        }

        try {
            if(soapEnvelope == null) {
                msgContext.setEnvelope((new SOAP11Factory()).getDefaultEnvelope());
            } else {
                msgContext.setEnvelope(soapEnvelope);
            }

            if(injectToAxis2Engine) {
                AxisEngine.receive(msgContext);
            }
        } catch (AxisFault var6) {
            log.error("AxisFault:"+var6);
            //handleException("Error processing " + this.request.getMethod() + " request for : " + this.request.getUri(), var6);
        } catch (Exception var7) {
            log.error("Exception:"+var7);
           // this.handleException("Error processing " + this.request.getMethod() + " reguest for : " + this.request.getUri() + ". Error detail: " + var7.getMessage() + ". ", var7);
        }

    }

    private boolean isAllowedAxis2Path(org.apache.synapse.MessageContext synapseMsgContext,HTTP2SourceRequest request,org.apache.axis2.context.MessageContext messageContext) {
        boolean isProxy = false;

        String reqUri = request.getUri();
        String tenant = MultitenantUtils.getTenantDomainFromUrl(request.getUri());
        String servicePath = messageContext.getConfigurationContext().getServicePath();

        //for tenants, service path will be appended by tenant name
        if (!reqUri.equalsIgnoreCase(tenant)) {
            servicePath = servicePath + "/t/" + tenant;
        }

        //Get the operation part from the request URL
        // e.g. '/services/TestProxy/' > TestProxy when service path is '/service/' > result 'TestProxy/'
        String serviceOpPart = Utils.getServiceAndOperationPart(reqUri,
                servicePath);
        //if proxy, then check whether it is deployed in the environment
        if (serviceOpPart != null) {
            isProxy = isProxyDeployed(synapseMsgContext, serviceOpPart);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Requested Proxy Service '" + serviceOpPart + "' is not deployed");
            }
        }
        return isProxy;
    }

    private boolean isProxyDeployed(org.apache.synapse.MessageContext synapseContext,
                                    String serviceOpPart) {
        boolean isDeployed = false;

        //extract proxy name from serviceOperation, get the first portion split by '/'
        String proxyName = serviceOpPart.split("/")[0];

        //check whether the proxy is deployed in synapse environment
        if (synapseContext.getConfiguration().getProxyService(proxyName) != null) {
            isDeployed = true;
        }
        return isDeployed;
    }

    public void processEntityEnclosingRequest(org.apache.axis2.context.MessageContext msgContext, boolean injectToAxis2Engine,HTTP2SourceRequest request) {
        try {
            String e = request.getHeaders().get("content-type");
            //e = e != null?e:this.inferContentType();
            String charSetEncoding = null;
            String contentType = null;
            if(e != null) {
                charSetEncoding = BuilderUtil.getCharSetEncoding(e);
                contentType = TransportUtils.getContentType(e, msgContext);
            }

            if(charSetEncoding == null) {
                charSetEncoding = "UTF-8";
            }

            String method =request!= null?request.getMethod().toUpperCase():"";
            msgContext.setTo(new EndpointReference(request.getUri()));
            msgContext.setProperty("HTTP_METHOD_OBJECT", method);
            msgContext.setProperty("CHARACTER_SET_ENCODING", charSetEncoding);
            msgContext.setServerSide(true);
            msgContext.setProperty("ContentType", e);
            msgContext.setProperty("messageType", contentType);
            if(e == null || HTTPTransportUtils.isRESTRequest(e) || this.isRest(e)) {
                msgContext.setProperty("synapse.internal.rest.contentType", contentType);
                msgContext.setDoingREST(true);
                SOAPEnvelope soapAction1 = this.handleRESTUrlPost(e,msgContext,request);
               // msgContext.setProperty("pass-through.pipe", this.request.getPipe());
                this.processNonEntityEnclosingRESTHandler(soapAction1, msgContext, injectToAxis2Engine,request);
                return;
            }

            String soapAction = request.getHeader("soapaction");
            int soapVersion = HTTPTransportUtils.initializeMessageContext(msgContext, soapAction, request.getUri(), e);
            SOAPEnvelope envelope;
            Builder builder=messageHandler.getMessageBuilder(contentType,msgContext);

            //Inject to the sequence
            InputStream in = new AutoCloseInputStream(new ByteArrayInputStream(ByteBufUtil.getBytes(
                    ((Http2DataFrame)request.getFrame(Http2FrameTypes.DATA)).content())));
            OMElement documentElement = builder.processDocument(in, contentType, msgContext);
            //synCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            envelope=TransportUtils.createSOAPEnvelope(documentElement);

            msgContext.setEnvelope(envelope);
          //  msgContext.setProperty("pass-through.pipe", this.request.getPipe());
            if(injectToAxis2Engine) {
                AxisEngine.receive(msgContext);
            }
        } catch (AxisFault var11) {
            log.error("Error processing " + request.getMethod() + " request for : " + request.getUri(), var11);
        } catch (Exception var12) {
            log.error("Error processing " + request.getMethod() + " reguest for : " + request.getUri() + ". Error detail: " + var12.getMessage() + ". ", var12);
        }

    }

    private boolean isRest(String contentType) {
        return contentType != null && contentType.indexOf("text/xml") == -1 && contentType.indexOf("application/soap+xml") == -1;
    }

    public ChannelHandlerContext getChannelCtx() {
        return channelCtx;
    }

    public boolean isRESTRequest(org.apache.axis2.context.MessageContext msgContext, String method) {
        if(msgContext.getProperty("rest_get_delete_invoke") != null && ((Boolean)msgContext.getProperty("rest_get_delete_invoke")).booleanValue()) {
            msgContext.setProperty("HTTP_METHOD_OBJECT", method);
            msgContext.setServerSide(true);
            msgContext.setDoingREST(true);
            return true;
        } else {
            return false;
        }
    }

    public void processHttpRequestUri(org.apache.axis2.context.MessageContext msgContext, String method,HTTP2SourceRequest request) {
        String servicePrefixIndex = "://";
        //ConfigurationContext cfgCtx = this.sourceConfiguration.getConfigurationContext();
        ConfigurationContext cfgCtx=msgContext.getConfigurationContext();
        msgContext.setProperty("HTTP_METHOD", method);
        String oriUri = request.getHeader(":path");
        oriUri="/"+oriUri;
        String restUrlPostfix = NhttpUtil.getRestUrlPostfix(oriUri, cfgCtx.getServicePath());
        String servicePrefix = oriUri.substring(0, oriUri.indexOf(restUrlPostfix));
        if(servicePrefix.indexOf(servicePrefixIndex) == -1) {
            /*HttpInetConnection response = (HttpInetConnection)this.request.getConnection();
            InetAddress entity = response.getLocalAddress();*/
            String schema=request.getHeader(":scheme");
            SocketAddress entity=request.getChannel().channel().localAddress();
            if(entity != null) {
                servicePrefix = schema + servicePrefixIndex.substring(0,servicePrefixIndex.length()-1) + entity+ servicePrefix;
            }
        }

        msgContext.setProperty("SERVICE_PREFIX", servicePrefix);
        msgContext.setTo(new EndpointReference(restUrlPostfix));
        msgContext.setProperty("REST_URL_POSTFIX", restUrlPostfix);
        if("GET".equals(method) || "DELETE".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            msgContext.setProperty(PassThroughConstants.REST_GET_DELETE_INVOKE, true);
        }
           /* HttpResponse response1 = this.sourceConfiguration.getResponseFactory().newHttpResponse(this.request.getVersion(), 200, this.request.getConnection().getContext());
            BasicHttpEntity entity1 = new BasicHttpEntity();
            if(this.request.getVersion().greaterEquals(HttpVersion.HTTP_1_1)) {
                entity1.setChunked(true);
            }

            response1.setEntity(entity1);
            this.httpGetRequestProcessor.process(this.request.getRequest(), response1, msgContext, this.request.getConnection(), this.os, this.isRestDispatching);
        }*/

    }

    public SOAPEnvelope handleRESTUrlPost(String contentTypeHdr, org.apache.axis2.context.MessageContext msgContext,HTTP2SourceRequest request) throws FactoryConfigurationError {
        SOAPEnvelope soapEnvelope = null;
        String contentType = contentTypeHdr != null?TransportUtils.getContentType(contentTypeHdr, msgContext):null;
        if(contentType == null || "".equals(contentType) || "application/x-www-form-urlencoded".equals(contentType)) {
            contentType = contentTypeHdr != null?contentTypeHdr:"application/x-www-form-urlencoded";
            msgContext.setTo(new EndpointReference(request.getUri()));
            msgContext.setProperty("ContentType", contentType);
            String charSetEncoding = BuilderUtil.getCharSetEncoding(contentType);
            msgContext.setProperty("CHARACTER_SET_ENCODING", charSetEncoding);

            try {
                RESTUtil.dispatchAndVerify(msgContext);
            } catch (AxisFault var11) {
                log.error("Error while building message for REST_URL request", var11);
            }

            try {
                boolean e = NHttpConfiguration.getInstance().isReverseProxyMode();
                AxisService axisService = null;
                if(!e) {
                    RequestURIBasedDispatcher isCustomRESTDispatcher = new RequestURIBasedDispatcher();
                    axisService = isCustomRESTDispatcher.findService(msgContext);
                }

                boolean isCustomRESTDispatcher1 = false;
                String requestURI = request.getUri();
                if(requestURI.matches(NHttpConfiguration.getInstance().getRestUriApiRegex()) || requestURI.matches(NHttpConfiguration.getInstance().getRestUriProxyRegex())) {
                    isCustomRESTDispatcher1 = true;
                }

                String multiTenantDispatchService;
                if(!isCustomRESTDispatcher1) {
                    if(axisService == null) {
                        multiTenantDispatchService = NHttpConfiguration.getInstance().getNhttpDefaultServiceName();
                        axisService = msgContext.getConfigurationContext().getAxisConfiguration().getService(multiTenantDispatchService);
                        msgContext.setAxisService(axisService);
                    }
                } else {
                    multiTenantDispatchService = PassThroughConfiguration.getInstance().getRESTDispatchService();
                    axisService = msgContext.getConfigurationContext().getAxisConfiguration().getService(multiTenantDispatchService);
                    msgContext.setAxisService(axisService);
                }
            } catch (AxisFault var12) {
                log.error("Error processing " + request.getMethod() + " request for : " + request.getUri(), var12);
            }

            try {
                soapEnvelope = TransportUtils.createSOAPMessage(msgContext, (InputStream)null, contentType);
            } catch (Exception var10) {
                log.error("Error while building message for REST_URL request");
            }

            msgContext.setProperty("messageType", "application/xml");
        }

        return soapEnvelope;
    }
}
