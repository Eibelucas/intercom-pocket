package de.local.intercompocket;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import android.app.UiModeManager;
import android.content.res.Configuration;
import java.text.DateFormat;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private int teal,rust,ink,muted,paper,cardColor,secondaryColor,onPrimary;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Config config;
    private LinearLayout page;
    private TextView time,liveHint;
    private Button talk;
    private Meter meter;
    private String fingerprint="";
    private boolean resumed;
    private Runnable afterPermission;
    private boolean answerPending;
    private boolean phoneReady;
    private String quickPeer="";
    private long quickUntil;
    private boolean quickStarted;
    private long lastSystemThemeCheck;
    private Boolean appliedSystemDark;
    private String screen="home";
    private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;refresh();handler.postDelayed(this,200);}};
    public void onCreate(Bundle saved){super.onCreate(saved);config=new Config(this);if(saved!=null)screen=saved.getString("screen","home");palette();setVolumeControlStream(android.media.AudioManager.STREAM_VOICE_CALL);answerPending=getIntent().getBooleanExtra("answer",false);quickPeer=getIntent().getStringExtra("quickPeer");if(quickPeer==null)quickPeer="";quickUntil=System.currentTimeMillis()+15000;render();}
    protected void onSaveInstanceState(Bundle state){state.putString("screen",screen);super.onSaveInstanceState(state);}
    public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);answerPending=i.getBooleanExtra("answer",false);String wanted=i.getStringExtra("quickPeer");if(wanted!=null&&!wanted.isEmpty()){screen="home";quickPeer=wanted;quickUntil=System.currentTimeMillis()+15000;quickStarted=false;}fingerprint="";}
    public void onResume(){super.onResume();syncSystemTheme();palette();KioskWidget.refreshAll(this);PhoneIntegration.register(this);phoneReady=PhoneIntegration.ready(this);fingerprint="";resumed=true;handler.post(tick);}
    public void onPause(){resumed=false;handler.removeCallbacks(tick);Engine e=engine();if(e!=null&&!e.handsFree)e.talk(false);super.onPause();}
    Engine engine(){IntercomService s=IntercomService.instance;return s==null?null:s.engine;}
    void syncSystemTheme(){if(!config.prefs.getString("theme","system").equals("system"))return;long now=System.currentTimeMillis();if(now-lastSystemThemeCheck<3000)return;lastSystemThemeCheck=now;boolean dark=(android.content.res.Resources.getSystem().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;if(appliedSystemDark!=null&&appliedSystemDark==dark)return;appliedSystemDark=dark;getSystemService(UiModeManager.class).setApplicationNightMode(dark?UiModeManager.MODE_NIGHT_YES:UiModeManager.MODE_NIGHT_NO);}
    void palette(){ThemePalette p=ThemePalette.load(this,config);teal=p.primary;onPrimary=p.onPrimary;rust=p.error;ink=p.onSurface;muted=p.muted;paper=p.background;cardColor=p.surface;secondaryColor=p.surfaceAlt;}
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    void refresh(){
        syncSystemTheme();
        Engine e=engine();StringBuilder f=new StringBuilder(e==null?"off":e.state+e.callId+e.config.dnd()+e.config.dndUntil()+e.config.quiet()+e.detail+e.phoneManaged);
        if(e!=null){for(Peer p:sorted(e))f.append(p.name).append(p.status).append(p.candidatePin).append(config.alias(p.id)).append(config.favorite(p.id));if(e.peer!=null)f.append(e.peer.candidatePin);}
        f.append(IntercomService.lastError);
        // Talk-state updates never replace the held touch target.
        if(!f.toString().equals(fingerprint)&&!(e!=null&&e.sending&&!e.handsFree)){fingerprint=f.toString();if(screen.equals("home")||e!=null&&e.active())render();}
        if(e!=null&&time!=null){long seconds=e.since==0?0:(System.currentTimeMillis()-e.since)/1000;time.setText(String.format(Locale.GERMAN,"%02d:%02d",seconds/60,seconds%60));}
        if(meter!=null)meter.invalidate();
        if(answerPending&&e!=null&&e.state.equals("ringing")&&e.callId.equals(getIntent().getStringExtra("call"))){answerPending=false;accept();}
        if(!quickPeer.isEmpty())quickCall(e);
    }
    ArrayList<Peer> sorted(Engine e){ArrayList<Peer> list=new ArrayList<>(e.peers.values());list.sort(Comparator.comparing((Peer p)->!config.favorite(p.id)).thenComparing(p->!p.ready).thenComparing(p->config.display(p)));return list;}
    void quickCall(Engine e){
        if(System.currentTimeMillis()>quickUntil){quickPeer="";toast("Kiosk gerade nicht erreichbar.");return;}
        if(e==null){if(!quickStarted&&!config.key().isEmpty()){quickStarted=true;enable();}return;}
        if(e.active()){quickPeer="";toast("Bitte zuerst das laufende Gespräch beenden.");return;}
        JSONObject known=null;JSONArray items=config.known();for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null&&quickPeer.equals(item.optString("id"))){known=item;break;}}
        if(known==null){quickPeer="";toast("Dieser Kiosk ist nicht mehr gespeichert.");return;}
        for(Peer p:e.peers.values())if(quickPeer.equals(p.id)&&p.ready){quickPeer="";withMic(()->{IntercomService s=IntercomService.instance;if(s!=null){s.microphone();e.call(p);}});return;}
        String host=known.optString("host");int port=known.optInt("port",2324);boolean tls=known.optBoolean("tls");
        boolean exists=false;for(Peer p:e.peers.values())if(host.equals(p.host)&&port==p.adminPort){exists=true;break;}
        if(!exists)try{e.add((tls?"https://":"http://")+host+":"+port);}catch(Exception ex){quickPeer="";toast("Kioskadresse ungültig.");}
    }
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    android.graphics.drawable.Drawable buttonShape(int color){StateListDrawable states=new StateListDrawable();GradientDrawable focused=shape(color,16);focused.setStroke(dp(3),color==teal?onPrimary:teal);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},shape(color,16));int ripple=((color==teal?onPrimary:teal)&0x00ffffff)|0x33000000;return new RippleDrawable(ColorStateList.valueOf(ripple),states,shape(Color.WHITE,16));}
    TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setIncludeFontPadding(false);return t;}
    void gap(LinearLayout box,int h){View v=new View(this);box.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    void add(LinearLayout box,View v){box.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    void tint(Switch control){int[][] states={{android.R.attr.state_checked},{-android.R.attr.state_checked}};control.setThumbTintList(new ColorStateList(states,new int[]{teal,muted}));control.setTrackTintList(new ColorStateList(states,new int[]{secondaryColor,cardColor}));}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout card(){LinearLayout c=column();c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(shape(cardColor,24));return c;}
    static final class AccessibleButton extends Button{AccessibleButton(Context c){super(c);}public boolean performClick(){return super.performClick();}}
    Button button(String label,int color,Runnable action){Button b=new AccessibleButton(this);b.setText(label);b.setTextColor(color==teal?onPrimary:Color.WHITE);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(54));b.setPadding(dp(16),dp(10),dp(16),dp(10));b.setBackground(buttonShape(color));b.setStateListAnimator(null);b.setOnClickListener(v->action.run());return b;}
    void secondary(LinearLayout box,String title,Runnable action){Button b=button(title,secondaryColor,action);b.setTextColor(ink);add(box,b);}
    void render(){
        time=null;talk=null;meter=null;liveHint=null;
        LinearLayout root=column();root.setBackgroundColor(paper);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        page=column();page.setPadding(dp(24),dp(18),dp(24),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(root);root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_intercom);header.addView(logo,new LinearLayout.LayoutParams(dp(36),dp(36)));
        Engine e=engine();boolean calling=e!=null&&e.active();
        if(!calling&&!screen.equals("home")){Button back=button("‹",paper,this::home);back.setTextColor(ink);back.setTextSize(28);back.setContentDescription("Zurück zur Übersicht");header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));}
        TextView brand=text(calling||screen.equals("home")?"Intercom Satelite":screen.equals("settings")?"Einstellungen":screen.equals("groups")?"Raumgruppen":"Anrufe",18,ink,true);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.setMargins(dp(12),0,0,0);header.addView(brand,bp);
        if(!calling&&screen.equals("home")){Button settings=button("⚙",paper,this::settings);settings.setTextColor(ink);settings.setContentDescription("Einstellungen");header.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));}add(page,header);gap(page,28);
        if(calling)renderCall(e);else if(screen.equals("settings"))renderSettings();else if(screen.equals("history"))renderHistory();else if(screen.equals("groups"))renderGroups();else renderHome(e);
    }
    void home(){screen="home";fingerprint="";render();}
    void renderHome(Engine e){
         LinearLayout availability=card();add(availability,text(e==null?"○  Du bist offline":config.dnd()?"◐  Nicht stören":config.quiet()?"◐  Ruhezeit":"●  Du bist erreichbar",18,e==null?muted:teal,true));gap(availability,8);
        add(availability,text(e==null?"Aktiviere Intercom, um im WLAN Anrufe zu empfangen.":config.name()+" · "+Discovery.localIp(),14,muted,false));gap(availability,18);
         if(e==null)add(availability,button(config.key().isEmpty()?"Intercom einrichten":"Intercom einschalten",teal,()->{if(config.key().isEmpty())settings();else enable();}));
        else{Switch dnd=new Switch(this);tint(dnd);dnd.setText("Nicht stören");dnd.setTextSize(15);dnd.setTextColor(ink);dnd.setChecked(config.dnd());dnd.setPadding(0,dp(6),0,dp(6));dnd.setOnCheckedChangeListener((b,on)->{config.setDnd(on);e.changed();});add(availability,dnd);
            if(config.dnd()){gap(availability,8);add(availability,text(config.dndUntil()==0?"Bis du es ausschaltest":"Bis "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.GERMAN).format(new Date(config.dndUntil())),14,muted,false));}
            gap(availability,12);secondary(availability,"Nicht stören für …",this::snoozeDnd);
        }
        add(page,availability);gap(page,24);
        if(!phoneReady){LinearLayout phoneCard=card();add(phoneCard,text("Telefonanrufe aktivieren",18,ink,true));gap(phoneCard,8);add(phoneCard,text("Eingehende Anrufe erscheinen dann in deiner Telefon-App.",14,muted,false));gap(phoneCard,14);secondary(phoneCard,"Anrufkonto einrichten",this::phoneSettings);add(page,phoneCard);gap(page,24);}
        if(!IntercomService.lastError.isEmpty()){add(page,text(IntercomService.lastError,14,rust,false));gap(page,16);}
        if(e!=null&&!e.detail.isEmpty()){add(page,text(e.detail,14,muted,false));gap(page,16);}
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);TextView rooms=text("Kiosks",21,ink,true);title.addView(rooms,new LinearLayout.LayoutParams(0,-2,1));
        if(e!=null){Button refresh=button("↻",paper,e::refresh);refresh.setTextColor(teal);refresh.setContentDescription("Geräte aktualisieren");title.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));}add(page,title);gap(page,12);
        if(e==null||e.peers.isEmpty()){
            LinearLayout empty=card();add(empty,text(e==null?"Intercom einschalten":"Suche Kiosks im WLAN …",17,ink,true));gap(empty,8);add(empty,text("Prüfe am Kiosk Intercom, Remote Administration und denselben Schlüssel.",14,muted,false));add(page,empty);
        }else for(Peer p:sorted(e)){
            LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);LinearLayout label=column();add(label,text(config.icon(p.id)+"  "+config.display(p)+(config.favorite(p.id)?"  ★":""),18,ink,true));gap(label,6);add(label,text(p.status,13,p.ready?teal:muted,false));gap(label,5);add(label,text(p.host,12,muted,false));line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button call=button(p.ready?"Anrufen":p.candidatePin.isEmpty()?"Info":"Prüfen",p.ready?teal:secondaryColor,()->{if(!p.candidatePin.isEmpty())trust(p);else if(p.ready)withMic(()->{IntercomService s=IntercomService.instance;if(s!=null){s.microphone();e.call(p);}});else explain(p);});if(!p.ready)call.setTextColor(teal);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(52));lp.setMargins(dp(12),0,0,0);line.addView(call,lp);add(row,line);row.setOnLongClickListener(v->{roomOptions(e,p);return true;});add(page,row);gap(page,10);
        }
        if(e!=null){gap(page,12);secondary(page,"+  Kiosk hinzufügen",this::addPeer);gap(page,24);Button announce=button("Durchsage starten …",secondaryColor,this::chooseAnnouncement);announce.setTextColor(ink);boolean any=false;for(Peer p:e.peers.values())if(p.ready){any=true;break;}announce.setEnabled(any);if(!any)announce.setAlpha(.55f);add(page,announce);gap(page,10);secondary(page,"Anrufe ansehen",this::showHistory);gap(page,14);Button off=button("Erreichbarkeit ausschalten",paper,()->startService(new Intent(this,IntercomService.class).setAction(IntercomService.STOP)));off.setTextColor(muted);add(page,off);}
        gap(page,12);secondary(page,"Raumgruppen verwalten",()->{screen="groups";render();});
        gap(page,18);
    }
    void renderCall(Engine e){
        LinearLayout hero=card();hero.setPadding(dp(22),dp(28),dp(22),dp(24));
        TextView tag=text(e.state.equals("ringing")?"EINGEHENDER ANRUF":e.broadcast?"DURCHSAGE":"INTERCOM-ANRUF",12,teal,true);tag.setLetterSpacing(.12f);tag.setGravity(Gravity.CENTER);add(hero,tag);gap(hero,22);
        TextView avatar=text(e.broadcast?"◉":e.peer==null?"●":config.icon(e.peer.id),42,teal,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(secondaryColor,28));LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(dp(88),dp(88));avatarParams.gravity=Gravity.CENTER_HORIZONTAL;hero.addView(avatar,avatarParams);gap(hero,18);
        TextView name=text(e.state.equals("broadcasting")?e.announcementTitle:e.peer==null?"Kiosk":config.display(e.peer),32,ink,true);name.setGravity(Gravity.CENTER);add(hero,name);gap(hero,10);
        String status=switch(e.state){case "calling"->"Kiosk wird angerufen …";case "ringing"->"Möchte mit dir sprechen";case "connecting"->"Audio wird verbunden …";case "broadcasting"->e.detail;case "listening"->"Du hörst eine Durchsage";default->"Verbunden";};
        TextView s=text(status,16,muted,false);s.setGravity(Gravity.CENTER);add(hero,s);gap(hero,20);
        time=text("00:00",24,ink,true);time.setTypeface(Typeface.MONOSPACE);time.setGravity(Gravity.CENTER);add(hero,time);gap(hero,14);
        meter=new Meter();hero.addView(meter,new LinearLayout.LayoutParams(-1,dp(64)));add(page,hero);gap(page,22);
        if(!e.detail.isEmpty()&&!e.state.equals("in_call")&&!e.state.equals("broadcasting")){add(page,text(e.detail,14,rust,false));gap(page,12);}
        if(e.peer!=null&&!e.peer.candidatePin.isEmpty()){secondary(page,"Zertifikat des Kiosks bestätigen",()->trust(e.peer));gap(page,12);}
        if(e.phoneManaged){
            add(page,text(e.state.equals("ringing")?"Nimm den Anruf in der Telefon-App an. Die Anrufanzeige findest du auch in den Benachrichtigungen.":!e.audio.microphoneAvailable&&e.state.equals("in_call")?"Das Mikrofon ist nicht verfügbar. Du kannst zuhören.":"Du telefonierst über deine Telefon-App. Dort kannst du das Mikrofon stummschalten und zwischen Hörer, Lautsprecher und verbundenem Headset wechseln.",16,muted,false));gap(page,20);
            add(page,button(e.state.equals("ringing")?"Ablehnen":"Gespräch beenden",rust,()->e.finish(e.state.equals("ringing")?"Anruf abgelehnt":"In Intercom Satelite aufgelegt",true)));
        }
        else if(e.state.equals("ringing")){add(page,button("Anruf annehmen",teal,this::accept));gap(page,12);add(page,button("Ablehnen",rust,()->e.finish("Anruf abgelehnt",true)));}
        else if(e.state.equals("in_call")||e.state.equals("broadcasting")){
            liveHint=text(!e.audio.microphoneAvailable?"Du kannst zuhören. Das Mikrofon konnte nicht gestartet werden.":e.handsFree?(e.sending?"Freisprechen ist aktiv":"Dein Mikrofon ist stumm"):"Halte die Taste, um zu sprechen.",15,muted,false);liveHint.setGravity(Gravity.CENTER);add(page,liveHint);gap(page,18);
            talk=button(e.handsFree?(e.sending?"Mikrofon stummschalten":"Mikrofon einschalten"):"Zum Sprechen halten",teal,()->{});talk.setMinHeight(dp(112));talk.setTextSize(20);
            final boolean[] touchHeld={false};
            talk.setOnTouchListener((v,event)->{int a=event.getActionMasked();if(a==MotionEvent.ACTION_DOWN){v.getParent().requestDisallowInterceptTouchEvent(true);if(!e.handsFree){touchHeld[0]=true;e.talk(true);talk.setText("Du sprichst …");if(liveHint!=null)liveHint.setText("Deine Stimme wird übertragen");}return true;}if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){if(touchHeld[0]){e.talk(false);talk.setText("Zum Sprechen halten");if(liveHint!=null)liveHint.setText("Halte die Taste, um zu sprechen.");}v.getParent().requestDisallowInterceptTouchEvent(false);if(a==MotionEvent.ACTION_UP)v.performClick();else touchHeld[0]=false;return true;}return true;});
            talk.setOnClickListener(v->{if(touchHeld[0]){touchHeld[0]=false;return;}if(e.handsFree)e.talk(!e.sending);else e.handsFree(true);talk.setText(e.sending?"Mikrofon stummschalten":"Mikrofon einschalten");});if(!e.audio.microphoneAvailable){talk.setEnabled(false);talk.setText("Mikrofon nicht verfügbar");}add(page,talk);gap(page,18);
            Switch hf=new Switch(this);tint(hf);hf.setText("Freisprechen");hf.setTextColor(ink);hf.setTextSize(16);hf.setChecked(e.handsFree);hf.setEnabled(e.audio.microphoneAvailable);hf.setOnCheckedChangeListener((b,on)->{e.handsFree(on);talk.setText(on?"Mikrofon stummschalten":"Zum Sprechen halten");liveHint.setText(on?"Freisprechen ist aktiv":"Halte die Taste, um zu sprechen.");});add(page,hf);gap(page,18);
            Switch speaker=new Switch(this);tint(speaker);speaker.setText("Lautsprecher");speaker.setTextColor(ink);speaker.setChecked(e.speaker);speaker.setOnCheckedChangeListener((b,on)->{e.speaker=on;e.audio.speaker(on);});add(page,speaker);gap(page,26);
            add(page,button("Gespräch beenden",rust,()->e.finish("Gespräch beendet",true)));
        }else add(page,button(e.broadcast?"Durchsage schließen":"Abbrechen",rust,()->e.finish("Gespräch beendet",true)));
    }
    void accept(){Engine current=engine();if(current==null||current.phoneManaged)return;String expected=current.callId;withMic(()->{IntercomService s=IntercomService.instance;if(s!=null&&s.engine!=null&&s.engine.callId.equals(expected)&&!s.engine.phoneManaged){try{s.microphone();s.engine.answer();}catch(Exception e){toast("Mikrofon konnte nicht gestartet werden");}}});}
    void phoneSettings(){withMic(()->{
        if(Build.VERSION.SDK_INT<35&&checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)!=PackageManager.PERMISSION_GRANTED){afterPermission=this::phoneSettings;requestPermissions(new String[]{Manifest.permission.READ_PHONE_NUMBERS},44);return;}
        if(!PhoneIntegration.register(this)){toast("Die Telefon-App konnte das Intercom-Konto nicht registrieren.");return;}
        new AlertDialog.Builder(this).setTitle("Intercom als Telefonkonto")
            .setMessage("Aktiviere im nächsten Bildschirm Intercom Satelite bei den Anrufkonten. Je nach Samsung-Version heißt der Bereich „Anrufkonten“ oder „Zusätzliche Anrufdienste“. Deine normale SIM bleibt unverändert.")
            .setNegativeButton("Später",null).setPositiveButton("Einstellungen öffnen",(d,w)->{
                try{startActivity(new Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS));}
                catch(ActivityNotFoundException ex){toast("Öffne Telefon → Einstellungen → Anrufkonten und aktiviere Intercom Satelite.");}
            }).show();
    });}
    void enable(){
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){afterPermission=this::startEnabled;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},43);}else startEnabled();
    }
    void startEnabled(){try{startForegroundService(new Intent(this,IntercomService.class).setAction(IntercomService.ENABLE));}catch(Exception e){toast("Start nicht möglich: "+e.getMessage());}}
    void withMic(Runnable r){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){try{r.run();}catch(Exception e){toast("Mikrofon konnte nicht gestartet werden");}}else{afterPermission=r;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},42);}}
    public void onRequestPermissionsResult(int request,String[]permissions,int[]grants){super.onRequestPermissionsResult(request,permissions,grants);Runnable r=afterPermission;afterPermission=null;if(request==43){if(grants.length==0||grants[0]!=PackageManager.PERMISSION_GRANTED)toast("Ohne Benachrichtigungen sind eingehende Anrufe im Hintergrund nicht sichtbar.");if(r!=null)r.run();}else if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&r!=null)r.run();else toast(request==44?"Diese Android-Version benötigt die Telefonberechtigung, um das aktivierte Anrufkonto zu prüfen.":"Zum Sprechen bitte den Mikrofonzugriff erlauben.");}
    void addPeer(){EditText input=field("192.168.1.50:2324");new AlertDialog.Builder(this).setTitle("Kiosk hinzufügen").setMessage("IP-Adresse oder Hostname, optional mit Port. Für HTTPS: https://adresse:port").setView(input).setNegativeButton("Abbrechen",null).setPositiveButton("Hinzufügen",(d,w)->{try{Engine e=engine();if(e!=null)e.add(input.getText().toString().trim());}catch(Exception ex){toast(ex.getMessage());}}).show();}
    void roomOptions(Engine e,Peer p){
        String[] options={config.favorite(p.id)?"Aus Favoriten entfernen":"Als Favorit markieren","Name und Symbol","Kiosk entfernen"};
        new AlertDialog.Builder(this).setTitle(config.display(p)).setItems(options,(d,which)->{
            if(which==0){config.prefs.edit().putBoolean("favorite:"+p.id,!config.favorite(p.id)).apply();fingerprint="";render();}
            else if(which==1)editRoom(p);
            else if(p.manual)new AlertDialog.Builder(this).setMessage("Gespeicherten Kiosk entfernen?").setNegativeButton("Abbrechen",null).setPositiveButton("Entfernen",(dialog,w)->e.remove(p)).show();
            else toast("Automatisch erkannte Kiosks verschwinden, wenn sie offline sind.");
        }).show();
    }
    void editRoom(Peer p){
        LinearLayout box=column();box.setPadding(dp(24),dp(10),dp(24),dp(4));
        EditText name=field("Raumname");name.setText(config.alias(p.id));add(box,name);gap(box,10);
        String[] icons={"⌂","✦","●","♪","☼","★"};
        Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,icons);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);for(int i=0;i<icons.length;i++)if(icons[i].equals(config.icon(p.id)))spinner.setSelection(i);add(box,spinner);
        new AlertDialog.Builder(this).setTitle("Name und Symbol").setView(box).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{
            String alias=name.getText().toString().trim();if(alias.length()>40){toast("Name zu lang (maximal 40 Zeichen).");return;}
            config.prefs.edit().putString("alias:"+p.id,alias).putString("icon:"+p.id,icons[spinner.getSelectedItemPosition()]).apply();KioskWidget.refreshAll(this);fingerprint="";render();
        }).show();
    }
    void snoozeDnd(){
        String[] labels={"30 Minuten","2 Stunden","Bis morgen, 07:00 Uhr","Bis ich es ausschalte"};
        new AlertDialog.Builder(this).setTitle("Nicht stören für …").setItems(labels,(dialog,choice)->{
            if(choice==3)config.setDnd(true);
            else{long until=choice==2?java.time.ZonedDateTime.now().plusDays(1).withHour(7).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli():System.currentTimeMillis()+(choice==0?30:120)*60000L;config.snoozeUntil(until);}
            Engine e=engine();if(e!=null)e.changed();fingerprint="";render();
        }).setNegativeButton("Abbrechen",null).show();
    }
    void chooseAnnouncement(){
        List<RoomGroups.Group> groups=new RoomGroups(config.prefs).list();
        ArrayList<String> labels=new ArrayList<>(Arrays.asList("Alle verfügbaren Kiosks","Kiosks auswählen …"));
        for(RoomGroups.Group group:groups)labels.add(group.name+" · "+group.members.size()+" Kiosks");
        new AlertDialog.Builder(this).setTitle("Durchsage an …").setItems(labels.toArray(new String[0]),(dialog,choice)->{
            if(choice==0)startAnnouncement(null,"Alle Kiosks");
            else if(choice==1)selectAnnouncementPeers();
            else{RoomGroups.Group group=groups.get(choice-2);startAnnouncement(group.members,group.name);}
        }).setNegativeButton("Abbrechen",null).show();
    }
    void startAnnouncement(Set<String> members,String title){
        Set<String> selected=members==null?null:new LinkedHashSet<>(members);
        withMic(()->{
            IntercomService service=IntercomService.instance;if(service==null||service.engine==null){toast("Bitte Intercom einschalten.");return;}
            Engine e=service.engine;if(e.active()){toast("Bitte zuerst das laufende Gespräch beenden.");return;}
            service.microphone();e.announce(selected,title);if(!e.active())e.changed();
        });
    }
    void selectAnnouncementPeers(){
        Engine e=engine();if(e==null)return;
        LinkedHashMap<String,Peer> unique=new LinkedHashMap<>();for(Peer peer:sorted(e))if(peer.ready&&!peer.id.isEmpty())unique.putIfAbsent(peer.id,peer);
        ArrayList<Peer> peers=new ArrayList<>(unique.values());if(peers.isEmpty()){toast("Kein Kiosk ist erreichbar.");return;}
        String[] labels=new String[peers.size()];for(int i=0;i<peers.size();i++)labels[i]=config.display(peers.get(i));
        Set<String> selected=new LinkedHashSet<>();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Kiosks auswählen").setMultiChoiceItems(labels,new boolean[labels.length],(d,i,on)->{if(on)selected.add(peers.get(i).id);else selected.remove(peers.get(i).id);}).setNegativeButton("Abbrechen",null).setPositiveButton("Durchsage starten",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(selected.isEmpty()||selected.size()>RoomGroups.MAX_MEMBERS){toast("Bitte 1–32 Kiosks auswählen.");return;}dialog.dismiss();startAnnouncement(selected,"Ausgewählte Kiosks");}));dialog.show();
    }
    void renderGroups(){
        add(page,text("Deine Raumgruppen",28,ink,true));gap(page,8);
        add(page,text("Fasse Kiosks zusammen, um nur diese Räume mit einer Durchsage zu erreichen. Nicht erreichbare Räume werden übersprungen.",14,muted,false));gap(page,20);
        List<RoomGroups.Group> groups=new RoomGroups(config.prefs).list();
        if(groups.isEmpty()){add(page,text("Noch keine Raumgruppen. Verbinde zuerst deine Kiosks und lege dann zum Beispiel „Erdgeschoss“ an.",16,muted,false));gap(page,18);}
        for(RoomGroups.Group group:groups){
            LinearLayout box=card();add(box,text(group.name,20,ink,true));gap(box,8);
            ArrayList<String> names=new ArrayList<>();for(String id:group.members)names.add(config.peerName(id));add(box,text(String.join(", ",names),14,muted,false));gap(box,14);
            secondary(box,"Gruppe bearbeiten",()->editGroup(group));gap(box,8);
            secondary(box,"Gruppe löschen",()->new AlertDialog.Builder(this).setTitle("Gruppe löschen?").setMessage(group.name+" wird entfernt. Die Kiosks bleiben gespeichert.").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(d,w)->{new RoomGroups(config.prefs).remove(group.id);render();}).show());add(page,box);gap(page,12);
        }
        add(page,button("+  Raumgruppe erstellen",teal,()->editGroup(null)));
    }
    void editGroup(RoomGroups.Group group){
        LinkedHashMap<String,String> known=new LinkedHashMap<>();JSONArray saved=config.known();
        for(int i=0;i<saved.length();i++){JSONObject p=saved.optJSONObject(i);if(p!=null&&!p.optString("id").isEmpty())known.put(p.optString("id"),config.peerName(p.optString("id")));}
        if(group!=null)for(String id:group.members)known.putIfAbsent(id,config.peerName(id));
        if(known.isEmpty()){toast("Verbinde zuerst mindestens einen Kiosk.");return;}
        ArrayList<String> ids=new ArrayList<>(known.keySet());ids.sort(Comparator.comparing(known::get));
        String[] labels=new String[ids.size()];Set<String> selected=new LinkedHashSet<>();if(group!=null)selected.addAll(group.members);
        for(int i=0;i<ids.size();i++)labels[i]=known.get(ids.get(i));
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(12));EditText name=field("Zum Beispiel Erdgeschoss");if(group!=null)name.setText(group.name);add(box,name);gap(box,12);
        Button members=button(selectedKiosks(selected.size()),secondaryColor,()->{});members.setTextColor(ink);
        members.setOnClickListener(v->{boolean[] pending=new boolean[ids.size()];for(int i=0;i<ids.size();i++)pending[i]=selected.contains(ids.get(i));new AlertDialog.Builder(this).setTitle("Räume der Gruppe").setMultiChoiceItems(labels,pending,(d,i,on)->pending[i]=on).setNegativeButton("Abbrechen",null).setPositiveButton("Übernehmen",(d,w)->{selected.clear();for(int i=0;i<ids.size();i++)if(pending[i])selected.add(ids.get(i));members.setText(selectedKiosks(selected.size()));}).show();});add(box,members);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(group==null?"Raumgruppe erstellen":"Raumgruppe bearbeiten").setView(box).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{new RoomGroups(config.prefs).save(group==null?null:group.id,name.getText().toString(),selected);dialog.dismiss();render();}catch(IllegalArgumentException ex){toast(ex.getMessage());}}));dialog.show();
    }
    String selectedKiosks(int count){return getResources().getQuantityString(R.plurals.selected_kiosks,count,count);}
    void showHistory(){screen="history";render();}
    void renderHistory(){
        add(page,text("Deine Anrufe",28,ink,true));gap(page,7);
        add(page,text("Nur Zeitpunkt und Status werden auf diesem Handy gespeichert. Audio wird nie aufgezeichnet.",14,muted,false));gap(page,22);
        ArrayList<JSONObject> rows=new CallHistory(config.prefs).entries();
        if(rows.isEmpty()){LinearLayout empty=card();add(empty,text("Noch keine Anrufe",19,ink,true));gap(empty,8);add(empty,text("Hier erscheinen deine Gespräche und Durchsagen.",14,muted,false));add(page,empty);return;}
        DateFormat dates=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT,Locale.GERMAN);
        for(JSONObject item:rows){String id=item.optString("id"),alias=config.alias(id);String name=item.optBoolean("announcement")?"Durchsage · "+item.optString("name","Kiosks"):alias.isEmpty()?item.optString("name","Kiosk"):alias;
            LinearLayout row=card();LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon=text(item.optBoolean("announcement")?"◉":item.optBoolean("out")?"↗":"↙",26,teal,true);icon.setGravity(Gravity.CENTER);top.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));
            LinearLayout labels=column();add(labels,text(name,18,ink,true));gap(labels,5);add(labels,text(dates.format(new Date(item.optLong("at"))),13,muted,false));LinearLayout.LayoutParams labelsParams=new LinearLayout.LayoutParams(0,-2,1);labelsParams.setMargins(dp(10),0,0,0);top.addView(labels,labelsParams);add(row,top);gap(row,12);
            String result=item.optString("result");long seconds=item.optLong("seconds");add(row,text(result+(seconds>0?"  ·  "+String.format(Locale.GERMAN,"%d:%02d min",seconds/60,seconds%60):""),14,muted,false));
            if(!id.isEmpty()&&!item.optBoolean("announcement")){gap(row,14);Button callback=button("Erneut anrufen",secondaryColor,()->{screen="home";quickPeer=id;quickUntil=System.currentTimeMillis()+15000;quickCall(engine());render();});callback.setTextColor(ink);add(row,callback);}
            add(page,row);gap(page,10);
        }
        gap(page,10);Button clear=button("Verlauf löschen",paper,()->new AlertDialog.Builder(this).setMessage("Anrufverlauf löschen?").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(dialog,button)->{new CallHistory(config.prefs).clear();render();}).show());clear.setTextColor(muted);add(page,clear);
    }
    void explain(Peer p){String message=p.status+"\n\n"+switch(p.status){case "Anderer Intercom-Schlüssel"->"Kopiere den Intercom-Schlüssel aus Kiosk Satellite in die Einstellungen dieser App.";case "TLS-Einstellung unterschiedlich"->"Aktiviere oder deaktiviere TLS auf beiden Geräten gleich.";default->"Prüfe WLAN, Remote Administration und Intercom auf dem Kiosk. Beide Geräte müssen einander im Netzwerk erreichen können.";};new AlertDialog.Builder(this).setTitle(p.name).setMessage(message).setPositiveButton("OK",null).show();}
    void trust(Peer p){Engine e=engine();if(e==null||p==null)return;new AlertDialog.Builder(this).setTitle("Kiosk-Zertifikat prüfen").setMessage(p.name+"\n"+p.host+":"+p.candidatePort+"\n\nSHA-256:\n"+p.candidatePin+"\n\nVergleiche diesen Fingerabdruck mit dem Zertifikat des Kiosks. Nach Bestätigung wird nur dieses Zertifikat akzeptiert.").setNegativeButton("Abbrechen",null).setPositiveButton("Vertrauen",(d,w)->e.trust(p)).show();}
    EditText field(String hint){EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(16);input.setHint(hint);input.setBackgroundTintList(ColorStateList.valueOf(teal));input.setPadding(dp(14),dp(12),dp(14),dp(12));return input;}
    void settings(){Engine e=engine();if(e!=null&&e.active()){toast("Beende zuerst das Gespräch.");return;}screen="settings";render();}
    void renderSettings(){
        add(page,text("Alles für deine Verbindung",26,ink,true));gap(page,7);
        add(page,text("Geräte, Anrufe und Darstellung an einem Ort einstellen.",14,muted,false));gap(page,22);
        add(page,text("VERBINDUNG",12,teal,true));gap(page,10);
        LinearLayout connection=card();add(connection,text("Dein Name im Intercom",14,ink,true));gap(connection,8);
        EditText name=field("Mein A56");name.setText(config.name());add(connection,name);gap(connection,18);
        add(connection,text("Intercom-Schlüssel",14,ink,true));gap(connection,8);
        EditText key=field("Vom Kiosk kopieren");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(config.key());add(connection,key);gap(connection,8);
        add(connection,text("Kiosk Satellite → Einstellungen → Intercom → Intercom key",13,muted,false));gap(connection,18);
        Switch tls=new Switch(this);tint(tls);tls.setText("TLS-Verschlüsselung");tls.setTextColor(ink);tls.setChecked(config.tls());add(connection,tls);gap(connection,7);
        add(connection,text("Muss zu „Encrypt communications“ am Kiosk passen.",13,muted,false));add(page,connection);gap(page,20);
        add(page,text("ANRUFE & ERREICHBARKEIT",12,teal,true));gap(page,10);
        LinearLayout calls=card();secondary(calls,"Telefonkonto",this::phoneSettings);gap(calls,10);secondary(calls,"Empfang im Hintergrund",this::battery);gap(calls,10);secondary(calls,"Ruhezeiten und Ausnahmen",this::quietSettings);gap(calls,18);
        Switch announcements=new Switch(this);tint(announcements);announcements.setText(R.string.receive_announcements);announcements.setTextColor(ink);announcements.setChecked(config.acceptAnnouncements());add(calls,announcements);gap(calls,7);
        add(calls,text("Wenn ausgeschaltet, dürfen Einzelanrufe weiterhin klingeln. Ruhezeiten und Nicht stören gelten zusätzlich.",13,muted,false));gap(calls,18);
        Switch boot=new Switch(this);tint(boot);boot.setText("Nach Neustart erreichbar");boot.setTextColor(ink);boot.setChecked(config.autoStart());add(calls,boot);gap(calls,7);
        add(calls,text("Startet nach dem ersten Entsperren, wenn Intercom zuvor eingeschaltet war.",13,muted,false));
        if(Build.VERSION.SDK_INT>=34){gap(calls,14);secondary(calls,"Anrufe auf dem Sperrbildschirm",()->startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName()))));}add(page,calls);gap(page,20);
        add(page,text("ERSCHEINUNGSBILD",12,teal,true));gap(page,10);
        LinearLayout appearance=card();secondary(appearance,"Design · "+config.prefs.getString("theme","system"),this::themeSettings);gap(appearance,10);secondary(appearance,"Akzentfarbe",this::accentSettings);add(page,appearance);gap(page,24);
        add(page,button("Einstellungen speichern",teal,()->{String n=name.getText().toString().trim(),k=key.getText().toString().trim();if(n.isEmpty()||n.length()>48){name.setError("Bitte einen Namen mit 1–48 Zeichen eingeben");return;}if(k.isEmpty()||k.length()>4096){key.setError("Bitte den Intercom-Schlüssel einfügen");return;}try{config.save(n,k,tls.isChecked());config.prefs.edit().putBoolean("autoStart",boot.isChecked()).putBoolean("acceptAnnouncements",announcements.isChecked()).apply();if(engine()!=null)startService(new Intent(this,IntercomService.class).setAction(IntercomService.RELOAD));else enable();toast("Einstellungen gespeichert");home();}catch(Exception ex){toast("Einstellungen konnten nicht gespeichert werden");}}));gap(page,26);
        add(page,text("Eigenständige Companion-App für Kiosk Satellite. Anrufe bleiben im lokalen WLAN; Audio wird nicht gespeichert.",12,muted,false));
    }
    void themeSettings(){String[] modes={"System","Hell","Dunkel"};String[] values={"system","light","dark"};String current=config.prefs.getString("theme","system");int selected=current.equals("light")?1:current.equals("dark")?2:0;
        new AlertDialog.Builder(this).setTitle("Design").setSingleChoiceItems(modes,selected,(dialog,choice)->{
            config.prefs.edit().putString("theme",values[choice]).apply();
            UiModeManager manager=getSystemService(UiModeManager.class);boolean systemDark=(android.content.res.Resources.getSystem().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;manager.setApplicationNightMode(choice==1?UiModeManager.MODE_NIGHT_NO:choice==2?UiModeManager.MODE_NIGHT_YES:systemDark?UiModeManager.MODE_NIGHT_YES:UiModeManager.MODE_NIGHT_NO);appliedSystemDark=choice==0?systemDark:null;
            dialog.dismiss();palette();KioskWidget.refreshAll(this);fingerprint="";recreate();
        }).setNegativeButton("Abbrechen",null).show();
    }
    void accentSettings(){String[] labels={"Teal","Blau","Pflaume","Systemfarben"};String[] values={"teal","blue","plum","dynamic"};String current=config.prefs.getString("accent","teal");int selected=0;for(int i=0;i<values.length;i++)if(values[i].equals(current))selected=i;
        new AlertDialog.Builder(this).setTitle("Akzentfarbe").setSingleChoiceItems(labels,selected,(dialog,choice)->{config.prefs.edit().putString("accent",values[choice]).apply();dialog.dismiss();palette();KioskWidget.refreshAll(this);fingerprint="";render();}).setNegativeButton("Abbrechen",null).show();
    }
    void quietSettings(){
        LinearLayout box=column();box.setPadding(dp(24),dp(10),dp(24),dp(8));
        Switch enabled=new Switch(this);tint(enabled);enabled.setText("Ruhezeiten aktiv");enabled.setTextColor(ink);enabled.setChecked(config.prefs.getBoolean("quietEnabled",false));add(box,enabled);gap(box,12);
        int[] start={config.prefs.getInt("quietStart",1320)},end={config.prefs.getInt("quietEnd",420)};
        Button from=button("Beginn: "+clock(start[0]),secondaryColor,()->timePicker(start,()->{}));from.setTextColor(ink);from.setOnClickListener(v->timePicker(start,()->from.setText("Beginn: "+clock(start[0]))));add(box,from);gap(box,8);
        Button until=button("Ende: "+clock(end[0]),secondaryColor,()->{});until.setTextColor(ink);until.setOnClickListener(v->timePicker(end,()->until.setText("Ende: "+clock(end[0]))));add(box,until);gap(box,12);
        String[] days={"Mo","Di","Mi","Do","Fr","Sa","So"};boolean[] checked=new boolean[7];int mask=config.prefs.getInt("quietDays",127);for(int i=0;i<7;i++)checked[i]=(mask&(1<<i))!=0;
        Button chooseDays=button("Wochentage wählen",secondaryColor,()->new AlertDialog.Builder(this).setTitle("Ruhetage").setMultiChoiceItems(days,checked,(d,i,on)->checked[i]=on).setPositiveButton("Fertig",null).show());chooseDays.setTextColor(ink);add(box,chooseDays);gap(box,10);
        add(box,text("Ausnahmen dürfen während der Ruhezeit klingeln. „Nicht stören“ sperrt weiterhin alle.",13,muted,false));gap(box,8);
        HashSet<String> exceptions=new HashSet<>(config.exceptions());JSONArray known=config.known();ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();for(int i=0;i<known.length();i++){JSONObject p=known.optJSONObject(i);if(p!=null&&!p.optString("id").isEmpty()){ids.add(p.optString("id"));names.add(config.display(Peer.from(p,p.optString("host"))));}}
        Button chooseExceptions=button("Ausnahmen wählen",secondaryColor,()->{boolean[] selected=new boolean[ids.size()];for(int i=0;i<ids.size();i++)selected[i]=exceptions.contains(ids.get(i));new AlertDialog.Builder(this).setTitle("Ausnahmen in der Ruhezeit").setMultiChoiceItems(names.toArray(new String[0]),selected,(d,i,on)->{if(on)exceptions.add(ids.get(i));else exceptions.remove(ids.get(i));}).setPositiveButton("Fertig",null).show();});chooseExceptions.setTextColor(ink);add(box,chooseExceptions);
        new AlertDialog.Builder(this).setTitle("Ruhezeiten").setView(box).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{int daysMask=0;for(int i=0;i<7;i++)if(checked[i])daysMask|=1<<i;config.prefs.edit().putBoolean("quietEnabled",enabled.isChecked()).putInt("quietDays",daysMask).putInt("quietStart",start[0]).putInt("quietEnd",end[0]).putStringSet("quietExceptions",exceptions).apply();fingerprint="";render();}).show();
    }
    String clock(int minutes){return String.format(Locale.GERMAN,"%02d:%02d",minutes/60,minutes%60);}
    void timePicker(int[] minutes,Runnable changed){new TimePickerDialog(this,(view,hour,minute)->{minutes[0]=hour*60+minute;changed.run();},minutes[0]/60,minutes[0]%60,true).show();}
    void battery(){PowerManager pm=getSystemService(PowerManager.class);if(pm.isIgnoringBatteryOptimizations(getPackageName())){toast("Akkuoptimierung ist bereits ausgenommen.");return;}new AlertDialog.Builder(this).setTitle("Im Hintergrund erreichbar").setMessage("Erlaube uneingeschränkte Akkunutzung, damit Android den WLAN-Empfang bei ausgeschaltetem Display möglichst nicht unterbricht. Die aktive Erreichbarkeit verbraucht zusätzlich Akku.").setNegativeButton("Später",null).setPositiveButton("Einstellung öffnen",(d,w)->startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())))).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    public void onBackPressed(){Engine e=engine();if(e!=null&&e.active())new AlertDialog.Builder(this).setMessage("Gespräch beenden?").setNegativeButton("Weiter sprechen",null).setPositiveButton("Beenden",(d,w)->e.finish("Gespräch beendet",true)).show();else if(!screen.equals("home"))home();else super.onBackPressed();}
    final class Meter extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Meter(){super(MainActivity.this);setContentDescription("Sprachpegel");}
        protected void onDraw(Canvas c){Engine e=engine();float level=e==null?0:(float)e.audio.level;int count=12;float unit=dp(12),space=dp(7),total=count*unit+(count-1)*space,left=(getWidth()-total)/2;for(int i=0;i<count;i++){float wave=(float)(.4+.6*Math.sin((i+1)*1.9));float height=dp(12)+level*dp(70)*wave;paint.setColor(level>.02?teal:Color.rgb(205,221,219));c.drawRoundRect(left+i*(unit+space),(getHeight()-height)/2,left+i*(unit+space)+unit,(getHeight()+height)/2,dp(6),dp(6),paint);}}
    }
}
