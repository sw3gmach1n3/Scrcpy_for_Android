package ro.sw3g.scrcpy;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Locale;

/** App-owned language preference, independent of the phone's system language. */
final class I18n {
    private static volatile Resources resources;
    private static volatile String selected="en";
    static void initialize(Context context){
        String code=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("appLanguage","en");
        code="ro".equals(code)?"ro":"en";
        Configuration configuration=new Configuration(context.getResources().getConfiguration());
        Locale locale=Locale.forLanguageTag(code);configuration.setLocales(new LocaleList(locale));configuration.setLayoutDirection(locale);
        resources=context.createConfigurationContext(configuration).getResources();selected=code;
    }
    static String language(){return selected;}
    static boolean romanian(){return "ro".equals(selected);}
    static String t(int id){Resources current=resources;if(current==null)throw new IllegalStateException("App language has not been initialized");return current.getString(id);}
    static String optionLabel(String value){
        switch(value){
            case "":return t(R.string.text_050);
            case "display":return t(R.string.option_display);
            case "camera":return t(R.string.option_camera);
            case "front":return t(R.string.option_front);
            case "back":return t(R.string.option_back);
            case "external":return t(R.string.option_external);
            case "output":return t(R.string.option_output);
            case "playback":return t(R.string.option_playback);
            case "mic":return t(R.string.option_mic);
            case "mic-unprocessed":return t(R.string.option_mic_unprocessed);
            case "mic-camcorder":return t(R.string.option_mic_camcorder);
            case "mic-voice-recognition":return t(R.string.option_mic_voice_recognition);
            case "mic-voice-communication":return t(R.string.option_mic_voice_communication);
            case "voice-call":return t(R.string.option_voice_call);
            case "voice-call-uplink":return t(R.string.option_voice_call_uplink);
            case "voice-call-downlink":return t(R.string.option_voice_call_downlink);
            case "voice-performance":return t(R.string.option_voice_performance);
            default:return value;
        }
    }
}
