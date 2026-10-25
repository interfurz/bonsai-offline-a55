package de.bonsai.offline;

import android.text.Spanned;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class AnswerMarkdownTest {
    private final android.content.Context context=RuntimeEnvironment.getApplication();
    private final AnswerMarkdown markdown=new AnswerMarkdown(context,new DesignTokens(context.getResources()));

    private static void hasSpan(TextView view,String name) {
        assertTrue(view.getText() instanceof Spanned);
        Spanned text=(Spanned)view.getText();
        for(Object span:text.getSpans(0,text.length(),Object.class))
            if(span.getClass().getSimpleName().equals(name))return;
        fail("Missing "+name+" in "+text);
    }

    @Test public void rendersCommonMarkdownAndKeepsCodeLiteral() {
        TextView view=new TextView(context);
        markdown.setText(view,"# Überblick\n\n**Fett** und *kursiv*, ~~alt~~ und `**Code**`.\n\n"
            +"- Eins\n- Zwei\n\n1. Erster\n2. Zweiter\n\n> Zitat\n\n"
            +"```java\nString s = \"**literal**\";\n```\n\n[Quelle](https://example.org)",true);
        String text=view.getText().toString();
        assertTrue(text.contains("Fett und kursiv"));assertTrue(text.contains("**Code**"));
        assertTrue(text.contains("\"**literal**\""));assertFalse(text.contains("```"));
        for(String span:new String[]{"HeadingSpan","StrongEmphasisSpan","EmphasisSpan",
                "StrikethroughSpan","CodeSpan","CodeBlockSpan","BulletListItemSpan",
                "OrderedListItemSpan","BlockQuoteSpan","LinkSpan"})hasSpan(view,span);
    }

    @Test public void rendersTablesAndCachesByOriginalSource() {
        TextView view=new TextView(context);
        String source="| Name | Wert |\n| --- | --- |\n| **A** | 42 |";
        assertTrue(markdown.setText(view,source,true));hasSpan(view,"TableRowSpan");
        // Table cells are drawn by replacement spans rather than stored as visible characters.
        Spanned table=(Spanned)view.getText();
        assertEquals(2,table.getSpans(0,table.length(),io.noties.markwon.ext.tables.TableRowSpan.class).length);
        CharSequence rendered=view.getText();
        assertFalse(markdown.setText(view,source,true));assertSame(rendered,view.getText());
        assertTrue(markdown.setText(view,source,false));assertEquals(source,view.getText().toString());
    }

    @Test public void incompleteStreamingSyntaxCanBeCompleted() {
        TextView view=new TextView(context);
        markdown.setText(view,"**Hal",true);assertTrue(view.getText().toString().contains("Hal"));
        markdown.setText(view,"**Hallo**",true);assertEquals("Hallo",view.getText().toString());
        hasSpan(view,"StrongEmphasisSpan");
        markdown.setText(view,"```\n**kein Fett**",true);
        assertTrue(view.getText().toString().contains("**kein Fett**"));hasSpan(view,"CodeBlockSpan");
    }

    @Test public void allowsOnlyWebAndMailLinks() {
        for(String link:new String[]{"https://example.org","HTTP://example.org","mailto:a@example.org"})
            assertTrue(link,AnswerMarkdown.isAllowedLink(link));
        for(String link:new String[]{"javascript:alert(1)","file:///secret","content://local/data",
                "intent://example.org","data:text/html,test","/relative","#anchor"})
            assertFalse(link,AnswerMarkdown.isAllowedLink(link));
    }

    @Test public void activityFormatsOnlyAnswersIncludingStreamingAndHistory() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();BonsaiApp app=(BonsaiApp)activity.getApplication();
            Method setText=MainActivity.class.getDeclaredMethod("setAnswerText",TextView.class,ChatStore.Message.class);
            setText.setAccessible(true);
            TextView view=new TextView(activity);
            ChatStore.Message user=new ChatStore.Message(0,"user","**Frage**","");
            setText.invoke(activity,view,user);assertEquals("**Frage**",view.getText().toString());
            app.busy=true;app.generationLine="**denken**";
            ChatStore.Message answer=new ChatStore.Message(0,"assistant","","");
            setText.invoke(activity,view,answer);assertEquals("**denken**",view.getText().toString());
            answer.text="**Antwort**";setText.invoke(activity,view,answer);
            assertEquals("Antwort",view.getText().toString());hasSpan(view,"StrongEmphasisSpan");
            assertTrue(view.isTextSelectable());assertEquals("**Antwort**",answer.text);
            app.busy=false;
            long chat=app.store.createChat("Markdown");app.store.add(chat,"user",user.text,"");
            app.store.add(chat,"assistant",answer.text,"");app.selectChat(chat);
            Field field=MainActivity.class.getDeclaredField("liveAnswer");field.setAccessible(true);
            TextView restored=(TextView)field.get(activity);
            assertEquals("Antwort",restored.getText().toString());hasSpan(restored,"StrongEmphasisSpan");
            assertEquals("**Antwort**",app.store.messages(chat).get(1).text);
        }
    }
}
