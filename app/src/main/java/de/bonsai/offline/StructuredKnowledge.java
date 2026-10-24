package de.bonsai.offline;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import java.io.File;
import java.io.IOException;
import java.math.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Fixed read-only queries. The model can supply values, never SQL or paths. */
final class StructuredKnowledge {
    static String norm(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ");}
    private static String text(JSONObject args,String key,int max) throws Exception {
        Object value=args.get(key);if(!(value instanceof String) || ((String)value).trim().isEmpty() || ((String)value).length()>max)throw new IllegalArgumentException("Ungültiges Argument: "+key);return (String)value;
    }
    private static void keys(JSONObject args,String... expected) throws Exception {
        Set<String> actual=new HashSet<>();args.keys().forEachRemaining(actual::add);
        if(!actual.equals(new HashSet<>(Arrays.asList(expected))))throw new IllegalArgumentException("Unbekannte oder fehlende Tool-Argumente.");
    }
    static JSONObject query(ChatStore store,String tool,JSONObject args,BooleanSupplier cancelled) throws Exception {
        String type=tool.equals("woerterbuch")?"dictionary":"tables";
        if(tool.equals("woerterbuch")){if(args.has("language"))keys(args,"word","language");else keys(args,"word");text(args,"word",256);}
        else {String op=text(args,"op",16);if(op.equals("convert"))keys(args,"op","value","from","to");else if(op.equals("constant") || op.equals("food"))keys(args,"op","name");else throw new IllegalArgumentException("tabellen: constant, food oder convert verwenden.");}
        List<KnowledgePackages.Installed> packages=KnowledgePackages.active(KnowledgePackages.files(store),type);
        if(packages.isEmpty())throw new IOException("Kein passendes Wissenspaket aktiviert.");
        JSONArray rows=new JSONArray(),sources=new JSONArray();boolean more=false;
        for(KnowledgePackages.Installed item:packages) {
            int before=rows.length();
            if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(item.directory,"knowledge.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                db.execSQL("PRAGMA cache_size=-2048");db.execSQL("PRAGMA temp_store=FILE");
                CancellationSignal signal=new CancellationSignal();long deadline=android.os.SystemClock.elapsedRealtime()+2000;
                Thread watcher=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){if(cancelled.getAsBoolean() || android.os.SystemClock.elapsedRealtime()>deadline){signal.cancel();return;}Thread.sleep(25);}}catch(InterruptedException ignored){}},"structured-query-cancel");watcher.setDaemon(true);watcher.start();
                try {
                    if(tool.equals("woerterbuch"))dictionary(db,args,rows,signal);
                    else switch(args.getString("op")) {
                        case "constant":constants(db,text(args,"name",256),rows,signal);break;
                        case "food":food(db,text(args,"name",256),rows,signal);break;
                        case "convert":convert(db,args,rows,signal);break;
                    }
                } finally {watcher.interrupt();}
                if(rows.length()>before)sources.put(new JSONObject().put("package_id",item.id()).put("date",item.manifest.getJSONObject("source").getString("date")).put("url",item.manifest.getJSONObject("source").getString("url")));
            }
            if(rows.length()>=10){more=true;break;}
        }
        JSONObject result=new JSONObject().put("status",rows.length()==0?"not_found":"found").put("rows",rows).put("sources",sources).put("coverage","installed_local_packages").put("more",more);
        while(result.toString().length()>6000 && rows.length()>1){rows.remove(rows.length()-1);result.put("more",true);}
        if(result.toString().length()>6000)throw new IOException("Wörterbucheintrag zu umfangreich; bitte Sprache präzisieren.");
        return result;
    }
    private static void dictionary(SQLiteDatabase db,JSONObject args,JSONArray rows,CancellationSignal signal) throws Exception {
        String word=norm(text(args,"word",256));boolean language=args.has("language");
        boolean aliases;
        try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='lexical_alias'",null,signal)){aliases=c.moveToFirst();}
        String requestedLanguage=language?text(args,"language",64):"";
        String prefix=aliases?"WITH RECURSIVE matched(norm,depth) AS (VALUES(?,0) UNION SELECT a.target_norm,m.depth+1 FROM lexical_alias a JOIN matched m ON a.norm=m.norm WHERE m.depth<4 AND (?='' OR a.language=? OR a.language='') LIMIT 256) ":"";
        String match=aliases?"(e.norm IN (SELECT norm FROM matched) OR e.id IN (SELECT entry_id FROM form WHERE norm IN (SELECT norm FROM matched)))":"(e.norm=? OR e.id IN (SELECT entry_id FROM form WHERE norm=?))";
        String sql=prefix+"SELECT e.id,e.lemma,e.language,e.pos,p.source FROM entry e JOIN page p ON p.id=e.page_id WHERE "+match+(language?" AND e.language=?":"")+" ORDER BY e.lemma,e.language,e.pos,e.id LIMIT 10";
        ArrayList<String> values=new ArrayList<>();values.add(word);values.add(aliases?requestedLanguage:word);if(aliases)values.add(requestedLanguage);if(language)values.add(requestedLanguage);
        int before=rows.length();
        try(Cursor c=db.rawQuery(sql,values.toArray(new String[0]),signal)) {
            while(c.moveToNext() && rows.length()<10) {
                JSONObject entry=new JSONObject().put("id",c.getLong(0)).put("lemma",c.getString(1)).put("language",c.getString(2)).put("pos",c.getString(3)).put("source",c.getString(4));JSONArray details=new JSONArray();
                try(Cursor d=db.rawQuery("SELECT kind,sense,substr(value,1,700),length(value)>700 FROM detail WHERE entry_id=? AND kind IN ('definitions','synonyms','antonyms','etymology','translations','grammar') ORDER BY CASE kind WHEN 'definitions' THEN 0 WHEN 'grammar' THEN 1 ELSE 2 END,kind,ordinal LIMIT 8",new String[]{c.getString(0)},signal)) {
                    while(d.moveToNext())details.put(new JSONObject().put("kind",d.getString(0)).put("sense",d.isNull(1)?JSONObject.NULL:d.getString(1)).put("text",d.getString(2)).put("truncated",d.getInt(3)!=0));
                }
                entry.put("details",details).put("lookup_word",args.getString("word"));rows.put(entry);
            }
        }
        if(rows.length()==before && aliases) {
            try(Cursor c=db.rawQuery("SELECT p.lemma,p.source,u.kind,substr(u.text,1,2500) FROM unparsed u JOIN page p ON p.id=u.page_id WHERE u.norm=? LIMIT 1",new String[]{word},signal)) {
                if(c.moveToFirst())rows.put(new JSONObject().put("lemma",c.getString(0)).put("source",c.getString(1)).put("status","unstructured_source_no_resolved_definition").put("kind",c.getString(2)).put("text",c.getString(3)));
            }
        }
    }
    private static void constants(SQLiteDatabase db,String name,JSONArray rows,CancellationSignal signal) throws Exception {
        String normalized=norm(name);
        try(Cursor c=db.rawQuery("SELECT name,value_text,unit,uncertainty_text,exact,status,source,source_version FROM reference_value WHERE kind='constant' AND (norm=? OR id IN (SELECT id FROM reference_alias WHERE norm=?)) ORDER BY id LIMIT 5",new String[]{normalized,normalized},signal)) {
            while(c.moveToNext())rows.put(new JSONObject().put("name",c.getString(0)).put("value",c.getString(1)).put("unit",c.getString(2)).put("uncertainty",c.isNull(3)?JSONObject.NULL:c.getString(3)).put("exact_definition",c.getInt(4)!=0).put("value_status",c.getString(5)).put("source",c.getString(6)).put("dataset",c.getString(7)));
        }
    }
    private static void food(SQLiteDatabase db,String name,JSONArray rows,CancellationSignal signal) throws Exception {
        java.util.regex.Matcher words=java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(norm(name));StringBuilder match=new StringBuilder();
        while(words.find() && match.length()<512){if(match.length()>0)match.append(" AND ");match.append('"').append(words.group()).append('"');}
        if(match.length()==0)return;
        try(Cursor c=db.rawQuery("SELECT f.id,f.name,f.brands,f.basis,f.source,f.modified FROM food f WHERE f.id IN (SELECT id FROM food_fts WHERE food_fts MATCH ? LIMIT 5) ORDER BY f.name,f.id LIMIT 5",new String[]{match.toString()},signal)) {
            while(c.moveToNext()) {
                JSONObject food=new JSONObject().put("id",c.getString(0)).put("name",c.getString(1)).put("brands",c.getString(2)).put("basis",c.getString(3)).put("source",c.getString(4)).put("modified",c.getString(5));JSONArray nutrients=new JSONArray();
                try(Cursor n=db.rawQuery("SELECT name,value,unit,status FROM nutrient WHERE food_id=? ORDER BY name LIMIT 24",new String[]{c.getString(0)},signal)) {while(n.moveToNext())nutrients.put(new JSONObject().put("name",n.getString(0)).put("value",n.isNull(1)?JSONObject.NULL:n.getString(1)).put("unit",n.getString(2)).put("status",n.getString(3)));}
                rows.put(food.put("nutrients",nutrients));
            }
        }
    }
    private static void convert(SQLiteDatabase db,JSONObject args,JSONArray rows,CancellationSignal signal) throws Exception {
        String raw=text(args,"value",128);if(!raw.matches("[-+]?\\d{1,50}(\\.\\d{1,50})?([eE][-+]?\\d{1,3})?"))throw new IllegalArgumentException("Endliche Dezimalzahl erforderlich.");
        BigDecimal value=new BigDecimal(raw);if(Math.abs(value.scale())>300)throw new IllegalArgumentException("Dezimalexponent zu groß.");
        String[][] units=new String[2][];String[] names={text(args,"from",64),text(args,"to",64)};
        for(int i=0;i<2;i++) {
            String canonical=Normalizer.normalize(names[i],Normalizer.Form.NFKC).trim();
            try(Cursor c=db.rawQuery("SELECT symbol,dimension,scale,offset,source FROM unit WHERE symbol=?",new String[]{canonical},signal)) {
                if(c.moveToFirst())units[i]=new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)};
            }
            if(units[i]==null)try(Cursor c=db.rawQuery("SELECT u.symbol,u.dimension,u.scale,u.offset,u.source FROM unit u JOIN unit_alias a ON a.symbol=u.symbol WHERE a.norm=?",new String[]{norm(names[i])},signal)) {
                if(!c.moveToFirst())return;
                if(norm(names[i]).equals(norm(c.getString(0))) && !canonical.equals(Normalizer.normalize(c.getString(0),Normalizer.Form.NFKC)))throw new IllegalArgumentException("Groß-/Kleinschreibung der Einheit beachten.");
                units[i]=new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)};
            }
        }
        if(!units[0][1].equals(units[1][1]))throw new IllegalArgumentException("Einheiten haben unterschiedliche Dimensionen.");
        MathContext context=new MathContext(50,RoundingMode.HALF_EVEN);
        BigDecimal result=value.multiply(new BigDecimal(units[0][2])).add(new BigDecimal(units[0][3])).subtract(new BigDecimal(units[1][3])).divide(new BigDecimal(units[1][2]),context);
        rows.put(new JSONObject().put("value",result.toPlainString()).put("unit",units[1][0]).put("dimension",units[1][1]).put("decimal_precision",50).put("source",units[0][4]));
    }
    private StructuredKnowledge(){}
}
