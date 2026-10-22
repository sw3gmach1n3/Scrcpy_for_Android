package ro.sw3g.scrcpy;

import android.app.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.hardware.usb.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements Session.Listener {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Object logLock=new Object();
    private final StringBuilder visibleLog=new StringBuilder();
    private volatile boolean dirty=false,destroyed=false;
    private File logFile,lastMedia;
    private String lastMime="video/mp4";
    private FrameLayout root,player;
    private LinearLayout home,body;
    private TextView console,status;
    private EditText host,port,pairPort,pairCode,command;
    private final Map<String,View> fields=new LinkedHashMap<>();
    private SharedPreferences prefs;
    private Session session;
    private RemoteView remote;
    private Surface renderSurface;
    private SurfaceTexture retainedVideoTexture;
    private final ConnectionGate connectionGate=new ConnectionGate();
    private Button connectButton;
    private int page;
    private boolean manualStop,leaving,retryPending;
    private volatile boolean pairing;
    private String lastHost;private int lastPort;
    private Options lastOptions;
    private final Runnable flushConsole=new Runnable(){public void run(){if(destroyed||!connectionGate.canStart())return;if(dirty&&console!=null){String text;synchronized(logLock){text=visibleLog.toString();dirty=false;}console.setText(ConsoleText.render(text));}ui.postDelayed(this,250);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("settings",MODE_PRIVATE);I18n.initialize(this);logFile=new File(getFilesDir(),"console.log");
        getWindow().setNavigationBarColor(Color.BLACK);getWindow().setStatusBarColor(Color.BLACK);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());root.setPadding(sys.left,sys.top,sys.right,sys.bottom);}else root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);buildHome();io.execute(()->{try{if(logFile.exists()){byte[] bytes=read(logFile);String text=new String(bytes,StandardCharsets.UTF_8);synchronized(logLock){visibleLog.insert(0,text);trimLog();dirty=true;}}}catch(Exception ignored){}});log(I18n.t(R.string.text_069)+Session.VERSION);setupUsb();}
    private static final String USB_PERMISSION="ro.sw3g.scrcpy.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbDevice lastUsb;
    private boolean usbMode,usbReceiverRegistered,usbScanPending;
    private String permissionDeviceName;
    private TextView usbStatus;
    private AlertDialog usbPicker;
    private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(USB_PERMISSION.equals(action)){
                String requested=permissionDeviceName;permissionDeviceName=null;
                UsbDevice granted=requested==null?null:usbManager.getDeviceList().get(requested);
                if(granted!=null&&usbManager.hasPermission(granted)){connectionGate.approveUsb(requested);connectApprovedUsb();}
                else{connectionGate.suppressAutoUsb();log(I18n.t(R.string.text_070));if(usbStatus!=null)usbStatus.setText(I18n.t(R.string.text_071));}
            }else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)){
                connectionGate.allowAutoUsb();usbScanPending=connectionGate.canAutoUsb(prefs.getBoolean("usbAuto",true));if(connectionGate.canStart())scanUsb(usbScanPending);
            }else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
                UsbDevice removed=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                connectionGate.detached(removed==null?null:removed.getDeviceName());
                if(removed!=null&&removed.getDeviceName().equals(permissionDeviceName))permissionDeviceName=null;
                if(usbMode&&lastUsb!=null&&(removed==null||removed.getDeviceName().equals(lastUsb.getDeviceName()))){
                    usbScanPending=false;manualStop=true;retryPending=false;log(I18n.t(R.string.text_072));
                    if(session!=null)session.stop();else leavePlayer();
                }
                if(connectionGate.canStart())scanUsb(false);
            }
        }
    };
    private void setupUsb(){
        usbManager=(UsbManager)getSystemService(USB_SERVICE);
        IntentFilter filter=new IntentFilter(USB_PERMISSION);filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(usbReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(usbReceiver,filter);
        usbReceiverRegistered=true;
    }
    private void autoUsb(){scanUsb(connectionGate.canAutoUsb(prefs.getBoolean("usbAuto",true)));}
    private boolean connectApprovedUsb(){
        String name=connectionGate.takeApprovedUsb();if(name==null||destroyed)return false;
        UsbDevice device=usbManager.getDeviceList().get(name);
        if(device!=null&&usbManager.hasPermission(device)){beginUsb(device);return true;}return false;
    }
    private ArrayList<UsbDevice> adbUsbDevices(){
        ArrayList<UsbDevice> devices=new ArrayList<>();if(usbManager==null)return devices;
        for(UsbDevice d:usbManager.getDeviceList().values())if(UsbAdbBridge.findAdbInterface(d)!=null)devices.add(d);
        devices.sort(Comparator.comparing(UsbDevice::getDeviceName));return devices;
    }
    private void scanUsb(boolean connect){
        if(usbManager==null||destroyed||!connectionGate.canStart())return;
        if(!getPackageManager().hasSystemFeature("android.hardware.usb.host")){if(usbStatus!=null)usbStatus.setText(I18n.t(R.string.text_073));return;}
        ArrayList<UsbDevice> devices=adbUsbDevices();
        if(usbStatus!=null){StringBuilder text=new StringBuilder();for(UsbDevice d:devices)text.append("• ").append(UsbAdbBridge.deviceLabel(d)).append('\n');
            usbStatus.setText(devices.isEmpty()?I18n.t(R.string.text_074):text.toString().trim());}
        if(!connect||devices.isEmpty()||session!=null||player!=null||pairing||permissionDeviceName!=null||usbPicker!=null)return;
        usbScanPending=false;
        if(devices.size()==1)requestUsb(devices.get(0));else{
            String[] names=new String[devices.size()];for(int i=0;i<names.length;i++)names[i]=UsbAdbBridge.deviceLabel(devices.get(i));
            usbPicker=new AlertDialog.Builder(this).setTitle(I18n.t(R.string.text_075)).setItems(names,(d,n)->requestUsb(devices.get(n))).setNegativeButton(I18n.t(R.string.text_076),(d,w)->connectionGate.suppressAutoUsb()).setOnCancelListener(d->connectionGate.suppressAutoUsb()).create();usbPicker.setOnDismissListener(d->usbPicker=null);usbPicker.show();
        }
    }
    private void requestUsb(UsbDevice device){
        if(destroyed||!connectionGate.canStart()||session!=null||player!=null||permissionDeviceName!=null)return;
        if(usbManager.hasPermission(device)){beginUsb(device);return;}
        permissionDeviceName=device.getDeviceName();
        PendingIntent permission=PendingIntent.getBroadcast(this,71,new Intent(USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        try{usbManager.requestPermission(device,permission);log(I18n.t(R.string.text_077)+UsbAdbBridge.deviceLabel(device));}
        catch(Exception e){permissionDeviceName=null;toast(I18n.t(R.string.text_078)+e.getMessage());log(I18n.t(R.string.text_078)+e);}
    }
    private void beginUsb(UsbDevice device){
        if(destroyed||!connectionGate.canStart()||player!=null||session!=null)return;
        try{saveFields();lastOptions=new Options(prefs);lastOptions.server();lastUsb=device;usbMode=true;lastHost="";lastPort=0;manualStop=false;leaving=false;
            status.setText(I18n.t(R.string.text_079)+UsbAdbBridge.deviceLabel(device));openPlayer();
        }catch(Exception e){log(I18n.t(R.string.text_080)+e);toast(e.getMessage());}
    }
    private boolean usbStillAttached(){return lastUsb!=null&&usbManager!=null&&usbManager.getDeviceList().containsKey(lastUsb.getDeviceName())&&usbManager.hasPermission(lastUsb);}

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private TextView title(String text){return label(text,21,Color.rgb(232,234,239));}
    private TextView note(String text){return label(text,13,Color.rgb(151,157,168));}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(Color.rgb(218,227,238));b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x335e809e),shape(Color.rgb(25,29,36),16),null));b.setMinHeight(dp(44));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setOnClickListener(v->action.run());return b;}
    private void addButton(LinearLayout l,String text,Runnable action){Button b=button(text,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(8);l.addView(b,p);}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(16));c.setBackground(shape(Color.rgb(12,14,18),20));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);parent.addView(c,p);return c;}
    private EditText input(LinearLayout parent,String caption,String value,String hint,boolean numeric){parent.addView(note(caption));EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setTextSize(16);e.setHintTextColor(Color.GRAY);e.setHint(hint);e.setText(value);e.setPadding(dp(10),dp(8),dp(10),dp(8));e.setBackground(shape(Color.rgb(22,24,30),10));if(numeric)e.setInputType(InputType.TYPE_CLASS_NUMBER);parent.addView(e,new LinearLayout.LayoutParams(-1,dp(46)));return e;}
    private ScrollView scroll(LinearLayout content){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(content,new ScrollView.LayoutParams(-1,-2));return s;}
    private void buildHome(){home=column();home.setPadding(dp(14),dp(8),dp(14),0);root.addView(home,new FrameLayout.LayoutParams(-1,-1));
        TextView heading=title("Scrcpy");heading.setTypeface(null,1);home.addView(heading);home.addView(note(I18n.t(R.string.text_081)));
        status=note(I18n.t(R.string.text_082));home.addView(status);
        LinearLayout tabs=row();String[] names={I18n.t(R.string.text_083),I18n.t(R.string.text_084),"Help",I18n.t(R.string.text_085)};for(int i=0;i<names.length;i++){final int n=i;Button b=button(names[i],()->select(n));b.setTextSize(12);b.setPadding(0,0,0,0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(2),0,dp(2),dp(10));tabs.addView(b,p);}home.addView(tabs);
        body=column();home.addView(body,new LinearLayout.LayoutParams(-1,0,1));select(0);
    }
    private void select(int n){saveFields();page=n;console=null;body.removeAllViews();if(n==0)connectionPage();else if(n==1)settingsPage();else if(n==2)helpPage();else consolePage();}
    private void connectionPage(){
        LinearLayout content=column();body.addView(scroll(content),new LinearLayout.LayoutParams(-1,-1));
        LinearLayout c=card(content);c.addView(title(I18n.t(R.string.text_086)));
        c.addView(note(I18n.t(R.string.text_087)));
        usbStatus=note(I18n.t(R.string.text_088));c.addView(usbStatus);
        Switch auto=new Switch(this);auto.setText(I18n.t(R.string.text_089));auto.setTextColor(0xffbfc9d6);auto.setChecked(prefs.getBoolean("usbAuto",true));c.addView(auto);
        auto.setOnCheckedChangeListener((v,enabled)->{prefs.edit().putBoolean("usbAuto",enabled).apply();if(enabled){connectionGate.allowAutoUsb();autoUsb();}});
        addButton(c,I18n.t(R.string.text_090),()->{connectionGate.allowAutoUsb();scanUsb(true);});addButton(c,I18n.t(R.string.text_091),()->scanUsb(false));
        c.addView(note(I18n.t(R.string.text_092)));
        LinearLayout wireless=column();wireless.setVisibility(View.GONE);
        addButton(content,I18n.t(R.string.text_093),()->wireless.setVisibility(wireless.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        content.addView(wireless);
        c=card(wireless);c.addView(title(I18n.t(R.string.text_094)));c.addView(note(I18n.t(R.string.text_095)));
        host=input(c,I18n.t(R.string.text_096),prefs.getString("host",""),"192.168.1.50",false);host.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        port=input(c,I18n.t(R.string.text_097),prefs.getString("port","5555"),I18n.t(R.string.text_098),true);
        connectButton=button(I18n.t(R.string.text_099),this::begin);LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(-1,dp(50));b.topMargin=dp(16);c.addView(connectButton,b);
        c=card(wireless);c.addView(title(I18n.t(R.string.text_100)));c.addView(note(I18n.t(R.string.text_101)));
        pairPort=input(c,I18n.t(R.string.text_102),prefs.getString("pairPort",""),I18n.t(R.string.text_103),true);
        pairCode=input(c,I18n.t(R.string.text_104),"",I18n.t(R.string.text_105),true);pairCode.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        addButton(c,I18n.t(R.string.text_106),this::pair);
        c=card(wireless);c.addView(title(I18n.t(R.string.text_107)));c.addView(note(I18n.t(R.string.text_108)));
        addButton(content,I18n.t(R.string.text_109),this::exportMedia);
        if(usbManager!=null)scanUsb(false);
    }
    private void settingsPage(){LinearLayout content=column();body.addView(scroll(content),new LinearLayout.LayoutParams(-1,-1));LinearLayout presets=card(content);presets.addView(title(I18n.t(R.string.text_110)));presets.addView(note(I18n.t(R.string.text_111)));
        addButton(presets,I18n.t(R.string.text_112),()->preset("1920","60","24000000"));
        addButton(presets,I18n.t(R.string.text_113),()->preset("1280","60","8000000"));
        addButton(presets,I18n.t(R.string.text_114),()->preset("800","30","2000000"));
        Options o=new Options(prefs);fields.clear();
        for(String[] f:Options.fields()){LinearLayout c=card(content);c.addView(label(f[1],16,Color.WHITE));String hint=f[3],val=o.get(f[0]);View v;
            if(hint.equals("bool")){Switch sw=new Switch(this);sw.setText(I18n.t(R.string.text_115));sw.setTextColor(0xffbfc9d6);sw.setChecked(Boolean.parseBoolean(val));c.addView(sw);sw.setOnCheckedChangeListener((b,checked)->prefs.edit().putString(f[0],Boolean.toString(checked)).apply());v=sw;}
            else if(hint.contains("|")){Spinner sp=new Spinner(this);String[] choices=hint.split("\\|",-1);String[] labels=new String[choices.length];for(int i=0;i<choices.length;i++)labels[i]=I18n.optionLabel(choices[i]);sp.setTag(choices);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);sp.setAdapter(a);int selected=Arrays.asList(choices).indexOf(val);sp.setSelection(Math.max(0,selected));c.addView(sp,new LinearLayout.LayoutParams(-1,dp(46)));v=sp;}
            else{EditText e=new EditText(this);e.setText(val);e.setTextColor(Color.WHITE);e.setTextSize(15);e.setSingleLine(!f[0].equals("extra"));if(f[0].equals("extra")){e.setMinLines(4);e.setGravity(Gravity.TOP);}if(SettingsChoices.numeric(f[0]))e.setInputType(InputType.TYPE_CLASS_NUMBER|((f[0].equals("max_fps")||f[0].equals("angle")||f[0].equals("mouseSpeed"))?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));
                LinearLayout editRow=row();editRow.setGravity(Gravity.TOP);editRow.addView(e,new LinearLayout.LayoutParams(0,-2,1));
                Button choose=button("▾",()->chooseSetting(f[0],e));choose.setTextSize(22);choose.setContentDescription(I18n.t(R.string.text_116)+f[1]);choose.setMinWidth(0);choose.setMinimumWidth(0);LinearLayout.LayoutParams choiceLayout=new LinearLayout.LayoutParams(dp(48),dp(46));choiceLayout.setMargins(dp(8),0,0,0);editRow.addView(choose,choiceLayout);c.addView(editRow);c.addView(note(hint));v=e;}
            fields.put(f[0],v);
        }
        addButton(content,I18n.t(R.string.text_117),()->{saveFields();try{new Options(prefs).server();toast(I18n.t(R.string.text_118));}catch(Exception e){toast(I18n.t(R.string.text_119)+e.getMessage());}});
        addButton(content,I18n.t(R.string.text_120),()->new AlertDialog.Builder(this).setMessage(I18n.t(R.string.text_121)).setPositiveButton(I18n.t(R.string.text_122),(d,w)->{SharedPreferences.Editor e=prefs.edit();for(String[]f:Options.fields())e.remove(f[0]);e.apply();fields.clear();body.removeAllViews();settingsPage();}).setNegativeButton(I18n.t(R.string.text_076),null).show());
        LinearLayout language=card(content);LinearLayout.LayoutParams languageLayout=(LinearLayout.LayoutParams)language.getLayoutParams();languageLayout.topMargin=dp(12);language.setLayoutParams(languageLayout);language.addView(label(I18n.t(R.string.language),16,Color.WHITE));language.addView(note(I18n.t(R.string.language_hint)));
        Spinner picker=new Spinner(this);String[] languages={"English","Română"};
        picker.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,languages));picker.setContentDescription(I18n.t(R.string.language));
        picker.setSelection(I18n.romanian()?1:0);language.addView(picker,new LinearLayout.LayoutParams(-1,dp(46)));
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                String code=position==1?"ro":"en";if(code.equals(I18n.language()))return;
                saveFields();prefs.edit().putString("appLanguage",code).apply();I18n.initialize(MainActivity.this);
                fields.clear();host=null;port=null;pairPort=null;pairCode=null;console=null;usbStatus=null;
                root.removeView(home);buildHome();select(1);
                if(body.getChildCount()>0&&body.getChildAt(0) instanceof ScrollView){ScrollView scroll=(ScrollView)body.getChildAt(0);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
            }
        });
    }
    private void chooseSetting(String key,EditText edit){
        SettingsChoices.Choice[] choices=SettingsChoices.options(key);PopupMenu menu=new PopupMenu(this,edit.getParent() instanceof View?((View)edit.getParent()):edit);
        for(int i=0;i<choices.length;i++)menu.getMenu().add(0,i,i,choices[i].label);menu.getMenu().add(0,choices.length,choices.length,I18n.t(R.string.text_123));
        menu.setOnMenuItemClickListener(item->{int n=item.getItemId();if(n==choices.length){edit.requestFocus();edit.setSelection(edit.getText().length());((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(edit,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}else{edit.setText(choices[n].value);prefs.edit().putString(key,choices[n].value).apply();}return true;});menu.show();
    }
    private void preset(String size,String fps,String bitrate){saveFields();prefs.edit().putString("max_size",size).putString("max_fps",fps).putString("video_bit_rate",bitrate).putString("video_codec","h264").apply();fields.clear();body.removeAllViews();settingsPage();}
    private void saveFields(){if(prefs==null)return;if(page==1&&!fields.isEmpty()){SharedPreferences.Editor e=prefs.edit();for(Map.Entry<String,View>x:fields.entrySet()){View v=x.getValue();String val=v instanceof Switch?Boolean.toString(((Switch)v).isChecked()):v instanceof Spinner?((String[])v.getTag())[((Spinner)v).getSelectedItemPosition()]:((EditText)v).getText().toString().trim();e.putString(x.getKey(),val);}e.apply();}if(host!=null)prefs.edit().putString("host",host.getText().toString().trim()).putString("port",port.getText().toString().trim()).putString("pairPort",pairPort==null?"":pairPort.getText().toString().trim()).apply();}
    private void helpPage(){body.addView(new HelpView(this),new LinearLayout.LayoutParams(-1,-1));}
    private void consolePage(){LinearLayout toolbar=row();toolbar.addView(button(I18n.t(R.string.text_128),()->copyConsole()),new LinearLayout.LayoutParams(0,dp(44),1));toolbar.addView(button(I18n.t(R.string.text_129),()->clearConsole()),new LinearLayout.LayoutParams(0,dp(44),1));toolbar.addView(button(I18n.t(R.string.text_130),()->exportLog()),new LinearLayout.LayoutParams(0,dp(44),1));body.addView(toolbar);
        body.addView(note(I18n.t(R.string.text_131)));
        console=label("",13,0xffd8dfe8);console.setLineSpacing(dp(3),1);console.setTypeface(android.graphics.Typeface.MONOSPACE);console.setTextIsSelectable(true);showConsoleLog(console);ScrollView scroll=new ScrollView(this);scroll.addView(console);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        command=new EditText(this);command.setTextColor(Color.WHITE);command.setTextSize(14);command.setHint(I18n.t(R.string.text_132));command.setSingleLine(true);body.addView(command);
        addButton(body,I18n.t(R.string.text_133),()->{String cmd=command.getText().toString();if(!cmd.trim().isEmpty()&&session!=null)session.shell(cmd);else toast(I18n.t(R.string.text_134));});
    }
    private String asset(String name)throws IOException{try(InputStream in=getAssets().open(name)){return new String(read(in),StandardCharsets.UTF_8);}}
    private int validPort(String s){int p=Integer.parseInt(s.trim());if(p<1||p>65535)throw new IllegalArgumentException(I18n.t(R.string.text_135));return p;}
    private void begin(){if(!connectionGate.canStart()||destroyed)return;saveFields();try{if(pairing){toast(I18n.t(R.string.text_136));return;}lastHost=prefs.getString("host","").trim();if(lastHost.isEmpty())throw new IllegalArgumentException(I18n.t(R.string.text_137));lastPort=validPort(prefs.getString("port","5555"));lastOptions=new Options(prefs);lastOptions.server();usbMode=false;lastUsb=null;manualStop=false;leaving=false;openPlayer();}catch(Exception e){toast(e.getMessage());log(I18n.t(R.string.text_138)+e.getMessage());}}
    private void pair(){if(!connectionGate.canStart()||destroyed)return;saveFields();if(pairing)return;final String h=host.getText().toString().trim(),code=pairCode.getText().toString().trim();final int p;try{p=validPort(pairPort.getText().toString());if(h.isEmpty()||!code.matches("[0-9]{6}"))throw new IllegalArgumentException(I18n.t(R.string.text_139));}catch(Exception e){toast(e.getMessage());return;}
        pairing=true;status.setText(I18n.t(R.string.text_140));io.execute(()->{try{AdbIdentity a=new AdbIdentity(this);a.pair(h,p,code);a.disconnect();log(I18n.t(R.string.text_141)+h+I18n.t(R.string.text_142));ui.post(()->{status.setText(I18n.t(R.string.text_143));pairCode.setText("");});}catch(Exception e){log(I18n.t(R.string.text_144)+e);ui.post(()->status.setText(I18n.t(R.string.text_145)));}finally{pairing=false;}});
    }
    private void openPlayer(){if(destroyed||!connectionGate.canStart()||player!=null)return;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);home.setVisibility(View.GONE);
        player=new FrameLayout(this);player.setMotionEventSplittingEnabled(true);player.setBackgroundColor(Color.BLACK);root.addView(player,new FrameLayout.LayoutParams(-1,-1));remote=new RemoteView(this,this::log);remote.speed=Float.parseFloat(lastOptions.get("mouseSpeed"));player.addView(remote,new FrameLayout.LayoutParams(-1,-1));remote.requestFocus();createMouseButtons();
        Button menu=button("•••",this::sessionMenu);menu.setContentDescription(I18n.t(R.string.text_146));menu.setAlpha(.42f);menu.setMinWidth(0);menu.setMinimumWidth(0);menu.setPadding(0,0,0,0);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(40),dp(36),Gravity.TOP|Gravity.END);mp.setMargins(dp(8),dp(10),dp(10),0);player.addView(menu,mp);
        // Long press and drag moves the small button; a tap opens the menu.
        menu.setOnTouchListener(new View.OnTouchListener(){float x,y,tx,ty;boolean moved;public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==0){x=e.getRawX();y=e.getRawY();tx=v.getTranslationX();ty=v.getTranslationY();moved=false;return true;}if(e.getActionMasked()==2){float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.hypot(dx,dy)>dp(8))moved=true;if(moved){v.setTranslationX(Math.max(-player.getWidth()+dp(50),Math.min(0,tx+dx)));v.setTranslationY(Math.max(0,Math.min(player.getHeight()-dp(60),ty+dy)));}return true;}if(e.getActionMasked()==1){if(!moved)v.performClick();return true;}return true;}});
        remote.texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){
                if(retainedVideoTexture!=null){SurfaceTexture retained=retainedVideoTexture;retainedVideoTexture=null;if(texture!=retained)remote.texture.setSurfaceTexture(retained);}
                if(renderSurface==null)renderSurface=new Surface(remote.texture.getSurfaceTexture());if(session!=null)session.setVideoVisible(connectionGate.canStart());connectSession();
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
                // Keep the existing decoder target when an active session is hidden.
                if(session!=null)session.setVideoVisible(false);
                if(!destroyed&&!manualStop&&session!=null){retainedVideoTexture=texture;return false;}
                if(session!=null){manualStop=true;session.stop();}
                if(renderSurface!=null){renderSurface.release();renderSurface=null;}return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
        if(Build.VERSION.SDK_INT>=30){getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void connectSession(){if(destroyed||!connectionGate.canStart()||player==null||remote==null||manualStop||renderSurface==null||!renderSurface.isValid()||session!=null)return;try{session=new Session(this,lastHost,lastPort,lastOptions,renderSurface,this,usbMode?lastUsb:null);remote.session=session;session.start();}catch(Exception e){log(I18n.t(R.string.text_147)+e);leavePlayer();}}
    private LinearLayout mouseButtonBar;
    private SessionPanel activePanel;
    private Dialog activeConsole;
    private final class SessionPanel {
        final Dialog dialog=new Dialog(MainActivity.this);
        final LinearLayout content=column();
        final TextView heading=title(I18n.t(R.string.text_148)),subtitle=note("");
        SessionPanel(){
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setCanceledOnTouchOutside(false);
            LinearLayout panel=column();panel.setPadding(dp(16),dp(12),dp(16),dp(14));
            GradientDrawable bg=shape(0xff101318,24);bg.setStroke(dp(1),0xff2b313c);panel.setBackground(bg);
            LinearLayout header=row(),titles=column();titles.addView(heading);titles.addView(subtitle);header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
            Button close=button("×",dialog::dismiss);close.setContentDescription(I18n.t(R.string.text_149));close.setTextSize(24);header.addView(close,new LinearLayout.LayoutParams(dp(46),dp(46)));panel.addView(header);
            ScrollView scroll=scroll(content);scroll.setClipToPadding(false);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            addButton(panel,I18n.t(R.string.text_150),dialog::dismiss);dialog.setContentView(panel);
            dialog.setOnDismissListener(d->{if(activePanel==this)activePanel=null;if(remote!=null)remote.requestFocus();});
        }
        void show(){dialog.show();Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));WindowManager.LayoutParams p=w.getAttributes();p.width=Math.min(dp(540),Math.max(dp(240),root.getWidth()-dp(24)));p.height=Math.min(dp(670),Math.max(dp(190),root.getHeight()-dp(24)));p.dimAmount=.55f;p.gravity=Gravity.CENTER;w.setAttributes(p);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);}}
        void heading(String value){heading.setText(value);subtitle.setText(remote==null?I18n.t(R.string.text_151):remote.modeName());}
    }
    private void tileRow(LinearLayout parent,String[] names,Runnable[] actions){
        LinearLayout line=row();for(int i=0;i<names.length;i++){Button b=button(names[i],actions[i]);b.setTextSize(13);b.setGravity(Gravity.CENTER);b.setMaxLines(3);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(62),1);p.setMargins(dp(3),dp(4),dp(3),dp(4));line.addView(b,p);}parent.addView(line);
    }
    private void groupLabel(LinearLayout parent,String text){TextView t=note(text);t.setPadding(dp(3),dp(15),0,dp(3));parent.addView(t);}
    private void sessionMenu(){
        if(remote==null)return;remote.releaseTouches();if(activePanel!=null&&activePanel.dialog.isShowing())return;
        SessionPanel p=new SessionPanel();activePanel=p;showMainPanel(p);p.show();
    }
    private void showMainPanel(SessionPanel p){
        p.content.removeAllViews();p.heading(I18n.t(R.string.text_152));p.content.addView(note(I18n.t(R.string.text_153)));
        LinearLayout modes=row();Button[] buttons=new Button[3];String[] names={I18n.t(R.string.text_154),I18n.t(R.string.text_155),I18n.t(R.string.text_156)};
        Runnable paint=()->{for(int i=0;i<buttons.length;i++){boolean selected=remote!=null&&remote.mode()==i;GradientDrawable b=shape(selected?0xff293b4b:0xff1b2029,16);b.setStroke(dp(1),selected?0xff9bbbd5:0xff303744);buttons[i].setBackground(b);}p.heading(I18n.t(R.string.text_152));};
        for(int i=0;i<3;i++){final int mode=i;Button b=button(names[i],()->{setInteractionMode(mode);showMainPanel(p);});b.setTextSize(12);b.setPadding(dp(5),dp(6),dp(5),dp(6));b.setMaxLines(3);buttons[i]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(90),1);lp.setMargins(dp(3),dp(6),dp(3),dp(10));modes.addView(b,lp);}p.content.addView(modes);paint.run();
        p.content.addView(note(I18n.t(R.string.text_157)));
        groupLabel(p.content,I18n.t(R.string.text_158));
        TextView zoom=note("");Runnable updateZoom=()->zoom.setText(remote==null?"":String.format(java.util.Locale.ROOT,I18n.t(R.string.text_159),remote.zoomFactor()));
        p.content.addView(zoom);updateZoom.run();
        tileRow(p.content,new String[]{"Zoom −","Reset 1×","Zoom +"},new Runnable[]{()->{if(remote!=null)remote.zoomBy(1/1.25f);updateZoom.run();},()->{if(remote!=null)remote.resetZoom();updateZoom.run();},()->{if(remote!=null)remote.zoomBy(1.25f);updateZoom.run();}});
        p.content.addView(note(I18n.t(R.string.text_160)));
        tileRow(p.content,new String[]{I18n.t(R.string.text_161),I18n.t(R.string.text_162)},new Runnable[]{()->showActionsPanel(p),this::sessionConsole});
        addButton(p.content,I18n.t(R.string.text_163),()->{p.dialog.dismiss();disconnect();});
    }
    private void withControl(java.util.function.Consumer<Control> action){Control c=session==null?null:session.control;if(c==null){toast(I18n.t(R.string.text_164));return;}action.accept(c);}
    private void showActionsPanel(SessionPanel p){
        if(remote==null)return;remote.releaseTouches();p.content.removeAllViews();p.heading(I18n.t(R.string.text_165));
        addButton(p.content,I18n.t(R.string.text_166),()->showMainPanel(p));
        groupLabel(p.content,I18n.t(R.string.text_167));
        tileRow(p.content,new String[]{I18n.t(R.string.text_168),"⌂\nHome",I18n.t(R.string.text_169)},new Runnable[]{()->withControl(c->c.clickKey(KeyEvent.KEYCODE_BACK)),()->withControl(c->c.clickKey(KeyEvent.KEYCODE_HOME)),()->withControl(c->c.clickKey(KeyEvent.KEYCODE_APP_SWITCH))});
        groupLabel(p.content,I18n.t(R.string.text_170));
        tileRow(p.content,new String[]{I18n.t(R.string.text_171),"Mute",I18n.t(R.string.text_172)},new Runnable[]{()->withControl(c->c.clickKey(KeyEvent.KEYCODE_VOLUME_DOWN)),()->withControl(c->c.clickKey(KeyEvent.KEYCODE_VOLUME_MUTE)),()->withControl(c->c.clickKey(KeyEvent.KEYCODE_VOLUME_UP))});
        groupLabel(p.content,I18n.t(R.string.text_173));
        tileRow(p.content,new String[]{I18n.t(R.string.text_174),I18n.t(R.string.text_175),I18n.t(R.string.text_176)},new Runnable[]{()->withControl(c->c.power(false)),()->withControl(c->c.power(true)),()->withControl(c->c.simple(11))});
        tileRow(p.content,new String[]{I18n.t(R.string.text_177),I18n.t(R.string.text_178),I18n.t(R.string.text_179)},new Runnable[]{()->withControl(c->c.simple(5)),()->withControl(c->c.simple(6)),()->withControl(c->c.simple(7))});
        groupLabel(p.content,I18n.t(R.string.text_180));
        tileRow(p.content,new String[]{I18n.t(R.string.text_181),I18n.t(R.string.text_182)},new Runnable[]{()->withControl(this::sendText),()->withControl(Control::getClipboard)});
        addButton(p.content,I18n.t(R.string.text_183),()->withControl(this::appName));
        groupLabel(p.content,I18n.t(R.string.text_184));
        tileRow(p.content,new String[]{I18n.t(R.string.text_185),I18n.t(R.string.text_186)},new Runnable[]{this::screenshot,this::exportMedia});
        groupLabel(p.content,I18n.t(R.string.text_187));
        tileRow(p.content,new String[]{I18n.t(R.string.text_188),I18n.t(R.string.text_189)},new Runnable[]{()->diagnostic("list_displays"),()->diagnostic("list_encoders")});
        tileRow(p.content,new String[]{I18n.t(R.string.text_190),I18n.t(R.string.text_191)},new Runnable[]{()->diagnostic("list_apps"),()->diagnostic("list_cameras")});
        addButton(p.content,I18n.t(R.string.text_192),()->diagnostic("list_camera_sizes"));addButton(p.content,I18n.t(R.string.text_193),this::sessionConsole);
    }
    private void diagnostic(String flag){if(session!=null)session.diagnostic(flag);toast(I18n.t(R.string.text_194));}
    private void setInteractionMode(int mode){
        if(remote==null||mouseButtonBar==null)return;mode=Math.max(0,Math.min(2,mode));remote.setMode(mode);prefs.edit().putInt("interactionMode",mode).apply();
        mouseButtonBar.setVisibility(mode==2?View.VISIBLE:View.GONE);FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)remote.getLayoutParams();lp.bottomMargin=0;remote.setLayoutParams(lp);
        for(int i=0;i<mouseButtonBar.getChildCount();i++)mouseButtonBar.getChildAt(i).setPressed(false);
    }
    private void createMouseButtons(){
        mouseButtonBar=row();mouseButtonBar.setMotionEventSplittingEnabled(true);mouseButtonBar.setPadding(0,0,0,0);mouseButtonBar.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams bar=new FrameLayout.LayoutParams(-1,dp(68),Gravity.BOTTOM);bar.setMargins(0,0,0,dp(12));player.addView(mouseButtonBar,bar);
        String[] labels={I18n.t(R.string.text_195),I18n.t(R.string.text_196)};int[] codes={MouseButtons.LEFT,MouseButtons.RIGHT};
        for(int i=0;i<2;i++){final int code=codes[i];Button b=button(labels[i],()->{if(remote!=null)remote.clickMouseButton(code);});b.setContentDescription(i==0?I18n.t(R.string.text_197):I18n.t(R.string.text_198));b.setFocusable(false);b.setTextSize(13);b.setPadding(dp(6),0,dp(6),0);b.setMinimumWidth(0);b.setMinWidth(0);b.setTextColor(Color.WHITE);b.setShadowLayer(dp(2),0,dp(1),Color.BLACK);b.setElevation(0);b.setStateListAnimator(null);b.setBackgroundTintList(null);
            GradientDrawable normal=shape(Color.TRANSPARENT,15);normal.setStroke(dp(1),0xffe4edf5);
            GradientDrawable pressed=shape(Color.TRANSPARENT,15);pressed.setStroke(dp(2),0xff80d5ff);
            android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();bg.addState(new int[]{android.R.attr.state_pressed},pressed);bg.addState(new int[]{},normal);b.setBackground(bg);
            b.setOnTouchListener((v,e)->{if(remote==null)return false;int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){v.setPressed(true);remote.mouseButton(code,true);return true;}if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){v.setPressed(false);remote.mouseButton(code,false);return true;}return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(4),0,dp(4),0);mouseButtonBar.addView(b,lp);
        }
        setInteractionMode(prefs.getInt("interactionMode",0));
    }
    private void sessionConsole(){
        if(activeConsole!=null&&activeConsole.isShowing())return;Dialog dialog=new Dialog(this);activeConsole=dialog;LinearLayout layout=column();layout.setPadding(dp(14),dp(12),dp(14),dp(12));layout.setBackgroundColor(Color.BLACK);
        layout.addView(title(I18n.t(R.string.text_199)));LinearLayout tools=row();
        tools.addView(button(I18n.t(R.string.text_128),this::copyConsole),new LinearLayout.LayoutParams(0,dp(44),1));tools.addView(button(I18n.t(R.string.text_129),this::clearConsole),new LinearLayout.LayoutParams(0,dp(44),1));tools.addView(button(I18n.t(R.string.text_130),this::exportLog),new LinearLayout.LayoutParams(0,dp(44),1));layout.addView(tools);
        TextView previous=console;TextView live=label("",13,0xffd8dfe8);live.setLineSpacing(dp(3),1);live.setTypeface(android.graphics.Typeface.MONOSPACE);live.setTextIsSelectable(true);showConsoleLog(live);console=live;
        ScrollView scroll=new ScrollView(this);scroll.addView(live);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        EditText cmd=new EditText(this);cmd.setTextColor(Color.WHITE);cmd.setSingleLine(true);cmd.setHint(I18n.t(R.string.text_200));layout.addView(cmd);
        addButton(layout,I18n.t(R.string.text_201),()->{if(session!=null&&!cmd.getText().toString().trim().isEmpty())session.shell(cmd.getText().toString());});
        addButton(layout,I18n.t(R.string.text_202),()->{if(session!=null)session.stopCommands();});
        addButton(layout,I18n.t(R.string.text_150),dialog::dismiss);dialog.setContentView(layout);dialog.setCanceledOnTouchOutside(false);dialog.setOnDismissListener(d->{console=previous;if(console!=null)showConsoleLog(console);if(activeConsole==dialog)activeConsole=null;});dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    private void sendText(Control c){EditText edit=new EditText(this);edit.setTextColor(Color.WHITE);ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);try{if(cb.hasPrimaryClip()&&cb.getPrimaryClip()!=null)edit.setText(cb.getPrimaryClip().getItemAt(0).coerceToText(this));}catch(Exception ignored){}
        new AlertDialog.Builder(this).setTitle(I18n.t(R.string.text_203)).setView(edit).setPositiveButton(I18n.t(R.string.text_204),(d,w)->c.clipboard(edit.getText().toString(),true)).setNeutralButton(I18n.t(R.string.text_205),(d,w)->c.clipboard(edit.getText().toString(),false)).setNegativeButton(I18n.t(R.string.text_076),null).show();}
    private void appName(Control c){EditText edit=new EditText(this);edit.setHint("com.android.settings");new AlertDialog.Builder(this).setTitle(I18n.t(R.string.text_206)).setView(edit).setPositiveButton(I18n.t(R.string.text_207),(d,w)->c.startApp(edit.getText().toString())).setNegativeButton(I18n.t(R.string.text_076),null).show();}
    private void screenshot(){if(remote==null||!remote.texture.isAvailable())return;Bitmap b=remote.texture.getBitmap();if(b==null){toast(I18n.t(R.string.text_208));return;}
        io.execute(()->{try{File dir=getExternalFilesDir(Environment.DIRECTORY_PICTURES);if(dir==null)dir=getFilesDir();File f=new File(dir,"Scrcpy_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".png");try(OutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.PNG,100,out);}mediaReady(f,"image/png");log(I18n.t(R.string.text_209)+f.getName());ui.post(()->toast(I18n.t(R.string.text_210)));}catch(Exception e){log(I18n.t(R.string.text_211)+e);}finally{b.recycle();}});
    }
    private void disconnect(){connectionGate.suppressAutoUsb();usbScanPending=false;manualStop=true;retryPending=false;if(remote!=null)remote.releaseTouches();if(session!=null){status.setText(I18n.t(R.string.text_212));session.stop();}else leavePlayer();}
    private void leavePlayer(){if(leaving)return;leaving=true;if(activeConsole!=null)activeConsole.dismiss();if(activePanel!=null)activePanel.dialog.dismiss();retryPending=false;if(player!=null){root.removeView(player);player=null;}releaseRetainedVideo();remote=null;mouseButtonBar=null;home.setVisibility(View.VISIBLE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=30)getWindow().getInsetsController().show(WindowInsets.Type.systemBars());else getWindow().getDecorView().setSystemUiVisibility(0);leaving=false;}
    private void releaseRetainedVideo(){if(retainedVideoTexture!=null){if(renderSurface!=null){renderSurface.release();renderSurface=null;}retainedVideoTexture.release();retainedVideoTexture=null;}}
    public void connected(String name){ui.post(()->{status.setText(I18n.t(R.string.text_018)+name);toast(I18n.t(R.string.text_213)+name);});}
    public void dimensions(int w,int h){ui.post(()->{if(remote!=null)remote.dimensions(w,h);});}
    public void clipboard(String text){ui.post(()->{try{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(I18n.t(R.string.text_214),text));log(I18n.t(R.string.text_215)+text.length()+I18n.t(R.string.text_216));}catch(Exception e){log("Clipboard: "+e.getMessage());}});}
    public void ended(Session who,String reason,boolean manual){ui.post(()->{if(session!=who)return;session=null;if(destroyed)return;if(remote!=null)remote.session=null;status.setText(reason);
        if(!destroyed&&connectionGate.canStart()&&!manual&&!manualStop&&lastOptions.yes("reconnect")&&player!=null&&(!usbMode||usbStillAttached())){retryPending=true;log(I18n.t(R.string.text_217));ui.postDelayed(()->{if(connectionGate.canStart()&&retryPending&&!manualStop&&session==null&&player!=null){retryPending=false;connectSession();}},2000);}else{leavePlayer();if(usbScanPending&&!destroyed&&connectionGate.canAutoUsb(prefs.getBoolean("usbAuto",true)))ui.post(this::autoUsb);}});}
    public void mediaReady(File file,String mime){ui.post(()->{lastMedia=file;lastMime=mime;});}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void showConsoleLog(TextView view){String text;synchronized(logLock){text=visibleLog.toString();}view.setText(ConsoleText.render(text));}
    private void trimLog(){if(visibleLog.length()>2*1024*1024){int start=visibleLog.length()-2*1024*1024;int nl=visibleLog.indexOf("\n",start);visibleLog.delete(0,nl<0?start:nl+1);}}
    public void log(String message){if(destroyed)return;StringBuilder b=new StringBuilder();for(String line:message.split("\\R",-1))b.append(LogFormat.create(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date()),line).line());String entry=b.toString();synchronized(logLock){visibleLog.append(entry);trimLog();dirty=true;}
        try{io.execute(()->{try(FileOutputStream out=new FileOutputStream(logFile,true)){out.write(entry.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
    }
    private void copyConsole(){io.execute(()->{try{String t=logFile.exists()?new String(read(logFile),StandardCharsets.UTF_8):"";ui.post(()->{try{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(I18n.t(R.string.text_218),t));toast(I18n.t(R.string.text_219));}catch(Exception e){toast(I18n.t(R.string.text_220));}});}catch(Exception e){log(I18n.t(R.string.text_221)+e);}});}
    private void clearConsole(){io.execute(()->{try{new FileOutputStream(logFile,false).close();synchronized(logLock){visibleLog.setLength(0);dirty=true;}}catch(Exception e){log(I18n.t(R.string.text_222)+e);}});}
    private File exportSource;
    private void exportLog(){exportFile(logFile,"text/plain","Scrcpy-console.txt");}
    private void exportMedia(){if(lastMedia==null||!lastMedia.exists()){toast(I18n.t(R.string.text_223));return;}exportFile(lastMedia,lastMime,lastMedia.getName());}
    private void exportFile(File f,String mime,String name){exportSource=f;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);try{startActivityForResult(i,80);}catch(Exception e){toast(e.getMessage());}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==80&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportSource!=null){File source=exportSource;Uri uri=data.getData();io.execute(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException(I18n.t(R.string.text_224));byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);ui.post(()->toast(I18n.t(R.string.text_225)));}catch(Exception e){log("Export: "+e);}});}}
    private static byte[] read(File file)throws IOException{try(InputStream in=new FileInputStream(file)){return read(in);}}
    private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[]tmp=new byte[65536];int n;while((n=in.read(tmp))!=-1)b.write(tmp,0,n);return b.toByteArray();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){if(hasWindowFocus()&&player!=null&&session!=null&&session.control!=null&&getCurrentFocus()!=null&&! (getCurrentFocus() instanceof EditText)&&e.getKeyCode()!=KeyEvent.KEYCODE_BACK){if(e.getAction()==0||e.getAction()==1){session.control.key(e.getAction(),e.getKeyCode(),e.getRepeatCount(),e.getMetaState());return true;}}return super.dispatchKeyEvent(e);}
    @Override public void onBackPressed(){if(player!=null)sessionMenu();else if(body.getChildCount()>0&&body.getChildAt(0) instanceof HelpView&&((HelpView)body.getChildAt(0)).navigateBack())return;else super.onBackPressed();}
    @Override protected void onResume(){
        super.onResume();connectionGate.resume();if(session!=null)session.setVideoVisible(remote!=null&&remote.texture.isAvailable()&&retainedVideoTexture==null);ui.removeCallbacks(flushConsole);ui.post(flushConsole);
        if(!connectApprovedUsb()){
            if(player!=null&&session==null&&!manualStop){retryPending=false;connectSession();}
            else autoUsb();
        }
    }
    @Override protected void onPause(){connectionGate.pause();retryPending=false;if(session!=null)session.setVideoVisible(false);if(usbPicker!=null)usbPicker.dismiss();ui.removeCallbacks(flushConsole);if(remote!=null)remote.releaseTouches();saveFields();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;connectionGate.pause();manualStop=true;retryPending=false;if(usbReceiverRegistered){try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}usbReceiverRegistered=false;}ui.removeCallbacksAndMessages(null);if(usbPicker!=null)usbPicker.dismiss();if(activeConsole!=null)activeConsole.dismiss();if(activePanel!=null)activePanel.dialog.dismiss();if(remote!=null)remote.releaseTouches();if(session!=null)session.stop();releaseRetainedVideo();io.shutdown();super.onDestroy();}
}
