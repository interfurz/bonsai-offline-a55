package de.bonsai.offline;

import org.json.JSONObject;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Buffers every generation: tool requests and results never enter the visible chat history. */
final class ToolLoop {
    static final int MAX_ROUNDS=3;
    static final String SYSTEM_PROMPT=SystemPrompt.compose(SystemPrompt.DEFAULT_INSTRUCTIONS,SystemPrompt.DEFAULT_TOOLS,false);
    private static final Pattern CALL=Pattern.compile("\\A\\s*<tool>\\s*(\\{.*\\})\\s*</tool>\\s*\\z",Pattern.DOTALL);
    private static final Pattern MARKER=Pattern.compile("</?tool\\b",Pattern.CASE_INSENSITIVE);
    interface Generator { Result generate(byte[][] roles,byte[][] texts,byte[] grammar) throws Exception; }
    static final class Result {
        final String text;
        final JSONObject stats;
        Result(String text,JSONObject stats) { this.text=text; this.stats=stats; }
    }
    static String systemPrompt(boolean grammarEnabled) { return SYSTEM_PROMPT+(grammarEnabled?ToolGrammar.FORMAT_PROMPT:""); }
    static Result run(List<ChatStore.Message> history,ChatStore store,Generator generator,BooleanSupplier cancelled) throws Exception {
        return run(history,store,generator,cancelled,false);
    }
    static Result run(List<ChatStore.Message> history,ChatStore store,Generator generator,BooleanSupplier cancelled,boolean grammarEnabled) throws Exception {
        return run(history,store,generator,cancelled,grammarEnabled,systemPrompt(grammarEnabled));
    }
    static Result run(List<ChatStore.Message> history,ChatStore store,Generator generator,BooleanSupplier cancelled,boolean grammarEnabled,String systemPrompt) throws Exception {
        SearchSources sources=new SearchSources();
        WikidataSources facts=new WikidataSources();
        StructuredSources structured=new StructuredSources();
        boolean untrustedKnowledge=false;
        List<ChatStore.Message> turns=new ArrayList<>();
        turns.add(new ChatStore.Message(0,"system",systemPrompt,""));
        turns.addAll(history);
        for(int round=0;round<MAX_ROUNDS;round++) {
            checkCancelled(cancelled);
            byte[][] roles=new byte[turns.size()][], texts=new byte[turns.size()][];
            for(int i=0;i<turns.size();i++) {
                roles[i]=turns.get(i).role.getBytes(StandardCharsets.UTF_8);
                texts[i]=turns.get(i).text.getBytes(StandardCharsets.UTF_8);
            }
            byte[] grammar=grammarEnabled?ToolGrammar.forRound(round==MAX_ROUNDS-1).getBytes(StandardCharsets.UTF_8):null;
            Result result=generator.generate(roles,texts,grammar);
            checkCancelled(cancelled);
            // Never execute a request cut short by a token limit or stop.
            boolean incomplete=result.stats.optBoolean("limit_reached") || result.stats.optBoolean("stopped");
            if(grammarEnabled && result.text.startsWith("<final>") && result.text.endsWith("</final>")) {
                if(incomplete) throw new IllegalStateException("GBNF-Endantwort wurde unterbrochen oder hat das Antwortlimit erreicht.");
                String answer=result.text.substring(7,result.text.length()-8);
                if(answer.trim().isEmpty() || answer.contains("</final>")) throw new IllegalStateException("Ungültige GBNF-Endantwort.");
                return structured.finish(facts.finish(sources.finish(new Result(answer,result.stats))));
            }
            Matcher match=CALL.matcher(result.text);
            if(!match.matches()) {
                if(grammarEnabled) throw new IllegalStateException("Ungültiges GBNF-Antwortformat.");
                if(MARKER.matcher(result.text).find()) throw new IllegalStateException("Ungültiges Tool-Format. Bitte erneut versuchen.");
                if(result.text.trim().isEmpty()) throw new IllegalStateException("Keine Endantwort erhalten.");
                return structured.finish(facts.finish(sources.finish(result)));
            }
            if(incomplete) throw new IllegalStateException("Tool-Aufruf wurde unterbrochen oder hat das Antwortlimit erreicht.");
            if(round==MAX_ROUNDS-1) throw new IllegalStateException("Tool-Limit erreicht. Keine Endantwort erhalten.");
            JSONObject reply=execute(match.group(1),store,cancelled,!untrustedKnowledge);
            if(java.util.Arrays.asList("suche","wikidata","woerterbuch","tabellen","erinnern").contains(reply.optString("name")))untrustedKnowledge=true;
            sources.collect(reply);
            facts.collect(reply);
            structured.collect(reply);
            turns.add(new ChatStore.Message(0,"assistant",result.text,""));
            String instruction=round==MAX_ROUNDS-2?"\nTool-Limit erreicht. Antworte jetzt abschließend ohne weiteren Tool-Aufruf.":"";
            turns.add(new ChatStore.Message(0,"user","<tool_result>"+reply.toString().replace("<","\\u003c").replace(">","\\u003e")+"</tool_result>"+instruction,""));
        }
        throw new IllegalStateException("Keine Endantwort erhalten.");
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new CancellationException("Abgebrochen.");
    }
    private static String string(JSONObject args,String key,int max) throws Exception {
        Object value=args.get(key);
        if(!(value instanceof String) || ((String)value).trim().isEmpty() || ((String)value).length()>max)
            throw new IllegalArgumentException("Ungültiges Argument: "+key);
        return (String)value;
    }
    private static JSONObject readCall(String json) throws Exception {
        // Android's JSONTokener accepts non-JSON syntax; JsonReader enforces the wire format.
        try(JsonReader reader=new JsonReader(new StringReader(json))) {
            JSONObject call=new JSONObject();
            reader.beginObject();
            while(reader.hasNext()) {
                String field=reader.nextName();
                if(call.has(field)) throw new IllegalArgumentException("Doppeltes Feld: "+field);
                if(field.equals("name")) {
                    if(reader.peek()!=JsonToken.STRING) throw new IllegalArgumentException("name muss Text sein.");
                    call.put(field,reader.nextString());
                } else if(field.equals("args")) {
                    JSONObject args=new JSONObject();
                    reader.beginObject();
                    while(reader.hasNext()) {
                        String key=reader.nextName();
                        if(args.has(key) || reader.peek()!=JsonToken.STRING) throw new IllegalArgumentException("Ungültiges Argument: "+key);
                        args.put(key,reader.nextString());
                    }
                    reader.endObject();
                    call.put(field,args);
                } else throw new IllegalArgumentException("Unbekanntes Feld: "+field);
            }
            reader.endObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Ungültiges Tool-JSON.");
            return call;
        }
    }
    private static JSONObject execute(String json,ChatStore store,BooleanSupplier cancelled,boolean allowWrites) throws Exception {
        JSONObject reply=new JSONObject();
        try {
            JSONObject call=readCall(json);
            if(call.length()!=2 || !(call.get("args") instanceof JSONObject)) throw new IllegalArgumentException("name und args erforderlich.");
            String name=string(call,"name",32);
            reply.put("name",name);
            JSONObject args=call.getJSONObject("args");
            checkCancelled(cancelled);
            switch(name) {
                case "mathe":
                    reply.put("result",SympyMath.run(store.context,args,cancelled));
                    break;
                case "erinnern":
                    if(args.length()!=1)throw new IllegalArgumentException("erinnern erwartet query.");
                    reply.put("result",MemorySearch.search(store,string(args,"query",256),cancelled));
                    break;
                case "woerterbuch": case "tabellen":
                    reply.put("result",StructuredKnowledge.query(store,name,args,cancelled));
                    break;
                case "wikidata":
                    reply.put("result",WikidataKnowledge.forStore(store).query(args,cancelled));
                    break;
                case "calc":
                    if(args.length()!=1) throw new IllegalArgumentException("calc erwartet expr.");
                    reply.put("result",Calculator.evaluate(string(args,"expr",1024)));
                    break;
                case "merken":
                    if(!allowWrites)throw new IllegalArgumentException("Nach Wissensabrufen sind Speicheränderungen gesperrt. Bitte in einer neuen Nachricht ausdrücklich speichern.");
                    if(args.length()!=2) throw new IllegalArgumentException("merken erwartet key und value.");
                    String key=string(args,"key",128), value=string(args,"value",4000);
                    store.remember(key,value);
                    reply.put("result","Gespeichert").put("key",key);
                    break;
                case "suche":
                    if(args.length()!=(args.has("corpus")?2:1)) throw new IllegalArgumentException("suche erwartet query und optional corpus.");
                    String query=string(args,"query",KnowledgeSearch.MAX_QUERY);
                    reply.put("result",KnowledgeSearch.searchInstalled(store,query,args.has("corpus")?string(args,"corpus",20):"all",cancelled));
                    break;
                case "abrufen":
                    if(args.length()!=1) throw new IllegalArgumentException("abrufen erwartet key.");
                    String memory=store.recall(string(args,"key",128));
                    reply.put("found",memory!=null).put("result",memory==null?JSONObject.NULL:memory);
                    break;
                default: throw new IllegalArgumentException("Unbekanntes Tool: "+name);
            }
            reply.put("ok",true);
        } catch(CancellationException e) { throw e; }
        catch(Exception e) { reply.put("ok",false).put("error",e.getMessage()==null?"Tool fehlgeschlagen.":e.getMessage()); }
        return reply;
    }
    private ToolLoop() {}
}
