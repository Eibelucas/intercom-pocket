package de.local.intercompocket;

import java.util.ArrayDeque;
import java.util.Deque;

/** Only local technical events. No peer names, addresses, keys, tokens or audio. */
final class CallDiagnostics {
    private final Deque<String> events=new ArrayDeque<>();
    private long started=System.nanoTime();
    synchronized void clear(){events.clear();started=System.nanoTime();}
    synchronized void event(String event){
        if(events.size()==60)events.removeFirst();
        events.addLast(String.format(java.util.Locale.ROOT,"+%.2fs %s",(System.nanoTime()-started)/1e9,event));
    }
    void error(String stage,Throwable error){
        // Exception messages can contain signed URLs. Record types only.
        StringBuilder types=new StringBuilder();
        for(int i=0;error!=null&&i<4;i++,error=error.getCause()){
            if(i>0)types.append(" / ");types.append(error.getClass().getSimpleName());
        }
        event(stage+": "+types);
    }
    synchronized String report(){return "Intercom Pocket 1.1.0\n"+String.join("\n",events);}
    synchronized boolean hasEvents(){return !events.isEmpty();}
}
