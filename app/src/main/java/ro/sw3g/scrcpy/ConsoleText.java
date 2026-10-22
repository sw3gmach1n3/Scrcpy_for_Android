package ro.sw3g.scrcpy;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;

/** One consistent presentation for the home console and the fullscreen console. */
final class ConsoleText {
    private static final int FLAGS=Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    static CharSequence render(String raw){
        SpannableStringBuilder out=new SpannableStringBuilder();int start=0;
        while(start<raw.length()){
            int end=raw.indexOf('\n',start);if(end<0)end=raw.length();
            LogFormat.Entry entry=LogFormat.parse(raw.substring(start,end));append(out,entry);start=end+1;
        }
        return out;
    }
    private static void append(SpannableStringBuilder out,LogFormat.Entry entry){
        int from=out.length();
        if(!entry.timestamp.isEmpty()){out.append(entry.timestamp).append("   ");color(out,from,out.length(),0xff7f8a99);}
        int level=out.length();out.append(entry.level.name());color(out,level,out.length(),color(entry.level));out.setSpan(new StyleSpan(Typeface.BOLD),level,out.length(),FLAGS);
        out.append("  ·  ");int channel=out.length();out.append(entry.channel);color(out,channel,out.length(),0xff92b5c8);
        out.setSpan(new RelativeSizeSpan(.82f),from,out.length(),FLAGS);out.append('\n');
        int message=out.length();out.append(LogFormat.visible(entry.message));color(out,message,out.length(),entry.level==LogFormat.Level.INFO?0xffd8dfe8:color(entry.level));out.append("\n\n");
    }
    private static void color(SpannableStringBuilder out,int from,int to,int color){if(to>from)out.setSpan(new ForegroundColorSpan(color),from,to,FLAGS);}
    private static int color(LogFormat.Level level){
        switch(level){case ERROR:return 0xffffa0a5;case WARN:return 0xffe8c384;case OK:return 0xff9fd6b4;case CMD:return 0xffa9caff;case DEBUG:return 0xff939eaf;default:return 0xffb9cde0;}
    }
}
