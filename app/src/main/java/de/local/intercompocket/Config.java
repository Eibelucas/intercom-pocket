package de.local.intercompocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.*;

import java.security.KeyStore;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Config {
    final SharedPreferences prefs;
    private final java.util.function.LongSupplier clock;

    private String unknownKiosk = "Kiosk";
    private String defaultName = "My smartphone";

    Config(Context c) {
        this(c.getSharedPreferences("intercom", Context.MODE_PRIVATE), System::currentTimeMillis);
        unknownKiosk = c.getString(R.string.unknown_kiosk);
        defaultName = c.getString(R.string.default_device_name);
    }

    /** Placeholder name for a kiosk that is neither aliased nor remembered. */
    String unknownKiosk() {
        return unknownKiosk;
    }

    Config(SharedPreferences prefs, java.util.function.LongSupplier clock) {
        this.prefs = prefs;
        this.clock = clock;
        if (!prefs.contains("id"))
            prefs.edit().putString("id", UUID.randomUUID().toString()).apply();
    }

    String id() {
        return prefs.getString("id", "");
    }

    String name() {
        return prefs.getString("name", defaultName);
    }

    boolean tls() {
        return prefs.getBoolean("tls", false);
    }

    boolean dnd() {
        long until = dndUntil();
        return prefs.getBoolean("dnd", false) && (until == 0 || clock.getAsLong() < until);
    }

    long dndUntil() {
        return prefs.getLong("dndUntil", 0);
    }

    void setDnd(boolean on) {
        prefs.edit().putBoolean("dnd", on).remove("dndUntil").apply();
    }

    void snoozeUntil(long until) {
        if (until <= clock.getAsLong())
            throw new IllegalArgumentException("End time must be in the future");
        prefs.edit().putBoolean("dnd", true).putLong("dndUntil", until).apply();
    }

    boolean acceptAnnouncements() {
        return prefs.getBoolean("acceptAnnouncements", true);
    }

    boolean autoStart() {
        return prefs.getBoolean("autoStart", false);
    }

    boolean enabledBefore() {
        return prefs.getBoolean("enabledBefore", false);
    }

    boolean quiet() {
        return QuietHours.active(
                prefs.getBoolean("quietEnabled", false),
                prefs.getInt("quietDays", 127),
                prefs.getInt("quietStart", 1320),
                prefs.getInt("quietEnd", 420),
                LocalDateTime.now());
    }

    boolean blocks(String id) {
        return dnd() || (quiet() && !exceptions().contains(id));
    }

    boolean advertisedDnd() {
        return dnd() || (quiet() && exceptions().isEmpty());
    }

    java.util.Set<String> exceptions() {
        return prefs.getStringSet("quietExceptions", java.util.Collections.emptySet());
    }

    String alias(String id) {
        return prefs.getString("alias:" + id, "");
    }

    String icon(String id) {
        return prefs.getString("icon:" + id, "⌂");
    }

    boolean favorite(String id) {
        return prefs.getBoolean("favorite:" + id, false);
    }

    String display(Peer p) {
        String alias = alias(p.id);
        if (!alias.isEmpty()) return alias;
        return p.name.isEmpty() ? unknownKiosk : p.name;
    }

    void remember(Peer p) {
        if (p == null || p.id == null || p.id.isEmpty()) return;
        try {
            JSONArray old = new JSONArray(prefs.getString("knownPeers", "[]")),
                    next = new JSONArray();
            next.put(
                    Wire.obj(
                            "id",
                            p.id,
                            "name",
                            p.name,
                            "host",
                            p.host,
                            "port",
                            p.adminPort,
                            "tls",
                            p.adminTls));
            for (int i = 0; i < old.length() && next.length() < 64; i++) {
                JSONObject j = old.optJSONObject(i);
                if (j != null && !p.id.equals(j.optString("id"))) next.put(j);
            }
            prefs.edit().putString("knownPeers", next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    JSONArray known() {
        try {
            return new JSONArray(prefs.getString("knownPeers", "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    String peerName(String id) {
        String alias = alias(id);
        if (!alias.isEmpty()) return alias;
        JSONArray peers = known();
        for (int i = 0; i < peers.length(); i++) {
            JSONObject p = peers.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p.optString("name", unknownKiosk);
        }
        return unknownKiosk;
    }

    String key() {
        try {
            String v = prefs.getString("secret", "");
            if (v.isEmpty()) return "";
            String[] a = v.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    storageKey(),
                    new GCMParameterSpec(128, Base64.decode(a[0], Base64.NO_WRAP)));
            return new String(
                    cipher.doFinal(Base64.decode(a[1], Base64.NO_WRAP)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    void save(String name, String key, boolean tls) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, storageKey());
        String secret =
                Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
                        + ":"
                        + Base64.encodeToString(
                                c.doFinal(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                Base64.NO_WRAP);
        prefs.edit()
                .putString("name", name)
                .putString("secret", secret)
                .putBoolean("tls", tls)
                .apply();
    }

    // ---- UI-related settings (typed accessors instead of raw pref keys in the UI) ----
    String themeMode() {
        return prefs.getString("theme", "system");
    }

    void setThemeMode(String mode) {
        prefs.edit().putString("theme", mode).apply();
    }

    void setFavorite(String id, boolean favorite) {
        prefs.edit().putBoolean("favorite:" + id, favorite).apply();
    }

    void setAliasAndIcon(String id, String alias, String icon) {
        prefs.edit().putString("alias:" + id, alias).putString("icon:" + id, icon).apply();
    }

    void setAcceptAnnouncements(boolean on) {
        prefs.edit().putBoolean("acceptAnnouncements", on).apply();
    }

    void setAutoStart(boolean on) {
        prefs.edit().putBoolean("autoStart", on).apply();
    }

    boolean quietEnabled() {
        return prefs.getBoolean("quietEnabled", false);
    }

    int quietDays() {
        return prefs.getInt("quietDays", 127);
    }

    int quietStart() {
        return prefs.getInt("quietStart", 1320);
    }

    int quietEnd() {
        return prefs.getInt("quietEnd", 420);
    }

    void saveQuietHours(
            boolean enabled, int days, int start, int end, java.util.Set<String> exceptions) {
        prefs.edit()
                .putBoolean("quietEnabled", enabled)
                .putInt("quietDays", days)
                .putInt("quietStart", start)
                .putInt("quietEnd", end)
                .putStringSet("quietExceptions", new java.util.HashSet<>(exceptions))
                .apply();
    }

    void setWidgetTarget(int widgetId, String peerId) {
        prefs.edit().putString("widget:" + widgetId, peerId).apply();
    }

    String widgetTarget(int widgetId) {
        return prefs.getString("widget:" + widgetId, "");
    }

    private SecretKey storageKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("intercom-secret")) {
            KeyGenerator gen =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(
                    new KeyGenParameterSpec.Builder(
                                    "intercom-secret",
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey("intercom-secret", null);
    }
}
