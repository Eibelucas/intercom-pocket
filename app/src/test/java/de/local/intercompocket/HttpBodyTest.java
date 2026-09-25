package de.local.intercompocket;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.Assert.*;

public class HttpBodyTest {
    String read(String body,Map<String,String> headers)throws IOException{return HttpBody.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),headers);}
    void refused(String body,Map<String,String> headers,int code)throws IOException{try{read(body,headers);fail("accepted invalid body");}catch(HttpBody.Invalid e){assertEquals(code,e.code);}}
    @Test public void multipleChunksExtensionsAndTrailersAreDecoded()throws Exception{
        assertEquals("{\"ok\":true}",read("5;x=y\r\n{\"ok\"\r\n6\r\n:true}\r\n0\r\nX-Note: value\r\n\r\n",Map.of("transfer-encoding","chunked")));
    }
    @Test public void ambiguousOrTruncatedRequestsAreRefused()throws Exception{
        refused("0\r\n\r\n",Map.of("transfer-encoding","chunked","content-length","5"),400);
        refused("1\r\nx",Map.of("transfer-encoding","chunked"),400);
        refused("{}",Map.of("content-length","5"),400);
        refused("{}",Map.of(),400);
        refused("-1\r\n",Map.of("transfer-encoding","chunked"),400);
    }
    @Test public void bothEncodingsEnforceTheSameTotalLimit()throws Exception{
        refused("",Map.of("content-length","65537"),413);
        refused("10001\r\n",Map.of("transfer-encoding","chunked"),413);
        refused("8000\r\n"+"x".repeat(32768)+"\r\n8001\r\n",Map.of("transfer-encoding","chunked"),413);
        assertEquals(65536,read("x".repeat(65536),Map.of("content-length","65536")).length());
    }
}
