package ar.local.controlsamsung;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class Protocol {
    private Protocol() {}
    public static boolean privateIp(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] p = host.split("\\.");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(p[i]); if (n[i] > 255) return false; }
        return n[0] == 10 || n[0] == 192 && n[1] == 168 || n[0] == 172 && n[1] >= 16 && n[1] <= 31;
    }
    public static URI uri(String host, int port, String token) throws Exception {
        if (!privateIp(host) || (port != 8001 && port != 8002)) throw new IllegalArgumentException("IP o puerto inválidos");
        String name = Base64.getEncoder().encodeToString("Control Samsung Android".getBytes(StandardCharsets.UTF_8));
        return new URI((port == 8002 ? "wss" : "ws") + "://" + host + ":" + port +
            "/api/v2/channels/samsung.remote.control?name=" + URLEncoder.encode(name, "UTF-8") +
            (token.isEmpty() ? "" : "&token=" + URLEncoder.encode(token, "UTF-8")));
    }
    public static String key(String key) throws Exception {
        return key(key, "Click");
    }
    public static String normalizeKey(String key) {
        if ("KEY_POWEROFF".equals(key)) return "KEY_POWER";
        return key;
    }
    public static String key(String key, String command) throws Exception {
        key = normalizeKey(key);
        if (key == null || !key.matches("KEY_(POWER|HOME|SOURCE|MENU|UP|DOWN|LEFT|RIGHT|ENTER|RETURN|EXIT|VOLUP|VOLDOWN|MUTE|CHUP|CHDOWN|GUIDE|INFO|PLAY|PAUSE|STOP|REWIND|FF|[0-9])"))
            throw new IllegalArgumentException("Botón inválido");
        if (!"Click".equals(command) && !"Press".equals(command) && !"Release".equals(command)) throw new IllegalArgumentException("Pulsación inválida");
        return new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
            .put("Cmd", command).put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString();
    }
    public static String appsRequest() throws Exception {
        return new JSONObject().put("method", "ms.channel.emit").put("params", new JSONObject()
            .put("event", "ed.installedApp.get").put("to", "host")).toString();
    }
    public static String launch(String id, int type) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,150}")) throw new IllegalArgumentException("Identificador inválido");
        return new JSONObject().put("method", "ms.channel.emit").put("params", new JSONObject()
            .put("event", "ed.apps.launch").put("to", "host").put("data", new JSONObject()
                .put("action_type", type == 2 ? "DEEP_LINK" : "NATIVE_LAUNCH").put("appId", id).put("metaTag", ""))).toString();
    }
    public static final class TVApp {
        public final String id, name;
        public final int type;
        public TVApp(String id, String name, int type) { this.id = id; this.name = name; this.type = type; }
    }
    public static List<TVApp> parseApps(JSONObject event) throws Exception {
        Object raw = event.opt("data");
        if (raw instanceof String) raw = new JSONObject((String) raw);
        JSONArray list = raw instanceof JSONObject ? ((JSONObject) raw).optJSONArray("data") : raw instanceof JSONArray ? (JSONArray) raw : null;
        List<TVApp> result = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            String id = a.optString("appId"), name = a.optString("name", id);
            if (id.matches("[A-Za-z0-9._-]{1,150}")) result.add(new TVApp(id, name, a.optInt("app_type", 2)));
        }
        return result;
    }
}
