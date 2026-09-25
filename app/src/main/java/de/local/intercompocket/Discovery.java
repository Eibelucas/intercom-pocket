package de.local.intercompocket;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

final class Discovery {
    private final NsdManager nsd;
    private final Engine engine;
    private WifiManager.MulticastLock multicast;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final Queue<NsdServiceInfo> queue=new ArrayDeque<>();
    private boolean resolving,stopped;
    Discovery(Context c,Engine e){engine=e;nsd=(NsdManager)c.getSystemService(Context.NSD_SERVICE);WifiManager wifi=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);if(wifi!=null)multicast=wifi.createMulticastLock("IntercomPocket-discovery");}
    void start(){
        if(multicast!=null){multicast.setReferenceCounted(false);multicast.acquire();}
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceType("_kiosk-satellite._tcp.");info.setServiceName("ks-"+engine.config.id());info.setPort(2324);
        info.setAttribute("id",engine.config.id());info.setAttribute("name",engine.config.name());info.setAttribute("version","2026.9.78-pocket.1");info.setAttribute("port","2324");
        registration=new NsdManager.RegistrationListener(){public void onRegistrationFailed(NsdServiceInfo i,int e){engine.detail="Automatische Sichtbarkeit fehlgeschlagen. App neu starten.";engine.changed();}public void onUnregistrationFailed(NsdServiceInfo i,int e){}public void onServiceRegistered(NsdServiceInfo i){}public void onServiceUnregistered(NsdServiceInfo i){}};
        nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
        discovery=new NsdManager.DiscoveryListener(){public void onDiscoveryStarted(String s){}public void onDiscoveryStopped(String s){}public void onStartDiscoveryFailed(String s,int e){engine.detail="Gerätesuche nicht verfügbar. Kiosk manuell hinzufügen.";engine.changed();}public void onStopDiscoveryFailed(String s,int e){}public void onServiceLost(NsdServiceInfo i){}public void onServiceFound(NsdServiceInfo i){if(!i.getServiceName().equals("ks-"+engine.config.id()))enqueue(i);}};
        nsd.discoverServices("_kiosk-satellite._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
    }
    synchronized void enqueue(NsdServiceInfo i){if(stopped||queue.size()>64)return;queue.add(i);next();}
    synchronized void next(){
        if(stopped||resolving||queue.isEmpty())return;resolving=true;
        nsd.resolveService(queue.remove(),new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo i,int e){done();}
            public void onServiceResolved(NsdServiceInfo i){
                if(!stopped&&i.getHost()!=null){Peer p=new Peer(i.getHost().getHostAddress(),i.getPort(),"1".equals(attr(i,"tls")));p.id=attr(i,"id");p.name=attr(i,"name");engine.found(p);}done();
            }
            void done(){synchronized(Discovery.this){resolving=false;next();}}
        });
    }
    String attr(NsdServiceInfo i,String k){byte[]b=i.getAttributes().get(k);return b==null?"":new String(b,StandardCharsets.UTF_8);}
    void stop(){stopped=true;try{if(discovery!=null)nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}try{if(registration!=null)nsd.unregisterService(registration);}catch(Exception ignored){}if(multicast!=null&&multicast.isHeld())multicast.release();}
    static String localIp(){try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))if(n.isUp()&&!n.isLoopback()&&n.getName().startsWith("wlan"))for(InetAddress a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address)return a.getHostAddress();}catch(Exception ignored){}return "WLAN nicht verbunden";}
}
