package de.local.intercompocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public final class WidgetSetupActivity extends Activity {
    public void onCreate(Bundle saved){super.onCreate(saved);
        int widgetId=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return;}
        Config config=new Config(this);JSONArray known=config.known();ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();
        for(int i=0;i<known.length();i++){JSONObject p=known.optJSONObject(i);if(p==null)continue;String id=p.optString("id");if(id.isEmpty())continue;ids.add(id);String alias=config.alias(id);names.add(alias.isEmpty()?p.optString("name","Kiosk"):alias);}
        if(ids.isEmpty()){new AlertDialog.Builder(this).setTitle("Kiosk-Widget").setMessage("Öffne Intercom Satelite und verbinde zuerst einen Kiosk. Danach kannst du das Widget hinzufügen.").setPositiveButton("OK",(d,w)->finish()).setOnCancelListener(d->finish()).show();return;}
        new AlertDialog.Builder(this).setTitle("Kiosk für das Widget wählen").setItems(names.toArray(new String[0]),(d,index)->{
            config.prefs.edit().putString("widget:"+widgetId,ids.get(index)).apply();KioskWidget.update(this,widgetId);
            setResult(RESULT_OK,new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId));finish();
        }).setNegativeButton("Abbrechen",(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
}
