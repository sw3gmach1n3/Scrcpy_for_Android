package ro.sw3g.scrcpy;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Stores compressed H264/AAC packets, never the decoded screen. */
final class Mp4Recorder implements Closeable {
    interface Log { void line(String value); }
    private final MediaMuxer mux;
    private final File file;
    private final Log log;
    private boolean audioExpected,started,closed,videoKey;
    private int video=-1,audio=-1;
    private long videoBase=-1,audioBase=-1;
    private final ArrayList<Sample> pending=new ArrayList<>();
    private int pendingBytes;
    private static class Sample {boolean audio;byte[] bytes;long pts;boolean key;Sample(boolean a,byte[]b,long p,boolean k){audio=a;bytes=b;pts=p;key=k;}}
    Mp4Recorder(File file,boolean audioExpected,Log log)throws IOException{this.file=file;this.audioExpected=audioExpected;this.log=log;mux=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);}
    synchronized void config(boolean isAudio,byte[] data,int width,int height)throws IOException {
        if(closed)return;
        if(isAudio&&audio<0) {MediaFormat f=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2);f.setByteBuffer("csd-0",ByteBuffer.wrap(data));audio=mux.addTrack(f);}
        if(!isAudio&&video<0){
            MediaFormat f=MediaFormat.createVideoFormat("video/avc",width,height);
            List<byte[]> nals=nals(data);byte[] sps=null,pps=null;
            for(byte[] n:nals){int off=n[2]==1?3:4;int type=n[off]&31;if(type==7)sps=n;if(type==8)pps=n;}
            if(sps==null||pps==null)throw new IOException(I18n.t(R.string.text_226));
            f.setByteBuffer("csd-0",ByteBuffer.wrap(sps));f.setByteBuffer("csd-1",ByteBuffer.wrap(pps));video=mux.addTrack(f);
        }
        startIfReady();
    }
    synchronized void noAudio(){if(closed)return;audioExpected=false;startIfReady();}
    private void startIfReady(){if(!started&&video>=0&&(!audioExpected||audio>=0)){mux.start();started=true;for(Sample s:pending)write(s);pending.clear();pendingBytes=0;}}
    synchronized void packet(boolean isAudio,byte[] b,long pts,boolean key){
        if(closed)return;Sample s=new Sample(isAudio,b,pts,key);
        if(!started){if(pendingBytes+b.length<=16*1024*1024){pending.add(s);pendingBytes+=b.length;}return;}
        write(s);
    }
    private void write(Sample s){
        if(!videoKey){if(s.audio||!s.key)return;videoKey=true;videoBase=s.pts;}
        int track=s.audio?audio:video;if(track<0)return;
        if(s.audio&&s.pts<videoBase)return;
        long pts=s.pts-videoBase;
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,s.bytes.length,pts,s.key?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
        mux.writeSampleData(track,ByteBuffer.wrap(s.bytes),info);
    }
    public synchronized void close(){if(closed)return;closed=true;try{if(started)mux.stop();}catch(Exception e){log.line("MP4: "+e);}finally{mux.release();}
        if(!videoKey){file.delete();log.line(I18n.t(R.string.text_227));}else log.line(I18n.t(R.string.text_228)+file.getAbsolutePath());}
    File file(){return file;}
    static List<byte[]> nals(byte[] b){
        ArrayList<byte[]> result=new ArrayList<>();ArrayList<Integer> starts=new ArrayList<>();
        for(int i=0;i+3<b.length;i++){if(b[i]==0&&b[i+1]==0&&(b[i+2]==1||(b[i+2]==0&&b[i+3]==1))){starts.add(i);i+=b[i+2]==1?2:3;}}
        for(int i=0;i<starts.size();i++)result.add(Arrays.copyOfRange(b,starts.get(i),i+1<starts.size()?starts.get(i+1):b.length));return result;
    }
}
