package ro.sw3g.scrcpy;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain, portable log records. Styling is applied only by the console view. */
final class LogFormat {
    enum Level { DEBUG, INFO, OK, WARN, ERROR, CMD }
    static final class Entry {
        final String timestamp,channel,message;
        final Level level;
        Entry(String timestamp,Level level,String channel,String message){this.timestamp=timestamp;this.level=level;this.channel=channel;this.message=message;}
        String line(){return "["+timestamp+"] ["+level+"]["+channel+"] "+message+"\n";}
    }
    private static final Pattern TIME=Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})] ?(.*)$");
    private static final Pattern META=Pattern.compile("^\\[(DEBUG|INFO|OK|WARN|ERROR|CMD)]\\[([A-Z]{2,10})] ?(.*)$");
    private static final Pattern EXPLICIT=Pattern.compile("^(?:\\[[^]]+]\\s*)?(DEBUG|INFO|WARN|WARNING|ERROR):",Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR=Pattern.compile("\\b(error|exception|failed|failure|invalid|denied|refused|missing|cannot|unsupported|eroare|esuat|esuata|refuzat|refuzata|neacordata|incomplet|incompleta|lipseste|lipsesc)\\b");
    private static final Pattern WARN=Pattern.compile("\\b(warn|warning|unavailable|disconnected|disconnect|indisponibil|indisponibila|deconectat|deconectata|reconnect|reconnection|reconectare)\\b");
    private static final Pattern OK=Pattern.compile("\\b(connected|paired|saved|exported|copied|conectat|salvat|salvata|exportat|copiat|copiata|reusita)\\b");
    private static final Pattern ANSI=Pattern.compile("\\u001b\\[[0-?]*[ -/]*[@-~]");
    static Entry create(String timestamp,String raw){
        Level forced=null;for(Level candidate:Level.values()){String prefix="["+candidate+"] ";if(raw.startsWith(prefix)){forced=candidate;raw=raw.substring(prefix.length());break;}}
        String channel=null,message=raw;
        if(raw.startsWith("[SHELL] ")){channel="SHELL";message=raw.substring(8);}
        else if(raw.startsWith("[B] ")){channel="SERVER";message=raw.substring(4);}
        else if(raw.startsWith("[USB] ")){channel="USB";message=raw.substring(6);}
        else if(raw.startsWith("SERVER: ")){return new Entry(timestamp,Level.CMD,"SERVER",raw.substring(8));}
        if(message.startsWith("$ "))return new Entry(timestamp,Level.CMD,channel==null?"SHELL":channel,message);
        if(channel==null)channel=channel(message);
        return new Entry(timestamp,forced==null?level(message):forced,channel,message);
    }
    static Entry parse(String line){
        Matcher time=TIME.matcher(line);if(!time.matches())return create("",line);
        String timestamp=time.group(1),body=time.group(2);Matcher meta=META.matcher(body);
        if(meta.matches())return new Entry(timestamp,Level.valueOf(meta.group(1)),meta.group(2),meta.group(3));
        return create(timestamp,body); // Existing logs from earlier versions remain readable.
    }
    static String visible(String text){return ANSI.matcher(text).replaceAll("");}
    private static String plain(String text){return Normalizer.normalize(visible(text),Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);}
    private static Level level(String message){
        String text=plain(message);Matcher explicit=EXPLICIT.matcher(visible(message));
        if(explicit.find()){String name=explicit.group(1).toUpperCase(Locale.ROOT);return name.equals("WARNING")?Level.WARN:Level.valueOf(name);}
        if(ERROR.matcher(text).find()||text.contains("nu pot ")||text.contains("nu poate ")||text.contains("nu s-a conectat")||text.contains("nu a deschis"))return Level.ERROR;
        if(WARN.matcher(text).find())return Level.WARN;
        if(OK.matcher(text).find())return Level.OK;
        return Level.INFO;
    }
    private static String channel(String message){
        String text=plain(message);
        if(text.contains("usb")||text.contains("otg"))return "USB";
        if(text.contains("clipboard"))return "CLIPBOARD";
        if(text.contains("mp4")||text.contains("record")||text.contains("inregistr"))return "RECORD";
        if(text.contains("audio")||text.contains("sunet")||text.contains("pcm"))return "AUDIO";
        if(text.contains("video")||text.contains("decoder")||text.contains("h264"))return "VIDEO";
        if(text.contains("touch")||text.contains("mouse")||text.contains("cursor")||text.contains("control:"))return "INPUT";
        if(text.contains("shell"))return "SHELL";
        if(text.contains("adb")||text.contains("pair")||text.contains("asocier"))return "ADB";
        if(text.contains("screenshot")||text.contains("captur")||text.contains("export"))return "FILES";
        return "APP";
    }
}
