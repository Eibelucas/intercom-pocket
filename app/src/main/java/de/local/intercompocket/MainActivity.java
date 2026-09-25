package de.local.intercompocket;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private final int teal=Color.rgb(72,130,132),ink=Color.rgb(33,35,39),muted=Color.rgb(93,96,102),paper=Color.rgb(245,244,242),rust=Color.rgb(169,80,31);
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
    private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;refresh();handler.postDelayed(this,200);}};
    public void onCreate(Bundle saved){super.onCreate(saved);config=new Config(this);setVolumeControlStream(android.media.AudioManager.STREAM_VOICE_CALL);answerPending=getIntent().getBooleanExtra("answer",false);render();}
    public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);answerPending=i.getBooleanExtra("answer",false);fingerprint="";}
    public void onResume(){super.onResume();PhoneIntegration.register(this);phoneReady=PhoneIntegration.ready(this);fingerprint="";resumed=true;handler.post(tick);}
    public void onPause(){resumed=false;handler.removeCallbacks(tick);Engine e=engine();if(e!=null&&!e.handsFree)e.talk(false);super.onPause();}
    Engine engine(){IntercomService s=IntercomService.instance;return s==null?null:s.engine;}
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    void refresh(){
        Engine e=engine();StringBuilder f=new StringBuilder(e==null?"off":e.state+e.callId+e.config.dnd()+e.detail+e.phoneManaged);
        if(e!=null){for(Peer p:sorted(e))f.append(p.name).append(p.status).append(p.candidatePin);if(e.peer!=null)f.append(e.peer.candidatePin);}
        f.append(IntercomService.lastError);
        // Talk-state updates never replace the held touch target.
        if(!f.toString().equals(fingerprint)&&!(e!=null&&e.sending&&!e.handsFree)){fingerprint=f.toString();render();}
        if(e!=null&&time!=null){long seconds=e.since==0?0:(System.currentTimeMillis()-e.since)/1000;time.setText(String.format(Locale.GERMAN,"%02d:%02d",seconds/60,seconds%60));}
        if(meter!=null)meter.invalidate();
        if(answerPending&&e!=null&&e.state.equals("ringing")&&e.callId.equals(getIntent().getStringExtra("call"))){answerPending=false;accept();}
    }
    ArrayList<Peer> sorted(Engine e){ArrayList<Peer> list=new ArrayList<>(e.peers.values());list.sort(Comparator.comparing((Peer p)->!p.ready).thenComparing(p->p.name));return list;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setIncludeFontPadding(false);return t;}
    void gap(LinearLayout box,int h){View v=new View(this);box.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    void add(LinearLayout box,View v){box.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout card(){LinearLayout c=column();c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(shape(Color.WHITE,24));return c;}
    Button button(String label,int color,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(54));b.setPadding(dp(16),dp(10),dp(16),dp(10));b.setBackground(shape(color,16));b.setStateListAnimator(null);b.setOnClickListener(v->action.run());return b;}
    void secondary(LinearLayout box,String title,Runnable action){Button b=button(title,Color.rgb(228,236,235),action);b.setTextColor(teal);add(box,b);}
    void render(){
        time=null;talk=null;meter=null;liveHint=null;
        LinearLayout root=column();root.setBackgroundColor(paper);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        page=column();page.setPadding(dp(24),dp(18),dp(24),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(root);root.requestApplyInsets();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_intercom);header.addView(logo,new LinearLayout.LayoutParams(dp(36),dp(36)));
        TextView brand=text("Intercom Pocket",18,ink,true);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.setMargins(dp(12),0,0,0);header.addView(brand,bp);
        Button settings=button("⋮",paper,this::settings);settings.setTextColor(ink);settings.setContentDescription("Einstellungen");header.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));add(page,header);gap(page,28);
        Engine e=engine();
        if(e!=null&&e.active())renderCall(e);else renderHome(e);
    }
    void renderHome(Engine e){
        TextView eyebrow=text("DEIN ZUHAUSE. DIREKT VERBUNDEN.",10,teal,true);eyebrow.setLetterSpacing(.12f);add(page,eyebrow);gap(page,10);
        add(page,text("Einfach\nzusammen sprechen.",32,ink,true));gap(page,12);add(page,text("Vom Handy zum Kiosk.\nUnd wieder zurück.",16,muted,false));gap(page,26);
        LinearLayout availability=card();add(availability,text(e==null?"○  Du bist offline":config.dnd()?"◐  Nicht stören":"●  Du bist erreichbar",18,e==null?muted:teal,true));gap(availability,8);
        add(availability,text(e==null?"Aktiviere Intercom, um im WLAN Anrufe zu empfangen.":config.name()+" · "+Discovery.localIp(),14,muted,false));gap(availability,18);
        if(e==null)add(availability,button(config.key().isEmpty()?"Intercom einrichten":"Intercom einschalten",teal,()->{if(config.key().isEmpty())settings();else enable();}));
        else{Switch dnd=new Switch(this);dnd.setText("Nicht stören");dnd.setTextSize(15);dnd.setTextColor(ink);dnd.setChecked(config.dnd());dnd.setPadding(0,dp(6),0,dp(6));dnd.setOnCheckedChangeListener((b,on)->{config.prefs.edit().putBoolean("dnd",on).apply();e.changed();});add(availability,dnd);}
        add(page,availability);gap(page,24);
        LinearLayout phoneCard=card();add(phoneCard,text(phoneReady?"✓  Anrufe in der Telefon-App":"Wie ein Telefonanruf",18,teal,true));gap(phoneCard,8);
        add(phoneCard,text(phoneReady?"Eingehende Kiosk-Anrufe erscheinen in deiner Telefon-App. Dort annehmen, stummschalten oder auflegen.":"Aktiviere Intercom Pocket einmal als Anrufkonto. Danach meldet sich dein Handy über die normale Telefon-App, auch auf dem Sperrbildschirm.",14,muted,false));gap(phoneCard,14);
        secondary(phoneCard,phoneReady?"Telefonkonto verwalten":"Telefonkonto aktivieren",this::phoneSettings);add(page,phoneCard);gap(page,24);
        if(!IntercomService.lastError.isEmpty()){add(page,text(IntercomService.lastError,14,rust,false));gap(page,16);}
        if(e!=null&&!e.detail.isEmpty()){add(page,text(e.detail,14,muted,false));gap(page,16);}
        if(e!=null&&e.diagnostics.hasEvents()){secondary(page,"Letzten Anruf prüfen",this::showDiagnostics);gap(page,16);}
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);TextView rooms=text("Deine Kiosks",21,ink,true);title.addView(rooms,new LinearLayout.LayoutParams(0,-2,1));
        if(e!=null){Button refresh=button("↻",paper,e::refresh);refresh.setTextColor(teal);refresh.setContentDescription("Geräte aktualisieren");title.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));}add(page,title);gap(page,12);
        if(e==null||e.peers.isEmpty()){
            LinearLayout empty=card();add(empty,text(e==null?"Bereit für den ersten Anruf?":"Suche im WLAN …",17,ink,true));gap(empty,10);add(empty,text("Am Kiosk müssen Intercom, Remote Administration und „Find other kiosks“ aktiviert sein. Alle Geräte brauchen denselben Intercom-Schlüssel.",14,muted,false));add(page,empty);
        }else for(Peer p:sorted(e)){
            LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);LinearLayout label=column();add(label,text(p.name.isEmpty()?"Kiosk":p.name,18,ink,true));gap(label,6);add(label,text(p.status,13,p.ready?teal:muted,false));gap(label,5);add(label,text(p.host,12,muted,false));line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button call=button(p.ready?"Anrufen":p.candidatePin.isEmpty()?"Info":"Prüfen",p.ready?teal:Color.rgb(226,232,230),()->{if(!p.candidatePin.isEmpty())trust(p);else if(p.ready)withMic(()->{IntercomService s=IntercomService.instance;if(s!=null){s.microphone();e.call(p);}});else explain(p);});if(!p.ready)call.setTextColor(teal);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(52));lp.setMargins(dp(12),0,0,0);line.addView(call,lp);add(row,line);row.setOnLongClickListener(v->{if(p.manual)new AlertDialog.Builder(this).setMessage("Gespeicherten Kiosk entfernen?").setNegativeButton("Abbrechen",null).setPositiveButton("Entfernen",(d,w)->e.remove(p)).show();return true;});add(page,row);gap(page,10);
        }
        if(e!=null){gap(page,12);secondary(page,"+  Kiosk per Adresse hinzufügen",this::addPeer);gap(page,14);Button off=button("Erreichbarkeit ausschalten",paper,()->startService(new Intent(this,IntercomService.class).setAction(IntercomService.STOP)));off.setTextColor(muted);add(page,off);}
        gap(page,18);TextView footer=text("Lokal im WLAN · Ohne Cloud",12,muted,false);footer.setGravity(Gravity.CENTER);add(page,footer);
    }
    void renderCall(Engine e){
        TextView tag=text(e.state.equals("ringing")?"EINGEHENDER ANRUF":e.broadcast?"DURCHSAGE":"INTERCOM",11,teal,true);tag.setLetterSpacing(.16f);tag.setGravity(Gravity.CENTER);add(page,tag);gap(page,30);
        TextView name=text(e.peer==null?"Kiosk":e.peer.name,36,ink,true);name.setGravity(Gravity.CENTER);add(page,name);gap(page,12);
        String status=switch(e.state){case "calling"->"Kiosk wird angerufen …";case "ringing"->"Möchte mit dir sprechen";case "connecting"->"Audio wird verbunden …";case "listening"->"Du hörst eine Durchsage";default->"Verbunden";};
        TextView s=text(status,16,muted,false);s.setGravity(Gravity.CENTER);add(page,s);gap(page,18);
        time=text("00:00",24,muted,false);time.setTypeface(Typeface.MONOSPACE);time.setGravity(Gravity.CENTER);add(page,time);gap(page,30);
        meter=new Meter();page.addView(meter,new LinearLayout.LayoutParams(-1,dp(100)));gap(page,30);
        if(!e.detail.isEmpty()&&!e.state.equals("in_call")){add(page,text(e.detail,14,rust,false));gap(page,12);}
        if(e.peer!=null&&!e.peer.candidatePin.isEmpty()){secondary(page,"Zertifikat des Kiosks bestätigen",()->trust(e.peer));gap(page,12);}
        if(e.phoneManaged){
            add(page,text(e.state.equals("ringing")?"Nimm den Anruf in der Telefon-App an. Die Anrufanzeige findest du auch in den Benachrichtigungen.":!e.audio.microphoneAvailable&&e.state.equals("in_call")?"Das Mikrofon ist nicht verfügbar. Du kannst zuhören.":"Du telefonierst über deine Telefon-App. Dort kannst du das Mikrofon stummschalten und zwischen Hörer, Lautsprecher und verbundenem Headset wechseln.",16,muted,false));gap(page,20);
            add(page,button(e.state.equals("ringing")?"Ablehnen":"Gespräch beenden",rust,()->e.finish(e.state.equals("ringing")?"Anruf abgelehnt":"In Intercom Pocket aufgelegt",true)));
            gap(page,12);secondary(page,"Anrufdetails anzeigen",this::showDiagnostics);
        }
        else if(e.state.equals("ringing")){add(page,button("Anruf annehmen",teal,this::accept));gap(page,12);add(page,button("Ablehnen",rust,()->e.finish("Anruf abgelehnt",true)));}
        else if(e.state.equals("in_call")){
            liveHint=text(!e.audio.microphoneAvailable?"Du kannst zuhören. Das Mikrofon konnte nicht gestartet werden.":e.handsFree?(e.sending?"Freisprechen ist aktiv":"Dein Mikrofon ist stumm"):"Halte die Taste, um zu sprechen.",15,muted,false);liveHint.setGravity(Gravity.CENTER);add(page,liveHint);gap(page,18);
            talk=button(e.handsFree?(e.sending?"Mikrofon stummschalten":"Mikrofon einschalten"):"Zum Sprechen halten",teal,()->{});talk.setMinHeight(dp(112));talk.setTextSize(20);
            talk.setOnTouchListener((v,event)->{int a=event.getActionMasked();if(a==MotionEvent.ACTION_DOWN){v.getParent().requestDisallowInterceptTouchEvent(true);if(e.handsFree)e.talk(!e.sending);else e.talk(true);talk.setText(e.sending?"Du sprichst …":"Mikrofon einschalten");talk.setBackground(shape(e.sending?Color.rgb(51,103,105):teal,16));if(liveHint!=null)liveHint.setText(e.sending?"Deine Stimme wird übertragen":"Du hörst zu");return true;}if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){if(!e.handsFree){e.talk(false);talk.setText("Zum Sprechen halten");talk.setBackground(shape(teal,16));if(liveHint!=null)liveHint.setText("Halte die Taste, um zu sprechen.");}v.getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;});
            talk.setOnClickListener(v->{if(e.handsFree)e.talk(!e.sending);else e.handsFree(true);talk.setText(e.sending?"Mikrofon stummschalten":"Mikrofon einschalten");});if(!e.audio.microphoneAvailable){talk.setEnabled(false);talk.setText("Mikrofon nicht verfügbar");}add(page,talk);gap(page,18);
            Switch hf=new Switch(this);hf.setText("Freisprechen");hf.setTextColor(ink);hf.setTextSize(16);hf.setChecked(e.handsFree);hf.setEnabled(e.audio.microphoneAvailable);hf.setOnCheckedChangeListener((b,on)->{e.handsFree(on);talk.setText(on?"Mikrofon stummschalten":"Zum Sprechen halten");liveHint.setText(on?"Freisprechen ist aktiv":"Halte die Taste, um zu sprechen.");});add(page,hf);gap(page,18);
            Switch speaker=new Switch(this);speaker.setText("Lautsprecher");speaker.setTextColor(ink);speaker.setChecked(e.speaker);speaker.setOnCheckedChangeListener((b,on)->{e.speaker=on;e.audio.speaker(on);});add(page,speaker);gap(page,26);
            add(page,button("Gespräch beenden",rust,()->e.finish("Gespräch beendet",true)));
            if(!e.audio.microphoneAvailable){gap(page,12);secondary(page,"Fehlerdetails anzeigen",this::showDiagnostics);}
        }else add(page,button(e.broadcast?"Durchsage schließen":"Abbrechen",rust,()->e.finish("Gespräch beendet",true)));
    }
    void accept(){Engine current=engine();if(current==null||current.phoneManaged)return;String expected=current.callId;withMic(()->{IntercomService s=IntercomService.instance;if(s!=null&&s.engine!=null&&s.engine.callId.equals(expected)&&!s.engine.phoneManaged){try{s.microphone();s.engine.answer();}catch(Exception e){toast("Mikrofon konnte nicht gestartet werden");}}});}
    void phoneSettings(){withMic(()->{
        if(Build.VERSION.SDK_INT<35&&checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)!=PackageManager.PERMISSION_GRANTED){afterPermission=this::phoneSettings;requestPermissions(new String[]{Manifest.permission.READ_PHONE_NUMBERS},44);return;}
        if(!PhoneIntegration.register(this)){toast("Die Telefon-App konnte das Intercom-Konto nicht registrieren.");return;}
        new AlertDialog.Builder(this).setTitle("Intercom als Telefonkonto")
            .setMessage("Aktiviere im nächsten Bildschirm Intercom Pocket bei den Anrufkonten. Je nach Samsung-Version heißt der Bereich „Anrufkonten“ oder „Zusätzliche Anrufdienste“. Deine normale SIM bleibt unverändert.")
            .setNegativeButton("Später",null).setPositiveButton("Einstellungen öffnen",(d,w)->{
                try{startActivity(new Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS));}
                catch(ActivityNotFoundException ex){toast("Öffne Telefon → Einstellungen → Anrufkonten und aktiviere Intercom Pocket.");}
            }).show();
    });}
    void enable(){
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){afterPermission=this::startEnabled;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},43);}else startEnabled();
    }
    void startEnabled(){try{startForegroundService(new Intent(this,IntercomService.class).setAction(IntercomService.ENABLE));}catch(Exception e){toast("Start nicht möglich: "+e.getMessage());}}
    void withMic(Runnable r){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){try{r.run();}catch(Exception e){toast("Mikrofon konnte nicht gestartet werden");}}else{afterPermission=r;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},42);}}
    public void onRequestPermissionsResult(int request,String[]permissions,int[]grants){super.onRequestPermissionsResult(request,permissions,grants);Runnable r=afterPermission;afterPermission=null;if(request==43){if(grants.length==0||grants[0]!=PackageManager.PERMISSION_GRANTED)toast("Ohne Benachrichtigungen sind eingehende Anrufe im Hintergrund nicht sichtbar.");if(r!=null)r.run();}else if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&r!=null)r.run();else toast(request==44?"Diese Android-Version benötigt die Telefonberechtigung, um das aktivierte Anrufkonto zu prüfen.":"Zum Sprechen bitte den Mikrofonzugriff erlauben.");}
    void addPeer(){EditText input=field("192.168.1.50:2324");new AlertDialog.Builder(this).setTitle("Kiosk hinzufügen").setMessage("IP-Adresse oder Hostname, optional mit Port. Für HTTPS: https://adresse:port").setView(input).setNegativeButton("Abbrechen",null).setPositiveButton("Hinzufügen",(d,w)->{try{Engine e=engine();if(e!=null)e.add(input.getText().toString().trim());}catch(Exception ex){toast(ex.getMessage());}}).show();}
    void explain(Peer p){String message=p.status+"\n\n"+switch(p.status){case "Anderer Intercom-Schlüssel"->"Kopiere den Intercom-Schlüssel aus Kiosk Satellite in die Einstellungen dieser App.";case "TLS-Einstellung unterschiedlich"->"Aktiviere oder deaktiviere TLS auf beiden Geräten gleich.";default->"Prüfe WLAN, Remote Administration und Intercom auf dem Kiosk. Beide Geräte müssen einander im Netzwerk erreichen können.";};new AlertDialog.Builder(this).setTitle(p.name).setMessage(message).setPositiveButton("OK",null).show();}
    void trust(Peer p){Engine e=engine();if(e==null||p==null)return;new AlertDialog.Builder(this).setTitle("Kiosk-Zertifikat prüfen").setMessage(p.name+"\n"+p.host+":"+p.candidatePort+"\n\nSHA-256:\n"+p.candidatePin+"\n\nVergleiche diesen Fingerabdruck mit dem Zertifikat des Kiosks. Nach Bestätigung wird nur dieses Zertifikat akzeptiert.").setNegativeButton("Abbrechen",null).setPositiveButton("Vertrauen",(d,w)->e.trust(p)).show();}
    EditText field(String hint){EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(16);input.setHint(hint);input.setPadding(dp(14),dp(12),dp(14),dp(12));return input;}
    void settings(){
        Engine e=engine();if(e!=null&&e.active()){toast("Beende zuerst das Gespräch.");return;}
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(16));
        add(box,text("Dein Name im Intercom",13,muted,true));EditText name=field("Mein A56");name.setText(config.name());add(box,name);gap(box,16);
        add(box,text("Intercom-Schlüssel",13,muted,true));EditText key=field("Vom Kiosk kopieren");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(config.key());add(box,key);gap(box,8);
        add(box,text("Kiosk Satellite → Einstellungen → Intercom → Intercom key. Hier exakt denselben Schlüssel einfügen.",13,muted,false));gap(box,18);
        Switch tls=new Switch(this);tls.setText("TLS-Verschlüsselung");tls.setTextColor(ink);tls.setChecked(config.tls());add(box,tls);gap(box,8);add(box,text("Muss zur Einstellung „Encrypt communications“ der Kiosks passen. Lokale Zertifikate einmal bestätigen.",13,muted,false));gap(box,18);
        secondary(box,"Empfang bei ausgeschaltetem Display",this::battery);gap(box,10);
        secondary(box,"Telefonkonto für eingehende Anrufe",this::phoneSettings);gap(box,10);
        if(Build.VERSION.SDK_INT>=34){secondary(box,"Anrufe auf dem Sperrbildschirm",()->startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+getPackageName()))));gap(box,10);}
        add(box,text("Eigenständige Companion-App für Kiosk Satellite von jxlarrea. Keine offizielle App. Anrufe sind lokal im WLAN; Audio wird nicht gespeichert. Erreichbarkeit im Hintergrund benötigt zusätzliche Energie. Nach einem Handy-Neustart die App erneut einschalten.",12,muted,false));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Intercom einrichten").setView(scroll).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String n=name.getText().toString().trim(),k=key.getText().toString().trim();if(n.isEmpty()||n.length()>48){name.setError("Bitte einen Namen mit 1–48 Zeichen eingeben");return;}if(k.isEmpty()||k.length()>4096){key.setError("Bitte den Intercom-Schlüssel einfügen");return;}try{config.save(n,k,tls.isChecked());dialog.dismiss();if(engine()!=null)startService(new Intent(this,IntercomService.class).setAction(IntercomService.RELOAD));else enable();fingerprint="";}catch(Exception ex){toast("Einstellungen konnten nicht gespeichert werden");}}));dialog.show();
    }
    void battery(){PowerManager pm=getSystemService(PowerManager.class);if(pm.isIgnoringBatteryOptimizations(getPackageName())){toast("Akkuoptimierung ist bereits ausgenommen.");return;}new AlertDialog.Builder(this).setTitle("Im Hintergrund erreichbar").setMessage("Erlaube uneingeschränkte Akkunutzung, damit Android den WLAN-Empfang bei ausgeschaltetem Display möglichst nicht unterbricht. Die aktive Erreichbarkeit verbraucht zusätzlich Akku.").setNegativeButton("Später",null).setPositiveButton("Einstellung öffnen",(d,w)->startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())))).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void showDiagnostics(){Engine e=engine();if(e==null)return;String report=e.diagnostics.report();new AlertDialog.Builder(this).setTitle("Letzter Anruf").setMessage(report).setNegativeButton("Schließen",null).setPositiveButton("Kopieren",(d,w)->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Intercom-Diagnose",report));toast("Fehlerdetails kopiert");}).show();}
    public void onBackPressed(){Engine e=engine();if(e!=null&&e.active())new AlertDialog.Builder(this).setMessage("Gespräch beenden?").setNegativeButton("Weiter sprechen",null).setPositiveButton("Beenden",(d,w)->e.finish("Gespräch beendet",true)).show();else super.onBackPressed();}
    final class Meter extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Meter(){super(MainActivity.this);setContentDescription("Sprachpegel");}
        protected void onDraw(Canvas c){Engine e=engine();float level=e==null?0:(float)e.audio.level;int count=12;float unit=dp(12),space=dp(7),total=count*unit+(count-1)*space,left=(getWidth()-total)/2;for(int i=0;i<count;i++){float wave=(float)(.4+.6*Math.sin((i+1)*1.9));float height=dp(12)+level*dp(70)*wave;paint.setColor(level>.02?teal:Color.rgb(205,221,219));c.drawRoundRect(left+i*(unit+space),(getHeight()-height)/2,left+i*(unit+space)+unit,(getHeight()+height)/2,dp(6),dp(6),paint);}}
    }
}
