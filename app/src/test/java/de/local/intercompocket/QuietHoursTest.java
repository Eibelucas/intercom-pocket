package de.local.intercompocket;

import static org.junit.Assert.*;
import java.time.LocalDateTime;
import org.junit.Test;

public class QuietHoursTest {
    @Test public void daytimeUsesSelectedStartDayAndExclusiveEnd(){
        assertFalse(QuietHours.active(true,1,540,1020,LocalDateTime.of(2026,9,28,8,59)));
        assertTrue(QuietHours.active(true,1,540,1020,LocalDateTime.of(2026,9,28,9,0)));
        assertFalse(QuietHours.active(true,1,540,1020,LocalDateTime.of(2026,9,28,17,0)));
    }
    @Test public void overnightWindowBelongsToPreviousDay(){
        int monday=1;
        assertTrue(QuietHours.active(true,monday,1320,420,LocalDateTime.of(2026,9,28,23,0)));
        assertTrue(QuietHours.active(true,monday,1320,420,LocalDateTime.of(2026,9,29,6,59)));
        assertFalse(QuietHours.active(true,monday,1320,420,LocalDateTime.of(2026,9,29,7,0)));
        assertFalse(QuietHours.active(true,monday,1320,420,LocalDateTime.of(2026,9,29,23,0)));
    }
    @Test public void disabledAndEmptyDaysNeverBlock(){
        LocalDateTime now=LocalDateTime.of(2026,9,28,23,0);
        assertFalse(QuietHours.active(false,127,1320,420,now));
        assertFalse(QuietHours.active(true,0,1320,420,now));
    }
}
