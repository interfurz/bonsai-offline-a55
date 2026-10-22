package de.bonsai.offline;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class SystemPromptTest {
    private SharedPreferences prefs() { return RuntimeEnvironment.getApplication().getSharedPreferences("prompt-test",Context.MODE_PRIVATE); }
    @Test public void defaultsRemainUnchangedAndGrammarIsAppendedOnce() {
        SharedPreferences prefs=prefs();
        assertEquals(ToolLoop.SYSTEM_PROMPT,SystemPrompt.read(prefs,false));
        assertEquals(ToolLoop.systemPrompt(true),SystemPrompt.read(prefs,true));
        assertFalse(SystemPrompt.customized(prefs));
        assertTrue(prefs.getAll().isEmpty());
    }
    @Test public void sectionsPersistIndependentlyAndResetFollowsFutureDefaults() {
        SharedPreferences prefs=prefs();
        SystemPrompt.save(prefs,"Antworte in Stichpunkten.",SystemPrompt.DEFAULT_TOOLS);
        assertEquals(1,prefs.getAll().size());
        assertEquals("Antworte in Stichpunkten.\n"+SystemPrompt.DEFAULT_TOOLS,SystemPrompt.read(prefs(),false));
        SystemPrompt.save(prefs,"Antworte in Stichpunkten.","Eigene Tool-Anweisungen");
        assertEquals("Eigene Tool-Anweisungen",SystemPrompt.tools(prefs()));
        assertTrue(SystemPrompt.customized(prefs));
        SystemPrompt.save(prefs,SystemPrompt.DEFAULT_INSTRUCTIONS,SystemPrompt.DEFAULT_TOOLS);
        assertTrue(prefs.getAll().isEmpty());assertFalse(SystemPrompt.customized(prefs));
    }
    @Test public void emptySectionsAreIntentionalButBlankWholePromptAndOversizeAreRejected() {
        SharedPreferences prefs=prefs();
        SystemPrompt.save(prefs,"","Nur Tools");assertEquals("Nur Tools",SystemPrompt.read(prefs,false));
        SystemPrompt.save(prefs,"Nur Vorgaben","");assertEquals("Nur Vorgaben",SystemPrompt.read(prefs,false));
        for(String[] bad:new String[][]{{" ","\n"},{"x".repeat(SystemPrompt.MAX_INSTRUCTIONS+1),"Tools"},{"Vorgaben","x".repeat(SystemPrompt.MAX_TOOLS+1)}}) {
            try { SystemPrompt.save(prefs,bad[0],bad[1]);fail(); }catch(IllegalArgumentException expected) { }
            assertEquals("Nur Vorgaben",SystemPrompt.read(prefs,false));
        }
    }
    @Test public void fingerprintChangesWithEitherSectionAndGrammarWithoutExposingPrompt() {
        String original=SystemPrompt.fingerprint(ToolLoop.SYSTEM_PROMPT);
        assertTrue(original.matches("[0-9a-f]{64}"));
        assertNotEquals(original,SystemPrompt.fingerprint(ToolLoop.systemPrompt(true)));
        assertNotEquals(SystemPrompt.fingerprint(SystemPrompt.compose("A","B",false)),SystemPrompt.fingerprint(SystemPrompt.compose("A","C",false)));
    }
}
