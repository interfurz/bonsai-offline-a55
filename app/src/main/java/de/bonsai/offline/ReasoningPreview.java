package de.bonsai.offline;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RotateDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A bounded live preview, separate from the answer and saved model history. */
final class ReasoningPreview extends LinearLayout {
    private final TextView toggle,excerpt,body;
    private final ScrollView expandedBody;
    private final RotateDrawable chevron;
    private boolean expanded,thinking;
    private String content="";

    public ReasoningPreview(Context context) {
        super(context);
        DesignTokens tokens=new DesignTokens(getResources());
        setOrientation(VERTICAL);setPadding(dp(12),0,dp(12),dp(10));
        GradientDrawable background=new GradientDrawable();background.setColor(tokens.surface);
        background.setCornerRadius(dp(DesignTokens.RADIUS_SMALL));setBackground(background);
        toggle=new TextView(context){
            @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
        };
        toggle.setTextSize(DesignTokens.TYPE_LABEL);toggle.setTextColor(tokens.muted);
        toggle.setTypeface(null,Typeface.BOLD);toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(dp(48));toggle.setFocusable(true);
        chevron=new RotateDrawable();chevron.setDrawable(BonsaiIcons.drawable(context,R.drawable.ic_reasoning_chevron,tokens.muted,dp(16)));
        chevron.setFromDegrees(0);chevron.setToDegrees(180);chevron.setBounds(0,0,dp(16),dp(16));
        toggle.setCompoundDrawables(BonsaiIcons.icon(context,"idea",tokens.muted,dp(16)),null,chevron,null);
        toggle.setCompoundDrawablePadding(dp(8));
        UiMotion.bindPress(toggle);addView(toggle,new LayoutParams(-1,-2));
        excerpt=copy(context,tokens);excerpt.setSingleLine(true);
        excerpt.setEllipsize(android.text.TextUtils.TruncateAt.START);addView(excerpt,new LayoutParams(-1,-2));
        body=copy(context,tokens);body.setTextIsSelectable(true);
        expandedBody=new ScrollView(context){
            @Override protected void onMeasure(int width,int height){
                super.onMeasure(width,MeasureSpec.makeMeasureSpec(dp(160),MeasureSpec.AT_MOST));
            }
        };
        expandedBody.setFillViewport(false);expandedBody.addView(body,new ScrollView.LayoutParams(-1,-2));
        addView(expandedBody,new LayoutParams(-1,-2));
        toggle.setOnClickListener(v->{setExpanded(!expanded);UiMotion.enter(expanded?expandedBody:excerpt,0,dp(3));});
        setExpanded(false);setVisibility(GONE);
    }
    private TextView copy(Context context,DesignTokens tokens) {
        TextView view=new TextView(context);view.setTextSize(DesignTokens.TYPE_LABEL);
        view.setTextColor(tokens.muted);view.setLineSpacing(0,1.35f);return view;
    }
    boolean update(String value,boolean active) {
        boolean changed=!content.equals(value)||thinking!=active;
        if(!changed)return false;
        boolean wasThinking=thinking;thinking=active;
        setVisibility(value.trim().isEmpty()?GONE:VISIBLE);
        if(wasThinking&&!thinking)expanded=false;
        setExpanded(expanded);
        if(!content.equals(value)) {
            content=value;
            boolean follow=expandedBody.getChildAt(0).getHeight()-expandedBody.getScrollY()-expandedBody.getHeight()<dp(32);
            String tail=value.length()>240?value.substring(value.offsetByCodePoints(value.length(),-Math.min(200,value.codePointCount(0,value.length())))):value;
            excerpt.setText(tail.replaceAll("\\s+"," ").trim());
            body.setText(value);
            if(expanded&&follow)expandedBody.post(()->expandedBody.scrollTo(0,body.getBottom()));
        }
        updateLabel();return true;
    }
    private void setExpanded(boolean value) {
        expanded=value;excerpt.setVisibility(!value&&thinking?VISIBLE:GONE);expandedBody.setVisibility(value?VISIBLE:GONE);
        updateLabel();
    }
    private void updateLabel() {
        String label=thinking?"Denkt nach …":"Reasoning";
        if(!android.text.TextUtils.equals(toggle.getText(),label))toggle.setText(label);
        chevron.setLevel(expanded?10000:0);
        toggle.setContentDescription(expanded?"Reasoning einklappen":"Reasoning aufklappen");
        if(android.os.Build.VERSION.SDK_INT>=30)toggle.setStateDescription(expanded?"Ausgeklappt":"Eingeklappt");
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
