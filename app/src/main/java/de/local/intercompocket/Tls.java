package de.local.intercompocket;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import okhttp3.OkHttpClient;

final class Tls {
    private final Config config;
    Tls(Config config){this.config=config;}
    static String pin(X509Certificate cert) throws Exception { return Wire.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())); }
    String saved(Peer p,boolean admin){return config.prefs.getString("pin:"+p.host+":"+(admin?p.adminPort:p.port),"");}
    void trust(Peer p){config.prefs.edit().putString("pin:"+p.host+":"+p.port,p.candidatePin).apply();if(p.adminTls&&p.adminPort==p.port)config.prefs.edit().putString("pin:"+p.host+":"+p.adminPort,p.candidatePin).apply();}
    OkHttpClient client(Peer p,boolean admin) throws Exception {
        OkHttpClient.Builder b=new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).writeTimeout(5,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false);
        if(admin?p.adminTls:p.tls){
            String expected=saved(p,admin);
            X509TrustManager tm=new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] c,String a)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("client");}
                public void checkServerTrusted(X509Certificate[] c,String a)throws java.security.cert.CertificateException{
                    try{if(c.length==0 || expected.isEmpty() || !expected.equals(pin(c[0])))throw new Exception("Zertifikat bitte bestätigen");}
                    catch(Exception e){throw new java.security.cert.CertificateException("Zertifikat bitte bestätigen");}
                }
            };
            SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new TrustManager[]{tm},new SecureRandom());
            b.sslSocketFactory(ssl.getSocketFactory(),tm).hostnameVerifier((hostname,session)->hostname.equals(p.host));
        }
        return b.build();
    }
    // This unauthenticated handshake collects a certificate only; it never sends a key, token or audio.
    String inspect(Peer p,boolean admin) throws Exception {
        final X509Certificate[][] chain={null};
        X509TrustManager tm=new X509TrustManager(){public X509Certificate[]getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[]c,String a)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("client certificates unsupported");}public void checkServerTrusted(X509Certificate[]c,String a){chain[0]=c;}};
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new TrustManager[]{tm},new SecureRandom());
        try(Socket raw=new Socket()){
            raw.connect(new java.net.InetSocketAddress(p.host,admin?p.adminPort:p.port),4000);raw.setSoTimeout(4000);
            try(SSLSocket s=(SSLSocket)ssl.getSocketFactory().createSocket(raw,p.host,admin?p.adminPort:p.port,true)){s.startHandshake();return pin(chain[0][0]);}
        }
    }
    SSLServerSocketFactory server() throws Exception {
        String alias="intercom-tls";KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias)){
            KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=Intercom Pocket"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(new Date(System.currentTimeMillis()-86400000L))
                .setCertificateNotAfter(new Date(System.currentTimeMillis()+10L*365*86400000)).build());gen.generateKeyPair();
        }
        PrivateKey privateKey=(PrivateKey)store.getKey(alias,null);
        X509Certificate cert=(X509Certificate)store.getCertificate(alias);
        X509ExtendedKeyManager km=new X509ExtendedKeyManager(){
            public String[]getClientAliases(String t,Principal[]i){return null;}public String chooseClientAlias(String[]t,Principal[]i,Socket s){return null;}
            public String[]getServerAliases(String t,Principal[]i){return "RSA".equals(t)?new String[]{alias}:null;}
            public String chooseServerAlias(String t,Principal[]i,Socket s){return "RSA".equals(t)?alias:null;}
            public X509Certificate[]getCertificateChain(String a){return new X509Certificate[]{cert};}public PrivateKey getPrivateKey(String a){return privateKey;}
        };
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(new KeyManager[]{km},null,new SecureRandom());return ssl.getServerSocketFactory();
    }
}
