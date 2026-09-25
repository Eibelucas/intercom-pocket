package de.local.intercompocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Config {
    final SharedPreferences prefs;
    Config(Context c) {prefs=c.getSharedPreferences("intercom",Context.MODE_PRIVATE);if(!prefs.contains("id"))prefs.edit().putString("id",UUID.randomUUID().toString()).apply();}
    String id(){return prefs.getString("id","");}
    String name(){return prefs.getString("name","Mein A56");}
    boolean tls(){return prefs.getBoolean("tls",false);}
    boolean dnd(){return prefs.getBoolean("dnd",false);}
    String key(){
        try {
            String v=prefs.getString("secret","");if(v.isEmpty())return "";
            String[] a=v.split(":"); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,storageKey(),new GCMParameterSpec(128,Base64.decode(a[0],Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(a[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }
    void save(String name,String key,boolean tls) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,storageKey());
        String secret=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        prefs.edit().putString("name",name).putString("secret",secret).putBoolean("tls",tls).apply();
    }
    private SecretKey storageKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias("intercom-secret")) {
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("intercom-secret",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (SecretKey)ks.getKey("intercom-secret",null);
    }
}
