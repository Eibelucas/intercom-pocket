package de.local.intercompocket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** NanoHTTPD does not decode chunked request bodies. Consume one bounded JSON body. */
final class HttpBody {
    static final int LIMIT=65536;
    static final class Invalid extends IOException {final int code;Invalid(int code){this.code=code;}}
    static String read(InputStream input,Map<String,String> headers)throws IOException{
        String transfer=headers.get("transfer-encoding"),length=headers.get("content-length");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(transfer!=null){
            if(length!=null||!transfer.trim().equalsIgnoreCase("chunked"))throw new Invalid(400);
            for(int count=0;count<1024;count++){
                String line=line(input);String sizeText=line.split(";",2)[0];
                if(!sizeText.matches("[0-9a-fA-F]{1,8}"))throw new Invalid(400);
                long size=Long.parseLong(sizeText,16);
                if(size>LIMIT-out.size())throw new Invalid(413);
                if(size==0){
                    int trailers=0;String trailer;
                    do{trailer=line(input);trailers+=trailer.length()+2;if(trailers>8192)throw new Invalid(413);}while(!trailer.isEmpty());
                    return new String(out.toByteArray(),StandardCharsets.UTF_8);
                }
                copy(input,out,(int)size);
                if(input.read()!='\r'||input.read()!='\n')throw new Invalid(400);
            }
            throw new Invalid(413);
        }
        if(length==null||!length.matches("[0-9]{1,10}"))throw new Invalid(400);
        long size=Long.parseLong(length);if(size>LIMIT)throw new Invalid(413);
        copy(input,out,(int)size);return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static String line(InputStream input)throws IOException{
        StringBuilder b=new StringBuilder();
        while(b.length()<1024){int c=input.read();if(c<0)throw new Invalid(400);if(c=='\r'){if(input.read()!='\n')throw new Invalid(400);return b.toString();}if(c=='\n')throw new Invalid(400);b.append((char)c);}
        throw new Invalid(413);
    }
    private static void copy(InputStream input,ByteArrayOutputStream out,int length)throws IOException{
        byte[] buffer=new byte[Math.min(4096,Math.max(1,length))];
        while(length>0){int n=input.read(buffer,0,Math.min(length,buffer.length));if(n<0)throw new Invalid(400);if(n==0)continue;out.write(buffer,0,n);length-=n;}
    }
}
