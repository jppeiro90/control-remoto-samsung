package ar.local.controlsamsung;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class KeyDeliveryTest {
    private JSONObject params(String frame) throws Exception { return new JSONObject(frame).getJSONObject("params"); }
    @Test public void powerUsesCompatibleKeyExactlyOnce() throws Exception {
        List<String> frames = new ArrayList<>();
        KeyDelivery.deliver("KEY_POWEROFF", false, frames::add, ms -> fail("Power no debe mantenerse pulsado"));
        assertEquals(1, frames.size());
        assertEquals("KEY_POWER", params(frames.get(0)).getString("DataOfCmd"));
        assertEquals("Click", params(frames.get(0)).getString("Cmd"));
    }
    @Test public void sourcePressReleaseStayTogetherBeforeNextKey() throws Exception {
        List<String> frames = new ArrayList<>(); List<Long> pauses = new ArrayList<>();
        KeyDelivery.deliver("KEY_SOURCE", true, frames::add, pauses::add);
        KeyDelivery.deliver("KEY_VOLUP", false, frames::add, pauses::add);
        assertEquals(Arrays.asList(180L), pauses);
        assertEquals("Press", params(frames.get(0)).getString("Cmd"));
        assertEquals("Release", params(frames.get(1)).getString("Cmd"));
        assertEquals("KEY_SOURCE", params(frames.get(1)).getString("DataOfCmd"));
        assertEquals("KEY_VOLUP", params(frames.get(2)).getString("DataOfCmd"));
    }
    @Test public void sourceHasSimpleClickAlternative() throws Exception {
        List<String> frames = new ArrayList<>();
        KeyDelivery.deliver("KEY_SOURCE", false, frames::add, ms -> fail());
        assertEquals(1, frames.size()); assertEquals("Click",params(frames.get(0)).getString("Cmd"));
    }
    @Test public void interruptedHoldStillReleasesOriginalKey() throws Exception {
        List<String> frames = new ArrayList<>();
        try { KeyDelivery.deliver("KEY_SOURCE", true, frames::add, ms -> { throw new InterruptedException(); }); fail(); }
        catch(InterruptedException expected) { }
        assertEquals(2, frames.size()); assertEquals("Release",params(frames.get(1)).getString("Cmd"));
    }
    @Test public void failedPowerIsNeverRetriedAutomatically() throws Exception {
        List<String> attempts = new ArrayList<>();
        try { KeyDelivery.deliver("KEY_POWER", false, data -> { attempts.add(data); throw new Exception("offline"); }, ms -> {}); fail(); }
        catch(Exception expected) { assertEquals("offline",expected.getMessage()); }
        assertEquals(1,attempts.size());
    }
    @Test public void everyVisibleButtonMapsToSupportedKey() throws Exception {
        java.nio.file.Path file=java.nio.file.Paths.get("src/main/assets/ui/index.html");
        if(!java.nio.file.Files.exists(file)) file=java.nio.file.Paths.get("app/src/main/assets/ui/index.html");
        String html=new String(java.nio.file.Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("data-key=\"([A-Z]+)\"").matcher(html);
        int count=0;while(matcher.find()) { Protocol.key("KEY_"+matcher.group(1));count++; }
        assertTrue(count>=20); assertTrue(html.contains("data-key=\"POWER\"")); assertFalse(html.contains("data-key=\"POWEROFF\""));
        for(int i=0;i<10;i++) Protocol.key("KEY_"+i);
    }
}
