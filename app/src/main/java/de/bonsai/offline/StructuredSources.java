package de.bonsai.offline;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.*;
final class StructuredSources {
    private final Set<String> sources=new LinkedHashSet<>();
    void collect(JSONObject reply) throws Exception {
        if(!reply.optBoolean("ok") || !(reply.optString("name").equals("woerterbuch") || reply.optString("name").equals("tabellen")))return;
        JSONArray list=reply.getJSONObject("result").optJSONArray("sources");
        if(list==null)return;
        for(int i=0;i<list.length() && sources.size()<8;i++){JSONObject item=list.getJSONObject(i);sources.add(item.optString("package_id")+" · Stand "+item.optString("date")+" · "+item.optString("url"));}
        JSONArray rows=reply.getJSONObject("result").optJSONArray("rows");
        if(rows!=null)for(int i=0;i<rows.length() && sources.size()<8;i++){String source=rows.getJSONObject(i).optString("source");if(!source.isEmpty())sources.add(source);}
    }
    ToolLoop.Result finish(ToolLoop.Result result) {
        if(sources.isEmpty())return result;
        return new ToolLoop.Result(result.text+"\n\nDatenquellen: "+String.join("; ",sources).replaceAll("[\\p{Cc}\\p{Cf}]"," ").replace('<','‹').replace('>','›'),result.stats);
    }
}
