package de.local.intercompocket;

import android.content.SharedPreferences;
import java.util.*;
import static org.mockito.Mockito.*;

/** In-memory persisted values shared by fresh Config/RoomGroups instances. */
final class TestPreferences {
    static SharedPreferences create(){
        Map<String,Object> values=new HashMap<>();
        SharedPreferences prefs=mock(SharedPreferences.class);
        when(prefs.contains(anyString())).thenAnswer(i->values.containsKey(i.getArgument(0)));
        when(prefs.getString(anyString(),any())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.getBoolean(anyString(),anyBoolean())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.getLong(anyString(),anyLong())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.getInt(anyString(),anyInt())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.getStringSet(anyString(),any())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        when(prefs.edit()).thenAnswer(unused->{
            Map<String,Object> pending=new HashMap<>();
            SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class,invocation->{
                String method=invocation.getMethod().getName();
                if(method.startsWith("put")){pending.put(invocation.getArgument(0),invocation.getArgument(1));return invocation.getMock();}
                if(method.equals("remove")){pending.put(invocation.getArgument(0),null);return invocation.getMock();}
                if(method.equals("apply")||method.equals("commit")){
                    pending.forEach((key,value)->{if(value==null)values.remove(key);else values.put(key,value);});
                    return method.equals("commit")?true:null;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            return editor;
        });
        return prefs;
    }
}
