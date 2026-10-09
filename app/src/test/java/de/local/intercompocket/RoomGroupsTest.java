package de.local.intercompocket;

import static org.junit.Assert.*;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.*;

public class RoomGroupsTest {
    @Test
    public void membershipSurvivesRestartAndRenameWithoutChangingIdentity() {
        SharedPreferences prefs = TestPreferences.create();
        RoomGroups groups = new RoomGroups(prefs);
        RoomGroups.Group group =
                groups.save(null, " Ground floor ", Arrays.asList("kitchen", "hall", "kitchen"));
        RoomGroups.Group restored = new RoomGroups(prefs).list().get(0);
        assertEquals("Ground floor", restored.name);
        assertEquals(Set.of("kitchen", "hall"), restored.members);
        groups.save(restored.id, "Downstairs", Collections.singleton("hall"));
        restored = new RoomGroups(prefs).list().get(0);
        assertEquals(group.id, restored.id);
        assertEquals("Downstairs", restored.name);
        assertEquals(Set.of("hall"), restored.members);
        groups.remove(group.id);
        assertTrue(new RoomGroups(prefs).list().isEmpty());
    }

    @Test
    public void invalidEditsDoNotReplaceExistingGroup() {
        SharedPreferences prefs = TestPreferences.create();
        RoomGroups groups = new RoomGroups(prefs);
        RoomGroups.Group group = groups.save(null, "Upstairs", Set.of("bedroom"));
        assertThrows(
                IllegalArgumentException.class, () -> groups.save(group.id, "", Set.of("bedroom")));
        assertThrows(IllegalArgumentException.class, () -> groups.save(group.id, "Upstairs", Set.of()));
        assertThrows(
                IllegalArgumentException.class, () -> groups.save(null, "upstairs", Set.of("kitchen")));
        Set<String> tooMany = new HashSet<>();
        for (int i = 0; i < 33; i++) tooMany.add("room-" + i);
        assertThrows(IllegalArgumentException.class, () -> groups.save(group.id, "Upstairs", tooMany));
        assertEquals(1, groups.list().size());
        assertEquals(Set.of("bedroom"), groups.list().get(0).members);
    }

    @Test
    public void corruptStoredDataDoesNotCrashSettings() {
        SharedPreferences prefs = TestPreferences.create();
        prefs.edit().putString("roomGroups", "invalid json").apply();
        assertTrue(new RoomGroups(prefs).list().isEmpty());
    }
}
