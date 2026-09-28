package de.local.intercompocket;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Local groups refer to kiosk identities, so IP and room-name changes keep membership. */
final class RoomGroups {
    static final int MAX_MEMBERS=32;
    static final class Group {
        final String id,name;
        final Set<String> members;
        Group(String id,String name,Collection<String> members){
            this.id=id;this.name=name;
            this.members=Collections.unmodifiableSet(new LinkedHashSet<>(members));
        }
    }
    private final SharedPreferences prefs;
    RoomGroups(SharedPreferences prefs){this.prefs=prefs;}
    List<Group> list(){
        List<Group> groups=new ArrayList<>();
        try{
            JSONArray data=new JSONArray(prefs.getString("roomGroups","[]"));
            for(int i=0;i<data.length();i++){
                JSONObject item=data.optJSONObject(i);if(item==null)continue;
                String id=item.optString("id"),name=item.optString("name");
                JSONArray saved=item.optJSONArray("members");
                Set<String> members=new LinkedHashSet<>();
                if(saved!=null)for(int n=0;n<saved.length();n++){String member=saved.optString(n);if(!member.isEmpty())members.add(member);}
                if(!id.isEmpty()&&!name.isEmpty())groups.add(new Group(id,name,members));
            }
        }catch(Exception ignored){}
        groups.sort(Comparator.comparing(g->g.name.toLowerCase(Locale.ROOT)));
        return groups;
    }
    Group save(String id,String name,Collection<String> members){
        name=name.trim();
        if(name.isEmpty()||name.length()>40)throw new IllegalArgumentException("Bitte einen Gruppennamen mit 1–40 Zeichen eingeben.");
        Set<String> unique=new LinkedHashSet<>(members);unique.remove(null);unique.remove("");
        if(unique.isEmpty()||unique.size()>MAX_MEMBERS)throw new IllegalArgumentException("Bitte 1–32 Kiosks auswählen.");
        List<Group> groups=list();
        for(Group group:groups)if(!group.id.equals(id)&&group.name.equalsIgnoreCase(name))throw new IllegalArgumentException("Dieser Gruppenname ist bereits vergeben.");
        String groupId=id==null?UUID.randomUUID().toString():id;
        groups.removeIf(g->g.id.equals(groupId));
        Group group=new Group(groupId,name,unique);groups.add(group);write(groups);return group;
    }
    void remove(String id){List<Group> groups=list();groups.removeIf(g->g.id.equals(id));write(groups);}
    private void write(List<Group> groups){
        JSONArray data=new JSONArray();
        for(Group group:groups)data.put(Wire.obj("id",group.id,"name",group.name,"members",new JSONArray(group.members)));
        prefs.edit().putString("roomGroups",data.toString()).apply();
    }
}
