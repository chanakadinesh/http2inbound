package org.wso2.custom.inbound;

import io.netty.channel.ChannelHandlerContext;

public class InboundHttp2ChannelContext  {
    private ChannelHandlerContext ctx;
    private String channelIdentifier;

    public InboundHttp2ChannelContext(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.channelIdentifier = ctx.channel().toString();
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return this.ctx;
    }

    public String getChannelIdentifier() {
        return channelIdentifier;
    }

}
