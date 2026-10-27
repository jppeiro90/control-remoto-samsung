package ar.local.controlsamsung;

import android.content.Context;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Discovery {
    public static final class Device {
        public final String host, name;
        Device(String host, String name) { this.host = host; this.name = name; }
    }
    public static List<Device> find(Context context) throws Exception {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifi.createMulticastLock("samsung-discovery");
        Set<String> hosts = new LinkedHashSet<>();
        List<Device> result = new ArrayList<>();
        lock.acquire();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(300);
            byte[] query = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(query, query.length, InetAddress.getByName("239.255.255.250"), 1900));
            long until = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < until) {
                DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                try {
                    socket.receive(packet);
                    String host = packet.getAddress().getHostAddress();
                    String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (Protocol.privateIp(host) && response.toLowerCase(Locale.ROOT).contains("samsung")) hosts.add(host);
                } catch (java.net.SocketTimeoutException ignored) { }
            }
        } finally { if (lock.isHeld()) lock.release(); }
        for (String host : hosts) {
            if (result.size() >= 12) break;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("http://" + host + ":8001/api/v2/").openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(900); connection.setReadTimeout(900);
                byte[] bytes;
                try (java.io.InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048]; int size;
                    while ((size = in.read(buffer)) != -1 && out.size() < 65536) out.write(buffer, 0, size);
                    bytes = out.toByteArray();
                }
                JSONObject info = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                JSONObject device = info.optJSONObject("device");
                if (device != null && (info.optString("type").equals("Samsung SmartTV") || device.optString("type").equals("Samsung SmartTV")))
                    result.add(new Device(host, device.optString("name", "Samsung TV") + " · " + device.optString("modelName", host)));
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
        }
        return result;
    }
}
