package de.local.intercompocket;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

final class QuietHours {
    private QuietHours(){}
    // Bits 0–6 represent Monday through Sunday. The start day owns an overnight window.
    static boolean active(boolean enabled,int days,int start,int end,LocalDateTime now){
        if(!enabled||days==0||start<0||start>1439||end<0||end>1439)return false;
        int minute=now.getHour()*60+now.getMinute();
        int today=now.getDayOfWeek().getValue()-1;
        if(start==end)return (days&(1<<today))!=0;
        if(start<end)return (days&(1<<today))!=0&&minute>=start&&minute<end;
        if(minute>=start)return (days&(1<<today))!=0;
        int yesterday=(today+6)%7;
        return minute<end&&(days&(1<<yesterday))!=0;
    }
}
