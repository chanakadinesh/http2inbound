package org.wso2.custom.inbound;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.passthru.util.RelayUtils;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;

public class InboundHttp2ResponseSender implements InboundResponseSender {

    private static final Log log = LogFactory.getLog(InboundHttp2ResponseSender.class);
    private InboundHttp2SourceHandler sourceHandler;

    public InboundHttp2ResponseSender(InboundHttp2SourceHandler sourceHandler) {
        this.sourceHandler = sourceHandler;
    }

    public void sendBack(MessageContext synCtx) {
        if(synCtx!=null){
            try {
                RelayUtils.buildMessage(((Axis2MessageContext)synCtx).getAxis2MessageContext());
            } catch (IOException iEx) {
                log.error("Error while building the message", iEx);
            } catch (XMLStreamException ex) {
                log.error("Failed to convert message to specified output format", ex);
            }
            sourceHandler.sendResponse(synCtx);
        }
        else {
            log.debug("send back message is null");
        }
    }
}
