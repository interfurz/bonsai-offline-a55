package de.bonsai.offline;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;

/** One disposable Python process per CAS call. Cancellation/timeout kills only that process. */
final class SympyMath {
    static final long TIMEOUT_MS=20000;
    static final int REQUEST=1,READY=2,RESULT=3,STOP=4;
    private static final Semaphore SLOT=new Semaphore(1,true);
    static final Set<String> OPERATIONS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "simplify","expand","factor","cancel","apart","trigsimp","differentiate","integrate",
        "solve","solveset","linsolve","nonlinsolve","nsolve","dsolve","limit","series","summation",
        "product","numeric","det","inverse","rank","eigenvalues","transpose","rref","gcd","lcm","factorint","isprime")));
    private static final Set<String> KEYS=new HashSet<>(Arrays.asList("op","expr","var","vars","order","lower","upper","point","direction","precision","domain","assumptions"));
    static void validate(JSONObject args) throws Exception {
        if(!args.has("op") || !args.has("expr"))throw new IllegalArgumentException("mathe erwartet op und expr.");
        for(Iterator<String> it=args.keys();it.hasNext();) {
            String key=it.next();Object value=args.get(key);
            if(!KEYS.contains(key) || !(value instanceof String) || ((String)value).length()>4096)
                throw new IllegalArgumentException("Ungültiges SymPy-Argument: "+key);
        }
        if(!OPERATIONS.contains(args.getString("op")))throw new IllegalArgumentException("Unbekannte SymPy-Operation.");
        if(args.getString("expr").trim().isEmpty() || args.toString().length()>8192)throw new IllegalArgumentException("Leerer oder zu großer SymPy-Aufruf.");
    }
    static JSONObject run(Context context,JSONObject args,BooleanSupplier cancelled) throws Exception {
        validate(args);
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("SymPy muss im Hintergrund ausgeführt werden.");
        long deadline=SystemClock.elapsedRealtime()+TIMEOUT_MS;
        while(!SLOT.tryAcquire(50,TimeUnit.MILLISECONDS))check(cancelled,deadline);
        try {check(cancelled,deadline);return invoke(context.getApplicationContext(),args,cancelled,deadline);}
        finally {SLOT.release();}
    }
    private static JSONObject invoke(Context context,JSONObject args,BooleanSupplier cancelled,long deadline) throws Exception {
        CompletableFuture<String> result=new CompletableFuture<>();AtomicInteger process=new AtomicInteger();
        AtomicBoolean closed=new AtomicBoolean();
        Handler main=new Handler(Looper.getMainLooper());
        Messenger callback=new Messenger(new Handler(Looper.getMainLooper(),message->{
            if(message.what==READY){process.set(message.arg1);if(closed.get())killWorker(message.arg1);}
            else if(message.what==RESULT)result.complete(message.getData().getString("payload",""));
            return true;
        }));
        Messenger[] remote={null};
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                try {
                    remote[0]=new Messenger(binder);Message request=Message.obtain(null,REQUEST);request.replyTo=callback;
                    if(closed.get()){remote[0].send(Message.obtain(null,STOP));return;}
                    Bundle data=new Bundle();data.putString("payload",args.toString());request.setData(data);remote[0].send(request);
                }catch(Exception e){result.completeExceptionally(e);}
            }
            @Override public void onServiceDisconnected(ComponentName name){result.completeExceptionally(new IllegalStateException("SymPy-Prozess beendet (Ressourcenlimit oder Prozessabbruch)."));}
            @Override public void onNullBinding(ComponentName name){result.completeExceptionally(new IllegalStateException("SymPy-Dienst nicht verfügbar."));}
            @Override public void onBindingDied(ComponentName name){onServiceDisconnected(name);}
        };
        Intent intent=new Intent(context,SympyService.class);boolean bound=false;
        try {
            bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
            if(!bound)throw new IllegalStateException("SymPy-Dienst konnte nicht gestartet werden.");
            while(true) {
                check(cancelled,deadline);
                try {
                    String payload=result.get(50,TimeUnit.MILLISECONDS);
                    check(cancelled,deadline);
                    JSONObject reply=new JSONObject(payload);
                    if(!reply.optBoolean("ok"))throw new IllegalArgumentException(reply.optString("error","SymPy-Berechnung fehlgeschlagen."));
                    return reply.getJSONObject("result");
                }catch(TimeoutException waiting){/* Poll cancellation even while native SymPy holds the GIL. */}
                catch(ExecutionException e){throw new IllegalStateException("SymPy nicht abgeschlossen: "+e.getCause().getMessage(),e.getCause());}
            }
        } finally {
            // The service is private, and READY carries its own PID, never model data.
            closed.set(true);int pid=process.get();
            if(pid>0 && pid!=android.os.Process.myPid())killWorker(pid);
            else main.post(()->{try{if(remote[0]!=null)remote[0].send(Message.obtain(null,STOP));}catch(RemoteException ignored){}});
            if(bound)context.unbindService(connection);
        }
    }
    private static void killWorker(int pid){if(pid>0 && pid!=android.os.Process.myPid())android.os.Process.killProcess(pid);}
    private static void check(BooleanSupplier cancelled,long deadline) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("SymPy abgebrochen.");
        if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("SymPy-Zeitlimit von 20 Sekunden erreicht.");
    }
    private SympyMath(){}
}
