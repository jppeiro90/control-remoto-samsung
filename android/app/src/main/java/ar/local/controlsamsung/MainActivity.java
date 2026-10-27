package ar.local.controlsamsung;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowInsets;
import android.webkit.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Interfaz empaquetada en el APK; conexión directa celular-TV. */
public class MainActivity extends Activity implements TvConnection.Listener {
    private static final String UI = "https://appassets.androidplatform.net/assets/ui/index.html";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Protocol.TVApp> installed = new ArrayList<>();
    private SharedPreferences prefs;
    private TvConnection tv;
    private WebView web;
    private boolean destroyed, loaded, pending;
    private String message = "Conectá tu televisor";
    private int appRequest;
    private AlertDialog connectionDialog;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        tv = new TvConnection(prefs, this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16,17,19));
        web = new WebView(this); web.setBackgroundColor(Color.rgb(16,17,19));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "AndroidRemote");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl(); String path = uri.getPath();
                if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost()) &&
                        path != null && path.startsWith("/assets/ui/") && !path.contains("..") && path.matches("[A-Za-z0-9/_.-]+")) {
                    try {
                        String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : path.endsWith(".jpg") ? "image/jpeg" : "text/plain";
                        return new WebResourceResponse(mime, "UTF-8", getAssets().open(path.substring(8)));
                    } catch (Exception ignored) { }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!UI.equals(url)) return;
                loaded = true; publish(); publishApps();
                if (Protocol.privateIp(prefs.getString("host", ""))) connect(prefs.getString("host", ""), prefs.getInt("port", 8002));
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1,-1)); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        web.loadUrl(UI);
    }
    public final class Bridge {
        @JavascriptInterface public void postMessage(String raw) {
            if (raw == null || raw.length() > 4096) return;
            runOnUiThread(() -> {
                if (destroyed || !loaded) return;
                try {
                    JSONObject data = new JSONObject(raw);
                    switch (data.optString("action")) {
                        case "settings": showSettings(); break;
                        case "key": if (tv.isReady()) tv.sendKey("KEY_" + data.getString("key")); else showSettings(); break;
                        case "sources": sourceOptions(); break;
                        case "shortcut": openShortcut(data.getString("name")); break;
                        case "variants": variants(data.getString("name")); break;
                        case "apps": requestApps(); break;
                        case "launch": {
                            String id = data.getString("id");
                            for (Protocol.TVApp a : allApps()) if (a.id.equals(id)) { launch(a); break; }
                            break;
                        }
                        case "addApp": customApp(); break;
                        case "wake": wakeDialog(); break;
                    }
                } catch (Exception e) { toast(e.getMessage()); }
            });
        }
    }
    private void call(String function, JSONObject data) { if (loaded && !destroyed) web.evaluateJavascript("window." + function + "(" + data + ")", null); }
    private void publish() {
        try { call("remoteState", new JSONObject().put("ready", tv.isReady()).put("pending", pending).put("message", message).put("host", prefs.getString("host", ""))); }
        catch (Exception ignored) { }
    }
    @Override public void state(boolean ready, boolean pending, String message) {
        this.pending = pending; this.message = message; publish();
        if (message.startsWith("El TV rechazó") || message.startsWith("No se pudo enviar")) toast(message);
        if (ready) { if (connectionDialog != null) connectionDialog.dismiss(); waitForApps(); }
    }
    @Override public void apps(List<Protocol.TVApp> list) { installed.clear(); installed.addAll(list); installed.sort((a,b) -> a.name.compareToIgnoreCase(b.name)); publishApps(); }
    private List<Protocol.TVApp> allApps() {
        List<Protocol.TVApp> result = new ArrayList<>(installed);
        try { JSONArray custom = new JSONArray(prefs.getString("customApps", "[]"));
            for (int i = 0; i < custom.length(); i++) { JSONObject a = custom.getJSONObject(i); result.add(new Protocol.TVApp(a.getString("id"), a.getString("name"), a.optInt("type", 2))); }
        } catch (Exception ignored) { }
        return result;
    }
    private void publishApps() {
        try { JSONArray apps = new JSONArray(); for (Protocol.TVApp a : allApps()) apps.put(new JSONObject().put("id", a.id).put("name", a.name));
            call("remoteApps", new JSONObject().put("apps", apps));
        } catch (Exception ignored) { }
    }
    private void toast(String text) { if (!destroyed) Toast.makeText(this, text == null ? "No se pudo completar la acción." : text, Toast.LENGTH_LONG).show(); }
    private boolean permission() {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, 101); return false;
        }
        return true;
    }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] result) {
        super.onRequestPermissionsResult(code,names,result); toast(result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED ? "Permiso concedido. Volvé a conectar." : "Se necesita permiso de red local para controlar el TV.");
    }
    private void connect(String host, int port) {
        if (pending || !permission()) return;
        if (!Protocol.privateIp(host) || (port != 8001 && port != 8002)) { toast("Ingresá una IP local válida."); return; }
        prefs.edit().putString("host", host).putInt("port", port).apply(); installed.clear(); publishApps(); tv.connect(host,port);
    }
    private void showSettings() {
        if (connectionDialog != null && connectionDialog.isShowing()) return;
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); int p = Math.round(24 * getResources().getDisplayMetrics().density); panel.setPadding(p,p/3,p,p/3);
        TextView tip = new TextView(this); tip.setText("Celular y TV en la misma red Wi‑Fi. Aceptá el permiso en el televisor al conectar."); panel.addView(tip);
        TextView label = new TextView(this); label.setText("Dirección IP del TV"); label.setPadding(0,24,0,0); panel.addView(label);
        EditText host = new EditText(this); host.setSingleLine(); host.setInputType(InputType.TYPE_CLASS_PHONE); host.setHint("192.168.0.50"); host.setText(prefs.getString("host", "")); panel.addView(host);
        Spinner port = new Spinner(this); port.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Conexión segura · 8002","Conexión alternativa · 8001"})); port.setSelection(prefs.getInt("port",8002)==8002?0:1); panel.addView(port);
        Button search = new Button(this); search.setText("Buscar mi televisor"); search.setAllCaps(false); panel.addView(search);
        search.setOnClickListener(v -> {
            if (!permission()) return; search.setEnabled(false); search.setText("Buscando…");
            network.execute(() -> {
                try { List<Discovery.Device> found = Discovery.find(this); runOnUiThread(() -> {
                    if (destroyed) return;
                    if (found.isEmpty()) toast("No encontré el TV. Podés ingresar la IP manualmente.");
                    else new AlertDialog.Builder(this).setTitle("Tu televisor").setItems(found.stream().map(x->x.name).toArray(String[]::new),(d,w)->host.setText(found.get(w).host)).show();
                }); } catch(Exception e) { runOnUiThread(()->toast("No se pudo buscar. Revisá el Wi‑Fi o ingresá la IP.")); }
                finally { runOnUiThread(()->{search.setEnabled(true);search.setText("Buscar mi televisor");}); }
            });
        });
        Switch rest = new Switch(this); rest.setText("Método alternativo para abrir apps"); rest.setMinimumHeight(56); rest.setChecked(prefs.getBoolean("rest",false)); rest.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("rest",value).apply()); panel.addView(rest);
        Button help = new Button(this); help.setText("Ayuda y vinculación"); help.setAllCaps(false); help.setOnClickListener(v->help()); panel.addView(help);
        connectionDialog = new AlertDialog.Builder(this).setTitle("Conectar Samsung TU7000").setView(panel).setNegativeButton("Cerrar",null).setPositiveButton(pending?"Vinculando…":"Conectar",null).create();
        connectionDialog.setOnShowListener(d -> connectionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = host.getText().toString().trim();
            if (!Protocol.privateIp(address)) { host.setError("Ingresá una IP local válida"); return; }
            connectionDialog.dismiss(); connect(address,port.getSelectedItemPosition()==0?8002:8001);
        })); connectionDialog.show();
    }
    private String[] ids(String name) {
        switch(name) {
            case "Netflix": return new String[]{"3201907018807","11101200001"};
            case "HBO Max": return new String[]{"3202301029760","3201601007230","3201706012478"};
            case "YouTube": return new String[]{"111299001912"};
            case "Prime Video": return new String[]{"3201910019365","3201512006785"};
            case "Disney+": return new String[]{"3202204027038","3202009021709","3201901017640"};
            case "Spotify": return new String[]{"3201606009684"};
            default: return new String[0];
        }
    }
    private void sourceOptions() {
        if (!tv.isReady()) { showSettings(); return; }
        String[] labels={"Abrir Fuente (pulsar y soltar)","Abrir Fuente (clic simple)","Abrir Inicio para elegir una entrada"};
        String[] keys={"SOURCE","SOURCE","HOME"};
        new AlertDialog.Builder(this).setTitle("Entradas del televisor").setItems(labels,(d,w)->{
            try { tv.sendKey("KEY_"+keys[w],w==0); }
            catch(Exception e){toast(e.getMessage());}
        }).setNegativeButton("Cerrar",null).show();
    }
    private void openShortcut(String name) {
        String[] candidates = ids(name); if(candidates.length==0) return;
        Protocol.TVApp selected = new Protocol.TVApp(candidates[0],name,2);
        for(Protocol.TVApp a:installed) { String lower=a.name.toLowerCase(Locale.ROOT); if(lower.contains(name.toLowerCase(Locale.ROOT)) || name.equals("HBO Max")&&(lower.contains("hbo")||lower.equals("max"))) {selected=a;break;} }
        String override = prefs.getString("shortcut:"+prefs.getString("host","")+":"+name,"");
        if(!override.isEmpty()) selected=new Protocol.TVApp(override,name,2); launch(selected);
    }
    private void variants(String name) {
        String[] values=ids(name); if(values.length==0)return;
        String[] labels=new String[values.length+1];labels[0]="Detección automática";for(int i=0;i<values.length;i++)labels[i+1]="Probar variante "+(i+1);
        new AlertDialog.Builder(this).setTitle("Acceso a "+name).setItems(labels,(d,w)->{
            String setting="shortcut:"+prefs.getString("host","")+":"+name;
            if(w==0){prefs.edit().remove(setting).apply();openShortcut(name);}else{prefs.edit().putString(setting,values[w-1]).apply();launch(new Protocol.TVApp(values[w-1],name,2));}
        }).show();
    }
    private void launch(Protocol.TVApp app) {
        if(!tv.isReady()){showSettings();return;}
        if(!prefs.getBoolean("rest",false)) { try{tv.launch(app);toast("Solicitud enviada para abrir "+app.name+".");}catch(Exception e){toast(e.getMessage());}return; }
        String host=prefs.getString("host",""); if(!Protocol.privateIp(host)||!app.id.matches("[A-Za-z0-9._-]{1,150}"))return;
        network.execute(()->{
            HttpURLConnection c=null;String feedback;
            try{c=(HttpURLConnection)new URL("http://"+host+":8001/api/v2/applications/"+app.id).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setRequestMethod("POST");int code=c.getResponseCode();feedback=code>=200&&code<300?"El TV aceptó el pedido para abrir "+app.name:"El TV no pudo abrir la app. Probá otra variante o abrila desde Inicio.";}
            catch(Exception e){feedback="No se pudo abrir la app. Probá desde Inicio del TV.";}finally{if(c!=null)c.disconnect();}
            final String result=feedback;runOnUiThread(()->toast(result));
        });
    }
    private void requestApps(){if(!tv.isReady()){showSettings();return;}try{tv.requestApps();waitForApps();}catch(Exception e){toast(e.getMessage());}}
    private void waitForApps(){int n=++appRequest;handler.postDelayed(()->{if(!destroyed&&n==appRequest&&installed.isEmpty()&&tv.isReady())try{call("appsNotice",new JSONObject().put("message","El TV no devolvió la lista. Usá los accesos rápidos o Inicio."));}catch(Exception ignored){}},8000);}
    private void customApp(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(32,8,32,8);EditText name=new EditText(this);name.setHint("Nombre de la app");box.addView(name);EditText id=new EditText(this);id.setHint("Identificador de la app en el TV");id.setSingleLine();box.addView(id);
        new AlertDialog.Builder(this).setTitle("Agregar acceso").setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{try{String value=id.getText().toString().trim(),label=name.getText().toString().trim();Protocol.launch(value,2);if(label.isEmpty())throw new Exception("Ingresá un nombre.");JSONArray a=new JSONArray(prefs.getString("customApps","[]"));a.put(new JSONObject().put("id",value).put("name",label));prefs.edit().putString("customApps",a.toString()).apply();publishApps();}catch(Exception e){toast(e.getMessage());}}).show();}
    private void help(){new AlertDialog.Builder(this).setTitle("Vincular tu Samsung TU7000").setMessage("En el TV: Configuración → General → Red → Estado de red → Configuración IP.\n\nConectá ambos equipos a la misma red, ingresá la IP y aceptá ‘Control Samsung Android’ en el televisor.\n\nSi una app no abre, mantené presionado su logo para probar otra variante.\n\nSi cambió el certificado o el permiso, olvidá el vínculo y conectá otra vez.").setPositiveButton("Entendido",null).setNeutralButton("Olvidar vínculo",(d,w)->{tv.disconnect();String host=prefs.getString("host","");prefs.edit().remove("token:"+host+":8002").remove("token:"+host+":8001").remove("cert:"+host).apply();state(false,false,"Vínculo olvidado. Volvé a conectar.");}).show();}
    private void wakeDialog(){EditText mac=new EditText(this);mac.setSingleLine();mac.setHint("AA:BB:CC:DD:EE:FF");mac.setText(prefs.getString("mac",""));new AlertDialog.Builder(this).setTitle("Encender por red").setMessage("Necesita la MAC del TV y ‘Encender con el móvil’ activado. Depende del soporte del TV y la red.").setView(mac).setNegativeButton("Cancelar",null).setPositiveButton("Enviar",(d,w)->{if(!permission())return;String value=mac.getText().toString().trim();if(!value.matches("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")){toast("MAC inválida.");return;}prefs.edit().putString("mac",value).apply();network.execute(()->{try(DatagramSocket socket=new DatagramSocket()){String hex=value.replace(":","").replace("-","");byte[] packet=new byte[102];Arrays.fill(packet,0,6,(byte)255);for(int i=6;i<102;i++)packet[i]=(byte)Integer.parseInt(hex.substring(((i-6)%6)*2,((i-6)%6)*2+2),16);socket.setBroadcast(true);socket.send(new DatagramPacket(packet,packet.length,InetAddress.getByName("255.255.255.255"),9));runOnUiThread(()->toast("Señal enviada. Esperá unos segundos y conectá."));}catch(Exception e){runOnUiThread(()->toast("No se pudo enviar la señal de encendido."));}});}).show();}
    @Override protected void onStop(){super.onStop();tv.disconnect();state(false,false,"Sin conexión. Tocá el indicador para conectar.");}
    @Override protected void onResume(){super.onResume();if(loaded&&!pending&&!tv.isReady()&&Protocol.privateIp(prefs.getString("host","")))connect(prefs.getString("host",""),prefs.getInt("port",8002));}
    @Override protected void onDestroy(){destroyed=true;tv.close();handler.removeCallbacksAndMessages(null);network.shutdownNow();web.removeJavascriptInterface("AndroidRemote");web.destroy();super.onDestroy();}
}
