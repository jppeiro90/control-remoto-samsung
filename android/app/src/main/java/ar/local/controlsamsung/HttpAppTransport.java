package ar.local.controlsamsung;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** HTTP local; nunca sigue redirecciones ni accede a hosts públicos. */
public final class HttpAppTransport implements AppLauncher.Transport {
    private final String host;
    private final BooleanSupplier active;
    public HttpAppTransport(String host,BooleanSupplier active){
        if(!Protocol.privateIp(host))throw new IllegalArgumentException("IP local inválida");
        this.host=host;this.active=active;
    }
    @Override public AppLauncher.Reply request(String method,String id)throws IOException{
        if(!active.getAsBoolean())throw new IOException("Cambió la conexión con el TV");
        if(!id.matches("[A-Za-z0-9._-]{1,150}")||(!method.equals("GET")&&!method.equals("POST")))throw new IOException("Solicitud inválida");
        HttpURLConnection connection=(HttpURLConnection)new URL("http://"+host+":8001/api/v2/applications/"+id).openConnection(Proxy.NO_PROXY);
        try{
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(2500);connection.setReadTimeout(method.equals("POST")?12000:8000);connection.setRequestMethod(method);
            connection.setRequestProperty("Connection","close");
            connection.setUseCaches(false);
            if(method.equals("POST")){
                connection.setDoOutput(true);connection.setFixedLengthStreamingMode(0);
                try(OutputStream out=connection.getOutputStream()){out.flush();}
            }
            int code=connection.getResponseCode();String body="";
            InputStream stream=code>=400?connection.getErrorStream():connection.getInputStream();
            if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[2048];int count;
                while((count=in.read(buffer))!=-1&&out.size()<65536)out.write(buffer,0,count);
                body=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            return new AppLauncher.Reply(code,body);
        }finally{connection.disconnect();}
    }
}
