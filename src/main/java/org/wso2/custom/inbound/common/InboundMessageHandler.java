package org.wso2.custom.inbound.common;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.MessageContextCreatorForAxis2;
import org.apache.synapse.inbound.InboundEndpoint;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.mediators.MediatorFaultHandler;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.inbound.endpoint.osgi.service.ServiceReferenceHolder;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.custom.inbound.InboundHttp2SourceHandler;

/**
 * Created by chanakabalasooriya on 8/29/16.
 */
public class InboundMessageHandler {
    private static final Log log = LogFactory.getLog(InboundMessageHandler.class);
    private InboundResponseSender responseSender;

    public InboundMessageHandler( InboundResponseSender responseSender) {
        this.responseSender=responseSender;
    }

    public void injectToSequence(org.apache.synapse.MessageContext synCtx,
                                  InboundEndpoint endpoint) {

        /*((Axis2MessageContext) synCtx).getAxis2MessageContext().setProperty(org.apache.axis2.context.MessageContext
                .TRANSPORT_HEADERS, headerMap);*/

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

    public org.apache.synapse.MessageContext getSynapseMessageContext(String tenantDomain) throws AxisFault {
        MessageContext synCtx = createSynapseMessageContext(tenantDomain);
        synCtx.setProperty(SynapseConstants.IS_INBOUND, true);
        ((Axis2MessageContext) synCtx).getAxis2MessageContext().setProperty(SynapseConstants.IS_INBOUND, true);
        synCtx.setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
        ((Axis2MessageContext) synCtx).getAxis2MessageContext()
                .setProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER, responseSender);
        return synCtx;
    }

    public org.apache.synapse.MessageContext createSynapseMessageContext(String tenantDomain) throws AxisFault {
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

    public Builder getMessageBuilder(String contentType,org.apache.axis2.context.MessageContext axis2MsgCtx){
        Builder builder=null;
        if (contentType == null) {
            log.info("No content type specified. Using SOAP builder.");
            builder= new SOAPBuilder();
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
        return builder;
    }
    public void injectToMainSequence(org.apache.synapse.MessageContext synCtx,
                                      InboundEndpoint endpoint) {

        SequenceMediator injectingSequence = (SequenceMediator) synCtx.getMainSequence();

        SequenceMediator faultSequence = getFaultSequence(synCtx, endpoint);

        MediatorFaultHandler mediatorFaultHandler = new MediatorFaultHandler(faultSequence);
        synCtx.pushFaultHandler(mediatorFaultHandler);

        /* handover synapse message context to synapse environment for inject it to given
        sequence in synchronous manner*/
        if (log.isDebugEnabled()) {
            log.debug("injecting message to sequence : " + endpoint.getInjectingSeq());
        }
        synCtx.getEnvironment().injectMessage(synCtx, injectingSequence);
    }
}
