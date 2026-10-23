package de.bonsai.offline;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.InflaterInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded offline graph operations. No SQL, filenames or SQL identifiers from the model. */
final class WikidataKnowledge {
    static final int MAX_ROWS=20, MAX_PATH=3, MAX_FRONTIER=64, MAX_CLASSES=128;
    static final int APPLICATION_ID=1113017159;
    private static final int MAX_REPLY=6000;
    private static final long TIMEOUT_MS=2000;
    private static final String BEST="s.rank>0 AND (s.rank=2 OR NOT EXISTS (SELECT 1 FROM statement p "
        +"WHERE p.subject=s.subject AND p.property=s.property AND p.rank=2))";
    private static final String COLUMNS="s.id,s.subject,s.property,s.rank,s.kind,s.target,s.amount,s.unit,"
        +"s.time,s.precision,s.calendar,CASE WHEN length(s.detail)<=32768 THEN s.detail END,s.number";
    private static final Set<String> KEYS=new HashSet<>(Arrays.asList("op","entity","property","value","unit"));
    private static final ScheduledExecutorService WATCHDOG=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"wikidata-query-cancel");t.setDaemon(true);return t;
    });
    private final File database;

    WikidataKnowledge(File database) {this.database=database;}
    static WikidataKnowledge forStore(ChatStore store) throws IOException {
        File internal=new File(store.getReadableDatabase().getPath()).getCanonicalFile().getParentFile().getParentFile();
        try {
            java.util.List<KnowledgePackages.Installed> packages=KnowledgePackages.active(new File(internal,"files"),"wikidata");
            if(packages.size()>1)throw new IOException("Mehrere Wikidata-Pakete aktiv; bitte einen Teilbestand wählen.");
            if(packages.size()==1)return new WikidataKnowledge(new File(packages.get(0).directory,"knowledge.sqlite"));
            for(KnowledgePackages.Installed item:KnowledgePackages.installed(new File(internal,"files")))if(item.type().equals("wikidata"))throw new IOException("Wikidata-Paket ist deaktiviert.");
        } catch(IOException e){throw e;}catch(Exception e){throw new IOException("Wikidata-Paketstatus ungültig.",e);}
        File database=new File(internal,"files/wikidata/knowledge.sqlite").getCanonicalFile();
        if(!database.getPath().startsWith(internal.getPath()+File.separator))throw new IOException("Wikidata-Pfad außerhalb des internen Speichers.");
        return new WikidataKnowledge(database);
    }

    JSONObject query(JSONObject args,BooleanSupplier cancelled) throws Exception {
        for(java.util.Iterator<String> it=args.keys();it.hasNext();)if(!KEYS.contains(it.next()))
            throw new IllegalArgumentException("Unbekanntes Wikidata-Argument.");
        String op=text(args,"op",16,true),entity=text(args,"entity",256,true);
        String property=text(args,"property",256,false),value=text(args,"value",256,false),unit=text(args,"unit",64,false);
        Set<String> expected=new HashSet<>(Arrays.asList("op","entity"));
        switch(op) {
            case "resolve": break;
            case "facts": case "path": expected.add("property");break;
            case "greater": expected.addAll(Arrays.asList("property","value","unit"));break;
            case "largest": expected.addAll(Arrays.asList("property","unit"));break;
            case "count": expected.addAll(Arrays.asList("property","value"));break;
            default: throw new IllegalArgumentException("Wikidata: resolve, facts, path, greater, largest oder count verwenden.");
        }
        Set<String> actual=new HashSet<>();args.keys().forEachRemaining(actual::add);
        if(!expected.equals(actual))throw new IllegalArgumentException("Wikidata-Argumente passen nicht zur Operation "+op+".");
        check(cancelled);
        if(!database.isFile())throw new IOException("Wikidata-Datenbank fehlt: files/wikidata/knowledge.sqlite bereitstellen.");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,
                SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            try(Session session=new Session(db,cancelled)) {
                session.scalar("PRAGMA cache_size=-4096");
                session.scalar("PRAGMA temp_store=FILE");
                if(!"1113017159".equals(session.scalar("PRAGMA application_id")) ||
                   !"1".equals(session.scalar("PRAGMA user_version")))throw new IOException("Unbekanntes Wikidata-Datenbankformat.");
                if(!"true".equals(session.scalar("SELECT value FROM metadata WHERE key='complete'")))
                    throw new IOException("Wikidata-Import ist unvollständig.");
                String date=session.scalar("SELECT value FROM metadata WHERE key='dump_date'");
                try {java.time.LocalDate.parse(date);}catch(RuntimeException e){throw new IOException("Wikidata-Datenstand fehlt oder ist ungültig.",e);}
                JSONObject result=new JSONObject().put("dataset_date",date)
                    .put("coverage","selected_local_subset").put("op",op);
                try {
                    if(op.equals("resolve")) {
                        JSONArray candidates=session.candidates(entity,null);
                        return boundCandidates(result.put("status",candidates.length()==0?"not_found":candidates.length()==1?"resolved":"ambiguous"),candidates);
                    }
                    long subject=session.resolve(entity,false);
                    JSONArray rows;
                    if(op.equals("path")) {
                        String[] path=property.split("/",-1);
                        if(path.length<1 || path.length>MAX_PATH)throw new IllegalArgumentException("Pfad muss 1 bis 3 Properties enthalten.");
                        Set<Long> frontier=new LinkedHashSet<>();frontier.add(subject);
                        JSONArray edges=new JSONArray();
                        for(String step:path) {
                            long prop=session.resolve(step,true);
                            Set<Long> next=new LinkedHashSet<>();
                            for(long node:frontier) {
                                try(Cursor c=session.raw("SELECT "+COLUMNS+" FROM statement s WHERE s.subject=? AND s.property=? AND s.kind='entity' AND "+BEST+" ORDER BY s.id LIMIT 65",
                                        strings(node,prop))) {
                                    while(c.moveToNext()) {
                                        if(!"entity".equals(c.getString(4)))continue;
                                        if(edges.length()>=MAX_FRONTIER || next.size()>=MAX_FRONTIER)
                                            throw new IOException("Pfad zu breit; präziser abfragen.");
                                        next.add(c.getLong(5));edges.put(session.statement(c));
                                    }
                                }
                            }
                            frontier=next;
                        }
                        rows=new JSONArray();
                        for(long node:frontier)rows.put(session.entity(node));
                        result.put("edges",edges);
                    } else {
                        long prop=session.resolve(property,true);
                        switch(op) {
                            case "facts": rows=session.facts(subject,prop);break;
                            case "greater": case "largest":
                                long unitId=unit.equals("1")?0:session.resolve(unit,false);
                                rows=session.numbers(subject,prop,unitId,op.equals("greater")?value:null);
                                result.put("unit",unitId==0?"1":id(unitId))
                                    .put("numeric_scope","finite_float64_index; exact_decimal_comparison_and_tie_sort");break;
                            default:
                                rows=session.count(subject,prop,session.resolve(value,false));
                                result.put("count_scope","only_matching_entities_in_local_subset; not_total_known_moons");
                        }
                    }
                    boolean more=rows.length()>MAX_ROWS;
                    while(rows.length()>MAX_ROWS)rows.remove(rows.length()-1);
                    result.put("status",rows.length()==0?"no_local_facts":"ok").put("rows",rows).put("more",more);
                    while(result.toString().length()>MAX_REPLY && rows.length()>0) {
                        rows.remove(rows.length()-1);result.put("more",true);
                    }
                    // A wide path's evidence must never be silently removed.
                    if(result.toString().length()>MAX_REPLY)throw new IOException("Pfad-Ergebnis zu groß; kürzeren Pfad abfragen.");
                    check(cancelled);
                    return result;
                } catch(Resolution e) {
                    return boundCandidates(result.put("status",e.candidates.length()==0?"not_found":"ambiguous")
                        .put("input",e.input),e.candidates);
                }
            }
        } catch(OperationCanceledException e) {
            check(cancelled);throw new IOException("Wikidata-Abfrage hat das Zeitlimit erreicht.",e);
        }
    }

    private static JSONObject boundCandidates(JSONObject result,JSONArray candidates) throws Exception {
        result.put("candidates",candidates).put("more",candidates.length()>MAX_ROWS);
        while(candidates.length()>MAX_ROWS || (result.toString().length()>MAX_REPLY && candidates.length()>1)) {
            candidates.remove(candidates.length()-1);result.put("more",true);
        }
        return result;
    }

    private static String text(JSONObject args,String key,int max,boolean required) throws Exception {
        if(!args.has(key) && !required)return "";
        Object value=args.get(key);
        if(!(value instanceof String) || ((String)value).trim().isEmpty() || ((String)value).length()>max)
            throw new IllegalArgumentException("Ungültiges Wikidata-Argument: "+key);
        return ((String)value).trim();
    }
    static String normalize(String value) {
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder();boolean space=false;
        for(int i=0;i<normalized.length();) {
            int cp=normalized.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){space=out.length()>0;continue;}
            if(space){out.append(' ');space=false;}out.appendCodePoint(cp);
        }
        return out.toString();
    }
    static long key(String value) {
        if(!value.matches("[QP][1-9][0-9]*"))throw new IllegalArgumentException("Ungültige Wikidata-ID.");
        try {long number=Long.parseLong(value.substring(1));return value.charAt(0)=='Q'?number:-number;}
        catch(NumberFormatException e){throw new IllegalArgumentException("Wikidata-ID zu groß.");}
    }
    static String id(long value) {return value<0?"P"+(-value):"Q"+value;}
    private static String[] strings(long... values) {
        String[] args=new String[values.length];for(int i=0;i<values.length;i++)args[i]=Long.toString(values[i]);return args;
    }
    private static void check(BooleanSupplier cancelled) {if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");}
    private static String bounded(String value,int max) {return value.length()>max?value.substring(0,max):value;}
    private static final class Resolution extends Exception {
        final String input;final JSONArray candidates;
        Resolution(String input,JSONArray candidates){this.input=input;this.candidates=candidates;}
    }

    private static final class Session implements AutoCloseable {
        final SQLiteDatabase db;final BooleanSupplier cancelled;final CancellationSignal signal=new CancellationSignal();
        final long deadline=System.nanoTime()+TIMEOUT_MS*1_000_000;
        final ScheduledFuture<?> watchdog;
        Session(SQLiteDatabase db,BooleanSupplier cancelled) {
            this.db=db;this.cancelled=cancelled;
            watchdog=WATCHDOG.scheduleWithFixedDelay(()->{
                try {if(cancelled.getAsBoolean() || System.nanoTime()>deadline)signal.cancel();}
                catch(RuntimeException e){signal.cancel();}
            },10,10,TimeUnit.MILLISECONDS);
        }
        void checkBudget() throws IOException {
            check(cancelled);
            if(System.nanoTime()>deadline)throw new IOException("Wikidata-Abfrage hat das Zeitlimit erreicht; präziser abfragen.");
        }
        Cursor raw(String sql,String[] args) throws IOException {
            checkBudget();
            try {return db.rawQuery(sql,args,signal);}
            catch(OperationCanceledException e){check(cancelled);throw new IOException("Wikidata-Abfrage abgebrochen oder Zeitlimit erreicht.",e);}
        }
        String scalar(String sql) throws IOException {
            try(Cursor c=raw(sql,new String[0])){return c.moveToFirst()?c.getString(0):"";}
        }
        JSONObject entity(long entity) throws Exception {
            JSONObject result=new JSONObject().put("id",id(entity));
            try(Cursor c=raw("SELECT kind,value,lang FROM term WHERE entity=? AND kind IN (0,2) ORDER BY lang,kind",strings(entity))) {
                while(c.moveToNext()) {
                    String field=c.getInt(0)==0?"label":"description";
                    if(!result.has(field) && !c.getString(1).isEmpty())result.put(field,bounded(c.getString(1),field.equals("label")?160:300));
                }
            }
            return result;
        }
        JSONArray candidates(String text,Boolean property) throws Exception {
            JSONArray result=new JSONArray();
            if(text.matches("[QP][1-9][0-9]*")) {
                long candidate=key(text);
                try(Cursor c=raw("SELECT id FROM entity WHERE id=?",strings(candidate))) {
                    if(c.moveToFirst() && (property==null || (candidate<0)==property))result.put(entity(candidate));
                }
                return result;
            }
            String kind=property==null?"":property?" AND entity<0":" AND entity>0";
            try(Cursor c=raw("SELECT DISTINCT entity FROM term WHERE norm=? AND kind IN (0,1)"+kind+" ORDER BY entity LIMIT 21",
                    new String[]{normalize(text)})) {
                while(c.moveToNext())result.put(entity(c.getLong(0)));
            }
            return result;
        }
        long resolve(String input,boolean property) throws Exception {
            JSONArray matches=candidates(input,property);
            if(matches.length()!=1)throw new Resolution(input,matches);
            return key(matches.getJSONObject(0).getString("id"));
        }
        JSONObject statement(Cursor c) throws Exception {
            checkBudget();
            JSONObject row=new JSONObject().put("statement_id",c.getLong(0)).put("entity",entity(c.getLong(1)))
                .put("property",entity(c.getLong(2))).put("rank",c.getInt(3)==2?"preferred":"normal").put("kind",c.getString(4));
            switch(c.getString(4)) {
                case "entity": row.put("value",entity(c.getLong(5)));break;
                case "quantity": row.put("amount",c.getString(6)).put("unit",c.getLong(7)==0?"1":entity(c.getLong(7)));break;
                case "time": row.put("time",c.getString(8)).put("precision",c.getInt(9)).put("calendar",entity(c.getLong(10)));break;
                default: break;
            }
            JSONObject detail=detail(c.isNull(11)?null:c.getBlob(11));
            if(detail==null)row.put("detail_omitted",true);
            else {
                row.put("guid",detail.opt("guid"));
                JSONObject mainsnak=detail.optJSONObject("mainsnak");
                if(mainsnak!=null && !"entity".equals(c.getString(4)))row.put("datavalue",mainsnak.opt("datavalue"));
                for(String field:new String[]{"qualifiers","references"}) {
                    Object v=detail.opt(field);
                    if(v!=null && v.toString().length()<=1600)row.put(field,v);
                    else row.put(field+"_omitted",true);
                }
            }
            return row;
        }
        JSONObject detail(byte[] compressed) throws Exception {
            if(compressed==null)return null;
            try(InflaterInputStream in=new InflaterInputStream(new ByteArrayInputStream(compressed));
                    ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[1024];int n;
                while((n=in.read(buffer))!=-1) {
                    if(out.size()+n>16384)return null;
                    out.write(buffer,0,n);
                }
                return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        }
        JSONArray facts(long subject,long property) throws Exception {
            JSONArray rows=new JSONArray();
            try(Cursor c=raw("SELECT "+COLUMNS+" FROM statement s WHERE s.subject=? AND s.property=? AND "+BEST+" ORDER BY s.id LIMIT 21",strings(subject,property))) {
                while(c.moveToNext())rows.put(statement(c));
            }
            return rows;
        }
        Set<Long> classes(long root) throws Exception {
            Set<Long> all=new LinkedHashSet<>(),frontier=new LinkedHashSet<>();all.add(root);frontier.add(root);
            for(int depth=0;!frontier.isEmpty();depth++) {
                if(depth>=8)throw new IOException("Klassenhierarchie zu tief; spezifischere Klasse wählen.");
                Set<Long> next=new LinkedHashSet<>();
                for(long target:frontier)try(Cursor c=raw("SELECT DISTINCT s.subject FROM statement s WHERE s.property=-279 AND s.target=? AND s.kind='entity' AND "+BEST+" LIMIT 129",strings(target))) {
                    while(c.moveToNext())if(all.add(c.getLong(0))) {
                        if(all.size()>MAX_CLASSES)throw new IOException("Klasse zu breit; spezifischere Klasse wählen.");
                        next.add(c.getLong(0));
                    }
                }
                frontier=next;
            }
            return all;
        }
        String classFilter(String subject,Set<Long> classes,List<String> args) {
            StringBuilder placeholders=new StringBuilder();
            for(long c:classes){if(placeholders.length()>0)placeholders.append(',');placeholders.append('?');args.add(Long.toString(c));}
            return "EXISTS (SELECT 1 FROM statement t WHERE t.subject="+subject+" AND t.property=-31 AND t.kind='entity' AND t.target IN ("+placeholders+") "
                +"AND t.rank>0 AND (t.rank=2 OR NOT EXISTS(SELECT 1 FROM statement p WHERE p.subject=t.subject AND p.property=-31 AND p.rank=2)))";
        }
        JSONArray numbers(long type,long property,long unit,String greater) throws Exception {
            List<String> args=new ArrayList<>();args.add(Long.toString(property));args.add(Long.toString(unit));
            BigDecimal threshold=null;
            String range="";
            if(greater!=null) {
                try {threshold=new BigDecimal(greater);if(threshold.precision()>100 || Math.abs((long)threshold.scale())>300)throw new NumberFormatException();}
                catch(NumberFormatException e){throw new IllegalArgumentException("Ungültiger Zahlenvergleich.");}
                double bound=threshold.doubleValue();if(!Double.isFinite(bound))throw new IllegalArgumentException("Zahl außerhalb des Indexbereichs.");
                double lower=Math.nextDown(bound);
                range=" AND s.number>=?";args.add(Double.toString(Double.isFinite(lower)?lower:-Double.MAX_VALUE));
            }
            String filter=classFilter("s.subject",classes(type),args);
            List<JSONObject> candidates=new ArrayList<>();
            Double last=null;
            try(Cursor c=raw("SELECT "+COLUMNS+" FROM statement s WHERE s.property=? AND s.unit=? AND s.kind='quantity' "
                    +"AND s.number IS NOT NULL "+range+" AND "+BEST+" AND "+filter+" ORDER BY s.number DESC,s.subject,s.id LIMIT 257",args.toArray(new String[0]))) {
                int scanned=0;
                while(c.moveToNext()) {
                    double current=c.getDouble(12);
                    // Include the complete REAL tie group before exact-decimal sorting.
                    if(candidates.size()>MAX_ROWS && last!=null && current<last)break;
                    if(++scanned>256)throw new IOException("Zu viele numerische Kandidaten; präziser abfragen.");
                    if(threshold==null || new BigDecimal(c.getString(6)).compareTo(threshold)>0)candidates.add(statement(c));
                    last=current;
                }
            }
            candidates.sort((a,b)->new BigDecimal(b.optString("amount")).compareTo(new BigDecimal(a.optString("amount"))));
            JSONArray rows=new JSONArray();for(JSONObject row:candidates)rows.put(row);
            return rows;
        }
        JSONArray count(long targetType,long property,long sourceType) throws Exception {
            Set<Long> targets=classes(targetType),sources=classes(sourceType);
            List<String> args=new ArrayList<>();args.add(Long.toString(property));
            String source=classFilter("s.subject",sources,args),target=classFilter("s.target",targets,args);
            JSONArray rows=new JSONArray();
            try(Cursor c=raw("SELECT s.target,COUNT(DISTINCT s.subject) AS n FROM statement s WHERE s.property=? AND s.kind='entity' AND "+BEST
                    +" AND "+source+" AND "+target+" GROUP BY s.target ORDER BY n DESC,s.target LIMIT 21",args.toArray(new String[0]))) {
                while(c.moveToNext())rows.put(new JSONObject().put("entity",entity(c.getLong(0))).put("local_count",c.getLong(1)));
            }
            return rows;
        }
        @Override public void close(){watchdog.cancel(false);}
    }
}
