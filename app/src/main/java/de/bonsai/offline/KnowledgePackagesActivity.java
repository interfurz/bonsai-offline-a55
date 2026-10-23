package de.bonsai.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SAF works in release builds; all large work stays off the UI thread. */
public final class KnowledgePackagesActivity extends Activity {
    private static final int PACKAGE=71,ENCODER=72;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private TextView status;private LinearLayout list;private Button importPackage,importEncoder;
    private volatile boolean busy;
    private long lastProgress;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("Offline-Wissenspakete");title.setTextSize(24);content.addView(title);
        TextView hint=new TextView(this);hint.setText("Lokale Pakete werden geprüft und im internen Speicher installiert. Vorherige Generationen bleiben erhalten. Große Importe bitte im Vordergrund geöffnet lassen.");content.addView(hint);
        importPackage=new Button(this);importPackage.setText("Wissenspaket importieren");importPackage.setOnClickListener(v->pick(PACKAGE));content.addView(importPackage);
        importEncoder=new Button(this);importEncoder.setText("EmbeddingGemma-2-Query-Modell importieren");importEncoder.setOnClickListener(v->pick(ENCODER));content.addView(importEncoder);
        status=new TextView(this);content.addView(status);
        Button cleanup=new Button(this);cleanup.setText("Unvollständige Importe entfernen");
        cleanup.setOnClickListener(v->{if(busy)return;new AlertDialog.Builder(this).setTitle("Unvollständige Importe entfernen?")
            .setMessage("Entfernt ausschließlich nicht aktivierte, unvollständige Importdateien. Installierte Pakete und frühere aktivierte Generationen bleiben erhalten.")
            .setNegativeButton("Abbrechen",null).setPositiveButton("Dateien entfernen",(dialog,which)->{
                busy=true;importPackage.setEnabled(false);importEncoder.setEnabled(false);refresh();
                worker.execute(()->{String message;try{message="Freigegeben: "+KnowledgePackages.discardIncomplete(getFilesDir())/1048576+" MiB";}catch(Exception e){message=e.getMessage();}final String text=message;
                    runOnUiThread(()->{busy=false;importPackage.setEnabled(true);importEncoder.setEnabled(true);status.setText(text);refresh();});});
            }).show();});content.addView(cleanup);
        Button cancel=new Button(this);cancel.setText("Import abbrechen");cancel.setOnClickListener(v->cancelled.set(true));content.addView(cancel);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);content.addView(list);
        Button close=new Button(this);close.setText("Schließen");close.setOnClickListener(v->{if(busy)cancelled.set(true);else finish();});content.addView(close);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);refresh();
    }
    private void pick(int request) {
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,request);
    }
    private void refresh() {
        list.removeAllViews();
        try {
            for(KnowledgePackages.Installed item:KnowledgePackages.installed(getFilesDir())) {
                Switch enabled=new Switch(this);enabled.setText(item.id()+" · "+item.manifest.getString("version")+"\n"+item.manifest.getJSONObject("source").getString("date"));enabled.setChecked(item.enabled);enabled.setEnabled(!busy);
                enabled.setOnCheckedChangeListener((v,value)->{try{KnowledgePackages.setEnabled(getFilesDir(),item.id(),value);}catch(Exception e){status.setText(e.getMessage());refresh();}});list.addView(enabled);
            }
        }catch(Exception e){status.setText("Paketstatus nicht lesbar: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null || (request!=PACKAGE && request!=ENCODER))return;
        busy=true;cancelled.set(false);importPackage.setEnabled(false);importEncoder.setEnabled(false);refresh();status.setText("Import startet …");
        android.net.Uri uri=data.getData();
        worker.execute(()-> {
            String message;
            try(InputStream input=getContentResolver().openInputStream(uri)) {
                if(input==null)throw new java.io.IOException("Datei nicht lesbar.");
                KnowledgePackages.Progress progress=text->{long now=android.os.SystemClock.elapsedRealtime();if(now-lastProgress>200){lastProgress=now;runOnUiThread(()->status.setText(text));}};
                if(request==PACKAGE){org.json.JSONObject manifest=KnowledgePackages.install(getFilesDir(),input,cancelled::get,progress);message=manifest.getString("package_id")+" geprüft und aktiviert.";}
                else {KnowledgePackages.importEncoder(getFilesDir(),input,cancelled::get,progress);message="Query-Modell geprüft und importiert.";}
            }catch(Exception e){message="Import nicht aktiviert: "+e.getMessage();}
            final String text=message;runOnUiThread(()->{busy=false;importPackage.setEnabled(true);importEncoder.setEnabled(true);status.setText(text);refresh();});
        });
    }
    @Override public void onBackPressed() {if(busy){cancelled.set(true);status.setText("Import wird abgebrochen …");}else super.onBackPressed();}
    @Override protected void onDestroy(){cancelled.set(true);worker.shutdown();super.onDestroy();}
}
