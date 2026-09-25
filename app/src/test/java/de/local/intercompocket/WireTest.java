package de.local.intercompocket;

import org.junit.Test;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class WireTest {
    // Constructed independently in the documented Dart AuthStore order and encoding.
    String kioskToken(String payload,String key)throws Exception{
        String encoded=Base64.getUrlEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(("intercom:"+key).getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return encoded+"."+Base64.getUrlEncoder().encodeToString(hmac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void acceptsKioskTokenWithPaddedBase64AndMillisecondExpiry()throws Exception{
        String token=kioskToken("{\"intercom\":\"test-call-1\",\"from\":\"kitchen\",\"n\":\"a1b\",\"exp\":1060000}","test-shared-key");
        Wire wire=new Wire("test-shared-key",()->1000000L);
        JSONObject verified=wire.verify(token,"test-call-1");assertNotNull(verified);assertEquals("kitchen",verified.getString("from"));assertNull("single-use token",wire.verify(token,"test-call-1"));
    }
    @Test public void refusesWrongKeyCallAndTampering(){Wire w=new Wire("correct");String t=w.token("call","phone");assertNull(new Wire("wrong").verify(t,"call"));assertNull(new Wire("correct").verify(t,"other"));assertNull(new Wire("correct").verify(t+"x","call"));}
    @Test public void expiryIsMillisecondsAndStrict(){AtomicLong now=new AtomicLong(1000000);Wire w=new Wire("key",now::get);String t=w.token("call","phone");now.addAndGet(60000);assertNull(w.verify(t,"call"));}
    @Test public void rejectsUnboundedExpiryAndMalformedData()throws Exception{Wire w=new Wire("key",()->1000000L);assertNull(w.verify(kioskToken("{\"intercom\":\"c\",\"from\":\"f\",\"exp\":2000000}","key"),"c"));assertNull(w.verify("a.b.c","c"));assertNull(w.verify(null,"c"));assertNull(new Wire("").verify(new Wire("").token("c","f"),"c"));}
    @Test public void publicKeyFingerprintMatchesSha256(){assertEquals("ba7816bf",Wire.fingerprint("abc"));assertEquals("",Wire.fingerprint(""));}
    @Test public void callIdsCannotEscapeRoute(){assertTrue(Wire.validCallId("m1-0_ab"));assertFalse(Wire.validCallId("../identity"));assertFalse(Wire.validCallId("a?token=x"));assertFalse(Wire.validCallId(""));}
    @Test public void audioFrameIsEightyMilliseconds(){assertEquals(80,Wire.FRAME_BYTES*1000/(Wire.RATE*2));}
}
