package de.local.intercompocket;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigTest {
    @Test public void legacyManualDndStaysUnlimited(){
        SharedPreferences prefs=TestPreferences.create();prefs.edit().putBoolean("dnd",true).apply();
        Config config=new Config(prefs,()->Long.MAX_VALUE);assertTrue(config.dnd());assertEquals(0,config.dndUntil());
    }
    @Test public void timedDndSurvivesRestartAndExpiresAtExactDeadline(){
        AtomicLong now=new AtomicLong(1000);SharedPreferences prefs=TestPreferences.create();
        new Config(prefs,now::get).snoozeUntil(5000);
        Config restored=new Config(prefs,now::get);assertTrue(restored.dnd());assertTrue(restored.advertisedDnd());
        now.set(4999);assertTrue(restored.blocks("kitchen"));
        now.set(5000);assertFalse(restored.dnd());assertFalse(restored.blocks("kitchen"));assertFalse(restored.advertisedDnd());
    }
    @Test public void manualToggleClearsOldDeadline(){
        AtomicLong now=new AtomicLong(1000);Config config=new Config(TestPreferences.create(),now::get);
        config.snoozeUntil(5000);config.setDnd(false);assertFalse(config.dnd());assertEquals(0,config.dndUntil());
        config.setDnd(true);now.set(6000);assertTrue(config.dnd());
    }
    @Test public void expirationPreservesQuietHoursAndTheirExceptions(){
        AtomicLong now=new AtomicLong(1000);SharedPreferences prefs=TestPreferences.create();
        prefs.edit().putBoolean("quietEnabled",true).putInt("quietDays",127).putInt("quietStart",0).putInt("quietEnd",0).putStringSet("quietExceptions",Collections.singleton("door")).apply();
        Config config=new Config(prefs,now::get);config.snoozeUntil(2000);assertTrue(config.blocks("door"));
        now.set(2000);assertFalse(config.blocks("door"));assertTrue(config.blocks("bedroom"));assertTrue(config.quiet());
    }
    @Test public void announcementPreferenceDefaultsOnAndPersistsSeparately(){
        SharedPreferences prefs=TestPreferences.create();assertTrue(new Config(prefs,()->1000).acceptAnnouncements());
        prefs.edit().putBoolean("acceptAnnouncements",false).apply();Config restored=new Config(prefs,()->1000);
        assertFalse(restored.acceptAnnouncements());assertFalse(restored.blocks("kitchen"));assertFalse(restored.advertisedDnd());
    }
}
