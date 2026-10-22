package ro.sw3g.scrcpy;

import android.hardware.usb.*;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** USB carries all remote traffic. Loopback only adapts the existing ADB client. */
final class UsbAdbBridge implements Closeable {
    private final UsbDeviceConnection connection;
    private final UsbInterface adbInterface;
    private final UsbEndpoint input,output;
    private final UsbRequest request;
    private final ServerSocket listener;
    private final Consumer<String> log;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final ByteBuffer readBuffer=ByteBuffer.allocateDirect(AdbUsbPackets.CHUNK);
    private volatile Socket client;
    UsbAdbBridge(UsbManager manager,UsbDevice device,Consumer<String> log)throws IOException {
        this.log=log;
        adbInterface=findAdbInterface(device);
        if(adbInterface==null)throw new IOException(I18n.t(R.string.text_229));
        input=endpoint(adbInterface,UsbConstants.USB_DIR_IN);output=endpoint(adbInterface,UsbConstants.USB_DIR_OUT);
        if(!manager.hasPermission(device))throw new IOException(I18n.t(R.string.text_230));
        UsbDeviceConnection opened=manager.openDevice(device);
        if(opened==null)throw new IOException(I18n.t(R.string.text_231));
        UsbRequest req=null;ServerSocket server=null;
        try {
            if(!opened.claimInterface(adbInterface,true))throw new IOException(I18n.t(R.string.text_232));
            req=new UsbRequest();if(!req.initialize(opened,input))throw new IOException(I18n.t(R.string.text_233));
            server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),1);server.setSoTimeout(1000);
        }catch(Exception e){if(req!=null)req.close();if(server!=null)try{server.close();}catch(IOException ignored){}opened.close();throw e instanceof IOException?(IOException)e:new IOException(e);}
        connection=opened;request=req;listener=server;
        log.accept("USB ADB: "+deviceLabel(device)+" • IN 0x"+Integer.toHexString(input.getAddress())+" / OUT 0x"+Integer.toHexString(output.getAddress()));
        thread("USB ADB accept",this::accept);
    }
    int port(){return listener.getLocalPort();}
    static UsbEndpoint endpoint(UsbInterface face,int direction){
        for(int i=0;i<face.getEndpointCount();i++){UsbEndpoint e=face.getEndpoint(i);if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&e.getDirection()==direction)return e;}return null;
    }
    static UsbInterface findAdbInterface(UsbDevice device){
        for(int i=0;i<device.getInterfaceCount();i++){UsbInterface face=device.getInterface(i);
            if(face.getInterfaceClass()==255&&face.getInterfaceSubclass()==66&&face.getInterfaceProtocol()==1&&endpoint(face,UsbConstants.USB_DIR_IN)!=null&&endpoint(face,UsbConstants.USB_DIR_OUT)!=null)return face;
        }return null;
    }
    static String deviceLabel(UsbDevice d){String product=d.getProductName();if(product==null||product.trim().isEmpty())product="Android USB";return product+String.format(java.util.Locale.US," (%04x:%04x)",d.getVendorId(),d.getProductId());}
    private void thread(String name,Runnable task){Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
    private void accept(){
        try {
            long deadline=SystemClock.elapsedRealtime()+20000;
            while(!closed.get()&&client==null){try{client=listener.accept();}catch(SocketTimeoutException e){if(SystemClock.elapsedRealtime()>deadline)throw new IOException(I18n.t(R.string.text_234));}}
            if(closed.get()){if(client!=null)client.close();return;}
            client.setTcpNoDelay(true);client.setReceiveBufferSize(256*1024);client.setSendBufferSize(256*1024);listener.close();
            thread("ADB to USB",()->{
                try{DataInputStream fromAdb=new DataInputStream(client.getInputStream());while(!closed.get())AdbUsbPackets.toUsb(fromAdb,this::writeUsb,output.getMaxPacketSize());}
                catch(Exception e){failure(e);}
            });
            OutputStream toAdb=client.getOutputStream();while(!closed.get())AdbUsbPackets.fromUsb(this::readUsb,toAdb);
        }catch(Exception e){failure(e);}finally{close();try{request.close();}catch(Exception ignored){}}
    }
    private void writeUsb(byte[] bytes,int offset,int length)throws IOException {
        if(closed.get())throw new IOException(I18n.t(R.string.text_235));
        int n=connection.bulkTransfer(output,bytes,offset,length,5000);
        if(n!=length)throw new IOException(I18n.t(R.string.text_236)+n+" / "+length+I18n.t(R.string.text_237));
    }
    private void readUsb(byte[] target,int offset,int length)throws IOException {
        int done=0;
        while(done<length){
            if(closed.get())throw new IOException(I18n.t(R.string.text_235));
            int n=Math.min(readBuffer.capacity(),length-done);((java.nio.Buffer)readBuffer).clear();((java.nio.Buffer)readBuffer).limit(n);
            if(!request.queue(readBuffer))throw new IOException(I18n.t(R.string.text_238));
            UsbRequest completed;
            while(true){
                if(closed.get())throw new IOException(I18n.t(R.string.text_235));
                try{completed=connection.requestWait(1000);break;}catch(TimeoutException normalIdle){/* The queued request remains active; do not requeue or discard bytes. */}
            }
            if(completed!=request)throw new IOException(I18n.t(R.string.text_239));
            int actual=readBuffer.position();if(actual==0)continue;
            ((java.nio.Buffer)readBuffer).flip();readBuffer.get(target,offset+done,actual);done+=actual;
        }
    }
    private void failure(Exception e){if(!closed.get())log.accept(I18n.t(R.string.text_240)+e.getClass().getSimpleName()+": "+e.getMessage());close();}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        try{request.cancel();}catch(Exception ignored){}
        try{listener.close();}catch(Exception ignored){}
        Socket active=client;if(active!=null)try{active.close();}catch(Exception ignored){}
        // close() also wakes requestWait(); request memory is released by its owning reader.
        try{connection.releaseInterface(adbInterface);}catch(Exception ignored){}
        connection.close();
    }
}
