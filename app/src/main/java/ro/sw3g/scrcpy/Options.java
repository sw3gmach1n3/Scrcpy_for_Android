package ro.sw3g.scrcpy;
import android.content.SharedPreferences;
import java.util.*;

final class Options {
    final LinkedHashMap<String,String> values=new LinkedHashMap<>();
    static String[][] fields(){return new String[][]{
        {"max_size",I18n.t(R.string.text_241),"1280",I18n.t(R.string.text_242)},
        {"video_bit_rate",I18n.t(R.string.text_243),"8000000",I18n.t(R.string.text_244)},
        {"max_fps",I18n.t(R.string.text_245),"60",I18n.t(R.string.text_246)},
        {"video_codec",I18n.t(R.string.text_247),"h264","h264|h265|av1"},
        {"video_source",I18n.t(R.string.text_248),"display","display|camera"},
        {"display_id",I18n.t(R.string.text_249),"0",I18n.t(R.string.text_250)},
        {"crop",I18n.t(R.string.text_251),"",I18n.t(R.string.text_252)},
        {"capture_orientation",I18n.t(R.string.text_253),"",I18n.t(R.string.text_254)},
        {"angle",I18n.t(R.string.text_255),"0",I18n.t(R.string.text_256)},
        {"video_encoder",I18n.t(R.string.text_257),"",I18n.t(R.string.text_258)},
        {"video_codec_options",I18n.t(R.string.text_259),"",I18n.t(R.string.text_260)},
        {"audio",I18n.t(R.string.text_261),"false","bool"},
        {"audio_codec",I18n.t(R.string.text_262),"aac","aac|opus|raw|flac"},
        {"audio_bit_rate",I18n.t(R.string.text_263),"128000",I18n.t(R.string.text_264)},
        {"audio_source",I18n.t(R.string.text_265),"output","output|playback|mic|mic-unprocessed|mic-camcorder|mic-voice-recognition|mic-voice-communication|voice-call|voice-call-uplink|voice-call-downlink|voice-performance"},
        {"audio_dup",I18n.t(R.string.text_266),"false","bool"},
        {"audio_encoder",I18n.t(R.string.text_267),"",I18n.t(R.string.text_268)},
        {"audio_codec_options",I18n.t(R.string.text_269),"",I18n.t(R.string.text_270)},
        {"camera_id",I18n.t(R.string.text_271),"",I18n.t(R.string.text_272)},
        {"camera_facing",I18n.t(R.string.text_273),"","|front|back|external"},
        {"camera_size",I18n.t(R.string.text_274),"",I18n.t(R.string.text_275)},
        {"camera_fps",I18n.t(R.string.text_276),"30",I18n.t(R.string.text_277)},
        {"camera_high_speed",I18n.t(R.string.text_278),"false","bool"},
        {"new_display",I18n.t(R.string.text_279),"",I18n.t(R.string.text_280)},
        {"control",I18n.t(R.string.text_281),"true","bool"},
        {"show_touches",I18n.t(R.string.text_282),"false","bool"},
        {"stay_awake",I18n.t(R.string.text_283),"false","bool"},
        {"power_off_on_close",I18n.t(R.string.text_284),"false","bool"},
        {"clipboard_autosync",I18n.t(R.string.text_285),"true","bool"},
        {"downsize_on_error",I18n.t(R.string.text_286),"true","bool"},
        {"screenOff",I18n.t(R.string.text_287),"false","bool"},
        {"record",I18n.t(R.string.text_288),"false","bool"},
        {"reconnect",I18n.t(R.string.text_289),"true","bool"},
        {"mouseSpeed",I18n.t(R.string.text_290),"1.2",I18n.t(R.string.text_291)},
        {"extra",I18n.t(R.string.text_292),"",I18n.t(R.string.text_293)}
    };}
    Options(SharedPreferences p){for(String[] f:fields())values.put(f[0],p.getString(f[0],f[2]));}
    String get(String key){return values.getOrDefault(key,"");}
    boolean yes(String key){return Boolean.parseBoolean(get(key));}
    void save(SharedPreferences p){SharedPreferences.Editor e=p.edit();for(Map.Entry<String,String>x:values.entrySet())e.putString(x.getKey(),x.getValue());e.apply();}
    LinkedHashMap<String,String> server() {
        LinkedHashMap<String,String> m=new LinkedHashMap<>(values);
        for(String key:new String[]{"screenOff","record","reconnect","mouseSpeed","extra"})m.remove(key);
        m.entrySet().removeIf(e->e.getValue().isEmpty());
        for(String line:get("extra").split("\\R")) {
            line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;
            int i=line.indexOf('=');if(i<1)throw new IllegalArgumentException(I18n.t(R.string.text_294)+line+I18n.t(R.string.text_295));
            String k=line.substring(0,i).trim(), v=line.substring(i+1).trim();
            if(!CATALOG.contains(k))throw new IllegalArgumentException(I18n.t(R.string.text_296)+k);
            if(RESERVED.contains(k))throw new IllegalArgumentException(I18n.t(R.string.text_297)+k+I18n.t(R.string.text_298));
            m.put(k,v);
        }
        for(String k:new String[]{"max_size","video_bit_rate","audio_bit_rate","display_id","camera_fps"}) {
            int n=Integer.parseInt(m.getOrDefault(k,"0"));if(n<0)throw new IllegalArgumentException(k+I18n.t(R.string.text_299));
        }
        float fps=Float.parseFloat(m.get("max_fps"));if(fps<0||fps>1000)throw new IllegalArgumentException(I18n.t(R.string.text_300));
        float speed=Float.parseFloat(get("mouseSpeed"));if(speed<0.2f||speed>5f)throw new IllegalArgumentException(I18n.t(R.string.text_301));
        if(!Arrays.asList("h264","h265","av1").contains(m.get("video_codec")))throw new IllegalArgumentException(I18n.t(R.string.text_302));
        if(!Arrays.asList("aac","opus","raw","flac").contains(m.get("audio_codec")))throw new IllegalArgumentException(I18n.t(R.string.text_303));
        if(yes("record")&&(!m.get("video_codec").equals("h264")||Boolean.parseBoolean(m.get("audio"))&&!m.get("audio_codec").equals("aac")))
            throw new IllegalArgumentException(I18n.t(R.string.text_304));
        if(m.get("video_source").equals("camera"))m.put("control","false");
        return m;
    }
    static final Set<String> RESERVED=new HashSet<>(Arrays.asList("scid","video","tunnel_forward","send_device_meta","send_frame_meta","send_dummy_byte","send_codec_meta","raw_stream","cleanup","list_encoders","list_displays","list_cameras","list_camera_sizes","list_apps"));
    static final Set<String> CATALOG=new HashSet<>(Arrays.asList("scid","log_level","video","audio","video_codec","audio_codec","video_source","audio_source","audio_dup","max_size","video_bit_rate","audio_bit_rate","max_fps","angle","tunnel_forward","crop","control","display_id","show_touches","stay_awake","screen_off_timeout","video_codec_options","audio_codec_options","video_encoder","audio_encoder","power_off_on_close","clipboard_autosync","downsize_on_error","cleanup","power_on","list_encoders","list_displays","list_cameras","list_camera_sizes","list_apps","camera_id","camera_size","camera_facing","camera_ar","camera_fps","camera_high_speed","new_display","vd_destroy_content","vd_system_decorations","capture_orientation","display_ime_policy","send_device_meta","send_frame_meta","send_dummy_byte","send_codec_meta","raw_stream","cleanup","local","fallback","hide"));
}
