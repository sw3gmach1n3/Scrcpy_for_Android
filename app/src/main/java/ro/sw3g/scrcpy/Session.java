package ro.sw3g.scrcpy;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.*;
import android.os.*;
import android.view.Surface;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class Session {
    static final String VERSION="3.3.4";
    interface Listener {
        void log(String message);
        void connected(String name);
        void dimensions(int width,int height);
        void clipboard(String text);
        void ended(Session who,String reason,boolean manual);
        void mediaReady(File file,String mime);
    }
    private final Context context;
    final Options options;
    private final String host;private final int port;
    private final UsbDevice usbDevice;
    private volatile UsbAdbBridge usbBridge;
    private final Surface surface;
    private final Listener listener;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final List<AdbStream> streams=Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed=new AtomicBoolean();
    private final java.util.Set<AdbStream> commandStreams=java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile AdbIdentity adb;
    volatile Control control;
    volatile boolean alive=true,connected=false;
    private volatile boolean videoVisible=true;
    void setVideoVisible(boolean visible){videoVisible=visible;}
    volatile int width=1,height=1;
    private volatile Mp4Recorder recorder;
    private volatile File recordingFile;
    private final LinkedHashMap<String,String> serverOptions;
    Session(Context context,String host,int port,Options options,Surface surface,Listener listener,UsbDevice usbDevice) {
        this.context=context.getApplicationContext();this.host=host;this.port=port;this.options=options;this.surface=surface;this.listener=listener;this.usbDevice=usbDevice;
        serverOptions=options.server();
    }
    void start(){workers.execute(()->{try{connect();}catch(Exception e){if(alive)fail(I18n.t(R.string.text_007)+e.getClass().getSimpleName()+": "+e.getMessage());}});}
    private void connect()throws Exception{
        adb=new AdbIdentity(context);
        if(usbDevice!=null){
            listener.log(I18n.t(R.string.text_008)+UsbAdbBridge.deviceLabel(usbDevice)+I18n.t(R.string.text_009));
            UsbAdbBridge bridge=new UsbAdbBridge((UsbManager)context.getSystemService(Context.USB_SERVICE),usbDevice,listener::log);usbBridge=bridge;
            if(!alive){bridge.close();check();}
            if(!adb.connect("127.0.0.1",bridge.port()))throw new IOException(I18n.t(R.string.text_010));
        }else{
            listener.log(I18n.t(R.string.text_011)+host+":"+port+I18n.t(R.string.text_012));
            if(!adb.connect(host,port))throw new IOException(I18n.t(R.string.text_013));
        }
        check();
        String scid=String.format(Locale.US,"%08x",new Random().nextInt()&0x7fffffff);
        String path="/data/local/tmp/scrcpy-oled-"+scid+".jar";
        try(InputStream asset=context.getAssets().open("scrcpy-server")){push(asset,path);}
        check();listener.log("Server scrcpy "+VERSION+I18n.t(R.string.text_014));
        LinkedHashMap<String,String> args=new LinkedHashMap<>(serverOptions);
        args.put("scid",scid);args.put("tunnel_forward","true");args.put("video","true");args.put("cleanup","true");
        args.put("send_device_meta","true");args.put("send_codec_meta","true");args.put("send_frame_meta","true");args.put("send_dummy_byte","true");
        String cmd="CLASSPATH="+quote(path)+" app_process / com.genymobile.scrcpy.Server "+VERSION;
        for(Map.Entry<String,String> e:args.entrySet())cmd+=" "+quote(e.getKey()+"="+e.getValue());
        listener.log("SERVER: "+cmd);
        AdbStream shell=track(adb.openStream("shell:"+cmd));
        workers.execute(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(shell.openInputStream(),StandardCharsets.UTF_8))){String line;while(alive&&(line=r.readLine())!=null)listener.log("[B] "+line);}catch(Exception e){if(alive)listener.log("Server shell: "+e.getMessage());}});
        ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(()->{if(alive&&!connected)fail(I18n.t(R.string.text_015));},25,TimeUnit.SECONDS);
        try {
            String socket="localabstract:scrcpy_"+scid;
            AdbStream video=openSocket(socket);DataInputStream vin=new DataInputStream(video.openInputStream());
            if(vin.readUnsignedByte()!=0)throw new IOException(I18n.t(R.string.text_016));
            boolean withAudio=Boolean.parseBoolean(serverOptions.get("audio"));
            AdbStream audio=withAudio?openSocket(socket):null;
            AdbStream ctl=Boolean.parseBoolean(serverOptions.get("control"))?openSocket(socket):null;
            byte[] name=new byte[64];vin.readFully(name);String device=new String(name,StandardCharsets.UTF_8).split("\u0000",2)[0];
            int codec=vin.readInt();width=vin.readInt();height=vin.readInt();
            if(width<1||height<1||width>32768||height>32768)throw new IOException(I18n.t(R.string.text_017));
            if(options.yes("record")){
                File dir=context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);if(dir==null)dir=context.getFilesDir();
                recordingFile=new File(dir,"Scrcpy_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".mp4");
                recorder=new Mp4Recorder(recordingFile,withAudio,listener::log);
            }
            if(ctl!=null){control=new Control(ctl.openOutputStream(),e->fail("Control: "+e.getMessage()));workers.execute(()->readControl(ctl));}
            connected=true;listener.dimensions(width,height);listener.connected(device);listener.log(I18n.t(R.string.text_018)+device+" • "+width+"×"+height);
            if(control!=null&&options.yes("screenOff"))control.power(false);
            if(audio!=null)workers.execute(()->audio(audio));
            decode(vin,codec,false);
            if(alive)fail(I18n.t(R.string.text_019));
        } finally{watchdog.shutdownNow();}
    }
    private AdbStream track(AdbStream s)throws IOException{if(!alive){s.close();throw new IOException(I18n.t(R.string.text_040));}streams.add(s);return s;}
    private AdbStream openSocket(String name)throws Exception{
        long until=SystemClock.elapsedRealtime()+10000;Exception last=null;
        do {check();try{return track(adb.openStream(name));}catch(Exception e){last=e;Thread.sleep(120);}}while(SystemClock.elapsedRealtime()<until);
        throw new IOException(I18n.t(R.string.text_020),last);
    }
    private void check()throws IOException{if(!alive)throw new IOException(I18n.t(R.string.text_021));}
    private void push(InputStream source,String path)throws Exception{
        AdbStream sync=track(adb.openStream("sync:"));
        try {
            OutputStream out=sync.openOutputStream();DataInputStream in=new DataInputStream(sync.openInputStream());
            syncPacket(out,"SEND",(path+",33261").getBytes(StandardCharsets.UTF_8));
            byte[] buf=new byte[32768];int n;while((n=source.read(buf))!=-1){check();syncPacket(out,"DATA",Arrays.copyOf(buf,n));}
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("DONE".getBytes(StandardCharsets.US_ASCII)).putInt((int)(System.currentTimeMillis()/1000)).array());out.flush();
            byte[] status=new byte[4];in.readFully(status);int len=Integer.reverseBytes(in.readInt());
            if(!new String(status,StandardCharsets.US_ASCII).equals("OKAY")){if(len<0||len>65536)throw new IOException(I18n.t(R.string.text_022));byte[] error=new byte[len];in.readFully(error);throw new IOException(new String(error,StandardCharsets.UTF_8));}
        } finally{sync.close();streams.remove(sync);}
    }
    private static void syncPacket(OutputStream out,String id,byte[] b)throws IOException{out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.getBytes(StandardCharsets.US_ASCII)).putInt(b.length).array());out.write(b);out.flush();}
    static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private void readControl(AdbStream stream){
        try{DataInputStream in=new DataInputStream(stream.openInputStream());while(alive){int t=in.readUnsignedByte();
            if(t==0){int n=in.readInt();if(n<0||n>262144)throw new IOException("Clipboard invalid");byte[] b=new byte[n];in.readFully(b);listener.clipboard(new String(b,StandardCharsets.UTF_8));}
            else if(t==1)in.readLong();
            else if(t==2){in.readUnsignedShort();int len=in.readUnsignedShort();byte[] b=new byte[len];in.readFully(b);}
            else throw new IOException(I18n.t(R.string.text_023)+t);
        }}catch(Exception e){if(alive)fail(I18n.t(R.string.text_024)+e.getMessage());}
    }
    private void audio(AdbStream stream){try{DataInputStream in=new DataInputStream(stream.openInputStream());int codec=in.readInt();
        if(codec==0||codec==1){listener.log(I18n.t(R.string.text_025));Mp4Recorder r=recorder;if(r!=null)r.noAudio();return;}
        if(codec==0x00726177)rawAudio(in);else decode(in,codec,true);
    }catch(Exception e){listener.log(I18n.t(R.string.text_026)+e.getMessage());Mp4Recorder r=recorder;if(r!=null)r.noAudio();}}
    private void rawAudio(DataInputStream in)throws Exception{
        AudioTrack track=makeAudio(48000,2);try{track.play();while(alive){long flags=in.readLong();int n=in.readInt();if(n<0||n>4*1024*1024)throw new IOException(I18n.t(R.string.text_027));byte[]b=new byte[n];in.readFully(b);int off=0;while(alive&&off<n){int k=track.write(b,off,n-off,AudioTrack.WRITE_BLOCKING);if(k<0)throw new IOException("AudioTrack "+k);off+=k;}}}finally{track.release();}
    }
    private AudioTrack makeAudio(int rate,int channels){int mask=channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
        int size=Math.max(AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT),rate*channels*2/20);
        return new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size).build();}
    private String mime(int codec,boolean audio)throws IOException{
        if(audio){if(codec==0x00616163)return "audio/mp4a-latm";if(codec==0x6f707573)return "audio/opus";if(codec==0x666c6163)return "audio/flac";}
        else{if(codec==0x68323634)return "video/avc";if(codec==0x68323635)return "video/hevc";if(codec==0x00617631)return "video/av01";}
        throw new IOException(I18n.t(R.string.text_028)+Integer.toHexString(codec));
    }
    private void decode(DataInputStream in,int codec,boolean isAudio)throws Exception{
        MediaCodec decoder=null;AtomicBoolean decoding=new AtomicBoolean(true);Thread drain=null;
        try{
            String mime=mime(codec,isAudio);MediaFormat format=isAudio?MediaFormat.createAudioFormat(mime,48000,2):MediaFormat.createVideoFormat(mime,width,height);
            if(!isAudio&&Build.VERSION.SDK_INT>=30)format.setInteger("low-latency",1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8*1024*1024);
            // Audio needs codec-specific data before MediaCodec.configure().
            byte[] first=null;long firstFlags=0;
            if(isAudio){firstFlags=in.readLong();int n=in.readInt();if(n<0||n>1048576)throw new IOException(I18n.t(R.string.text_029));first=new byte[n];in.readFully(first);
                if((firstFlags&Long.MIN_VALUE)==0)throw new IOException(I18n.t(R.string.text_030));
                format.setByteBuffer("csd-0",ByteBuffer.wrap(first));
                if(mime.equals("audio/opus")){ByteBuffer delay=ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());delay.putLong(0);((java.nio.Buffer)delay).rewind();format.setByteBuffer("csd-1",delay);ByteBuffer preroll=ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());preroll.putLong(80000000L);((java.nio.Buffer)preroll).rewind();format.setByteBuffer("csd-2",preroll);}
                Mp4Recorder r=recorder;if(r!=null)r.config(true,first,width,height);
            }
            decoder=MediaCodec.createDecoderByType(mime);decoder.configure(format,isAudio?null:surface,null,0);decoder.start();
            final MediaCodec d=decoder;
            drain=new Thread(()->drain(d,isAudio,decoding),isAudio?"Audio output":"Video output");drain.start();
            boolean seenConfig=false;
            while(alive&&decoding.get()){
                long flags=in.readLong();int size=in.readInt();if(size<0||size>8*1024*1024)throw new IOException(I18n.t(R.string.text_031)+size);
                byte[] data=new byte[size];in.readFully(data);boolean config=(flags&Long.MIN_VALUE)!=0,key=(flags&(1L<<62))!=0;long pts=flags&0x3fffffffffffffffL;
                Mp4Recorder r=recorder;
                if(r!=null){try{if(config){if(!isAudio&&seenConfig){r.close();recorder=null;listener.mediaReady(r.file(),"video/mp4");listener.log(I18n.t(R.string.text_032));}else r.config(isAudio,data,width,height);}else r.packet(isAudio,data,pts,key);}catch(Exception e){listener.log(I18n.t(R.string.text_033)+e.getMessage());r.close();recorder=null;}}
                if(config)seenConfig=true;
                int index=-1;while(alive&&decoding.get()&&(index=d.dequeueInputBuffer(20000))<0){}if(index<0)break;
                ByteBuffer b=d.getInputBuffer(index);if(b==null||b.capacity()<size)throw new IOException(I18n.t(R.string.text_034));((java.nio.Buffer)b).clear();b.put(data);d.queueInputBuffer(index,0,size,pts,config?MediaCodec.BUFFER_FLAG_CODEC_CONFIG:0);
            }
        }finally{decoding.set(false);if(drain!=null)drain.join(1500);if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}}
    }
    private void drain(MediaCodec d,boolean isAudio,AtomicBoolean decoding){
        AudioTrack track=null;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try{while(alive&&decoding.get()){
            int index=d.dequeueOutputBuffer(info,20000);
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=d.getOutputFormat();
                if(isAudio){if(track!=null)track.release();track=makeAudio(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));track.play();}
                else{int w=f.getInteger(MediaFormat.KEY_WIDTH),h=f.getInteger(MediaFormat.KEY_HEIGHT);
                    if(f.containsKey("crop-right")&&f.containsKey("crop-left"))w=f.getInteger("crop-right")-f.getInteger("crop-left")+1;
                    if(f.containsKey("crop-bottom")&&f.containsKey("crop-top"))h=f.getInteger("crop-bottom")-f.getInteger("crop-top")+1;
                    width=w;height=h;listener.dimensions(w,h);}
            }else if(index>=0){
                if(isAudio){ByteBuffer b=d.getOutputBuffer(index);if(b!=null&&info.size>0&&track!=null){((java.nio.Buffer)b).position(info.offset);((java.nio.Buffer)b).limit(info.offset+info.size);while(alive&&b.hasRemaining()){int n=track.write(b,b.remaining(),AudioTrack.WRITE_BLOCKING);if(n<0)throw new IOException("AudioTrack "+n);}}d.releaseOutputBuffer(index,false);}
                else d.releaseOutputBuffer(index,videoVisible);
            }
        }}catch(Exception e){if(alive&&decoding.get()){listener.log("Decoder "+(isAudio?"audio":"video")+": "+e);if(!isAudio)fail(I18n.t(R.string.text_035));}}
        finally{decoding.set(false);if(track!=null)track.release();}
    }
    void shell(String command){if(adb==null||!connected){listener.log(I18n.t(R.string.text_036));return;}
        workers.execute(()->{listener.log("$ "+command);try{AdbStream s=track(adb.openStream("shell:"+command));commandStreams.add(s);try(BufferedReader r=new BufferedReader(new InputStreamReader(s.openInputStream(),StandardCharsets.UTF_8))){String l;while(alive&&(l=r.readLine())!=null)listener.log("[SHELL] "+l);}finally{s.close();streams.remove(s);commandStreams.remove(s);}}catch(Exception e){listener.log("Shell: "+e.getMessage());}});}
    void stopCommands(){workers.execute(()->{for(AdbStream s:commandStreams){try{s.close();}catch(Exception ignored){}}commandStreams.clear();listener.log(I18n.t(R.string.text_037));});}
    void diagnostic(String flag){workers.execute(()->{try{check();String scid=Integer.toHexString(new Random().nextInt()&0x7fffffff);String path="/data/local/tmp/scrcpy-list-"+scid+".jar";try(InputStream in=context.getAssets().open("scrcpy-server")){push(in,path);}shell("CLASSPATH="+quote(path)+" app_process / com.genymobile.scrcpy.Server "+VERSION+" "+flag+"=true; rm -f "+quote(path));}catch(Exception e){listener.log(I18n.t(R.string.text_038)+e.getMessage());}});}
    void stop(){finish(I18n.t(R.string.text_039),true);}
    private void fail(String reason){finish(reason,false);}
    private void finish(String reason,boolean manual){
        if(!closed.compareAndSet(false,true))return;alive=false;connected=false;
        Control c=control;if(c!=null)c.close();
        new Thread(()->{
            if(c!=null)c.awaitClosed();
            try{AdbIdentity a=adb;if(a!=null&&a.getAdbConnection()!=null)a.getAdbConnection().close();}catch(Exception ignored){}
            UsbAdbBridge bridge=usbBridge;if(bridge!=null)bridge.close();
            synchronized(streams){for(AdbStream s:streams)try{s.close();}catch(Exception ignored){}streams.clear();}
            workers.shutdownNow();
            try{workers.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            Mp4Recorder r=recorder;if(r!=null){r.close();recorder=null;}
            if(recordingFile!=null&&recordingFile.exists()&&recordingFile.length()>0)listener.mediaReady(recordingFile,"video/mp4");
            listener.log((manual?"":"[ERROR] ")+reason);listener.ended(this,reason,manual);
        },"Scrcpy cleanup").start();
    }
}
