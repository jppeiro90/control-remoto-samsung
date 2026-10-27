package ar.local.controlsamsung;

/** Una pulsación completa. Nunca reintenta Power ni la envía a otra sesión. */
public final class KeyDelivery {
    public interface Sender { void send(String payload) throws Exception; }
    public interface Pause { void waitMillis(long millis) throws InterruptedException; }
    private KeyDelivery() {}
    public static void deliver(String key, boolean held, Sender sender, Pause pause) throws Exception {
        String press = Protocol.key(key, held ? "Press" : "Click");
        String release = held ? Protocol.key(key, "Release") : null;
        sender.send(press);
        if (held) {
            try { pause.waitMillis(180); }
            finally { sender.send(release); }
        }
    }
}
