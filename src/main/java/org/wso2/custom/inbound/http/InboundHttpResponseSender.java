package org.wso2.custom.inbound.http;

import org.apache.log4j.Logger;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.custom.inbound.InboundHttp2SourceHandler;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;

/**
 * Created by chanakabalasooriya on 8/29/16.
 */
public class InboundHttpResponseSender implements InboundResponseSender {
    private Logger log = Logger.getLogger(InboundHttpResponseSender.class);
    private InboundHttpSourceHandler sourceHandler;
    public InboundHttpResponseSender(InboundHttpSourceHandler sourceHandler) {
        this.sourceHandler = sourceHandler;
    }
    public void sendBack(MessageContext messageContext) {
        log.debug("sendBack method is called");
        if(messageContext!=null){
            try {
                RelayUtils.buildMessage(((Axis2MessageContext)messageContext).getAxis2MessageContext());
            } catch (IOException iEx) {
                log.error("Error while building the message", iEx);
            } catch (XMLStreamException ex) {
                log.error("Failed to convert message to specified output format", ex);
            }
            sourceHandler.sendResponse(messageContext);
        }
        else {
            log.debug("send back message is null");
        }
    }
}
