package org.wso2.custom.inbound;

import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.custom.inbound.common.SourceHandler;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;

public class InboundHttp2ResponseSender implements InboundResponseSender {

    private static final Log log = LogFactory.getLog(InboundHttp2ResponseSender.class);
    private SourceHandler sourceHandler;

    public InboundHttp2ResponseSender(SourceHandler sourceHandler) {
        this.sourceHandler = sourceHandler;
    }

    public void sendBack(MessageContext synCtx) {
        if(synCtx!=null){
            try {
                RelayUtils.buildMessage(((Axis2MessageContext)synCtx).getAxis2MessageContext());
                sourceHandler.sendResponse(synCtx);
            } catch (IOException iEx) {
                log.error("Error while building the message", iEx);
            } catch (XMLStreamException ex) {
                log.error("Failed to convert message to specified output format", ex);
            }
        }
        else {
            log.debug("send back message is null");
        }
    }

}
