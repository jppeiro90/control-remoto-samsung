package ar.local.controlsamsung;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class ProtocolTest {
    @Test public void onlyPrivateIPv4Targets() {
        for (String host : new String[]{"192.168.0.50", "10.1.2.3", "172.16.2.1", "172.31.255.254"}) assertTrue(host, Protocol.privateIp(host));
        for (String host : new String[]{"8.8.8.8", "127.0.0.1", "169.254.169.254", "172.32.0.1", "192.168.0.256", "192.168.0.1/path", "localhost", ""}) assertFalse(host, Protocol.privateIp(host));
    }
    @Test public void pairingUriEncodesToken() throws Exception {
        String value = Protocol.uri("192.168.0.50", 8002, "a&b").toString();
        assertTrue(value.startsWith("wss://192.168.0.50:8002/api/v2/channels/samsung.remote.control?name="));
        assertTrue(value.endsWith("&token=a%26b"));
        assertEquals("ws", Protocol.uri("192.168.0.50", 8001, "").getScheme());
    }
    @Test public void keyUsesSamsungProtocol() throws Exception {
        JSONObject value = new JSONObject(Protocol.key("KEY_VOLUP"));
        assertEquals("ms.remote.control", value.getString("method"));
        assertEquals("Click", value.getJSONObject("params").getString("Cmd"));
        assertEquals("KEY_VOLUP", value.getJSONObject("params").getString("DataOfCmd"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidKey() throws Exception { Protocol.key("KEY_SHELL"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidAppId() throws Exception { Protocol.launch("../../config", 2); }
    @Test public void launchAppTypes() throws Exception {
        JSONObject deep = new JSONObject(Protocol.launch("3201601007230", 2));
        assertEquals("ed.apps.launch", deep.getJSONObject("params").getString("event"));
        assertEquals("DEEP_LINK", deep.getJSONObject("params").getJSONObject("data").getString("action_type"));
        JSONObject nativeApp = new JSONObject(Protocol.launch("org.tizen.app", 1));
        assertEquals("NATIVE_LAUNCH", nativeApp.getJSONObject("params").getJSONObject("data").getString("action_type"));
    }
    @Test public void readsInstalledAppsAndActualIds() throws Exception {
        JSONObject event = new JSONObject("{\"event\":\"ed.installedApp.get\",\"data\":{\"data\":[{\"appId\":\"3201907018807\",\"name\":\"Netflix\",\"app_type\":2}]}}");
        List<Protocol.TVApp> apps = Protocol.parseApps(event);
        assertEquals(1, apps.size()); assertEquals("Netflix", apps.get(0).name); assertEquals("3201907018807", apps.get(0).id);
        event.put("data", event.getJSONObject("data").toString());
        assertEquals("3201907018807", Protocol.parseApps(event).get(0).id);
    }
    @Test public void appDiscoveryRequest() throws Exception {
        JSONObject value = new JSONObject(Protocol.appsRequest());
        assertEquals("ed.installedApp.get", value.getJSONObject("params").getString("event"));
        assertEquals("host", value.getJSONObject("params").getString("to"));
    }
}
