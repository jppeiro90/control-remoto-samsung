package ar.local.controlsamsung;

import org.json.JSONObject;
import java.io.IOException;
import java.util.*;

/** Resolución y apertura con confirmación del estado que devuelve el TV. */
public final class AppLauncher {
    public static final class Reply {
        public final int code;
        public final String body;
        public Reply(int code, String body) { this.code=code; this.body=body == null ? "" : body; }
    }
    public interface Transport { Reply request(String method, String id) throws IOException; }
    public interface Pause { void waitMillis(long millis) throws InterruptedException; }
    public enum Status { VISIBLE, ACCEPTED, NOT_FOUND, UNAVAILABLE, REJECTED }
    public static final class Result {
        public final Status status;
        public final Protocol.TVApp app;
        public Result(Status status, Protocol.TVApp app) { this.status=status; this.app=app; }
    }
    private AppLauncher() {}
    public static boolean visible(Reply reply) {
        if(reply.code!=200)return false;
        try{return new JSONObject(reply.body).optBoolean("visible",false);}catch(Exception e){return false;}
    }
    private static boolean accepted(Reply reply) {
        if(reply.code<200||reply.code>=300||reply.body.trim().equalsIgnoreCase("false"))return false;
        try { JSONObject data=new JSONObject(reply.body); return !data.has("error") && data.optInt("code",0)<400 && data.optInt("status",0)<400; }
        catch(Exception ignored){return reply.body.trim().isEmpty()||reply.body.trim().equalsIgnoreCase("true");}
    }
    public static Result launch(List<Protocol.TVApp> candidates,Transport transport,Pause pause) throws InterruptedException {
        Set<String> tried=new HashSet<>();
        for(Protocol.TVApp app:candidates){
            if(!tried.add(app.id))continue;
            Reply state;
            try{state=transport.request("GET",app.id);}catch(IOException e){return new Result(Status.UNAVAILABLE,app);}
            if(state.code==404)continue;
            if(state.code==401||state.code==403)return new Result(Status.REJECTED,app);
            if(state.code!=200)return new Result(Status.UNAVAILABLE,app);
            Reply response;
            try{response=transport.request("POST",app.id);}catch(IOException e){
                // Un timeout del POST puede ocurrir después de abrir la app.
                try{if(visible(transport.request("GET",app.id)))return new Result(Status.VISIBLE,app);}catch(IOException ignored){}
                return new Result(Status.UNAVAILABLE,app);
            }
            if(response.code==404)continue;
            if(!accepted(response))return new Result(Status.REJECTED,app);
            for(long delay:new long[]{500,1000,2000}){
                pause.waitMillis(delay);
                try{if(visible(transport.request("GET",app.id)))return new Result(Status.VISIBLE,app);}catch(IOException ignored){}
            }
            return new Result(Status.ACCEPTED,app);
        }
        return new Result(Status.NOT_FOUND,candidates.isEmpty()?null:candidates.get(0));
    }
    public static List<Protocol.TVApp> discover(List<List<Protocol.TVApp>> catalog,Transport transport){
        List<Protocol.TVApp> found=new ArrayList<>();
        for(List<Protocol.TVApp> alternatives:catalog){
            for(Protocol.TVApp app:alternatives){
                Reply response;
                try{response=transport.request("GET",app.id);}catch(IOException e){return found;}
                if(response.code==404)continue;
                if(response.code!=200)return found;
                try{
                    JSONObject state=new JSONObject(response.body);
                    if(!state.has("id")&&!state.has("name"))continue;
                    found.add(new Protocol.TVApp(app.id,state.optString("name",app.name),app.type));break;
                }catch(Exception ignored){}
            }
        }
        return found;
    }
}
