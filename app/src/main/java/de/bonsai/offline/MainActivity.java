package de.bonsai.offline;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.drawable.ColorDrawable;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.HapticFeedbackConstants;
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
    private DesignTokens tokens;
    private int BG, PANEL, TEXT, MUTED, ACCENT, LINE;
    private FrameLayout screenHost;
    private LinearLayout page, chatPage, modelPage, performancePage, statusCard, composerGroup;
    private Dialog sectionDialog;
    private FrameLayout popupHost;
    private TextView popupTitle, popupState, popupAction;
    private LinearLayout popupStatusCard;
    private ProgressBar popupProgress;
    private TextView statusAction, inputError;
    private ProgressBar statusProgress;
    private int activeScreen;
    private boolean lastLoaded, lastModelPresent;
    private long busySince;
    private String pendingPrompt="";
    private final Runnable progressTick=()->{ if(!isFinishing()) render(); };
    private BonsaiApp app;
    private FrameLayout root, drawer;
    private LinearLayout chat;
    private ScrollView scroll;
    private EditText input;
    private TextView subtitle, title, state, liveAnswer, liveDetail;
    private IconButton send, newChat;
    private TextView performanceStatus, performanceResult, performanceStart, performanceCompare, performanceExport;
    private AlertDialog errorDialog;
    private BonsaiApp.ModelApproval shownModelApproval;
    private AlertDialog chatActionsDialog;
    private ProgressBar performanceProgress;
    private View drawerPanel;
    private boolean drawerClosing;
    private int renderedCount=-1;
    private long renderedChat=-1;
    private boolean lastBusy, autoScroll=true;
    private final Runnable uiListener=this::render;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app=(BonsaiApp)getApplication();
        tokens=new DesignTokens(getResources());
        BG=tokens.background; PANEL=tokens.surface; TEXT=tokens.text; MUTED=tokens.muted; ACCENT=tokens.primary; LINE=tokens.border;
        if(android.os.Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        root=new FrameLayout(this); root.setBackgroundColor(BG);
        page=column(); FrameLayout.LayoutParams pageBounds=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER_HORIZONTAL);
        root.addView(page,pageBounds);
        page.setOnApplyWindowInsetsListener((v,insets) -> {
            if(android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int width=Math.min(r-l,dp(DesignTokens.CONTENT_WIDTH));
            if(page.getLayoutParams().width!=width){page.getLayoutParams().width=width;page.requestLayout();}
        });
        LinearLayout header=row(); header.setPadding(dp(16),dp(8),dp(16),dp(8));
        header.addView(icon("menu","Menü öffnen",v -> showDrawer()));
        LinearLayout heading=column(); heading.setPadding(dp(8),0,dp(8),0);
        title=text("Bonsai",DesignTokens.TYPE_TITLE,TEXT); title.setTypeface(null,Typeface.BOLD); title.setAccessibilityHeading(true);
        subtitle=text("Privat. Auf deinem Gerät.",DesignTokens.TYPE_META,MUTED);
        heading.addView(title); heading.addView(subtitle); header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        newChat=icon("edit","Neuen Chat starten",v -> {app.newChat();input.setText("");navigate(0);});newChat.setBackground(interactiveBackground(PANEL,DesignTokens.RADIUS_SMALL));header.addView(newChat);page.addView(header);

        statusCard=column();statusCard.setPadding(dp(16),dp(8),dp(16),dp(8));
        LinearLayout statusRow=row();state=text("",DesignTokens.TYPE_LABEL,MUTED);state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(state,new LinearLayout.LayoutParams(0,-2,1));
        statusAction=menuItem("Abbrechen");statusRow.addView(statusAction);statusCard.addView(statusRow);
        statusProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);statusProgress.setMax(1000);
        statusProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));statusCard.addView(statusProgress,new LinearLayout.LayoutParams(-1,dp(4)));
        page.addView(statusCard);
        screenHost=new FrameLayout(this);page.addView(screenHost,new LinearLayout.LayoutParams(-1,0,1));
        createChatPage();screenHost.addView(chatPage,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        if(saved!=null){input.setText(saved.getString("draft",""));pendingPrompt=saved.getString("pendingPrompt","");}
        app.listener=uiListener;navigate(saved==null?0:saved.getInt("screen",0));render();
    }
    private void createChatPage() {
        chatPage=column();
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(dp(24),dp(16),dp(24),dp(24));
        chat=column();scroll.addView(chat,new ScrollView.LayoutParams(-1,-1));chatPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.getViewTreeObserver().addOnScrollChangedListener(()->{
            View content=scroll.getChildAt(0);autoScroll=content==null||content.getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(100);
        });
        composerGroup=column();composerGroup.setPadding(dp(16),dp(8),dp(16),0);
        TextView label=text("Deine Nachricht",DesignTokens.TYPE_LABEL,MUTED);label.setPadding(dp(8),0,0,dp(8));composerGroup.addView(label);
        LinearLayout composer=row();composer.setGravity(Gravity.BOTTOM);composer.setPadding(dp(8),dp(4),dp(8),dp(4));composer.setBackground(outline(tokens.raised,DesignTokens.RADIUS_CARD));
        input=new EditText(this);input.setId(View.generateViewId());label.setLabelFor(input.getId());input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setTextSize(DesignTokens.TYPE_BODY);
        input.setHint("Stelle eine Frage …");input.setBackground(interactiveBackground(Color.TRANSPARENT,DesignTokens.RADIUS_SMALL));
        input.setPadding(dp(8),dp(12),dp(8),dp(12));input.setMaxLines(4);input.setMinLines(1);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=icon("send","Nachricht senden",v->{
            if(app.busy){app.cancel();return;}
            String draft=input.getText().toString(),value=draft.trim();
            if(draft.length()>32000){showInputError();return;}
            if(!value.isEmpty()&&app.loaded){
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if(app.send(value)){pendingPrompt=draft;input.setText("");autoScroll=true;}
            }
        });composer.addView(send);composerGroup.addView(composer);
        inputError=text("",DesignTokens.TYPE_LABEL,tokens.error);inputError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);composerGroup.addView(inputError);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){showInputError();updateSend();}
            public void afterTextChanged(Editable e){}
        });
        TextView note=text("Offline verarbeitet · KI-Antworten können Fehler enthalten",DesignTokens.TYPE_META,MUTED);note.setGravity(Gravity.CENTER);note.setPadding(dp(8),dp(8),dp(8),dp(8));composerGroup.addView(note);
        chatPage.addView(composerGroup);
    }
    private void showInputError(){
        boolean tooLong=input.length()>32000;inputError.setText(tooLong?"Bitte kürze deine Nachricht auf höchstens 32.000 Zeichen.":"");inputError.setVisibility(tooLong?View.VISIBLE:View.GONE);
    }
    private void navigate(int destination){
        if(destination<1||destination>2){
            if(sectionDialog!=null)sectionDialog.dismiss();
            return;
        }
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        activeScreen=destination;
        if(sectionDialog==null){
            sectionDialog=new Dialog(this);
            sectionDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel=column();panel.setBackground(round(BG,DesignTokens.RADIUS_CARD));panel.setClipToOutline(true);
            LinearLayout header=row();header.setPadding(dp(16),dp(12),dp(8),dp(8));
            popupTitle=text("",DesignTokens.TYPE_TITLE,TEXT);popupTitle.setTypeface(null,Typeface.BOLD);popupTitle.setAccessibilityHeading(true);
            popupTitle.setCompoundDrawablePadding(dp(12));header.addView(popupTitle,new LinearLayout.LayoutParams(0,-2,1));
            header.addView(icon("close","Pop-up schließen",v->{if(sectionDialog!=null)sectionDialog.dismiss();}));panel.addView(header);
            popupStatusCard=column();popupStatusCard.setPadding(dp(16),0,dp(16),dp(8));
            LinearLayout statusRow=row();popupState=text("",DesignTokens.TYPE_LABEL,MUTED);
            statusRow.addView(popupState,new LinearLayout.LayoutParams(0,-2,1));
            popupAction=menuItem("");popupAction.setOnClickListener(v->statusAction.performClick());statusRow.addView(popupAction);popupStatusCard.addView(statusRow);
            popupProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);popupProgress.setMax(1000);
            popupProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));popupStatusCard.addView(popupProgress,new LinearLayout.LayoutParams(-1,dp(4)));panel.addView(popupStatusCard);
            popupHost=new FrameLayout(this);panel.addView(popupHost,new LinearLayout.LayoutParams(-1,0,1));
            sectionDialog.setContentView(panel);sectionDialog.setCanceledOnTouchOutside(true);
            sectionDialog.setOnDismissListener(d->{
                if(sectionDialog!=d)return;
                sectionDialog=null;activeScreen=0;popupHost=null;popupTitle=null;popupState=null;popupAction=null;popupStatusCard=null;popupProgress=null;
                modelPage=null;performancePage=null;performanceStatus=null;performanceResult=null;performanceStart=null;performanceCompare=null;performanceExport=null;performanceProgress=null;
            });
            sectionDialog.show();
            Window window=sectionDialog.getWindow();
            if(window!=null){
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                window.setLayout(Math.min(dp(DesignTokens.CONTENT_WIDTH),getResources().getDisplayMetrics().widthPixels-dp(32)),(int)(getResources().getDisplayMetrics().heightPixels*.88f));
                window.setGravity(Gravity.CENTER);
            }
        }
        popupTitle.setText(destination==1?"Modell":"Leistung");sectionDialog.setTitle(popupTitle.getText());
        popupTitle.setCompoundDrawables(UiArtwork.icon(destination==1?"model":"performance",ACCENT,dp(24)),null,null,null);
        refreshPopupContent();updatePopupStatus();
        UiMotion.enter(popupHost,0,dp(8));
    }
    private void refreshPopupContent(){
        if(popupHost==null)return;
        popupHost.removeAllViews();
        if(activeScreen==1){modelPage=buildModelPage();popupHost.addView(modelPage,new FrameLayout.LayoutParams(-1,-1));}
        else{performancePage=buildPerformancePage();popupHost.addView(performancePage,new FrameLayout.LayoutParams(-1,-1));updatePerformance();}
    }
    private void updatePopupStatus(){
        if(popupStatusCard==null)return;
        popupStatusCard.setVisibility(statusCard.getVisibility());
        popupState.setText(state.getText());popupState.setTextColor(state.getCurrentTextColor());popupState.setAccessibilityLiveRegion(state.getAccessibilityLiveRegion());
        popupAction.setText(statusAction.getText());popupAction.setEnabled(statusAction.isEnabled());
        popupProgress.setVisibility(statusProgress.getVisibility());popupProgress.setIndeterminate(statusProgress.isIndeterminate());popupProgress.setProgress(statusProgress.getProgress());
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("draft",input.getText().toString());out.putInt("screen",activeScreen);out.putString("pendingPrompt",pendingPrompt);}
    @Override protected void onDestroy(){
        app.main.removeCallbacks(progressTick);
        if(sectionDialog!=null){sectionDialog.setOnDismissListener(null);sectionDialog.dismiss();sectionDialog=null;}
        if(errorDialog!=null)errorDialog.dismiss();
        if(chatActionsDialog!=null)chatActionsDialog.dismiss();
        if(app.listener==uiListener)app.listener=null;
        super.onDestroy();
    }
    @Override protected void onStart(){super.onStart();app.listener=uiListener;render();}
    @Override protected void onStop(){
        app.main.removeCallbacks(progressTick);
        if(app.listener==uiListener)app.listener=null;
        super.onStop();
    }
    @Override public void onBackPressed(){if(drawer!=null)closeDrawer();else if(sectionDialog!=null)sectionDialog.dismiss();else super.onBackPressed();}

    private void render(){
        if(app.busy && android.os.Build.VERSION.SDK_INT>=33 && !app.prefs.getBoolean("notificationsAsked",false)) {
            app.prefs.edit().putBoolean("notificationsAsked",true).apply();
            if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},20);
        }
        if(app.busy)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!app.busy&&!pendingPrompt.isEmpty()){
            if((!app.error.isEmpty()||app.cancelled)&&input.length()==0)input.setText(pendingPrompt);
            pendingPrompt="";
        }
        subtitle.setText(app.loaded?app.backend+" · Modell bereit":!app.messages.isEmpty()?"Modell nicht geladen":"Privat. Auf deinem Gerät.");
        updateStatus();updatePerformance();
        newChat.setEnabled(!app.busy);newChat.setAlpha(app.busy?.4f:1);updateSend();
        boolean modelChanged=lastLoaded!=app.loaded||lastModelPresent!=app.modelFile().isFile();
        boolean phaseChanged=lastBusy!=app.busy;
        if(renderedCount!=app.messages.size()||renderedChat!=app.currentChat||phaseChanged||modelChanged){
            boolean switched=renderedChat!=-1&&renderedChat!=app.currentChat;
            renderedCount=app.messages.size();renderedChat=app.currentChat;lastBusy=app.busy;lastLoaded=app.loaded;lastModelPresent=app.modelFile().isFile();
            chat.removeAllViews();liveAnswer=null;liveDetail=null;
            if(app.messages.isEmpty())emptyState();else{chat.setGravity(Gravity.TOP);for(ChatStore.Message m:app.messages)addMessage(m);}
            // Existing streamed text never fades or moves for each token or status update.
            if(switched)UiMotion.enter(chat,0,dp(8));
            if(activeScreen==1&&(phaseChanged||modelChanged))refreshPopupContent();
        }else if(liveAnswer!=null&&!app.messages.isEmpty()){
            ChatStore.Message m=app.messages.get(app.messages.size()-1);liveAnswer.setText(m.text.isEmpty()?"Bonsai denkt nach …":m.text);
            if(liveDetail!=null)setAnswerDetail(liveDetail,m.detail);
        }
        if(app.modelApproval!=null&&!app.busy)showError();
        if(autoScroll&&!app.messages.isEmpty())scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
        if(app.modelCheckFinished&&!app.busy){app.modelCheckFinished=false;showModelCheck();}
    }
    private void updateStatus(){
        app.main.removeCallbacks(progressTick);
        if(app.busy){
            if(busySince==0)busySince=android.os.SystemClock.elapsedRealtime();
            long elapsed=android.os.SystemClock.elapsedRealtime()-busySince;
            state.setAccessibilityLiveRegion(lastBusy?View.ACCESSIBILITY_LIVE_REGION_NONE:View.ACCESSIBILITY_LIVE_REGION_POLITE);
            state.setText(app.status+(elapsed>=10000?" · "+elapsed/1000+" s":""));state.setTextColor(TEXT);
            statusAction.setText(app.cancelled?"Wird beendet":"Abbrechen");statusAction.setEnabled(!app.cancelled);statusAction.setOnClickListener(v->app.cancel());
            statusProgress.setVisibility(elapsed>=DesignTokens.LOADING_DELAY?View.VISIBLE:View.GONE);
            statusProgress.setIndeterminate(!app.optimizing);
            if(app.optimizing)statusProgress.setProgress((int)(app.optimizingFraction*1000));
            app.main.postDelayed(progressTick,elapsed<DesignTokens.LOADING_DELAY?DesignTokens.LOADING_DELAY:1000);
        }else{
            busySince=0;state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);statusProgress.setVisibility(View.GONE);statusAction.setEnabled(true);
            boolean error=!app.error.isEmpty();state.setText(error?"Vorgang nicht abgeschlossen. Öffne die Details für den nächsten Schritt.":"Sicheres CPU-Profil ist aktiv.");state.setTextColor(error?tokens.error:MUTED);
            statusAction.setText("Details");statusAction.setOnClickListener(v->{if(error)showError();else new AlertDialog.Builder(this).setTitle("Rechenprüfung").setMessage(app.backendNotice).setPositiveButton("Schließen",null).setNeutralButton("Modell öffnen",(d,w)->settings()).show();});
        }
        boolean needsModel=!app.loaded&&!app.messages.isEmpty();
        if(!app.busy&&app.error.isEmpty()&&app.backendNotice.isEmpty()&&needsModel){
            state.setText("Lade dein Modell, um diesen Chat fortzusetzen.");state.setTextColor(MUTED);statusAction.setText("Modell öffnen");statusAction.setOnClickListener(v->settings());
        }
        statusCard.setVisibility(app.busy||!app.error.isEmpty()||!app.backendNotice.isEmpty()||needsModel?View.VISIBLE:View.GONE);
        updatePopupStatus();
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
        } else builder.setTitle("Vorgang nicht abgeschlossen")
            .setMessage(app.error+"\n\nPrüfe das Modell und die Einstellungen. Danach kannst du den Vorgang erneut starten.")
            .setPositiveButton("Schließen",null).setNeutralButton("Modell öffnen",(d,w)->settings());
        errorDialog=builder.create();
        errorDialog.setOnDismissListener(d->{if(errorDialog==d) errorDialog=null;});
        errorDialog.show();
    }
    private void updateSend(){
        if(send==null||input==null)return;
        send.kind=app.busy?"stop":"send";send.setContentDescription(app.busy?"Vorgang abbrechen":"Nachricht senden");
        send.setEnabled(app.busy?!app.cancelled:app.loaded&&!input.getText().toString().trim().isEmpty()&&input.length()<=32000);
        send.setAlpha(send.isEnabled()?1:.4f);send.invalidate();
        // Drafting the next question stays available while inference runs.
        input.setEnabled(true);
        composerGroup.setVisibility(app.loaded||!app.messages.isEmpty()||input.length()>0?View.VISIBLE:View.GONE);
    }
    private void emptyState(){
        chat.setGravity(Gravity.TOP);
        LinearLayout hero=column();hero.setPadding(0,dp(8),0,dp(16));
        LinearLayout privacy=row();TextView badge=badge("Privat auf deinem Gerät","shield");
        if(app.loaded){
            privacy.addView(badge,new LinearLayout.LayoutParams(0,-2,1));
            privacy.addView(new UiArtwork.BonsaiScene(this,tokens),new LinearLayout.LayoutParams(dp(104),dp(80)));
        }else{
            hero.addView(new UiArtwork.BonsaiScene(this,tokens),new LinearLayout.LayoutParams(-1,dp(132)));
            privacy.addView(badge);
        }
        addSpaced(hero,privacy,8);
        TextView greeting=text(app.loaded?"Was möchtest du\nherausfinden?":"Gute Gedanken.\nGanz für dich.",DesignTokens.TYPE_HERO,TEXT);greeting.setTypeface(null,Typeface.BOLD);greeting.setLineSpacing(0,1.1f);greeting.setPadding(0,dp(8),0,dp(12));greeting.setAccessibilityHeading(true);hero.addView(greeting);
        TextView caption=text(app.loaded?"Dein Modell ist bereit. Stelle eine Frage oder nutze einen Einstieg.":"Einmal ein Modell importieren. Danach Fragen stellen – auch ohne Internet.",DesignTokens.TYPE_BODY,MUTED);hero.addView(caption);
        if(!app.loaded){
            LinearLayout card=card();card.addView(badge(app.busy?"Wird vorbereitet":"DEIN ERSTER SCHRITT","model"),new LinearLayout.LayoutParams(-2,-2));
            TextView step=text("Ein Modell für deine Gedanken",DesignTokens.TYPE_SUBTITLE,TEXT);step.setTypeface(null,Typeface.BOLD);addSpaced(card,step,12);
            addSpaced(card,text("1. Modell öffnen   2. Frage stellen",DesignTokens.TYPE_LABEL,MUTED),8);
            TextView start=primary(app.busy?"Vorgang läuft …":app.modelFile().isFile()?"Modell laden":"Modell importieren");start.setEnabled(!app.busy);start.setOnClickListener(v->{if(app.modelFile().isFile())app.loadModel();else pickModel();});addSpaced(card,start,16);
            TextView hint=text("GGUF-Datei · bleibt auf diesem Gerät",DesignTokens.TYPE_META,MUTED);addSpaced(card,hint,12);addSpaced(hero,card,24);
        }else{
            addSpaced(hero,suggestion("Einfach verstehen","Komplexes auf den Punkt bringen","book","Erkläre mir ein schwieriges Thema einfach: "),24);
            addSpaced(hero,suggestion("Eine Idee entwickeln","Gedanken sammeln und weiterdenken","idea","Hilf mir, eine Idee weiterzuentwickeln: "),8);
        }
        chat.addView(hero,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView suggestion(String label,String description,String glyph,String prompt){
        android.text.SpannableString content=new android.text.SpannableString(label+"\n"+description);
        content.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,label.length(),0);
        content.setSpan(new android.text.style.RelativeSizeSpan(DesignTokens.TYPE_LABEL/(float)DesignTokens.TYPE_BODY),label.length()+1,content.length(),0);
        content.setSpan(new android.text.style.ForegroundColorSpan(MUTED),label.length()+1,content.length(),0);
        TextView t=menuItem("");t.setText(content);t.setPadding(dp(16),dp(16),dp(16),dp(16));t.setBackground(interactiveBackground(PANEL,DesignTokens.RADIUS_SMALL));
        t.setCompoundDrawables(UiArtwork.icon(glyph,ACCENT,dp(28)),null,UiArtwork.icon("arrow",ACCENT,dp(18)),null);t.setCompoundDrawablePadding(dp(16));
        t.setOnClickListener(v->{input.setText(prompt);input.setSelection(input.length());input.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});return t;
    }
    private void addMessage(ChatStore.Message m){
        boolean user=m.role.equals("user");LinearLayout box=column();box.setPadding(user?dp(16):0,dp(16),user?dp(16):0,dp(16));
        if(user)box.setBackground(round(PANEL,DesignTokens.RADIUS_CARD));
        TextView author=text(user?"Du":"Bonsai",DesignTokens.TYPE_LABEL,user?MUTED:ACCENT);author.setTypeface(null,Typeface.BOLD);
        author.setCompoundDrawables(UiArtwork.icon(user?"chat":"leaf",user?MUTED:ACCENT,dp(20)),null,null,null);author.setCompoundDrawablePadding(dp(8));box.addView(author);
        TextView body=text(m.text.isEmpty()?"Bonsai denkt nach …":m.text,DesignTokens.TYPE_BODY,TEXT);body.setTextIsSelectable(true);addSpaced(box,body,8);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,-2);bounds.topMargin=dp(8);bounds.bottomMargin=dp(8);if(user)bounds.leftMargin=dp(24);
        if(!user){
            TextView detail=text("",DesignTokens.TYPE_META,MUTED);setAnswerDetail(detail,m.detail);addSpaced(box,detail,8);
            if(!m.text.isEmpty()&&!app.busy){
                LinearLayout actions=row();
                android.widget.ImageButton copy=answerAction(R.drawable.ic_answer_copy,"Antwort kopieren",v->{
                    ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Bonsai",m.text));
                    Toast.makeText(this,"Antwort kopiert",Toast.LENGTH_SHORT).show();
                });actions.addView(copy);
                if(m==app.messages.get(app.messages.size()-1)) {
                    android.widget.ImageButton retry=answerAction(R.drawable.ic_answer_retry,"Neu generieren",v->{autoScroll=true;app.regenerateLastAnswer();});
                    retry.setTooltipText("Neu generieren – speichert die Variante als eigenen Chat");
                    retry.setContentDescription("Neu generieren – Variante als eigenen Chat speichern");
                    retry.setEnabled(app.loaded);retry.setAlpha(app.loaded?1f:.38f);
                    LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(dp(48),dp(48));space.leftMargin=dp(8);actions.addView(retry,space);
                }
                addSpaced(box,actions,4);
            }
            if(m==app.messages.get(app.messages.size()-1)){liveAnswer=body;liveDetail=detail;}
        }
        chat.addView(box,bounds);
    }

    private android.graphics.drawable.Drawable answerGlyph(int resource,int size) {
        android.graphics.drawable.Drawable drawable=getDrawable(resource).mutate();drawable.setTint(MUTED);
        drawable.setBounds(0,0,dp(size),dp(size));return drawable;
    }
    private android.widget.ImageButton answerAction(int resource,String label,View.OnClickListener listener) {
        android.widget.ImageButton button=new android.widget.ImageButton(this);
        button.setImageDrawable(answerGlyph(resource,28));button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(10),dp(10),dp(10),dp(10));button.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
        button.setBackground(interactiveBackground(Color.TRANSPARENT,DesignTokens.RADIUS_SMALL));
        button.setContentDescription(label);button.setTooltipText(label);button.setOnClickListener(listener);return button;
    }
    private void setAnswerDetail(TextView view,String detail) {
        view.setVisibility(detail.trim().isEmpty()?View.GONE:View.VISIBLE);
        view.setContentDescription(detail);view.setTooltipText(detail);
        android.text.SpannableStringBuilder content=new android.text.SpannableStringBuilder();
        String[] parts=detail.split("\\s*·\\s*");
        int[] icons={R.drawable.ic_answer_speed,R.drawable.ic_answer_count,R.drawable.ic_answer_time};
        for(int i=0;i<parts.length;i++) {
            if(parts[i].trim().isEmpty())continue;
            if(content.length()>0)content.append("   ");
            int at=content.length();content.append("\uFFFC");
            content.setSpan(new android.text.style.ImageSpan(answerGlyph(i<icons.length?icons[i]:R.drawable.ic_answer_info,16),android.text.style.ImageSpan.ALIGN_BASELINE),at,at+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            content.append("\u00A0").append(parts[i].replace(" bis zum ersten Token","").replace(' ','\u00A0'));
        }
        view.setText(content);
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
                    app.main.post(()->Toast.makeText(this,"Messprotokoll gespeichert",Toast.LENGTH_SHORT).show());
                } catch(Exception e) { app.main.post(()->Toast.makeText(this,"Export fehlgeschlagen: "+e.getMessage(),Toast.LENGTH_LONG).show()); }
            });
        }
    }
    private void showDrawer() {
        showDrawer(false);
    }
    private void showDrawer(boolean archived) {
        if(drawer!=null) return;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        drawer=new FrameLayout(this); drawer.setBackgroundColor(0x99000000); drawer.setOnClickListener(v->closeDrawer());
        LinearLayout panel=column(); panel.setBackgroundColor(BG); panel.setPadding(dp(16),Math.max(dp(24),page.getPaddingTop()),dp(16),dp(24)); panel.setOnClickListener(v->{});
        FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(Math.min(dp(315),getResources().getDisplayMetrics().widthPixels-dp(40)),-1);
        drawer.addView(panel,bounds);
        LinearLayout drawerHeader=row();
        TextView brand=text("Bonsai",24,TEXT); brand.setTypeface(null,Typeface.BOLD); brand.setCompoundDrawables(UiArtwork.icon("leaf",ACCENT,dp(28)),null,null,null);brand.setCompoundDrawablePadding(dp(12));brand.setPadding(dp(10),0,0,0);drawerHeader.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        IconButton close=icon("close","Menü schließen",v->closeDrawer());drawerHeader.addView(close);panel.addView(drawerHeader);
        ScrollView menuScroll=new ScrollView(this);menuScroll.setFillViewport(true);LinearLayout menu=column();menuScroll.addView(menu);panel.addView(menuScroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView fresh=menuItem("Neuen Chat starten"); fresh.setEnabled(!app.busy); fresh.setOnClickListener(v->{closeDrawer();app.newChat();input.setText("");navigate(0);}); addSpaced(menu,fresh,16);
        TextView model=menuItem("Modell");decorateAction(model,"model");model.setOnClickListener(v->closeDrawer(()->navigate(1)));menu.addView(model);
        TextView performance=menuItem("Leistung");decorateAction(performance,"performance");performance.setOnClickListener(v->closeDrawer(()->navigate(2)));menu.addView(performance);
        TextView label=text(archived?"Archivierte Chats":"Deine Chats",12,MUTED); label.setPadding(dp(12),dp(24),0,dp(12)); menu.addView(label);
        LinearLayout entries=column();menu.addView(entries);
        for(ChatStore.Chat c:app.store.chats(archived)) {
            TextView item=menuItem(c.title); item.setMaxLines(2);item.setEllipsize(android.text.TextUtils.TruncateAt.END); item.setEnabled(!app.busy);
            if(c.id==app.currentChat) item.setBackground(interactiveBackground(PANEL,DesignTokens.RADIUS_SMALL));
            item.setOnClickListener(v->{closeDrawer();app.selectChat(c.id);input.setText("");navigate(0);});
            item.setOnLongClickListener(v->{showChatActions(c,archived);return true;}); entries.addView(item);
        }
        TextView archive=menuItem(archived?"Zurück zu deinen Chats":"Archivierte Chats");
        archive.setOnClickListener(v->refreshDrawer(!archived));menu.addView(archive);
        TextView privacy=text("Alle Chats bleiben auf diesem Gerät.",14,MUTED); privacy.setPadding(dp(12),dp(16),0,0); menu.addView(privacy);
        if(app.store.chats(archived).isEmpty())entries.addView(text(archived?"Noch keine archivierten Chats.":"Noch keine Chats. Deine Gespräche erscheinen hier, sobald du eine Nachricht sendest.",16,MUTED));
        TextView delete=menuItem("Aktuellen Chat löschen");delete.setTextColor(tokens.error);delete.setEnabled(!app.busy&&app.currentChat!=0);delete.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Chat dauerhaft löschen?").setMessage("Alle Nachrichten dieses Chats werden von diesem Gerät gelöscht.").setNegativeButton("Behalten",null).setPositiveButton("Chat löschen",(d,w)->{app.deleteCurrentChat();closeDrawer();navigate(0);}).show());menu.addView(delete);
        page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);page.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(drawer,new FrameLayout.LayoutParams(-1,-1));close.requestFocus();
        drawerPanel=panel; drawerClosing=false;
        UiMotion.enter(panel,-bounds.width,0);

    }
    private void refreshDrawer(boolean archived) {
        if(drawer==null || drawerClosing)return;
        drawerPanel.animate().cancel(); root.removeView(drawer); drawer=null;
        showDrawer(archived);
    }
    private void showChatActions(ChatStore.Chat selected,boolean archived) {
        if(app.busy)return;
        chatActionsDialog=new AlertDialog.Builder(this).setTitle(selected.title)
            .setItems(new String[]{"Löschen",archived?"Wiederherstellen":"Archivieren","Abbrechen"},(dialog,which)->{
                if(which==0) {
                    chatActionsDialog=new AlertDialog.Builder(this).setTitle("Chat löschen?")
                        .setMessage("Dieser Chat wird dauerhaft von diesem Gerät gelöscht.")
                        .setNegativeButton("Abbrechen",null)
                        .setPositiveButton("Löschen",(d,w)->{
                            if(app.busy)return;
                            boolean current=app.currentChat==selected.id;
                            app.deleteChat(selected.id);
                            if(current)input.setText("");
                            refreshDrawer(archived);
                        }).create();
                    chatActionsDialog.setCanceledOnTouchOutside(true); chatActionsDialog.show();
                } else if(which==1 && !app.busy) {
                    boolean current=app.currentChat==selected.id;
                    app.archiveChat(selected.id,!archived);
                    if(current && !archived)input.setText("");
                    refreshDrawer(archived);
                }
            }).create();
        chatActionsDialog.setCanceledOnTouchOutside(true); chatActionsDialog.show();
    }
    private void closeDrawer() { closeDrawer(null); }
    private void closeDrawer(Runnable afterClose) {
        if(drawer==null){if(afterClose!=null)afterClose.run();return;}
        if(drawerClosing)return;
        FrameLayout closing=drawer; drawerClosing=true;
        Runnable done=()->{root.removeView(closing);if(drawer==closing)drawer=null;drawerClosing=false;page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);page.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);if(afterClose!=null)afterClose.run();};
        UiMotion.exit(drawerPanel,-drawerPanel.getWidth(),done);
    }

    private void settings(){navigate(1);}
    private LinearLayout buildModelPage(){
        LinearLayout screen=column();ScrollView pane=new ScrollView(this);pane.setFillViewport(true);
        LinearLayout content=column();content.setPadding(dp(24),dp(16),dp(24),dp(24));pane.addView(content);screen.addView(pane,new LinearLayout.LayoutParams(-1,0,1));
        addSectionTitle(content,"Dein Modell. Dein Gerät.","Das Modell verarbeitet deine Nachrichten vollständig offline.");
        LinearLayout modelCard=card();modelCard.addView(badge(app.loaded?"Bereit zum Chatten":app.modelFile().isFile()?"Importiert · nicht geladen":"Noch kein Modell","model"),new LinearLayout.LayoutParams(-2,-2));
        TextView name=text(app.modelFile().isFile()?app.modelName():"Bonsai 2 · GGUF",DesignTokens.TYPE_TITLE,TEXT);name.setTextIsSelectable(true);addSpaced(modelCard,name,8);
        addSpaced(modelCard,text(app.modelFile().isFile()?"Prozess-RAM: "+ram(BonsaiApp.rssKb())+" · "+app.backend:"Wähle eine lokale GGUF-Datei. Die App legt eine private Kopie an.",DesignTokens.TYPE_BODY,MUTED),12);addSpaced(modelCard,profileFacts(),20);addSpaced(content,modelCard,24);
        if(app.modelFile().isFile()){
            TextView replace=menuItem("Anderes Modell importieren");replace.setEnabled(!app.busy);replace.setOnClickListener(v->pickModel());addSpaced(content,replace,8);
        }
        TextView download=menuItem("Beispielmodell: Bonsai 2 4B herunterladen ↗");
        download.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(ModelIntegrity.downloadUrl())))); content.addView(download);
        content.addView(settingLabel("Du kannst auch andere GGUF-Modelle über die Dateiauswahl importieren. Unterstützte Architektur, Quantisierung und verfügbarer RAM bestimmen, ob ein Modell geladen werden kann."));
        TextView section=text("Antworten anpassen",DesignTokens.TYPE_TITLE,TEXT);section.setAccessibilityHeading(true);addSpaced(content,section,24);
        LinearLayout controls=card();addSpaced(content,controls,16);
        TextView ctx=settingLabel("Kontextlänge");controls.addView(ctx);Spinner context=spinner(new String[]{"2.048 Tokens","4.096 Tokens","8.192 Tokens"},app.context()==2048?0:app.context()==8192?2:1);labelSpinner(ctx,context);controls.addView(context);
        TextView threadLabel=settingLabel("CPU-Threads");controls.addView(threadLabel);Spinner threads=spinner(new String[]{"2","4","6","8"},app.threads()/2-1);labelSpinner(threadLabel,threads);controls.addView(threads);
        TextView lengthLabel=settingLabel("Maximale Antwortlänge");controls.addView(lengthLabel);Spinner length=spinner(new String[]{"128 Tokens","256 Tokens","512 Tokens","1.024 Tokens","2.048 Tokens"},app.maxTokens()==128?0:app.maxTokens()==256?1:app.maxTokens()==1024?3:app.maxTokens()==2048?4:2);labelSpinner(lengthLabel,length);controls.addView(length);
        TextView engineLabel=settingLabel("Rechenbackend");controls.addView(engineLabel);Spinner engine=spinner(BuildConfig.VULKAN_BUILD?new String[]{"CPU","Vulkan · experimentell"}:new String[]{"CPU"},app.gpu()?1:0);labelSpinner(engineLabel,engine);controls.addView(engine);
        CheckBox grammar=new CheckBox(this);grammar.setText("GBNF für Tool-Aufrufe (experimentell)");
        grammar.setTextColor(TEXT);grammar.setChecked(app.gbnfTools());grammar.setEnabled(!app.busy);controls.addView(grammar);
        controls.addView(settingLabel("Erzwingt im Chat ein gültiges Tool-Format. Bei unzuverlässigen Tool-Aufrufen einschalten. Gilt ab der nächsten Nachricht, ohne das Modell neu zu laden."));
        CheckBox compatible=new CheckBox(this); compatible.setText("CPU-Kompatibilitätsmodus");
        compatible.setTextColor(TEXT); compatible.setChecked(app.prefs.getBoolean("cpuCompatibility",false)); compatible.setEnabled(!app.busy); controls.addView(compatible);
        controls.addView(settingLabel("Bei Zeichensalat: CPU und Kompatibilitätsmodus wählen, übernehmen und einen neuen Chat starten. Der Modus nutzt skalare Bonsai-Quantisierungskernel und kann langsamer sein."));
        controls.addView(settingLabel("NPU: Für Bonsai-GGUF auf dem Exynos 1480 derzeit nicht verfügbar."));
        for(Spinner field:new Spinner[]{context,threads,length,engine})field.setEnabled(!app.busy);
        addSpaced(controls,text("Mehr Kontext benötigt mehr Arbeitsspeicher. Änderungen an Kontext, Threads, Backend oder Kompatibilitätsmodus laden ein vorhandenes Modell neu.",DesignTokens.TYPE_LABEL,MUTED),16);
        TextView apply=primary(app.busy?"Vorgang läuft …":!app.modelFile().isFile()?"Modell importieren":app.loaded?"Einstellungen speichern":"Modell laden");apply.setEnabled(!app.busy);
        apply.setOnClickListener(v->{
            if(app.busy)return;
            int c=new int[]{2048,4096,8192}[context.getSelectedItemPosition()],t=new int[]{2,4,6,8}[threads.getSelectedItemPosition()],l=new int[]{128,256,512,1024,2048}[length.getSelectedItemPosition()];boolean gpu=engine.getSelectedItemPosition()==1;
            boolean changed=c!=app.context()||t!=app.threads()||gpu!=app.gpu()||compatible.isChecked()!=app.prefs.getBoolean("cpuCompatibility",false);
            if(changed)app.prefs.edit().remove("optimizedFor").remove("batchThreads").remove("microBatch").apply();
            app.prefs.edit().putInt("context",c).putInt("threads",t).putInt("maxTokens",l).putBoolean("gpu",gpu).putBoolean("cpuCompatibility",compatible.isChecked()).putBoolean("gbnfTools",grammar.isChecked()).apply();
            if(compatible.isChecked()&&!gpu)app.prefs.edit().putInt("microBatch",1).apply();
            if(!app.modelFile().isFile())pickModel();else if(changed||!app.loaded)app.loadModel();else Toast.makeText(this,"Einstellungen gespeichert",Toast.LENGTH_SHORT).show();
        });
        if(app.loaded){TextView unload=menuItem("Modell entladen und RAM freigeben");unload.setEnabled(!app.busy);unload.setOnClickListener(v->app.unloadModel());addSpaced(content,unload,16);}
        TextView check=menuItem("Modell prüfen");check.setOnClickListener(v->showModelCheck());content.addView(check);
        TextView licenses=menuItem("Open-Source-Lizenzen");licenses.setOnClickListener(v->showLicenses());content.addView(licenses);
        addSpaced(content,text("Bonsai "+BuildConfig.VERSION_NAME+" · lokal auf diesem Gerät",DesignTokens.TYPE_META,MUTED),16);
        LinearLayout footer=column();footer.setPadding(dp(16),dp(8),dp(16),dp(8));footer.addView(apply);screen.addView(footer);return screen;
    }
    private void labelSpinner(TextView label,Spinner spinner){spinner.setId(View.generateViewId());label.setLabelFor(spinner.getId());spinner.setContentDescription(label.getText());}
    private void showLicenses(){
        try(InputStream stream=getAssets().open("third_party_notices.txt")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=stream.read(buffer))!=-1)bytes.write(buffer,0,n);
            TextView notice=text(new String(bytes.toByteArray(),StandardCharsets.UTF_8),DesignTokens.TYPE_LABEL,TEXT);notice.setTextIsSelectable(true);notice.setPadding(dp(24),dp(16),dp(24),dp(16));
            ScrollView pane=new ScrollView(this);pane.addView(notice);new AlertDialog.Builder(this).setTitle("Open-Source-Lizenzen").setView(pane).setPositiveButton("Schließen",null).show();
        }catch(Exception e){Toast.makeText(this,"Lizenzen konnten nicht geöffnet werden. Bitte versuche es erneut.",Toast.LENGTH_LONG).show();}
    }
    private void showPerformance(){navigate(2);}
    private LinearLayout buildPerformancePage(){
        if(app.optimizerReport==null)app.optimizerReport=app.readReport("optimizer.json");
        LinearLayout panel=column();ScrollView details=new ScrollView(this);details.setFillViewport(true);LinearLayout content=column();content.setPadding(dp(24),dp(16),dp(24),dp(24));details.addView(content);panel.addView(details,new LinearLayout.LayoutParams(-1,0,1));
        addSectionTitle(content,"Passend zu deinem Gerät.","Finde mit einer lokalen Messung die schnellste geprüfte Einstellung.");
        LinearLayout resultCard=card();resultCard.addView(badge("LOKALE LEISTUNG","performance"),new LinearLayout.LayoutParams(-2,-2));
        performanceStatus=text("",DesignTokens.TYPE_SUBTITLE,TEXT);performanceStatus.setTypeface(null,Typeface.BOLD);addSpaced(resultCard,performanceStatus,16);
        performanceProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);performanceProgress.setMax(1000);performanceProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));addSpaced(resultCard,performanceProgress,16);
        performanceResult=text("",DesignTokens.TYPE_BODY,MUTED);performanceResult.setTextIsSelectable(true);addSpaced(resultCard,performanceResult,16);addSpaced(content,resultCard,24);
        addSpaced(content,text("So funktioniert die Messung",DesignTokens.TYPE_TITLE,TEXT),24);
        addSpaced(content,stepRow("01","Messung starten","Optimierung bei jedem Temperaturstatus starten."),16);
        addSpaced(content,stepRow("02","Entspannt weiterarbeiten","Die Messung läuft auch im Hintergrund weiter."),12);
        addSpaced(content,stepRow("03","Profil übernehmen","Das schnellste geprüfte Profil wird gespeichert."),12);
        addSpaced(content,text("Die Messung kann mehrere Minuten dauern. Temperatur und Drosselung beeinflussen die Messwerte; Androids Geräteschutz bleibt aktiv. Modellpräzision und Kontext bleiben gleich.",DesignTokens.TYPE_LABEL,MUTED),16);
        TextView benchmark=menuItem("Benchmark öffnen");decorateAction(benchmark,"performance");benchmark.setOnClickListener(v->showBenchmark());addSpaced(content,benchmark,16);
        TextView export=menuItem("Messprotokoll exportieren");performanceExport=export;export.setEnabled(app.readReport("optimizer.json")!=null&&!app.busy);export.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"bonsai-optimizer.json").addCategory(Intent.CATEGORY_OPENABLE),EXPORT_OPTIMIZER));content.addView(export);
        performanceCompare=menuItem("CPU und Vulkan vergleichen");performanceCompare.setOnClickListener(v->{if(!app.busy)app.optimize(true);});content.addView(performanceCompare);
        if(!BuildConfig.VULKAN_BUILD)content.addView(text("Vulkan ist in diesem Build nicht verfügbar.",DesignTokens.TYPE_META,MUTED));
        LinearLayout footer=column();footer.setPadding(dp(16),dp(8),dp(16),dp(8));performanceStart=primary("");performanceStart.setOnClickListener(v->{if(app.optimizing)app.cancel();else if(!app.busy&&!app.loaded)settings();else if(!app.busy)app.optimize(false);});footer.addView(performanceStart);panel.addView(footer);return panel;
    }
    private void updatePerformance(){
        if(activeScreen!=2||performanceStatus==null)return;
        performanceStatus.setText(app.optimizing?app.optimizingProgress:app.loaded?app.backend+" · bereit zur Messung":"Zuerst ein Modell laden");
        performanceProgress.setVisibility(app.optimizing?View.VISIBLE:View.GONE);performanceProgress.setProgress((int)(app.optimizingFraction*1000));
        JSONObject r=app.optimizerReport;String result=app.optimized()?"Dein gemessenes Profil ist aktiv.":"Noch kein aktives Messprofil. Die App verwendet deine manuellen Einstellungen.";
        if(r!=null)result+="\n\nLetzte Messung: "+r.optString("winner_label")+String.format(Locale.GERMAN,"\n%.1f %% kürzere Laufzeit im Test.\n",r.optDouble("estimated_gain_percent"))+"Das Ergebnis gilt für den gemessenen Prompt und die damalige Temperatur.";
        result+="\n\n"+app.threads()+" CPU-Threads · "+app.context()+" Tokens Kontext";performanceResult.setText(result);
        performanceStart.setText(app.optimizing?"Optimierung abbrechen":!app.loaded?"Modell öffnen":"Optimierung starten");performanceStart.setEnabled((app.optimizing&&!app.cancelled)||!app.busy);
        performanceCompare.setEnabled(!app.busy&&app.loaded&&BuildConfig.VULKAN_BUILD);
        performanceExport.setEnabled(!app.busy&&app.optimizerReport!=null);
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
        s.setMinimumHeight(dp(48));s.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));s.setPadding(dp(12),dp(8),dp(12),dp(8));return s;
    }
    private GradientDrawable outline(int color,float radius){GradientDrawable d=round(color,radius);d.setStroke(dp(1),LINE);return d;}
    private TextView settingLabel(String s){TextView t=text(s,DesignTokens.TYPE_LABEL,MUTED);t.setPadding(0,dp(16),0,dp(8));return t;}
    private TextView menuItem(String s){
        TextView t=new ActionText();t.setText(s);t.setTextSize(DesignTokens.TYPE_BODY);t.setTextColor(actionColors(TEXT));t.setGravity(Gravity.CENTER_VERTICAL);t.setMinHeight(dp(DesignTokens.TOUCH_TARGET));t.setPadding(dp(12),dp(12),dp(12),dp(12));t.setBackground(interactiveBackground(Color.TRANSPARENT,DesignTokens.RADIUS_SMALL));return t;
    }
    private TextView primary(String s){TextView t=menuItem(s);t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER);t.setTextColor(actionColors(tokens.onPrimary));t.setBackground(interactiveBackground(tokens.action,DesignTokens.RADIUS_SMALL));return t;}
    private android.content.res.ColorStateList actionColors(int normal){return new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,normal});}
    private android.graphics.drawable.Drawable interactiveBackground(int fill,int radius){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},round(PANEL,radius));
        GradientDrawable focus=round(fill,radius);focus.setStroke(dp(2),fill==tokens.action?tokens.onPrimary:ACCENT);states.addState(new int[]{android.R.attr.state_focused},focus);
        GradientDrawable hovered=round(fill,radius);hovered.setStroke(dp(1),fill==tokens.action?tokens.onPrimary:ACCENT);states.addState(new int[]{android.R.attr.state_hovered},hovered);
        states.addState(new int[]{},round(fill,radius));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((ACCENT&0x00ffffff)|0x33000000),states,round(Color.WHITE,radius));
    }
    private final class ActionText extends TextView{
        ActionText(){super(MainActivity.this);setFocusable(true);setClickable(true);setLineSpacing(0,1.2f);}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
    }
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(24),dp(24),dp(24),dp(24));GradientDrawable background=round(PANEL,DesignTokens.RADIUS_CARD);background.setStroke(dp(1),tokens.subtleBorder);c.setBackground(background);return c;}
    private void decorateAction(TextView view,String glyph){
        view.setCompoundDrawables(UiArtwork.icon(glyph,ACCENT,dp(20)),null,null,null);view.setCompoundDrawablePadding(dp(12));
    }
    private TextView badge(String label,String glyph){
        TextView view=text(label,DesignTokens.TYPE_META,ACCENT);view.setTypeface(null,Typeface.BOLD);
        view.setPadding(dp(12),dp(8),dp(12),dp(8));view.setBackground(round(tokens.accentSurface,DesignTokens.RADIUS_SMALL));
        view.setCompoundDrawables(UiArtwork.icon(glyph,ACCENT,dp(16)),null,null,null);view.setCompoundDrawablePadding(dp(8));return view;
    }
    private LinearLayout profileFacts(){
        LinearLayout facts=row();facts.setBaselineAligned(false);
        String[] values={String.valueOf(app.threads()),String.format(Locale.GERMAN,"%,d",app.context())};
        String[] labels={"CPU-Threads","Tokens Kontext"};
        for(int i=0;i<2;i++){
            LinearLayout fact=column();fact.setPadding(dp(12),dp(12),dp(12),dp(12));fact.setBackground(round(tokens.raised,DesignTokens.RADIUS_SMALL));
            TextView value=text(values[i],DesignTokens.TYPE_SUBTITLE,TEXT);value.setTypeface(null,Typeface.BOLD);fact.addView(value);fact.addView(text(labels[i],DesignTokens.TYPE_META,MUTED));
            LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(0,-2,1);if(i>0)bounds.leftMargin=dp(8);facts.addView(fact,bounds);
        }
        return facts;
    }
    private LinearLayout stepRow(String number,String heading,String description){
        LinearLayout step=row();step.setGravity(Gravity.TOP);TextView index=text(number,DesignTokens.TYPE_LABEL,ACCENT);
        index.setTypeface(null,Typeface.BOLD);index.setGravity(Gravity.CENTER);index.setPadding(dp(8),dp(12),dp(8),dp(12));
        index.setBackground(round(tokens.accentSurface,DesignTokens.RADIUS_SMALL));step.addView(index,new LinearLayout.LayoutParams(dp(48),-2));
        LinearLayout copy=column();TextView title=text(heading,DesignTokens.TYPE_BODY,TEXT);title.setTypeface(null,Typeface.BOLD);copy.addView(title);
        addSpaced(copy,text(description,DesignTokens.TYPE_LABEL,MUTED),4);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(0,-2,1);bounds.leftMargin=dp(12);step.addView(copy,bounds);return step;
    }
    private void addSpaced(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private void addSectionTitle(LinearLayout parent,String heading,String description){TextView h=text(heading,DesignTokens.TYPE_HERO,TEXT);h.setTypeface(null,Typeface.BOLD);h.setLineSpacing(0,1.1f);h.setAccessibilityHeading(true);parent.addView(h);addSpaced(parent,text(description,DesignTokens.TYPE_BODY,MUTED),16);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,float size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(0,size>=16?1.4f:1.3f);return t;}
    private GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private IconButton icon(String kind,String description,View.OnClickListener listener) {
        IconButton v=new IconButton(kind); v.setContentDescription(description); v.setOnClickListener(listener);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));v.setFocusable(true);v.setBackground(interactiveBackground(Color.TRANSPARENT,DesignTokens.RADIUS_SMALL));return v;
    }
    private final class IconButton extends View {
        String kind;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF stopBounds=new RectF(-5,-5,5,5);
        final Path editPath=new Path();
        IconButton(String kind) { super(MainActivity.this); this.kind=kind; setMinimumHeight(dp(48)); setMinimumWidth(dp(48)); }
        @Override public boolean performClick() { return super.performClick(); }
        @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
        private boolean pressedBefore;
        @Override protected void drawableStateChanged(){
            super.drawableStateChanged();
            boolean down=isEnabled()&&isPressed();
            if(down!=pressedBefore){pressedBefore=down;UiMotion.press(this,down);}
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save(); canvas.translate(getWidth()/2f,getHeight()/2f); canvas.scale(dp(1),dp(1));
            paint.setColor(ACCENT); paint.setStrokeWidth(1.8f); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("send") || kind.equals("stop")) {
                paint.setColor(tokens.action);paint.setStyle(Paint.Style.FILL); canvas.drawCircle(0,0,18,paint); paint.setColor(tokens.onPrimary);
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
