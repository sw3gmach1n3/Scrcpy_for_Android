package ro.sw3g.scrcpy;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Native topic cards, scoped search, readable reference entries and copyable examples. */
final class HelpView extends LinearLayout {
    private final EditText search;
    private final LinearLayout content;
    private final ScrollView scroll;
    private HelpContent guide;
    private HelpContent.Topic selected;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=this::render;
    private static final Pattern INLINE=Pattern.compile("--[a-z][a-z0-9-]*(?:=[^\\s,;]+)?|\\b[a-z_]+=[^\\s,;]+|\\b(?:adb|scrcpy\\.exe|wm size|getprop|h264|h265|av1|MP4|AAC|Opus)\\b");
    HelpView(Context context){
        super(context);setOrientation(VERTICAL);
        addView(text(I18n.t(R.string.text_124),21,0xffe8eaef,true));
        addView(text(I18n.t(R.string.help_intro),13,0xff979da8,false));
        search=new EditText(context);search.setTextColor(Color.WHITE);search.setTextSize(15);search.setSingleLine(true);search.setHintTextColor(0xff8f99a9);search.setPadding(dp(12),dp(8),dp(12),dp(8));search.setBackground(shape(0xff16181e,12));
        LinearLayout.LayoutParams input=new LinearLayout.LayoutParams(-1,dp(48));input.setMargins(0,dp(8),0,dp(8));addView(search,input);
        content=column();content.setPadding(0,dp(4),0,dp(12));scroll=new ScrollView(context);scroll.addView(content);addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        try(InputStream in=context.getAssets().open(I18n.romanian()?"help-ro.json":"help-en.json")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            guide=new HelpContent(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }catch(Exception error){content.addView(text(error.toString(),14,0xffffa0a5,false));}
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable s){}public void onTextChanged(CharSequence s,int start,int before,int count){handler.removeCallbacks(refresh);handler.postDelayed(refresh,150);}});
        if(guide!=null)render();
    }
    boolean navigateBack(){if(selected==null)return false;selected=null;search.setText("");render();scroll.scrollTo(0,0);return true;}
    private void render(){
        handler.removeCallbacks(refresh);if(guide==null)return;content.removeAllViews();String query=HelpContent.normalize(search.getText().toString());
        search.setHint(I18n.t(selected==null?R.string.help_search_all:R.string.help_search_topic));
        if(selected==null){
            LinearLayout line=null;int count=0;
            for(HelpContent.Topic topic:guide.topics){if(!topic.matches(query))continue;
                if(count%2==0){line=column();line.setOrientation(HORIZONTAL);content.addView(line,new LinearLayout.LayoutParams(-1,-2));}
                Button tile=button(topic.title+"\n"+topic.summary,()->{selected=topic;render();scroll.scrollTo(0,0);});tile.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);tile.setTextSize(13);tile.setMaxLines(5);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(108),1);p.setMargins(dp(3),dp(4),dp(3),dp(4));line.addView(tile,p);count++;
            }
            if(count==0)content.addView(text(I18n.t(R.string.text_127),14,0xff979da8,false));
            return;
        }
        Button back=button(I18n.t(R.string.help_all_topics),this::navigateBack);content.addView(back,new LinearLayout.LayoutParams(-1,dp(44)));
        content.addView(text(selected.title,21,0xffe8eaef,true));content.addView(text(selected.summary,13,0xff979da8,false));
        boolean wholeTopic=query.isEmpty()||selected.search.contains(query);int count=0;
        for(HelpContent.Section section:selected.sections){if(!wholeTopic&&!section.matches(query))continue;addSection(section);count++;}
        if(count==0)content.addView(text(I18n.t(R.string.text_127),14,0xff979da8,false));
    }
    private void addSection(HelpContent.Section section){
        LinearLayout card=column();card.setPadding(dp(14),dp(10),dp(14),dp(12));card.setBackground(shape(0xff0c0e12,16));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,0);content.addView(card,p);
        if(!section.title.isEmpty()&&!(section.kind.equals("command")&&section.title.equals(section.command))){TextView heading=text(section.title,16,section.kind.equals("reference")?0xffaacce5:0xffe5eaf1,true);if(section.kind.equals("reference"))heading.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);card.addView(heading);}
        if(!section.body.isEmpty()){
            for(String paragraph:section.body.split("\\n\\n+")){
                TextView body=text("",14,0xffc4cbd6,false);body.setLineSpacing(dp(4),1);body.setTextIsSelectable(true);body.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);body.setLinkTextColor(0xffacd0ea);body.setText(styled(paragraph));card.addView(body);
            }
        }
        if(!section.command.isEmpty()){
            TextView code=text(section.command,14,0xffafd4f2,false);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setPadding(dp(10),dp(10),dp(10),dp(10));code.setBackground(shape(0xff171d27,10));
            LinearLayout.LayoutParams commandLayout=new LinearLayout.LayoutParams(-1,-2);commandLayout.topMargin=dp(8);card.addView(code,commandLayout);
            Button copy=button(I18n.t(R.string.help_copy_command),()->{
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText(section.title,section.command));Toast.makeText(getContext(),I18n.t(R.string.help_command_copied),Toast.LENGTH_SHORT).show();
            });LinearLayout.LayoutParams copyLayout=new LinearLayout.LayoutParams(-1,dp(42));copyLayout.topMargin=dp(6);card.addView(copy,copyLayout);
        }
    }
    private CharSequence styled(String source){
        SpannableStringBuilder result=new SpannableStringBuilder(source);Matcher matcher=INLINE.matcher(source);
        while(matcher.find()){
            result.setSpan(new ForegroundColorSpan(0xffacd0ea),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new TypefaceSpan("monospace"),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher steps=Pattern.compile("(?m)^(?:[1-9][0-9]*\\.|•) ").matcher(source);
        while(steps.find())result.setSpan(new ForegroundColorSpan(0xffaad0e0),steps.start(),steps.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(VERTICAL);return view;}
    private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(getContext());view.setText(value);view.setTextColor(color);view.setTextSize(size);view.setPadding(0,dp(6),0,dp(6));if(bold)view.setTypeface(null,Typeface.BOLD);return view;}
    private Button button(String title,Runnable action){Button view=new Button(getContext());view.setText(title);view.setAllCaps(false);view.setTextSize(13);view.setTextColor(0xffdae3ee);view.setPadding(dp(12),dp(6),dp(12),dp(6));view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x335e809e),shape(0xff191d24,14),null));view.setOnClickListener(v->action.run());return view;}
    private GradientDrawable shape(int color,int radius){GradientDrawable value=new GradientDrawable();value.setColor(color);value.setCornerRadius(dp(radius));return value;}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onDetachedFromWindow(){handler.removeCallbacks(refresh);super.onDetachedFromWindow();}
}
