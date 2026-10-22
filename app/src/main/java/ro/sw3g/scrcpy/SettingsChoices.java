package ro.sw3g.scrcpy;

/** Concrete editable presets; native device support still determines usable modes. */
final class SettingsChoices {
    static final class Choice {
        final String label,value;
        Choice(String label,String value){this.label=label;this.value=value;}
    }
    static Choice c(String label,String value){return new Choice(label,value);}
    static Choice[] options(String key){
        switch(key){
            case "max_size":return new Choice[]{c(I18n.t(R.string.text_048),"0"),c("4320 px","4320"),c("3840 px","3840"),c("2560 px","2560"),c("2400 px","2400"),c("2160 px","2160"),c("1920 px","1920"),c("1600 px","1600"),c("1440 px","1440"),c("1280 px","1280"),c("1024 px","1024"),c("960 px","960"),c("800 px","800"),c("720 px","720"),c("640 px","640"),c("480 px","480")};
            case "max_fps":return new Choice[]{c(I18n.t(R.string.text_049),"0"),c("24 FPS","24"),c("25 FPS","25"),c("30 FPS","30"),c("45 FPS","45"),c("60 FPS","60"),c("90 FPS","90"),c("120 FPS","120"),c("144 FPS","144"),c("165 FPS","165"),c("180 FPS","180"),c("240 FPS","240")};
            case "video_bit_rate":return new Choice[]{c("0.5 Mbps","500000"),c("1 Mbps","1000000"),c("2 Mbps","2000000"),c("4 Mbps","4000000"),c("6 Mbps","6000000"),c("8 Mbps","8000000"),c("12 Mbps","12000000"),c("16 Mbps","16000000"),c("24 Mbps","24000000"),c("32 Mbps","32000000"),c("48 Mbps","48000000"),c("64 Mbps","64000000"),c("80 Mbps","80000000"),c("100 Mbps","100000000"),c("150 Mbps","150000000")};
            case "audio_bit_rate":return new Choice[]{c("32 kbps","32000"),c("48 kbps","48000"),c("64 kbps","64000"),c("96 kbps","96000"),c("128 kbps","128000"),c("192 kbps","192000"),c("256 kbps","256000"),c("320 kbps","320000"),c("512 kbps","512000")};
            case "camera_fps":return new Choice[]{c("24 FPS","24"),c("25 FPS","25"),c("30 FPS","30"),c("60 FPS","60"),c("90 FPS","90"),c("120 FPS","120"),c("240 FPS","240")};
            case "mouseSpeed":return new Choice[]{c("×0.2","0.2"),c("×0.5","0.5"),c("×0.75","0.75"),c("×1.0","1.0"),c("×1.2","1.2"),c("×1.5","1.5"),c("×2.0","2.0"),c("×2.5","2.5"),c("×3.0","3.0"),c("×4.0","4.0"),c("×5.0","5.0")};
            case "angle":return new Choice[]{c("0°","0"),c("45°","45"),c("90°","90"),c("180°","180"),c("270°","270")};
            case "capture_orientation":return new Choice[]{c(I18n.t(R.string.text_050),""),c("0°","0"),c("90°","90"),c("180°","180"),c("270°","270"),c(I18n.t(R.string.text_051),"@0"),c(I18n.t(R.string.text_052),"@90"),c(I18n.t(R.string.text_053),"@180"),c(I18n.t(R.string.text_054),"@270")};
            case "camera_size":return new Choice[]{c(I18n.t(R.string.text_050),""),c("640 × 480","640x480"),c("1280 × 720","1280x720"),c("1920 × 1080","1920x1080"),c("2560 × 1440","2560x1440"),c("3840 × 2160","3840x2160")};
            case "new_display":return new Choice[]{c(I18n.t(R.string.text_055),""),c("1280 × 720 / 240 dpi","1280x720/240"),c("1920 × 1080 / 240 dpi","1920x1080/240"),c("1920 × 1080 / 320 dpi","1920x1080/320"),c("1080 × 1920 / 320 dpi","1080x1920/320"),c("2560 × 1440 / 320 dpi","2560x1440/320")};
            case "crop":return new Choice[]{c(I18n.t(R.string.text_056),""),c("1080:1920:0:0","1080:1920:0:0"),c("720:1280:0:0","720:1280:0:0"),c("1920:1080:0:0","1920:1080:0:0")};
            case "display_id":return new Choice[]{c(I18n.t(R.string.text_057),"0")};
            case "camera_id":return new Choice[]{c(I18n.t(R.string.text_050),""),c(I18n.t(R.string.text_058),"0"),c(I18n.t(R.string.text_059),"1")};
            case "video_codec_options":return new Choice[]{c(I18n.t(R.string.text_060),""),c(I18n.t(R.string.text_061),"i-frame-interval=1"),c(I18n.t(R.string.text_062),"i-frame-interval=2")};
            case "extra":return new Choice[]{c(I18n.t(R.string.text_063),""),c(I18n.t(R.string.text_064),"log_level=debug"),c(I18n.t(R.string.text_065),"camera_ar=16:9")};
            default:return new Choice[]{c(I18n.t(R.string.text_066),"")};
        }
    }
    static boolean numeric(String key){return java.util.Arrays.asList("max_size","max_fps","video_bit_rate","audio_bit_rate","camera_fps","mouseSpeed","angle","display_id").contains(key);}
}
