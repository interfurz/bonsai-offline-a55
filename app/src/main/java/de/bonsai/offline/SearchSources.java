package de.bonsai.offline;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Trusted provenance from retrieval, appended only after the final generation. */
final class SearchSources {
    static final int MAX_SOURCES=(ToolLoop.MAX_ROUNDS-1)*KnowledgeSearch.MAX_HITS;
    private final Map<String,JSONObject> sources=new LinkedHashMap<>();
    void collect(JSONObject reply) throws Exception {
        if(!reply.optBoolean("ok") || !"suche".equals(reply.optString("name")))return;
        JSONObject result=reply.getJSONObject("result");
        JSONArray hits=result.getJSONArray("hits"),bounded=new JSONArray();
        for(int i=0;i<hits.length();i++) {
            JSONObject hit=hits.getJSONObject(i);String id=hit.optString("package_id","legacy")+":"+hit.getLong("chunk_id");
            if(!sources.containsKey(id) && sources.size()>=MAX_SOURCES)continue;
            sources.putIfAbsent(id,hit);
            int citation=1;for(String existing:sources.keySet()){if(existing.equals(id))break;citation++;}
            hit.put("citation",citation);bounded.put(hit);
        }
        result.put("hits",bounded);
    }
    ToolLoop.Result finish(ToolLoop.Result answer) {
        if(sources.isEmpty())return answer;
        StringBuilder cited=new StringBuilder(),all=new StringBuilder();int index=0;
        for(JSONObject hit:sources.values()) {
            index++;
            String line="\n["+index+"] "+singleLine(hit.optString("heading"))+" — "+singleLine(hit.optString("source"));
            JSONObject attribution=hit.optJSONObject("attribution");
            if(attribution!=null)for(String key:new String[]{"question_author","answer_author"}) {
                JSONObject author=attribution.optJSONObject(key);
                if(author!=null){String name=author.optString("name");line+=" · "+singleLine(name.substring(0,Math.min(80,name.length())))+" ("+singleLine(author.optString("url"))+", "+singleLine(author.optString("license"))+")";}
            }
            all.append(line);
            if(answer.text.contains("["+index+"]"))cited.append(line);
        }
        String footer=cited.length()>0?"\n\nQuellen:"+cited:"\n\nSuchquellen:"+all;
        return new ToolLoop.Result(answer.text+footer,answer.stats);
    }
    private static String singleLine(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}]"," ").replace('<','‹').replace('>','›').trim();
    }
}
