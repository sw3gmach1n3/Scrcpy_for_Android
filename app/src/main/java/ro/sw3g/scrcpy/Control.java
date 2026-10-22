package ro.sw3g.scrcpy;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

final class Control implements Closeable {
    interface Failure { void error(Exception e); }
    private final OutputStream out;
    private final Failure failure;
    private volatile boolean stopped;
    private final ThreadPoolExecutor sender=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256));
    Control(OutputStream out,Failure failure){this.out=out;this.failure=failure;}
    private void send(byte[] data) {
        if(stopped)return;
        try {sender.execute(()->{try {out.write(data);out.flush();}catch(Exception e){if(!stopped)failure.error(e);}});}
        catch(RejectedExecutionException e){if(!stopped)failure.error(new IOException(I18n.t(R.string.text_042)));}
    }
    void key(int action,int code,int repeat,int meta){send(ByteBuffer.allocate(14).put((byte)0).put((byte)action).putInt(code).putInt(repeat).putInt(meta).array());}
    void clickKey(int code){key(0,code,0,0);key(1,code,0,0);}
    void touch(int action,long pointer,int x,int y,int w,int h,float pressure,boolean mouse,int buttons){
        pointer(action,pointer,x,y,w,h,pressure,mouse&&(action==0||action==1)?1:0,buttons);
    }
    void mouse(int action,int x,int y,int w,int h,int actionButton,int buttons){
        pointer(action,-1L,x,y,w,h,buttons==0?0:1,actionButton,buttons);
    }
    private void pointer(int action,long pointer,int x,int y,int w,int h,float pressure,int actionButton,int buttons){
        send(ByteBuffer.allocate(32).put((byte)2).put((byte)action).putLong(pointer).putInt(x).putInt(y).putShort((short)w).putShort((short)h)
            .putShort((short)Math.min(65535,Math.max(0,(int)(pressure*65535))))
            .putInt(actionButton).putInt(buttons).array());
    }
    void scroll(int x,int y,int w,int h,float horizontal,float vertical){
        send(ByteBuffer.allocate(21).put((byte)3).putInt(x).putInt(y).putShort((short)w).putShort((short)h)
            .putShort((short)(Math.max(-16,Math.min(15.99f,horizontal))*2048))
            .putShort((short)(Math.max(-16,Math.min(15.99f,vertical))*2048)).putInt(0).array());
    }
    void simple(int type){send(new byte[]{(byte)type});}
    void power(boolean on){send(new byte[]{10,(byte)(on?1:0)});}
    void clipboard(String text,boolean paste){byte[] t=text.getBytes(StandardCharsets.UTF_8);if(t.length>262130){failure.error(new IOException(I18n.t(R.string.text_043)));return;}
        send(ByteBuffer.allocate(14+t.length).put((byte)9).putLong(0).put((byte)(paste?1:0)).putInt(t.length).put(t).array());}
    void getClipboard(){send(new byte[]{8,0});}
    void startApp(String name){byte[] n=name.getBytes(StandardCharsets.UTF_8);if(n.length<256)send(ByteBuffer.allocate(2+n.length).put((byte)16).put((byte)n.length).put(n).array());}
    public void close(){stopped=true;sender.shutdown();}
    void awaitClosed(){try{if(!sender.awaitTermination(300,TimeUnit.MILLISECONDS))sender.shutdownNow();}catch(InterruptedException e){sender.shutdownNow();Thread.currentThread().interrupt();}}
}
