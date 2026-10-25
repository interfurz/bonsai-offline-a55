package de.bonsai.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Extra benchmark controls without changing MainActivity or its export flow. */
final class BenchmarkControls implements Application.ActivityLifecycleCallbacks {
    private static WeakReference<Activity> foreground=new WeakReference<>(null);
    static void install(BonsaiApp app) { app.registerActivityLifecycleCallbacks(new BenchmarkControls()); }
    static boolean show(BonsaiApp app) {
        Activity activity=foreground.get();
        if(activity==null || activity.isFinishing()) return false;
        new AlertDialog.Builder(activity).setTitle("Benchmark-Modus")
            .setItems(new String[]{"5 Fragen als zusammenhängender Chat", "Bisheriger Einzelprompt-Benchmark", "Messoptionen", "Letzten Chat-Lauf anzeigen"},(dialog,which)->{
                if(which==0)app.runChatBenchmark();else if(which==1)app.runLegacyBenchmark();else if(which==2)options(activity,app);else results(app);
            }).setNegativeButton("Schließen",null).show();
        return true;
    }
    static void results(BonsaiApp app) {
        Activity activity=foreground.get();org.json.JSONObject report=app.lastBenchmark();
        if(activity==null || activity.isFinishing() || report==null || !"bonsai-chat-5-v1".equals(report.optString("benchmark")))return;
        StringBuilder summary=new StringBuilder(report.optBoolean("completed")?"5 verbundene Chat-Turns":"Unvollständiger Chat-Lauf");
        summary.append("\n").append(report.optString("backend")).append("\n").append(report.optString("timestamp_utc"));
        org.json.JSONArray turns=report.optJSONArray("turns");
        if(turns!=null)for(int i=0;i<turns.length();i++) {
            org.json.JSONObject turn=turns.optJSONObject(i);if(turn==null)continue;
            summary.append("\n\nTurn ").append(i+1).append(": ").append(turn.optString("question"))
                .append("\n").append(BenchmarkMetrics.detail(turn));
        }
        summary.append("\n\nTemperaturen, thermischer Status und Einzelrunden stehen im JSON-Export.");
        TextView text=new TextView(activity);text.setText(summary);text.setTextIsSelectable(true);text.setPadding(32,16,32,16);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(text);
        new AlertDialog.Builder(activity).setTitle("Chat-Benchmark").setView(scroll).setNegativeButton("Schließen",null)
            .setPositiveButton("JSON exportieren",(dialog,which)->activity.startActivityForResult(
                new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_TITLE,"bonsai-chat-benchmark.json").addCategory(android.content.Intent.CATEGORY_OPENABLE),
                11 /* Existing MainActivity benchmark-export request contract. */)).show();
    }
    private static CheckBox check(Activity activity,LinearLayout pane,String label,boolean value) {
        CheckBox box=new CheckBox(activity);box.setText(label);box.setChecked(value);pane.addView(box);return box;
    }
    @android.annotation.SuppressLint("SetTextI18n")
    private static void options(Activity activity,BonsaiApp app) {
        LinearLayout pane=new LinearLayout(activity);pane.setOrientation(LinearLayout.VERTICAL);pane.setPadding(32,16,32,16);
        TextView explanation=new TextView(activity);
        explanation.setText("CPU/Vulkan in den Modelleinstellungen wählen. Jede Änderung am Rechenprofil lädt das Modell neu. Größere Microbatches können mehr Arbeitsspeicher benötigen. Erst Probe-Messungen im Kernel Optimizer vergleichen.");pane.addView(explanation);
        CheckBox log=check(activity,pane,"Turn-Statistik in Logcat (BonsaiTurn)",app.prefs.getBoolean("turnDebugLog",false));
        CheckBox warm=check(activity,pane,"System- und Tool-Präfix vorwärmen",app.prefs.getBoolean("prefixWarmup",true));
        CheckBox flash=check(activity,pane,"Experiment: Flash Attention",app.prefs.getBoolean("flashAttention",false));
        CheckBox q8=check(activity,pane,"Experiment: Q8_0 für K und V (benötigt Flash Attention)",app.prefs.getBoolean("kvQ8",false));
        q8.setOnCheckedChangeListener((v,on)->{if(on)flash.setChecked(true);});
        flash.setOnCheckedChangeListener((v,on)->{if(!on)q8.setChecked(false);});
        TextView label=new TextView(activity);label.setText("Microbatch (Vulkan-Standard 1, CPU-Standard 512)");pane.addView(label);
        Integer[] values={1,2,4,8,16,32,64,128,256,512};Spinner micro=new Spinner(activity);
        micro.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));
        for(int i=0;i<values.length;i++)if(values[i]==app.microBatch())micro.setSelection(i);
        pane.addView(micro);
        new AlertDialog.Builder(activity).setTitle("Messoptionen").setView(pane).setNegativeButton("Abbrechen",null)
            .setPositiveButton("Übernehmen",(dialog,which)->{
                if(app.busy)return;
                int selected=(Integer)micro.getSelectedItem();
                boolean changed=selected!=app.microBatch() || flash.isChecked()!=app.prefs.getBoolean("flashAttention",false) || q8.isChecked()!=app.prefs.getBoolean("kvQ8",false);
                android.content.SharedPreferences.Editor editor=app.prefs.edit().putBoolean("turnDebugLog",log.isChecked()).putBoolean("prefixWarmup",warm.isChecked())
                    .putBoolean("flashAttention",flash.isChecked()).putBoolean("kvQ8",q8.isChecked()).putInt("microBatch",selected);
                if(changed)editor.remove("optimizedFor");editor.apply();
                if(changed && app.loaded)app.loadModel();
            }).show();
    }
    @Override public void onActivityResumed(Activity a) { foreground=new WeakReference<>(a); }
    @Override public void onActivityPaused(Activity a) { if(foreground.get()==a)foreground.clear(); }
    @Override public void onActivityCreated(Activity a,Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { if(foreground.get()==a)foreground.clear(); }
}
