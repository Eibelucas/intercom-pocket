package de.local.intercompocket;
import org.junit.Test;
import static org.junit.Assert.*;
public class CallDiagnosticsTest {
    @Test public void signedUrlsAndKeysInExceptionsAreNeverCopied(){CallDiagnostics d=new CallDiagnostics();d.error("WebSocket",new RuntimeException("https://device/api/audio?token=SECRET",new SecurityException("shared-key")));String r=d.report();assertTrue(r.contains("RuntimeException / SecurityException"));assertFalse(r.contains("SECRET"));assertFalse(r.contains("device"));assertFalse(r.contains("shared-key"));}
    @Test public void historyIsBoundedAndNewCallResetsIt(){CallDiagnostics d=new CallDiagnostics();for(int i=0;i<80;i++)d.event("event "+i);assertEquals(61,d.report().split("\n").length);d.clear();assertFalse(d.hasEvents());}
}
