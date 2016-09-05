package org.wso2.custom.inbound;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameTypes;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * Created by chanakabalasooriya on 8/31/16.
 */
public class HTTP2SourceRequest {
    private Logger log = Logger.getLogger(HTTP2SourceRequest.class);

    private int streamID;
    private ChannelHandlerContext channel;
    private HashMap<Byte,Http2Frame> frames=new HashMap<Byte,Http2Frame>();
    private Map<String,String> headers=new TreeMap<String, String>();
    public HTTP2SourceRequest(int streamID,ChannelHandlerContext channel) {
        //log.info("HTTP2Request created for stram id:"+streamID);
        this.streamID = streamID;
        this.channel=channel;
    }

    public ChannelHandlerContext getChannel() {
        return channel;
    }

    public void setChannel(ChannelHandlerContext channel) {
        this.channel = channel;
    }

    public Map<String, String> getHeaders() {

        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getMethod(){
        if(headers.containsKey(":method")){
            return headers.get(":method");
        }else{
            return null;
        }
    }
    public String getHeader(String key){
        if(headers.containsKey(key)){
            return headers.get(key);
        }else{
            return null;
        }
    }
    public String getUri(){
        if(headers.containsKey(":path")){
            return "/"+headers.get(":path");
        }else{
            return null;
        }
    }

    public boolean addFrame(Byte frameType,Http2Frame frame) {
        if (!frames.containsKey(frameType)){
            frames.put(frameType, frame);
            return true;
        }else
            return false;
    }

    public Http2Frame getFrame(byte frameType){
        if(frames.containsKey(frameType)){
            return frames.get(frameType);
        }else{
            return null;
        }
    }

    @Override
    public String toString(){
        String name="";
        name+="Stream Id:"+streamID+"/n";
        if(headers.size()>0) {
            name += "Headers:/n";
            for (Map.Entry h : headers.entrySet()) {
                name += h.getKey() + ":" + h.getValue() + "/n";
            }
        }
        if(frames.size()>0){
            name+="Frames : /n";
            for (Map.Entry h:frames.entrySet()) {
                name+=h.getKey().toString()+":"+h.getValue()+"/n";
            }
        }
        return name;
    }
}
