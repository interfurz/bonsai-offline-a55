package de.bonsai.offline;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Private :sympy process: interpreter and CAS state never share the inference process. */
public final class SympyService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean running;
    private final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper(),message->{
        if(message.what==SympyMath.STOP){android.os.Process.killProcess(android.os.Process.myPid());return true;}
        if(message.what!=SympyMath.REQUEST || message.replyTo==null)return false;
        Messenger callback=message.replyTo;
        if(running){reply(callback,"{\"ok\":false,\"error\":\"SymPy-Dienst ist beschäftigt.\"}");return true;}
        running=true;
        try{Message ready=Message.obtain(null,SympyMath.READY);ready.arg1=android.os.Process.myPid();callback.send(ready);}
        catch(RemoteException disconnected){stopSelf();return true;}
        String payload=message.getData().getString("payload","");
        worker.execute(()->{
            String result;
            try {
                SympyMath.validate(new JSONObject(payload));
                if(!Python.isStarted())Python.start(new AndroidPlatform(this));
                PyObject module=Python.getInstance().getModule("bonsai_sympy");
                module.callAttr("constrain_worker");
                result=module.callAttr("run_json",payload).toString();
            }catch(Exception | LinkageError e){
                JSONObject failure=new JSONObject();
                try{failure.put("ok",false).put("error","SymPy konnte nicht rechnen: "+e.getClass().getSimpleName());}catch(Exception ignored){}
                result=failure.toString();
            }
            reply(callback,result);
        });
        return true;
    }));
    private void reply(Messenger callback,String payload) {
        try{Message response=Message.obtain(null,SympyMath.RESULT);Bundle data=new Bundle();data.putString("payload",payload);response.setData(data);callback.send(response);}
        catch(RemoteException ignored){stopSelf();}
    }
    @Override public IBinder onBind(Intent intent){return incoming.getBinder();}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
