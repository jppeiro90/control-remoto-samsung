package ar.local.controlsamsung;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class AppLauncherTest {
    private Protocol.TVApp app(String id){return new Protocol.TVApp(id,"YouTube",2);}
    private AppLauncher.Reply reply(int code,String body){return new AppLauncher.Reply(code,body);}
    @Test public void bringsBackgroundYouTubeToForegroundOverRest()throws Exception{
        List<String> calls=new ArrayList<>();int[] gets={0};
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("111299001912")),(method,id)->{
            calls.add(method+" "+id);
            if(method.equals("POST"))return reply(200,"true");
            return reply(200,++gets[0]==1?"{\"running\":true,\"visible\":false}":"{\"running\":true,\"visible\":true}");
        },ms->{});
        assertEquals(AppLauncher.Status.VISIBLE,result.status);
        assertEquals(Arrays.asList("GET 111299001912","POST 111299001912","GET 111299001912"),calls);
    }
    @Test public void resolvesRegionalDisneyIdAfter404()throws Exception{
        List<String> calls=new ArrayList<>();
        AppLauncher.Result result=AppLauncher.launch(Arrays.asList(app("3202204027038"),app("3202009021709"),app("3201901017640")),(method,id)->{
            calls.add(method+" "+id);return id.equals("3201901017640")?reply(200,"{\"visible\":true}"):reply(404,"{}");
        },ms->{});
        assertEquals(AppLauncher.Status.VISIBLE,result.status);assertEquals("3201901017640",result.app.id);
        assertTrue(calls.contains("POST 3201901017640"));
        assertFalse(calls.contains("POST 3202204027038"));
        assertFalse(calls.contains("POST 3202009021709"));
    }
    @Test public void runningIsNotProofOfForeground()throws Exception{
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("youtube")),(m,id)->reply(200,m.equals("POST")?"true":"{\"running\":true,\"visible\":false}"),ms->{});
        assertEquals(AppLauncher.Status.ACCEPTED,result.status);
    }
    @Test public void falsePostBodyIsRejectedEvenWithHttp200()throws Exception{
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("youtube")),(m,id)->reply(200,m.equals("POST")?"false":"{\"visible\":false}"),ms->{});
        assertEquals(AppLauncher.Status.REJECTED,result.status);
    }
    @Test public void jsonErrorIsRejectedEvenWithHttp200()throws Exception{
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("youtube")),(m,id)->reply(200,m.equals("POST")?"{\"code\":403,\"message\":\"denied\"}":"{\"visible\":false}"),ms->{});
        assertEquals(AppLauncher.Status.REJECTED,result.status);
    }
    @Test public void unavailableApiDoesNotPretendAppOpened()throws Exception{
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("youtube")),(m,id)->{throw new IOException("timeout");},ms->{});
        assertEquals(AppLauncher.Status.UNAVAILABLE,result.status);
    }
    @Test public void timeoutAfterSuccessfulPostChecksStateInsteadOfRetryingPost()throws Exception{
        int[] gets={0},posts={0};
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("youtube")),(m,id)->{
            if(m.equals("POST")){posts[0]++;throw new IOException("read timeout");}
            return reply(200,++gets[0]==1?"{\"visible\":false}":"{\"visible\":true}");
        },ms->{});
        assertEquals(AppLauncher.Status.VISIBLE,result.status);assertEquals(1,posts[0]);
    }
    @Test public void discoveryOnlyListsAppsActuallyReportedByTv()throws Exception{
        List<Protocol.TVApp> found=AppLauncher.discover(Arrays.asList(Arrays.asList(app("old"),app("installed")),Collections.singletonList(app("missing"))),(m,id)->id.equals("installed")?reply(200,"{\"id\":\"installed\",\"name\":\"YouTube\"}"):reply(404,"{}"));
        assertEquals(1,found.size());assertEquals("installed",found.get(0).id);
    }
    @Test public void missingAppIsNotPosted()throws Exception{
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("missing")),(m,id)->{assertEquals("GET",m);return reply(404,"{}");},ms->{});
        assertEquals(AppLauncher.Status.NOT_FOUND,result.status);
    }
    @Test public void staleVisibleFlagDoesNotSkipBringingAppToFront()throws Exception{
        int[] posts={0};
        AppLauncher.Result result=AppLauncher.launch(Collections.singletonList(app("netflix")),(m,id)->{if(m.equals("POST")){posts[0]++;return reply(200,"true");}return reply(200,"{\"visible\":true}");},ms->{});
        assertEquals(AppLauncher.Status.VISIBLE,result.status);assertEquals(1,posts[0]);
    }
}
