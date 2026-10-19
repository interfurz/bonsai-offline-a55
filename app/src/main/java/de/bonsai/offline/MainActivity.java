package de.bonsai.offline;

import android.app.Activity;
import android.app.Dialog;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int IMPORT=10, EXPORT=11, EXPORT_OPTIMIZER=12;
    private static final int BG=0xff101713, PANEL=0xff1d2922, TEXT=0xfff0f2e9, MUTED=0xffa7b6aa, ACCENT=0xffe8c39a, LINE=0xff34483b;
    private BonsaiApp app;
    private FrameLayout root, drawer;
    private LinearLayout chat;
    private ScrollView scroll;
    private EditText input;
    private TextView subtitle, title, state, liveAnswer, liveDetail;
    private IconButton send, newChat;
    private Dialog performanceDialog;
    private AlertDialog errorDialog;
    private BonsaiApp.ModelApproval shownModelApproval;
    private TextView performanceStatus, performanceResult, performanceStart, performanceCompare;
    private ProgressBar performanceProgress;
    private View drawerPanel;
    private boolean drawerClosing;
    private int renderedCount=-1;
    private long renderedChat=-1;
    private boolean lastBusy, autoScroll=true;
    private String shownError="";
    private final Runnable uiListener=this::render;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app=(BonsaiApp)getApplication();
        if(android.os.Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        root=new FrameLayout(this); root.setBackgroundColor(BG);
        LinearLayout page=column(); root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        page.setOnApplyWindowInsetsListener((v,insets) -> {
            if(android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout header=row(); header.setPadding(dp(12),dp(8),dp(12),dp(8));
        header.addView(icon("menu","Chatverlauf öffnen",v -> showDrawer()));
        LinearLayout heading=column(); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(10),0,0,0);
        title=text("bonsai",25,TEXT); title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); title.setLetterSpacing(-.04f);
        subtitle=text("DEIN RAUM. DEIN GERÄT.",9,MUTED); subtitle.setLetterSpacing(.12f); subtitle.setMaxLines(1); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(title); heading.addView(subtitle);
        heading.setContentDescription("Modell und Einstellungen"); heading.setOnClickListener(v -> settings());
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView local=text("● OFFLINE",10,ACCENT); local.setLetterSpacing(.08f); local.setPadding(dp(10),dp(9),dp(10),dp(9)); local.setBackground(outline(PANEL,30)); header.addView(local);
        newChat=icon("edit","Neuen Chat starten",v -> { app.newChat(); input.setText(""); }); header.addView(newChat);
        page.addView(header);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setPadding(dp(24),dp(8),dp(24),dp(20));
        chat=column(); scroll.addView(chat,new ScrollView.LayoutParams(-1,-1));
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View content=scroll.getChildAt(0);
            autoScroll=content==null || content.getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(100);
        });
        LinearLayout dock=row(); dock.setPadding(dp(20),0,dp(20),dp(10));
        TextView chatTab=navItem("◌  Chat"), performanceTab=navItem("↗  Leistung"), modelTab=navItem("◇  Modell");
        chatTab.setTextColor(ACCENT); chatTab.setBackground(outline(PANEL,20));
        chatTab.setOnClickListener(v->scroll.smoothScrollTo(0,chat.getHeight()));
        performanceTab.setOnClickListener(v->showPerformance()); modelTab.setOnClickListener(v->settings());
        for(TextView tab:new TextView[]{chatTab,performanceTab,modelTab}) dock.addView(tab,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(dock);
        state=text("",12,MUTED); state.setPadding(dp(24),dp(4),dp(24),dp(8)); state.setGravity(Gravity.CENTER);
        state.setOnClickListener(v -> {
            if(!app.error.isEmpty()) showError();
            else if(!app.backendNotice.isEmpty())new AlertDialog.Builder(this).setTitle("Rechenprüfung").setMessage(app.backendNotice).setPositiveButton("OK",null).show();
            else if(app.optimizing) showPerformance();
            else if(!app.benchmarkProgress.isEmpty()) showBenchmark();
            else settings();
        }); page.addView(state);
        LinearLayout composer=row(); composer.setGravity(Gravity.BOTTOM); composer.setBackground(outline(PANEL,26));
        composer.setPadding(dp(5),dp(5),dp(5),dp(5));
        composer.addView(icon("plus","Modell importieren oder Werkzeuge öffnen",v -> composerMenu(v)));
        input=new EditText(this); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setHint("Was beschäftigt dich?"); input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(4),dp(12),dp(4),dp(12)); input.setMaxLines(6); input.setMinLines(1);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setContentDescription("Nachricht eingeben");
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=icon("send","Nachricht senden",v -> {
            if(app.busy) { app.cancel(); return; }
            String value=input.getText().toString().trim();
            if(!value.isEmpty() && app.loaded) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if(app.send(value)) { input.setText(""); autoScroll=true; }
            }
            else if(!app.loaded) settings();
        }); composer.addView(send);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { updateSend(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams composerParams=new LinearLayout.LayoutParams(-1,-2); composerParams.setMargins(dp(12),0,dp(12),0);
        page.addView(composer,composerParams);
        TextView note=text("Privat gedacht. Lokal verarbeitet.",10,MUTED); note.setGravity(Gravity.CENTER); note.setPadding(0,dp(8),0,dp(10)); page.addView(note);
        setContentView(root);
        if(saved!=null) input.setText(saved.getString("draft",""));
        app.listener=uiListener; render();
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("draft",input.getText().toString()); }
    @Override protected void onDestroy() {
        if(errorDialog!=null) errorDialog.dismiss();
        if(performanceDialog!=null) performanceDialog.dismiss();
        if(app.listener==uiListener) app.listener=null;
        super.onDestroy();
    }
    @Override protected void onStart() {
        super.onStart();
        app.listener=uiListener;
        render();
    }
    @Override protected void onStop() {
        if(app.listener==uiListener) app.listener=null;
        super.onStop();
    }
    @Override public void onBackPressed() { if(drawer!=null) closeDrawer(); else super.onBackPressed(); }

    private void render() {
        if(app.busy && android.os.Build.VERSION.SDK_INT>=33 && !app.prefs.getBoolean("notificationsAsked",false)) {
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();
            if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},20);
        }
        if(app.busy)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        subtitle.setText(app.loaded ? app.backend+(app.optimized()?" · OPTIMIERT":" · LOKAL") : app.busy ? "WIRD VORBEREITET" : "DEIN RAUM. DEIN GERÄT.");
        updatePerformance();
        state.setText(app.busy ? app.status : app.error.isEmpty() ? app.backendNotice.isEmpty()?"":"Sicheres CPU-Profil · Details" : "Hinweis anzeigen");
        state.setVisibility(app.busy || !app.error.isEmpty() || !app.backendNotice.isEmpty() ? View.VISIBLE : View.GONE);
        newChat.setEnabled(!app.busy); newChat.setAlpha(app.busy?.35f:1);
        updateSend();
        if(renderedCount!=app.messages.size() || renderedChat!=app.currentChat || lastBusy!=app.busy) {
            renderedCount=app.messages.size(); renderedChat=app.currentChat; lastBusy=app.busy;
            chat.removeAllViews(); liveAnswer=null; liveDetail=null;
            if(ValueAnimator.areAnimatorsEnabled()) { chat.setAlpha(.4f); chat.setTranslationY(dp(8)); chat.animate().alpha(1).translationY(0).setDuration(220).start(); }
            if(app.messages.isEmpty()) emptyState();
            else {
                chat.setGravity(Gravity.TOP);
                for(ChatStore.Message m:app.messages) addMessage(m);
            }
        } else if(liveAnswer!=null && !app.messages.isEmpty()) {
            ChatStore.Message m=app.messages.get(app.messages.size()-1);
            liveAnswer.setText(m.text.isEmpty() ? "●" : m.text);
            if(liveDetail!=null) liveDetail.setText(m.detail);
        }
        if(autoScroll && !app.messages.isEmpty()) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        if(!app.error.isEmpty() && !app.error.equals(shownError)) {
            shownError=app.error;
            showError();
        }
        if(app.error.isEmpty()) shownError="";
        if(app.modelCheckFinished && !app.busy) { app.modelCheckFinished=false;showModelCheck(); }
    }
    private void showError() {
        BonsaiApp.ModelApproval request=app.modelApproval;
        if(errorDialog!=null && errorDialog.isShowing()) {
            if(shownModelApproval==request) return;
            errorDialog.dismiss();
        }
        shownModelApproval=request;
        AlertDialog.Builder builder=new AlertDialog.Builder(this);
        if(request!=null) {
            builder.setTitle("Abweichende Prüfsumme").setMessage(request.mismatch.getMessage())
                .setPositiveButton("Trotzdem fortfahren",(d,w)->app.acceptModel(request))
                .setNegativeButton("Abbrechen",(d,w)->app.declineModel(request))
                .setOnCancelListener(d->app.declineModel(request));
        } else builder.setTitle("Bonsai").setMessage(app.error).setPositiveButton("OK",null);
        errorDialog=builder.create();
        errorDialog.setOnDismissListener(d->{if(errorDialog==d) errorDialog=null;});
        errorDialog.show();
    }
    private void updateSend() {
        if(send==null || input==null) return;
        send.kind=app.busy ? "stop" : "send";
        send.setContentDescription(app.busy ? "Vorgang abbrechen" : "Nachricht senden");
        send.setAlpha(app.busy || input.getText().length()>0 ? 1:.45f); send.invalidate();
        input.setEnabled(!app.busy);
    }
    private void emptyState() {
        chat.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hero=column();
        OrbitView orbit=new OrbitView(this); hero.addView(orbit,new LinearLayout.LayoutParams(dp(116),dp(116)));
        TextView eyebrow=text("INTELLIGENZ, DIE BEI DIR BLEIBT",10,ACCENT); eyebrow.setLetterSpacing(.12f); eyebrow.setPadding(0,dp(16),0,dp(16)); hero.addView(eyebrow);
        TextView greeting=text("Raum für deine\nGedanken.",38,TEXT); greeting.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); greeting.setLetterSpacing(-.045f); greeting.setLineSpacing(dp(1),1); hero.addView(greeting);
        TextView caption=text("Fragen, Ideen und neue Perspektiven.\nGanz ohne Verbindung nach draußen.",14,MUTED); caption.setLineSpacing(dp(4),1); caption.setPadding(0,dp(16),0,dp(22)); hero.addView(caption);
        if(!app.loaded) {
            TextView start=pill(app.modelFile().isFile()?"Modell aufwecken  ↗":"Dein Modell verbinden  ↗"); start.setTextColor(BG); start.setBackground(round(ACCENT,18));
            start.setOnClickListener(v->{ if(!app.busy) { if(app.modelFile().isFile())app.loadModel();else pickModel(); } }); hero.addView(start);
        } else {
            LinearLayout ideas=row();
            TextView explain=suggestion("01 / VERSTEHEN", "Mach es verständlich", "Erkläre mir ein schwieriges Thema einfach: ");
            TextView create=suggestion("02 / WEITERDENKEN", "Eine Idee entwickeln", "Hilf mir, eine Idee weiterzuentwickeln: ");
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=dp(8);ideas.addView(explain,left);ideas.addView(create,new LinearLayout.LayoutParams(0,-2,1));hero.addView(ideas);
        }
        chat.addView(hero,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView suggestion(String label,String title,String prompt) {
        TextView t=text(label+"\n\n"+title+"  ↗",12,TEXT);t.setLineSpacing(dp(3),1);t.setPadding(dp(14),dp(18),dp(14),dp(18));t.setBackground(outline(PANEL,18));
        t.setOnClickListener(v->{input.setText(prompt);input.setSelection(input.length());input.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});return t;
    }
    private void addMessage(ChatStore.Message m) {
        boolean user=m.role.equals("user");
        LinearLayout box=column(); box.setPadding(user?dp(14):0,user?dp(10):dp(14),user?dp(14):0,user?dp(10):dp(8));
        if(user) box.setBackground(outline(PANEL,22));
        else { TextView author=text("✳  BONSAI",10,ACCENT); author.setLetterSpacing(.13f); author.setPadding(0,0,0,dp(12)); box.addView(author); }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(user?-2:-1,-2);
        p.gravity=user?Gravity.END:Gravity.START; p.topMargin=dp(16); p.bottomMargin=dp(10);
        if(user) p.leftMargin=dp(38);
        TextView body=text(m.text.isEmpty()?"●":m.text,16,TEXT);
        body.setTextIsSelectable(true); body.setLineSpacing(dp(5),1); box.addView(body);
        if(!user) {
            TextView detail=text(m.detail,10,MUTED); detail.setPadding(0,dp(10),0,0); box.addView(detail);
            if(!m.text.isEmpty()) {
                TextView copy=text("Kopieren",11,MUTED);copy.setMinHeight(dp(48));copy.setGravity(Gravity.CENTER_VERTICAL);
                copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bonsai",m.text));Toast.makeText(this,"Antwort kopiert",Toast.LENGTH_SHORT).show();});box.addView(copy);
            }
            if(m==app.messages.get(app.messages.size()-1)) { liveAnswer=body; liveDetail=detail; }
        }
        chat.addView(box,p);
    }

    private void composerMenu(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("GGUF-Modell importieren").setEnabled(!app.busy).setOnMenuItemClickListener(i->{pickModel();return true;});
        menu.getMenu().add("Modell und Einstellungen").setOnMenuItemClickListener(i->{settings();return true;});
        menu.getMenu().add("Modelltest ohne Chatverlauf").setOnMenuItemClickListener(i->{showModelCheck();return true;});
        menu.getMenu().add("Kernel Optimizer").setOnMenuItemClickListener(i->{showPerformance();return true;});
        menu.getMenu().add("Benchmark").setOnMenuItemClickListener(i->{showBenchmark();return true;});
        menu.show();
    }
    private void pickModel() {
        if(app.busy) return;
        new AlertDialog.Builder(this).setTitle("GGUF-Modell importieren")
            .setMessage("Wähle eine lokale GGUF-Datei. Die Inferenz-Engine muss Architektur und Quantisierung des Modells unterstützen; außerdem muss genügend RAM frei sein.\n\nDie App legt eine private Kopie an und benötigt dafür zusätzlichen Speicher in Größe der Datei. Ein bestehendes Modell wird erst nach vollständigem Import und gegebenenfalls deiner Bestätigung ersetzt.")
            .setNegativeButton("Abbrechen",null).setPositiveButton("Datei auswählen",(d,w)-> {
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent,IMPORT);
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(request==IMPORT) app.importModel(uri);
        if(request==EXPORT || request==EXPORT_OPTIMIZER) {
            JSONObject report=request==EXPORT_OPTIMIZER?app.readReport("optimizer.json"):app.lastBenchmark();
            if(report==null) return;
            app.worker.execute(() -> {
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if(out==null) throw new IllegalStateException("Datei nicht beschreibbar.");
                    out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
                    app.main.post(()->Toast.makeText(this,"Benchmark gespeichert",Toast.LENGTH_SHORT).show());
                } catch(Exception e) { app.main.post(()->Toast.makeText(this,"Export fehlgeschlagen: "+e.getMessage(),Toast.LENGTH_LONG).show()); }
            });
        }
    }
    private void showDrawer() {
        if(drawer!=null) return;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        drawer=new FrameLayout(this); drawer.setBackgroundColor(0x99000000); drawer.setOnClickListener(v->closeDrawer());
        LinearLayout panel=column(); panel.setBackgroundColor(BG); panel.setPadding(dp(16),dp(40),dp(16),dp(24)); panel.setOnClickListener(v->{});
        FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(Math.min(dp(315),getResources().getDisplayMetrics().widthPixels-dp(40)),-1);
        drawer.addView(panel,bounds);
        TextView brand=text("Bonsai",24,TEXT); brand.setTypeface(null,Typeface.BOLD); brand.setPadding(dp(10),0,0,dp(24)); panel.addView(brand);
        TextView fresh=menuItem("＋   Neuer Chat"); fresh.setEnabled(!app.busy); fresh.setOnClickListener(v->{closeDrawer();app.newChat();input.setText("");}); panel.addView(fresh);
        TextView label=text("Deine Chats",12,MUTED); label.setPadding(dp(12),dp(24),0,dp(12)); panel.addView(label);
        ScrollView history=new ScrollView(this); LinearLayout entries=column(); history.addView(entries);
        for(ChatStore.Chat c:app.store.chats()) {
            TextView item=menuItem(c.title); item.setMaxLines(2); item.setEnabled(!app.busy);
            if(c.id==app.currentChat) item.setBackground(round(PANEL,12));
            item.setOnClickListener(v->{closeDrawer();app.selectChat(c.id);input.setText("");}); entries.addView(item);
        }
        panel.addView(history,new LinearLayout.LayoutParams(-1,0,1));
        TextView optimize=menuItem("↗   Kernel Optimizer");optimize.setOnClickListener(v->{closeDrawer();showPerformance();});panel.addView(optimize);
        TextView bench=menuItem("Benchmark"); bench.setOnClickListener(v->{closeDrawer();showBenchmark();}); panel.addView(bench);
        TextView prefs=menuItem("Modell und Einstellungen"); prefs.setOnClickListener(v->{closeDrawer();settings();}); panel.addView(prefs);
        TextView privacy=text("Alle Chats bleiben auf diesem Gerät.",11,MUTED); privacy.setPadding(dp(12),dp(14),0,0); panel.addView(privacy);
        root.addView(drawer,new FrameLayout.LayoutParams(-1,-1));
        drawerPanel=panel; drawerClosing=false;
        if(ValueAnimator.areAnimatorsEnabled()) { panel.setTranslationX(-bounds.width); panel.animate().translationX(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start(); }

    }
    private void closeDrawer() {
        if(drawer==null || drawerClosing)return;
        FrameLayout closing=drawer; drawerClosing=true;
        Runnable done=()->{root.removeView(closing);if(drawer==closing)drawer=null;drawerClosing=false;};
        if(ValueAnimator.areAnimatorsEnabled()) drawerPanel.animate().translationX(-drawerPanel.getWidth()).setDuration(200).withEndAction(done).start();else done.run();
    }

    private void settings() {
        LinearLayout content=column(); content.setPadding(dp(24),dp(8),dp(24),dp(16));
        TextView model=text(app.modelFile().isFile()?app.modelName():"Kein Modell importiert",15,TEXT); model.setTextIsSelectable(true); content.addView(model);
        content.addView(settingLabel((app.loaded?"Geladen · "+app.backend:"Nicht geladen")+"\nProzess-RAM: "+ram(BonsaiApp.rssKb())+" · Native Heap: "+ram(Debug.getNativeHeapAllocatedSize()/1024)));
        TextView choose=menuItem("GGUF-Datei auswählen …"); choose.setEnabled(!app.busy); content.addView(choose);
        TextView download=menuItem("Beispielmodell: Bonsai 2 4B herunterladen ↗");
        download.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ModelIntegrity.downloadUrl())))); content.addView(download);
        content.addView(settingLabel("Du kannst auch andere GGUF-Modelle über die Dateiauswahl importieren. Unterstützte Architektur, Quantisierung und verfügbarer RAM bestimmen, ob ein Modell geladen werden kann."));
        TextView ctx=settingLabel("Kontext"); content.addView(ctx);
        Spinner context=spinner(new String[]{"2.048 Tokens","4.096 Tokens","8.192 Tokens"},app.context()==2048?0:app.context()==8192?2:1); content.addView(context);
        content.addView(settingLabel("CPU-Threads"));
        Spinner threads=spinner(new String[]{"2","4","6","8"},app.threads()/2-1); content.addView(threads);
        content.addView(settingLabel("Maximale Antwortlänge"));
        Spinner length=spinner(new String[]{"128 Tokens","256 Tokens","512 Tokens","1.024 Tokens","2.048 Tokens"},app.maxTokens()==128?0:app.maxTokens()==256?1:app.maxTokens()==1024?3:app.maxTokens()==2048?4:2); content.addView(length);
        content.addView(settingLabel("Inferenz"));
        Spinner engine=spinner(BuildConfig.VULKAN_BUILD?new String[]{"CPU","Vulkan · experimentell"}:new String[]{"CPU"},app.gpu()?1:0); content.addView(engine);
        CheckBox compatible=new CheckBox(this); compatible.setText("CPU-Kompatibilitätsmodus");
        compatible.setTextColor(TEXT); compatible.setChecked(app.prefs.getBoolean("cpuCompatibility",false)); compatible.setEnabled(!app.busy); content.addView(compatible);
        content.addView(settingLabel("Bei Zeichensalat: CPU und Kompatibilitätsmodus wählen, übernehmen und einen neuen Chat starten. Der Modus nutzt skalare Bonsai-Quantisierungskernel und kann langsamer sein."));
        content.addView(settingLabel("NPU: Für Bonsai-GGUF auf dem Exynos 1480 derzeit nicht verfügbar."));
        for(Spinner s:new Spinner[]{context,threads,length,engine}) s.setEnabled(!app.busy);
        TextView nativeInfo=settingLabel("Bonsai "+BuildConfig.VERSION_NAME+" · Runtime prism-b10777-e3de715\n"+(BuildConfig.VULKAN_BUILD?"CPU/Vulkan-Wechsel lädt das Modell neu.\nKernel Optimizer misst das schnellste geprüfte Profil auf deinem Gerät.":"Vulkan ist in diesem Build nicht enthalten.")+"\nThinking ist für kurze Antworten deaktiviert."); content.addView(nativeInfo);
        TextView load=menuItem(app.loaded?"Modell entladen · RAM freigeben":"Modell laden"); load.setEnabled(!app.busy&&app.modelFile().isFile()); content.addView(load);
        TextView delete=menuItem("Aktuellen Chat löschen"); delete.setEnabled(!app.busy&&app.currentChat!=0); content.addView(delete);
        TextView check=menuItem("Modelltest ohne Chatverlauf  ↗"); content.addView(check);
        TextView licenses=menuItem("Open-Source-Lizenzen"); content.addView(licenses);
        ScrollView scroll=new ScrollView(this); scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Modell und Einstellungen").setView(scroll)
            .setNegativeButton("Schließen",null).setPositiveButton("Übernehmen",(d,w)-> {
                if(app.busy) return;
                int c=new int[]{2048,4096,8192}[context.getSelectedItemPosition()], t=new int[]{2,4,6,8}[threads.getSelectedItemPosition()], l=new int[]{128,256,512,1024,2048}[length.getSelectedItemPosition()];
                boolean gpu=engine.getSelectedItemPosition()==1;
                boolean changed=c!=app.context()||t!=app.threads()||gpu!=app.gpu()||compatible.isChecked()!=app.prefs.getBoolean("cpuCompatibility",false);
                boolean reload=app.modelFile().isFile()&&changed;
                if(changed)app.prefs.edit().remove("optimizedFor").remove("batchThreads").remove("microBatch").apply();
                app.prefs.edit().putInt("context",c).putInt("threads",t).putInt("maxTokens",l).putBoolean("gpu",gpu).putBoolean("cpuCompatibility",compatible.isChecked()).apply();
                if(compatible.isChecked() && !gpu) app.prefs.edit().putInt("microBatch",1).apply();
                if(reload) app.loadModel();
            }).create();
        check.setOnClickListener(v->{dialog.dismiss();showModelCheck();});
        choose.setOnClickListener(v->{dialog.dismiss();pickModel();});
        load.setOnClickListener(v->{dialog.dismiss();if(app.loaded) app.unloadModel();else app.loadModel();});
        delete.setOnClickListener(v->{dialog.dismiss();new AlertDialog.Builder(this).setTitle("Chat löschen?").setMessage("Dieser Chat wird dauerhaft von diesem Gerät gelöscht.").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(d,w)->app.deleteCurrentChat()).show();});
        licenses.setOnClickListener(v->{
            dialog.dismiss();
            try(InputStream stream=getAssets().open("third_party_notices.txt")) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
                while((n=stream.read(buffer))!=-1) bytes.write(buffer,0,n);
                TextView notice=text(new String(bytes.toByteArray(),StandardCharsets.UTF_8),12,TEXT);
                notice.setTextIsSelectable(true); notice.setPadding(dp(20),dp(12),dp(20),dp(12));
                ScrollView pane=new ScrollView(this); pane.addView(notice);
                new AlertDialog.Builder(this).setTitle("Open-Source-Lizenzen").setView(pane).setPositiveButton("Schließen",null).show();
            } catch(Exception e) { Toast.makeText(this,"Lizenzen konnten nicht geöffnet werden.",Toast.LENGTH_SHORT).show(); }
        });
        dialog.show();
    }
    private void showPerformance() {
        if(performanceDialog!=null && performanceDialog.isShowing())return;
        if(app.optimizerReport==null)app.optimizerReport=app.readReport("optimizer.json");
        performanceDialog=new Dialog(this);performanceDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=column();panel.setPadding(dp(24),dp(12),dp(24),dp(24));panel.setBackground(outline(BG,28));
        TextView handle=text("━━━━",16,LINE);handle.setGravity(Gravity.CENTER);panel.addView(handle);
        LinearLayout heading=row();TextView title=text("Mehr aus deinem\nModell holen.",29,TEXT);title.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));heading.addView(icon("close","Leistungsbereich schließen",v->performanceDialog.dismiss()));panel.addView(heading);
        TextView badge=text("KERNEL OPTIMIZER / ON DEVICE",10,ACCENT);badge.setLetterSpacing(.13f);badge.setPadding(0,dp(16),0,dp(16));panel.addView(badge);
        ScrollView details=new ScrollView(this);LinearLayout content=column();details.addView(content);panel.addView(details,new LinearLayout.LayoutParams(-1,0,1));
        performanceStatus=text("",15,TEXT);performanceStatus.setPadding(0,0,0,dp(12));content.addView(performanceStatus);
        performanceProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);performanceProgress.setMax(1000);performanceProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));content.addView(performanceProgress,new LinearLayout.LayoutParams(-1,dp(6)));
        performanceResult=text("",13,MUTED);performanceResult.setTextIsSelectable(true);performanceResult.setLineSpacing(dp(6),1);performanceResult.setPadding(0,dp(20),0,dp(18));content.addView(performanceResult);
        TextView explanation=text("Findet das schnellste geprüfte Profil für vorhandene Compute-Kernel. Vergleicht Threads und Batchgrößen mit identischer Arbeit; prüft Rechenergebnisse und speichert lokal. Modellpräzision und Kontext bleiben gleich.\n\nOptimierung ist bei jedem Temperaturstatus möglich. Temperatur und Drosselung beeinflussen die Messwerte; Androids Geräteschutz bleibt aktiv. Die Optimierung läuft auch im Hintergrund weiter. Eine Messung kann mehrere Minuten dauern.",13,MUTED);explanation.setLineSpacing(dp(4),1);content.addView(explanation);
        TextView benchmark=menuItem("Benchmark & Messbericht  ↗");benchmark.setOnClickListener(v->{performanceDialog.dismiss();showBenchmark();});content.addView(benchmark);
        TextView export=menuItem("Optimizer-Protokoll exportieren  ↗");export.setOnClickListener(v->{if(app.readReport("optimizer.json")!=null&&!app.busy)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"bonsai-optimizer.json").addCategory(Intent.CATEGORY_OPENABLE),EXPORT_OPTIMIZER);else Toast.makeText(this,"Noch kein abgeschlossenes Protokoll",Toast.LENGTH_SHORT).show();});content.addView(export);
        performanceStart=pill("");performanceStart.setTextColor(BG);performanceStart.setBackground(round(ACCENT,18));performanceStart.setOnClickListener(v->{if(app.optimizing)app.cancel();else if(!app.busy)app.optimize(false);});panel.addView(performanceStart);
        performanceCompare=menuItem("CPU & Vulkan vergleichen  ↗");performanceCompare.setGravity(Gravity.CENTER);performanceCompare.setOnClickListener(v->{if(!app.busy)app.optimize(true);});panel.addView(performanceCompare);
        performanceDialog.setContentView(panel);Window w=performanceDialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*.88f));w.setGravity(Gravity.BOTTOM);w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.65f);}
        performanceDialog.show();if(w!=null)w.setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*.88f));updatePerformance();
        if(ValueAnimator.areAnimatorsEnabled()){panel.setTranslationY(dp(60));panel.setAlpha(0);panel.animate().translationY(0).alpha(1).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    }
    private void updatePerformance() {
        if(performanceDialog==null || !performanceDialog.isShowing())return;
        performanceStatus.setText(app.optimizing?app.optimizingProgress:app.loaded?"● "+app.backend+" · bereit":"Modell zuerst laden");
        performanceProgress.setVisibility(app.optimizing?View.VISIBLE:View.GONE);performanceProgress.setProgress((int)(app.optimizingFraction*1000));
        JSONObject r=app.optimizerReport;
        String result=app.optimized()?"Dein gemessenes Profil ist aktiv.":"Manuelles Profil · noch nicht für diese Konfiguration optimiert.";
        if(r!=null)result+="\n\n"+r.optString("winner_label")+String.format(Locale.GERMAN,"\nKürzere Laufzeit im Testworkload: %.1f %%\n",r.optDouble("estimated_gain_percent"))+"Gilt für den gemessenen Prompt und die aktuelle Temperatur. Andere Chatlängen können sich anders verhalten.";
        result+="\n\n"+app.threads()+" Decode-Threads · "+app.batchThreads()+" Prefill-Threads\nMicrobatch "+app.microBatch()+" · Kontext "+app.context();
        performanceResult.setText(result);performanceStart.setText(app.optimizing?"Optimierung abbrechen":"Dieses Backend optimieren  ↗");performanceStart.setEnabled(app.optimizing||(!app.busy&&app.loaded));performanceStart.setAlpha(performanceStart.isEnabled()?1:.4f);
        performanceCompare.setEnabled(!app.busy&&app.loaded&&BuildConfig.VULKAN_BUILD);performanceCompare.setAlpha(performanceCompare.isEnabled()?1:.4f);
    }

    private void showModelCheck() {
        JSONObject r=app.readReport("modelcheck.json");
        String info="Prüft das geladene Modell mit einer Rechenfrage und einer kurzen deutschen Erklärung. Der Test verwendet keinen bisherigen Chatverlauf und verändert keine gespeicherten Chats.\n\nWenn dieser Test lesbar antwortet, ein alter Chat aber nicht, starte einen neuen Chat. Fehlerhafte alte Antworten können die Fortsetzung beeinflussen.";
        if(r!=null) {
            info="Letzter Test · "+r.optString("backend")+"\nRechenfrage exakt richtig: "+(r.optBoolean("arithmetic_exact")?"ja":"nein")+"\n\n";
            org.json.JSONArray answers=r.optJSONArray("answers");
            if(answers!=null)for(int i=0;i<answers.length();i++){JSONObject answer=answers.optJSONObject(i);if(answer!=null)info+=answer.optString("question")+"\n\n"+answer.optString("answer")+"\n\n";}
            info+="Dieser Test ist eine Diagnose und keine vollständige Qualitätsbewertung.";
        }
        TextView body=text(info,14,TEXT);body.setTextIsSelectable(true);body.setPadding(dp(24),dp(12),dp(24),dp(16));ScrollView pane=new ScrollView(this);pane.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Modell prüfen").setView(pane).setNegativeButton("Schließen",null)
            .setNeutralButton("Protokoll kopieren",(d,w)->{if(r!=null){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bonsai Modelltest",r.toString()));Toast.makeText(this,"Diagnose kopiert",Toast.LENGTH_SHORT).show();}})
            .setPositiveButton("Test starten",(d,w)->app.runModelCheck()).create();
        dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(app.loaded&&!app.busy);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(r!=null);});dialog.show();
    }

    private void showBenchmark() {
        JSONObject r=app.lastBenchmark();
        String description="Ein Aufwärmdurchlauf und drei Messläufe mit festem Prompt. Maximal 128 Ausgabetokens je Messlauf. Für vergleichbare Werte das Handy abkühlen lassen und andere Apps schließen.\n\nDer Benchmark erzeugt keine Chatnachrichten.";
        if(!app.benchmarkProgress.isEmpty()) description="Benchmark läuft: "+app.benchmarkProgress+"\n\n"+description;
        if(r!=null) {
            String summary=String.format(Locale.GERMAN,"%s\n\n%.1f Tokens/s (Median)\n%.2f s bis zum ersten Token (Median)\n%.2f s Prompt-Verarbeitung (Median)\n%s Prozess-RAM (gemessener Spitzenwert)\n\nModell: %s\nBackend: %s\nKontext: %d · Threads: %d\n%s\n\nRAM wird alle 250 ms erfasst. GPU-Speicher ist darin möglicherweise nicht vollständig enthalten.",
                r.optBoolean("completed")?"Letztes Ergebnis":"Unvollständige Messung",r.optDouble("median_tokens_per_second",0),r.optDouble("median_first_token_ms",0)/1000,r.optDouble("median_prefill_ms",0)/1000,
                ram(r.optLong("rss_peak_kb",-1)),r.optString("model"),r.optString("backend"),r.optInt("context_tokens"),r.optInt("threads"),r.optString("timestamp_utc"));
            description=summary+"\n\n"+description;
        }
        TextView body=text(description,14,TEXT); body.setTextIsSelectable(true); body.setPadding(dp(24),dp(10),dp(24),dp(16));
        ScrollView pane=new ScrollView(this); pane.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Benchmark").setView(pane).setNegativeButton("Schließen",null)
            .setNeutralButton("JSON exportieren",(d,w)-> {
                if(app.lastBenchmark()!=null) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"bonsai-benchmark.json").addCategory(Intent.CATEGORY_OPENABLE),EXPORT);
            }).setPositiveButton(app.busy?"Abbrechen":"Benchmark starten",(d,w)->{if(app.busy)app.cancel();else app.runBenchmark();}).create();
        dialog.setOnShowListener(d->{ dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(r!=null&&!app.busy); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(app.busy||app.loaded); });
        dialog.show();
    }

    private String ram(long kb) { return kb<0?"nicht verfügbar":String.format(Locale.GERMAN,"%.0f MB",kb/1024.0); }
    private Spinner spinner(String[] values,int selected) {
        Spinner s=new Spinner(this); ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setSelection(selected);
        s.setMinimumHeight(dp(48)); return s;
    }
    private TextView navItem(String s) { TextView t=text(s,12,MUTED);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));t.setPadding(dp(4),dp(10),dp(4),dp(10));return t; }
    private GradientDrawable outline(int color,float radius) { GradientDrawable d=round(color,radius);d.setStroke(dp(1),LINE);return d; }
    private TextView settingLabel(String s) { TextView t=text(s,12,MUTED); t.setPadding(0,dp(14),0,dp(6)); return t; }
    private TextView menuItem(String s) { TextView t=text(s,15,TEXT); t.setGravity(Gravity.CENTER_VERTICAL); t.setMinHeight(dp(52)); t.setPadding(dp(12),dp(10),dp(12),dp(10)); t.setBackground(round(0x00212121,12)); return t; }
    private TextView pill(String s) { TextView t=text(s,14,TEXT); t.setPadding(dp(20),dp(14),dp(20),dp(14)); t.setBackground(round(PANEL,26)); t.setGravity(Gravity.CENTER); t.setMinHeight(dp(48)); return t; }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String s,float size,int color) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    private GradientDrawable round(int color,float radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private IconButton icon(String kind,String description,View.OnClickListener listener) {
        IconButton v=new IconButton(kind); v.setContentDescription(description); v.setOnClickListener(listener);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48))); v.setFocusable(true); return v;
    }
    private final class IconButton extends View {
        String kind;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF stopBounds=new RectF(-5,-5,5,5);
        final Path editPath=new Path();
        IconButton(String kind) { super(MainActivity.this); this.kind=kind; setMinimumHeight(dp(48)); setMinimumWidth(dp(48)); }
        @Override public boolean performClick() { return super.performClick(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(isEnabled() && ValueAnimator.areAnimatorsEnabled()) {
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN)animate().scaleX(.88f).scaleY(.88f).setDuration(100).start();
                else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)animate().scaleX(1).scaleY(1).setDuration(180).start();
            }
            return super.onTouchEvent(event);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save(); canvas.translate(getWidth()/2f,getHeight()/2f); canvas.scale(dp(1),dp(1));
            paint.setColor(ACCENT); paint.setStrokeWidth(1.8f); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("send") || kind.equals("stop")) {
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(0,0,18,paint); paint.setColor(BG);
                if(kind.equals("stop")) canvas.drawRoundRect(stopBounds,1,1,paint);
                else { paint.setStyle(Paint.Style.STROKE); canvas.drawLine(0,8,0,-8,paint); canvas.drawLine(0,-8,-6,-2,paint); canvas.drawLine(0,-8,6,-2,paint); }
            } else if(kind.equals("close")) {
                canvas.drawLine(-6,-6,6,6,paint);canvas.drawLine(-6,6,6,-6,paint);
            } else if(kind.equals("menu")) {
                canvas.drawLine(-9,-5,9,-5,paint); canvas.drawLine(-9,3,3,3,paint);
            } else if(kind.equals("plus")) {
                canvas.drawLine(-8,0,8,0,paint); canvas.drawLine(0,-8,0,8,paint);
            } else {
                editPath.reset(); editPath.moveTo(5,-8); editPath.lineTo(9,-4); editPath.lineTo(-3,8); editPath.lineTo(-8,9); editPath.lineTo(-7,4); editPath.close(); canvas.drawPath(editPath,paint);
                canvas.drawLine(-6,-9,-10,-9,paint); canvas.drawLine(-10,-9,-10,-3,paint);
            }
            canvas.restore();
        }
    }
}
