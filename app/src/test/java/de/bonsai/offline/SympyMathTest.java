package de.bonsai.offline;

import android.app.Application;
import android.content.*;
import android.os.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class SympyMathTest {
    private final Application app=RuntimeEnvironment.getApplication();
    private static JSONObject args() throws Exception {return new JSONObject().put("op","integrate").put("expr","sin(t)").put("var","t");}
    private void bind(Handler.Callback callback) {
        Messenger messenger=new Messenger(new Handler(Looper.getMainLooper(),callback));
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,SympyService.class),messenger.getBinder());
    }
    private static void pump(Future<?> future) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!future.isDone() && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield();}
        assertTrue("CAS client did not finish",future.isDone());
    }
    @Test public void validatesBeforeBindingAndRejectsUnsupportedKeys() throws Exception {
        for(JSONObject invalid:new JSONObject[]{new JSONObject(),args().put("op","exec"),args().put("code","print(1)"),args().put("expr",12),args().put("expr","x".repeat(4097))}) {
            try{SympyMath.validate(invalid);fail(invalid.toString());}catch(IllegalArgumentException expected){}
        }
        SympyMath.validate(args().put("lower","0").put("upper","pi"));
        assertTrue(Shadows.shadowOf(app).getBoundServiceConnections().isEmpty());
    }
    @Test public void dispatcherUsesServiceAndReturnsActualCasPayload() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        bind(message->{if(message.what==SympyMath.REQUEST) {
            try {
                JSONObject request=new JSONObject(message.getData().getString("payload"));assertEquals("integrate",request.getString("op"));assertEquals("t",request.getString("var"));calls.incrementAndGet();
                Message ready=Message.obtain(null,SympyMath.READY);ready.arg1=android.os.Process.myPid();message.replyTo.send(ready);
                Message result=Message.obtain(null,SympyMath.RESULT);Bundle data=new Bundle();data.putString("payload","{\"ok\":true,\"result\":{\"engine\":\"sympy\",\"result\":\"-cos(t)\"}}");result.setData(data);message.replyTo.send(result);
            }catch(Exception e){throw new AssertionError(e);}
        }return true;});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ChatStore store=new ChatStore(app)) {
            AtomicInteger rounds=new AtomicInteger();
            Future<ToolLoop.Result> future=executor.submit(()->ToolLoop.run(Collections.emptyList(),store,(roles,texts,rules)->{
                if(rounds.getAndIncrement()==0)return new ToolLoop.Result("<tool>{\"name\":\"mathe\",\"args\":{\"op\":\"integrate\",\"expr\":\"sin(t)\",\"var\":\"t\"}}</tool>",new JSONObject());
                String reply=new String(texts[texts.length-1],java.nio.charset.StandardCharsets.UTF_8);assertTrue(reply.contains("-cos(t)"));return new ToolLoop.Result("Ergebnis",new JSONObject());
            },()->false));
            pump(future);assertEquals("Ergebnis",future.get().text);assertEquals(1,calls.get());
            assertTrue(Shadows.shadowOf(app).getBoundServiceConnections().isEmpty());
        }finally{executor.shutdownNow();}
    }
    @Test public void cancellationUnbindsAndAllowsNextRequest() throws Exception {
        AtomicBoolean cancel=new AtomicBoolean();
        bind(message->{if(message.what==SympyMath.REQUEST)cancel.set(true);return true;});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<?> future=executor.submit(()->{try{SympyMath.run(app,args(),cancel::get);fail();}catch(CancellationException expected){}catch(Exception e){throw new AssertionError(e);}});
            pump(future);future.get();assertTrue(Shadows.shadowOf(app).getBoundServiceConnections().isEmpty());
            cancel.set(false);
            Future<?> second=executor.submit(()->{try{SympyMath.run(app,args(),cancel::get);fail();}catch(CancellationException expected){}catch(Exception e){throw new AssertionError(e);}});
            pump(second);second.get();
        }finally{executor.shutdownNow();}
    }
    @Test public void timeoutDoesNotDependOnPythonResponding() throws Exception {
        bind(message->{if(message.what==SympyMath.REQUEST)org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(21));return true;});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<?> future=executor.submit(()->{try{SympyMath.run(app,args(),()->false);fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Zeitlimit"));}catch(Exception e){throw new AssertionError(e);}});
            pump(future);future.get();assertTrue(Shadows.shadowOf(app).getBoundServiceConnections().isEmpty());
        }finally{executor.shutdownNow();}
    }
}
