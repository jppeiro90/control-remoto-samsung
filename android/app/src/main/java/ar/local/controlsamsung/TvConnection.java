package ar.local.controlsamsung;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.drafts.Draft_6455;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class TvConnection {
    public interface Listener { void state(boolean ready, boolean pending, String text); void apps(List<Protocol.TVApp> list); }
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebSocketClient client;
    private volatile boolean ready;
    private volatile int attempt;
    private final java.util.concurrent.ExecutorService keyQueue = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastPower;

    public TvConnection(SharedPreferences prefs, Listener listener) { this.prefs = prefs; this.listener = listener; }
    public boolean isReady() { return ready; }
    private void report(int n, boolean r, boolean p, String message) {
        main.post(() -> { if (n == attempt) { ready = r; listener.state(r, p, message); } });
    }
    public void disconnect() { attempt++; ready = false; if (client != null) { client.close(); client = null; } }
    public void connect(String host, int port) {
        disconnect(); final int n = attempt;
        java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean(false);
        report(n, false, true, "Aceptá ‘Control Samsung Android’ en la pantalla del TV.");
        try {
            String tokenKey = "token:" + host + ":" + port;
            WebSocketClient next = new WebSocketClient(Protocol.uri(host, port, prefs.getString(tokenKey, "")), new Draft_6455(), null, 6000) {
                private boolean denied;
                @Override protected void onSetSSLParameters(SSLParameters parameters) {
                    // Certificado del TV fijado por huella, sin DNS público.
                    parameters.setEndpointIdentificationAlgorithm(null);
                }
                @Override public void onOpen(ServerHandshake handshake) { }
                @Override public void onMessage(String message) {
                    if (n != attempt) return;
                    try {
                        JSONObject event = new JSONObject(message);
                        String kind = event.optString("event");
                        if (kind.equals("ms.channel.connect")) {
                            settled.set(true);
                            JSONObject data = event.optJSONObject("data");
                            if (data != null && data.has("token")) prefs.edit().putString(tokenKey, data.getString("token")).apply();
                            report(n, true, false, "Conectado al televisor · " + host);
                            send(Protocol.appsRequest());
                        } else if (kind.equals("ed.installedApp.get")) {
                            List<Protocol.TVApp> list = Protocol.parseApps(event);
                            main.post(() -> { if (n == attempt) listener.apps(list); });
                        } else if (kind.equals("ms.channel.unauthorized")) {
                            settled.set(true);
                            denied = true;
                            prefs.edit().remove(tokenKey).apply();
                            report(n, false, false, "Permiso rechazado. Autorizá el teléfono en el TV y volvé a conectar.");
                            close();
                        } else if (kind.equals("ms.error")) {
                            report(n, ready, false, "El TV rechazó el comando. Para Fuente, mantené presionado su botón y probá otra opción.");
                        }
                    } catch (Exception e) { report(n, ready, false, "No se pudo interpretar la respuesta del televisor."); }
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    settled.set(true);
                    if (n == attempt && !denied) report(n, false, false, "TV desconectado. Encendelo y presioná Conectar.");
                }
                @Override public void onError(Exception e) {
                    settled.set(true);
                    report(n, false, false, "No se pudo conectar. Revisá Wi‑Fi, IP y permisos del TV. Podés probar el puerto 8001.");
                }
            };
            next.setConnectionLostTimeout(20);
            if (port == 8002) {
                // Confianza en el primer uso, aislada a este TV. El resto de conexiones no cambia.
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException(); }
                    public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                        try {
                            byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                            String fingerprint = java.util.Base64.getEncoder().encodeToString(hash);
                            String key = "cert:" + host;
                            String saved = prefs.getString(key, "");
                            if (!saved.isEmpty() && !saved.equals(fingerprint)) throw new CertificateException("Cambió el certificado del TV");
                            if (saved.isEmpty()) prefs.edit().putString(key, fingerprint).apply();
                        } catch (CertificateException e) { throw e; }
                        catch (Exception e) { throw new CertificateException(e); }
                    }
                }}, null);
                next.setSocketFactory(ssl.getSocketFactory());
            }
            client = next;
            next.connect();
            main.postDelayed(() -> {
                if (n == attempt && !settled.get()) { disconnect(); listener.state(false, false, "Sin autorización tras 45 segundos. Aceptá el permiso en el TV y conectá de nuevo."); }
            }, 45000);
        } catch (Exception e) { report(n, false, false, "No se pudo iniciar la conexión: " + e.getMessage()); }
    }
    public void sendKey(String key) throws Exception { sendKey(key, "KEY_SOURCE".equals(key)); }
    public synchronized void sendKey(String key, boolean held) throws Exception {
        final String normalized = Protocol.normalizeKey(key);
        Protocol.key(normalized); // Validar antes de encolar.
        final WebSocketClient target = client;
        final int n = attempt;
        if (!ready || target == null || !target.isOpen()) throw new IllegalStateException("Primero conectá el televisor.");
        if ("KEY_POWER".equals(normalized)) {
            long now = System.nanoTime();
            if (lastPower != 0 && now - lastPower < 2_500_000_000L) return;
            lastPower = now;
        }
        keyQueue.execute(() -> {
            if (n != attempt || !target.isOpen()) return;
            try {
                KeyDelivery.deliver(normalized, held, payload -> {
                    if (!target.isOpen()) throw new IllegalStateException("Se cerró la conexión con el TV.");
                    target.send(payload);
                }, Thread::sleep);
            } catch (Exception e) { report(n, ready, false, "No se pudo enviar el botón al TV. Revisá la conexión."); }
        });
    }
    public void close() { disconnect(); keyQueue.shutdownNow(); }
    public void requestApps() throws Exception { send(Protocol.appsRequest()); }
    public void launch(Protocol.TVApp app) throws Exception { send(Protocol.launch(app.id, app.type)); }
    private void send(String payload) throws Exception {
        WebSocketClient current = client;
        if (current == null || !current.isOpen()) throw new IllegalStateException("Primero conectá el televisor.");
        current.send(payload);
    }
}
