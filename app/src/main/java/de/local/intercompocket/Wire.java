package de.local.intercompocket;

import org.json.JSONObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Independent implementation of the public Kiosk Satellite intercom wire format. */
public final class Wire {
    public static final int RATE = 16000, FRAME_BYTES = 2560;
    private final String key;
    private final java.util.function.LongSupplier clock;
    private final Map<String, Long> used = new HashMap<>();
    public Wire(String key) { this(key, System::currentTimeMillis); }
    Wire(String key, java.util.function.LongSupplier clock) { this.key = key; this.clock = clock; }
    public static JSONObject obj(Object... pairs) {
        JSONObject j = new JSONObject();
        try { for (int i=0;i<pairs.length;i+=2) j.put(pairs[i].toString(),pairs[i+1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return j;
    }
    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(); for(byte v:b) s.append(String.format("%02x",v & 255)); return s.toString();
    }
    public static String fingerprint(String key) {
        if(key.isEmpty()) return "";
        try { return hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0,8); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String sign(String payload) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(("intercom:"+key).getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Base64.getUrlEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    public String token(String call,String from) {
        try {
            String payload=Base64.getUrlEncoder().encodeToString(obj("intercom",call,"from",from,"n",UUID.randomUUID().toString(),"exp",clock.getAsLong()+60000).toString().getBytes(StandardCharsets.UTF_8));
            return payload+"."+sign(payload);
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    public synchronized JSONObject verify(String token,String call) {
        try {
            if(key.isEmpty() || token==null || token.length()>8192) return null;
            String[] p=token.split("\\.",-1);
            if(p.length!=2 || !MessageDigest.isEqual(sign(p[0]).getBytes(StandardCharsets.US_ASCII),p[1].getBytes(StandardCharsets.US_ASCII))) return null;
            JSONObject j=new JSONObject(new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8));
            long now=clock.getAsLong(), exp=j.getLong("exp");
            if(exp<=now || exp>now+90000 || !call.equals(j.optString("intercom")) || j.optString("from").isEmpty()) return null;
            used.entrySet().removeIf(e->e.getValue()<now);
            if(used.containsKey(token) || used.size()>4096) return null;
            used.put(token,exp); return j;
        } catch(Exception e) {return null;}
    }
    public static boolean validCallId(String id) { return id!=null && id.matches("[A-Za-z0-9_-]{1,160}"); }
}
