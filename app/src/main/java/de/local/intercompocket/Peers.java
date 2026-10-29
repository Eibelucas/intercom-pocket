package de.local.intercompocket;

import java.util.ArrayList;
import java.util.Comparator;

final class Peers {
    private Peers() {}

    /** Favorites first, then reachable kiosks, then by display name. */
    static ArrayList<Peer> sorted(Engine engine, Config config) {
        ArrayList<Peer> list = new ArrayList<>(engine.peers.values());
        list.sort(
                Comparator.comparing((Peer p) -> !config.favorite(p.id))
                        .thenComparing(p -> !p.ready)
                        .thenComparing(config::display));
        return list;
    }

    static boolean anyReady(Engine engine) {
        for (Peer p : engine.peers.values()) if (p.ready) return true;
        return false;
    }
}
