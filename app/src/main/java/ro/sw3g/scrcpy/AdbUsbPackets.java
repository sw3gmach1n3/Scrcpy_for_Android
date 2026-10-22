package ro.sw3g.scrcpy;

import java.io.*;
import java.nio.*;

/** ADB USB transfers are header (24 bytes), then payload, never a merged transfer. */
final class AdbUsbPackets {
    static final int CHUNK=16384;
    static final int MAX_PAYLOAD=16*1024*1024;
    interface Reader { void readFully(byte[] bytes,int offset,int length)throws IOException; }
    interface Writer { void write(byte[] bytes,int offset,int length)throws IOException; }
    static int payloadSize(byte[] header)throws IOException {
        if(header.length!=24)throw new IOException(I18n.t(R.string.text_044));
        ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command=h.getInt(0),length=h.getInt(12),magic=h.getInt(20);
        if(magic!=(command^0xffffffff))throw new IOException(I18n.t(R.string.text_045));
        if(length<0||length>MAX_PAYLOAD)throw new IOException(I18n.t(R.string.text_046)+length);
        return length;
    }
    static void toUsb(DataInputStream source,Writer target,int maxPacket)throws IOException {
        if(maxPacket<1)throw new IOException(I18n.t(R.string.text_047));
        byte[] header=new byte[24];source.readFully(header);int length=payloadSize(header);
        target.write(header,0,header.length);
        byte[] buffer=new byte[Math.min(CHUNK,Math.max(1,length))];
        for(int left=length;left>0;){int n=Math.min(buffer.length,left);source.readFully(buffer,0,n);target.write(buffer,0,n);left-=n;}
        // adbd uses FunctionFS reads: a final aligned payload needs a short packet.
        if(length>0&&length%maxPacket==0)target.write(new byte[0],0,0);
    }
    static void fromUsb(Reader source,OutputStream target)throws IOException {
        byte[] header=new byte[24];source.readFully(header,0,header.length);int length=payloadSize(header);
        target.write(header);
        byte[] buffer=new byte[Math.min(CHUNK,Math.max(1,length))];
        for(int left=length;left>0;){int n=Math.min(buffer.length,left);source.readFully(buffer,0,n);target.write(buffer,0,n);left-=n;}
        target.flush();
    }
}
