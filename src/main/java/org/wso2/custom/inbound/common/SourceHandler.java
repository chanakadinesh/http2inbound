package org.wso2.custom.inbound.common;

/**
 * Created by chanakabalasooriya on 8/31/16.
 */
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
public interface SourceHandler {
    public void sendResponse(MessageContext synCtx) throws AxisFault;
}
