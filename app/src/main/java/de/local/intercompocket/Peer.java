package de.local.intercompocket;

import org.json.JSONObject;

public final class Peer {
    /** Reachability of a kiosk as shown in the list. */
    public enum Status {
        CHECKING(R.string.peer_checking),
        READY(R.string.peer_ready),
        UNREACHABLE(R.string.peer_unreachable),
        OFF(R.string.peer_off),
        DND(R.string.peer_dnd),
        KEY_MISMATCH(R.string.peer_key_mismatch),
        TLS_MISMATCH(R.string.peer_tls_mismatch),
        CONFIRM_CERT(R.string.peer_confirm_cert);

        public final int label;

        Status(int label) {
            this.label = label;
        }
    }

    public String id = "", name = "", host, version = "", candidatePin = "";
    public Status status = Status.CHECKING;
    public int adminPort = 2324, port = 2324, candidatePort = 2324;
    public boolean adminTls, tls, ready, manual;
    public long checked;

    public Peer(String host, int port, boolean tls) {
        this.host = host;
        this.port = port;
        this.adminPort = port;
        this.tls = tls;
        this.adminTls = tls;
    }

    public String base(boolean admin) {
        return ((admin ? adminTls : tls) ? "https://" : "http://")
                + (host.contains(":") ? "[" + host + "]" : host)
                + ":"
                + (admin ? adminPort : port);
    }

    public String addressKey() {
        return host + ":" + adminPort;
    }

    public JSONObject json() {
        return Wire.obj(
                "id", id, "name", name, "address", host, "port", port, "version", version, "tls",
                tls);
    }

    public static Peer from(JSONObject j, String actualHost) {
        Peer p = new Peer(actualHost, j.optInt("port", 2324), j.optBoolean("tls"));
        p.id = j.optString("id");
        p.name = j.optString("name", "");
        p.version = j.optString("version");
        return p;
    }
}
