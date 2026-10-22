package de.bonsai.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.KeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Drafts stay in the dialog until saved; tool instructions require a fresh warning each opening. */
final class SystemPromptEditor extends Dialog {
    private final BonsaiApp app;
    private final DesignTokens tokens;
    private final EditText instructions,tools;
    private final KeyListener toolsKeyListener;
    private final TextView toolState;
    private final Button unlock,resetTools;
    private boolean toolsUnlocked;
    private AlertDialog warning;

    SystemPromptEditor(Activity activity,BonsaiApp app,Bundle draft) {
        super(activity);
        this.app=app;tokens=new DesignTokens(activity.getResources());
        requestWindowFeature(Window.FEATURE_NO_TITLE);setTitle("Systemprompt");
        setCanceledOnTouchOutside(false);
        LinearLayout panel=column();panel.setBackgroundColor(tokens.background);panel.setPadding(dp(20),dp(12),dp(20),dp(12));
        TextView heading=label("Systemprompt",22);heading.setTypeface(null,Typeface.BOLD);heading.setAccessibilityHeading(true);panel.addView(heading);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);
        LinearLayout content=column();scroll.addView(content);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content.addView(label("Gilt für alle Chats ab der nächsten Nachricht. Gespeichert wird nur auf diesem Gerät. Lange Vorgaben lassen weniger Platz für den Verlauf.",14));
        TextView ownLabel=label("Eigene Vorgaben",18);ownLabel.setAccessibilityHeading(true);content.addView(ownLabel);
        instructions=editor(draft==null?SystemPrompt.instructions(app.prefs):draft.getString("instructions"),SystemPrompt.MAX_INSTRUCTIONS,5);
        ownLabel.setLabelFor(instructions.getId());instructions.setContentDescription("Eigene Vorgaben");content.addView(instructions);
        content.addView(label("Bis zu 4.000 Zeichen, etwa für Sprache, Stil und gewünschte Ausführlichkeit.",14));
        content.addView(button("Vorgaben auf Standard setzen",()->instructions.setText(SystemPrompt.DEFAULT_INSTRUCTIONS)));
        toolState=label("Tool-Anweisungen · schreibgeschützt",18);toolState.setAccessibilityHeading(true);content.addView(toolState);
        content.addView(label("Enthält calc, merken, abrufen, suche und wikidata sowie Aufrufformat, Argumente und Ergebnisregeln.",14));
        tools=editor(draft==null?SystemPrompt.tools(app.prefs):draft.getString("tools"),SystemPrompt.MAX_TOOLS,8);
        tools.setTypeface(Typeface.MONOSPACE);toolState.setLabelFor(tools.getId());tools.setContentDescription("Tool-Anweisungen");
        toolsKeyListener=tools.getKeyListener();tools.setKeyListener(null);tools.setTextIsSelectable(true);tools.setCursorVisible(false);content.addView(tools);
        content.addView(label("Bis zu 16.000 Zeichen. Die aktive GBNF-Formatvorgabe ergänzt die App automatisch; ausführbare Tools bleiben im Code festgelegt.",14));
        unlock=button("Tool-Anweisungen entsperren",this::requestUnlock);content.addView(unlock);
        resetTools=button("Tool-Anweisungen auf Standard setzen",()->tools.setText(SystemPrompt.DEFAULT_TOOLS));resetTools.setEnabled(false);content.addView(resetTools);
        panel.addView(button("Prompt speichern",this::save));panel.addView(button("Abbrechen",this::dismiss));
        setContentView(panel);
    }
    private int dp(float value) { return Math.round(value*getContext().getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view; }
    private TextView label(String text,int size) {
        TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);view.setTextColor(tokens.text);view.setPadding(0,dp(12),0,dp(8));return view;
    }
    private EditText editor(String value,int limit,int lines) {
        EditText view=new EditText(getContext());view.setId(View.generateViewId());view.setTextSize(16);view.setTextColor(tokens.text);
        view.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);view.setGravity(Gravity.TOP|Gravity.START);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});view.setMinLines(3);view.setMaxLines(lines);view.setText(value);return view;
    }
    private Button button(String text,Runnable action) {
        Button view=new Button(getContext());view.setText(text);view.setAllCaps(false);view.setMinHeight(dp(48));view.setOnClickListener(v->action.run());return view;
    }
    private void requestUnlock() {
        if(warning!=null && warning.isShowing())return;
        warning=new AlertDialog.Builder(getContext()).setTitle("Tool-Anweisungen ändern?")
            .setMessage("Diese Anweisungen erklären dem Modell, wann und wie es Tools aufruft. Änderungen können Tool-Aufrufe und Antworten unbrauchbar machen. Neue Tool-Namen im Prompt erzeugen keine neuen Funktionen; die Java-Prüfung und die optionale GBNF-Grammatik bleiben bestehen.\n\nDer Bereich wird nur für diese Bearbeitung entsperrt. Bei Problemen kannst du die Tool-Anweisungen auf Standard setzen.")
            .setNegativeButton("Abbrechen",null).setPositiveButton("Trotzdem bearbeiten",(d,w)->{
                toolsUnlocked=true;tools.setTextIsSelectable(false);tools.setKeyListener(toolsKeyListener);tools.setCursorVisible(true);
                toolState.setText("Tool-Anweisungen · entsperrt");unlock.setEnabled(false);resetTools.setEnabled(true);tools.requestFocus();
            }).create();
        warning.show();
    }
    private void save() {
        if(app.busy) { Toast.makeText(getContext(),"Bitte warte, bis die laufende Berechnung beendet ist.",Toast.LENGTH_LONG).show();return; }
        // Restored tool drafts must be acknowledged again after an Activity recreation.
        if(!toolsUnlocked && !tools.getText().toString().equals(SystemPrompt.tools(app.prefs))) { requestUnlock();return; }
        try {
            if(app.saveSystemPrompt(instructions.getText().toString(),tools.getText().toString())) {
                Toast.makeText(getContext(),"Systemprompt gespeichert · gilt ab der nächsten Nachricht",Toast.LENGTH_LONG).show();dismiss();
            }
        } catch(IllegalArgumentException e) { instructions.setError(e.getMessage()); }
    }
    Bundle draft() {
        Bundle state=new Bundle();state.putString("instructions",instructions.getText().toString());state.putString("tools",tools.getText().toString());return state;
    }
    @Override protected void onStart() {
        super.onStart();Window window=getWindow();
        if(window!=null) {
            window.setBackgroundDrawable(new ColorDrawable(tokens.background));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(Math.min(dp(DesignTokens.CONTENT_WIDTH),getContext().getResources().getDisplayMetrics().widthPixels-dp(32)),(int)(getContext().getResources().getDisplayMetrics().heightPixels*.9f));
        }
    }
    @Override public void dismiss() { if(warning!=null)warning.dismiss();super.dismiss(); }
}
