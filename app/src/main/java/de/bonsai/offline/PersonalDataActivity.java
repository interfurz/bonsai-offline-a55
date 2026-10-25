package de.bonsai.offline;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.widget.*;
import java.io.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.*;

/** Explicit local memory management and SAF backup; no model writes on this screen. */
public final class PersonalDataActivity extends Activity {
    private static final int EXPORT=81,IMPORT=82;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private BonsaiApp app;private LinearLayout list;private TextView status;private EditText query;private boolean busy;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);app=(BonsaiApp)getApplication();
        DesignTokens tokens=new DesignTokens(getResources());
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(20),dp(20),dp(20));content.setBackgroundColor(tokens.background);
        TextView title=new TextView(this);title.setText("Gedächtnis und Sicherung");title.setTextSize(24);title.setTextColor(tokens.text);title.setAccessibilityHeading(true);content.addView(title);
        TextView hint=new TextView(this);hint.setText("Sicherungen enthalten deine Chats, Archive und Erinnerungen als lesbare JSON-Datei. Modelle, Wissenspakete und Einstellungen sind nicht enthalten. Beim Import werden Chats ergänzt; vorhandene Erinnerungsschlüssel behalten ihren Wert.");hint.setTextColor(tokens.text);content.addView(hint);
        status=new TextView(this);status.setTextColor(tokens.text);status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);
        button(content,"Sicherung exportieren",()->pick(EXPORT));button(content,"Sicherung importieren",()->pick(IMPORT));
        button(content,"Erinnerung hinzufügen",()->edit(null));
        query=new EditText(this);query.setSingleLine(true);query.setHint("Erinnerungen durchsuchen");query.setTextColor(tokens.text);content.addView(query);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);content.addView(list);
        CheckBox semantic=new CheckBox(this);semantic.setText("Semantische Gedächtnissuche mit lokalem Embedding-Modell");semantic.setTextColor(tokens.text);semantic.setChecked(app.prefs.getBoolean("semanticMemory",false));semantic.setOnCheckedChangeListener((v,enabled)->app.prefs.edit().putBoolean("semanticMemory",enabled).apply());content.addView(semantic);
        button(content,"Ähnliche Erinnerungen suchen",this::searchMemory);
        Button cancelSearch=new Button(this);cancelSearch.setText("Suche abbrechen");cancelSearch.setOnClickListener(v->cancelled.set(true));content.addView(cancelSearch);
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refresh();}public void afterTextChanged(Editable e){}});
        button(content,"Schließen",this::finish);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);refresh();
    }
    private void searchMemory() {
        String text=query.getText().toString();if(text.trim().isEmpty()){query.setError("Bitte Suchtext eingeben.");return;}
        busy=true;cancelled.set(false);status.setText("Erinnerungen werden lokal durchsucht …");
        worker.execute(()->{String message;
            try {
                org.json.JSONObject result=MemorySearch.search(app.store,text,cancelled::get);org.json.JSONArray hits=result.getJSONArray("hits");
                StringBuilder out=new StringBuilder();for(int i=0;i<hits.length();i++){org.json.JSONObject hit=hits.getJSONObject(i);out.append(hit.getString("key")).append(": ").append(hit.getString("value")).append("\n\n");}
                if(hits.length()==0)out.append("Keine passenden gespeicherten Erinnerungen.\n");
                out.append(result.optString("warning"));message=out.toString();
            }catch(Exception | LinkageError e){message="Suche nicht abgeschlossen: "+e.getMessage();}
            String result=message;runOnUiThread(()->{busy=false;if(!isDestroyed())status.setText(result);});
        });
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void button(LinearLayout parent,String label,Runnable action){Button button=new Button(this);button.setText(label);button.setMinHeight(dp(48));button.setOnClickListener(v->{if(!busy && !app.busy)action.run();else status.setText("Bitte warte, bis der laufende Vorgang beendet ist.");});parent.addView(button);}
    private void refresh() {
        list.removeAllViews();
        java.util.List<ChatStore.Memory> matches=app.store.memories(query.getText().toString());
        if(matches.size()>100){TextView count=new TextView(this);count.setText("100 von "+matches.size()+" Einträgen. Grenze die Suche ein, um weitere zu finden.");count.setTextColor(new DesignTokens(getResources()).text);list.addView(count);}
        for(ChatStore.Memory memory:matches.subList(0,Math.min(100,matches.size()))) {
            TextView text=new TextView(this);text.setText(memory.key+"\n"+memory.value+"\n"+memory.source+" · "+DateFormat.getDateTimeInstance().format(new Date(memory.updated)));text.setTextColor(new DesignTokens(getResources()).text);text.setTextIsSelectable(true);text.setPadding(0,dp(16),0,0);list.addView(text);
            button(list,"Bearbeiten: "+memory.key,()->edit(memory));
            button(list,"Löschen: "+memory.key,()->new AlertDialog.Builder(this).setTitle("Erinnerung löschen?").setMessage(memory.key).setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(d,w)->{app.store.forget(memory.key);refresh();}).show());
        }
        if(list.getChildCount()==0){TextView empty=new TextView(this);empty.setText("Keine passenden Erinnerungen.");empty.setTextColor(new DesignTokens(getResources()).text);list.addView(empty);}
    }
    private void edit(ChatStore.Memory memory) {
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),0,dp(20),0);
        EditText key=new EditText(this);key.setHint("Schlüssel");key.setSingleLine(true);key.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});panel.addView(key);
        EditText value=new EditText(this);value.setHint("Inhalt");value.setMinLines(3);value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});panel.addView(value);
        if(memory!=null){key.setText(memory.key);key.setEnabled(false);value.setText(memory.value);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(memory==null?"Erinnerung hinzufügen":"Erinnerung bearbeiten").setView(panel).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{if(app.busy)throw new IllegalStateException("Bitte laufende Generierung abwarten.");
                if(memory==null && app.store.recall(key.getText().toString())!=null){key.setError("Schlüssel ist bereits vorhanden. Bitte den vorhandenen Eintrag bearbeiten.");return;}
                app.store.remember(key.getText().toString(),value.getText().toString(),"Manuell");dialog.dismiss();refresh();
            }catch(Exception e){value.setError(e.getMessage());}
        }));dialog.show();
    }
    private void pick(int request) {
        Intent intent=new Intent(request==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        if(request==EXPORT)intent.putExtra(Intent.EXTRA_TITLE,"bonsai-persoenliche-sicherung.json");
        startActivityForResult(intent,request);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null || (request!=IMPORT && request!=EXPORT))return;
        if(app.busy){status.setText("Bitte Generierung beenden und erneut versuchen.");return;}
        if(request==IMPORT)new AlertDialog.Builder(this).setTitle("Sicherung ergänzen?").setMessage("Chats werden zusätzlich importiert, auch bei wiederholtem Import. Vorhandene Erinnerungen bleiben erhalten.").setNegativeButton("Abbrechen",null).setPositiveButton("Importieren",(d,w)->transfer(request,data)).show();
        else transfer(request,data);
    }
    private void transfer(int request,Intent data) {
        if(busy || app.busy)return;busy=true;status.setText("Sicherung wird verarbeitet …");
        worker.execute(()->{String message;
            try {
                if(request==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IOException("Datei nicht verfügbar.");PersonalBackup.write(app.store,out);}message="Sicherung exportiert.";}
                else {try(InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null)throw new IOException("Datei nicht verfügbar.");message=PersonalBackup.restore(app.store,in)+" Chats ergänzt.";}}
            }catch(Exception e){message="Sicherung nicht abgeschlossen: "+e.getMessage();}
            String result=message;runOnUiThread(()->{busy=false;if(!isDestroyed()){status.setText(result);refresh();}});
        });
    }
    @Override protected void onDestroy(){cancelled.set(true);worker.shutdown();super.onDestroy();}
}
