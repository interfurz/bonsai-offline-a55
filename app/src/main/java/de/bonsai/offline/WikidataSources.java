package de.bonsai.offline;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic provenance survives the final generation, as with existing search sources. */
final class WikidataSources {
    private final Set<String> ids=new LinkedHashSet<>();
    private String date;
    private boolean counted;
    void collect(JSONObject reply) throws Exception {
        if(!reply.optBoolean("ok") || !"wikidata".equals(reply.optString("name")))return;
        JSONObject result=reply.getJSONObject("result");
        if(!"ok".equals(result.optString("status")))return;
        date=result.getString("dataset_date");counted|=result.has("count_scope");
        for(String field:new String[]{"rows","edges"}) {
            JSONArray rows=result.optJSONArray(field);if(rows==null)continue;
            for(int i=0;i<rows.length() && ids.size()<6;i++) {
                JSONObject entity=rows.getJSONObject(i).optJSONObject("entity");
                if(entity==null)entity=rows.getJSONObject(i);
                String id=entity.optString("id");
                if(id.matches("Q[1-9][0-9]*"))ids.add(id);
            }
        }
    }
    ToolLoop.Result finish(ToolLoop.Result answer) {
        if(date==null)return answer;
        StringBuilder footer=new StringBuilder("\n\nWikidata: lokaler Teilbestand, Dump-Stand ").append(date).append('.');
        if(counted)footer.append(" Zählungen gelten nur für diesen Teilbestand, nicht für die Gesamtzahl bekannter Monde.");
        for(String id:ids)footer.append("\nhttps://www.wikidata.org/wiki/").append(id);
        return new ToolLoop.Result(answer.text+footer,answer.stats);
    }
}
