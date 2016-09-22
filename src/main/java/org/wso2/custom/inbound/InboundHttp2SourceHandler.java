package org.wso2.custom.inbound;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
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
import org.apache.axiom.util.UIDGenerator;
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
import org.apache.http.HttpInetConnection;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.reactor.ssl.SSLIOSession;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundEndpoint;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.rest.RESTRequestHandler;
import org.apache.synapse.transport.nhttp.HttpCoreRequestResponseTransport;
import org.apache.synapse.transport.nhttp.NHttpConfiguration;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.nhttp.util.RESTUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.SourceRequest;
import org.apache.synapse.transport.passthru.config.PassThroughConfiguration;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.wso2.carbon.inbound.endpoint.protocol.http.InboundHttpConstants;
import org.wso2.carbon.inbound.endpoint.protocol.http.InboundHttpResponseSender;
import org.wso2.carbon.inbound.endpoint.protocol.http.management.HTTPEndpointManager;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.custom.inbound.common.InboundHttp2Constants;
import org.wso2.custom.inbound.common.InboundMessageHandler;
import org.wso2.custom.inbound.common.SourceHandler;

import javax.net.ssl.SSLPeerUnverifiedException;
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
   // static final ByteBuf RESPONSE_BYTES = unreleasableBuffer(copiedBuffer("Inbound Endpoint", CharsetUtil.UTF_8));
   // public enum ResponseType {RST_STREAM};
    private InboundMessageHandler messageHandler;
    private InboundHttp2ResponseSender responseSender;
  //  private ChannelHandlerContext channelCtx;
    private final InboundHttp2Configuration config;
    private HashMap<Integer,HTTP2SourceRequest> streams=new HashMap<Integer,HTTP2SourceRequest>();
    private Map<String, String> headerMap
            = new TreeMap<String, String>(new Comparator<String>() {
        public int compare(String o1, String o2) {
            return o1.compareToIgnoreCase(o2);
        }
    });
    //private InboundHttp2Configuration config=new InboundHttp2Configuration.InboundHttp2ConfigurationBuilder()

    public InboundHttp2SourceHandler(InboundHttp2Configuration config) {
        this.config=config;

    }

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
        //this.channelCtx = ctx;
        this.responseSender = new InboundHttp2ResponseSender(this);
        this.messageHandler=new InboundMessageHandler(this.responseSender,this.config);
    }

    public void onDataRead(ChannelHandlerContext ctx, Http2DataFrame data) throws Exception {


      //  headerMap=request.getHeaders();
        if (data.isEndStream()) {
            int streamId=data.streamId();
            HTTP2SourceRequest request=null;
            request=streams.get(streamId);
            request.setChannel(ctx);

            request.addFrame(Http2FrameTypes.DATA,data);
            messageHandler.processRequest(request);
            streams.remove(request.getStreamID());
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
            request.setHeader(header.toString(), headers.headers().get(header).toString());
        }
        if (headers.isEndStream() && !r_headers.containsKey("http2-settings")){
            messageHandler.processRequest(request);
            streams.remove(request.getStreamID());
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
        ChannelHandlerContext channel=(ChannelHandlerContext) msgCtx.getProperty("stream-channel");

        ByteBuf content = channel.alloc().buffer();
        content.writeBytes(msgCtx.getEnvelope().toString().getBytes());

        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());


        channel.write(new DefaultHttp2HeadersFrame(headers));
        channel.writeAndFlush(new DefaultHttp2DataFrame(content, true));
    }


}
