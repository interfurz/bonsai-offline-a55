package de.bonsai.offline;

import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Opt-in deterministic title/heading coverage boost on existing retrieval candidates. */
final class TitleReranker {
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    static JSONArray rank(JSONArray candidates,String query,int maximum) throws Exception {
        Set<String> terms=tokens(query);List<Integer> order=new ArrayList<>();Map<Integer,Double> scores=new HashMap<>();
        for(int i=0;i<candidates.length();i++) {
            JSONObject hit=candidates.getJSONObject(i);Set<String> title=tokens(hit.optString("title")),heading=tokens(hit.optString("heading"));
            double boost=0;for(String term:terms){if(title.contains(term))boost+=.02;if(heading.contains(term))boost+=.01;}
            scores.put(i,1.0/(20+i)+boost/Math.max(1,terms.size()));order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble(i->-scores.get(i)).thenComparingInt(i->i));
        JSONArray result=new JSONArray();for(int i:order.subList(0,Math.min(maximum,order.size())))result.put(new JSONObject(candidates.getJSONObject(i).toString()));return result;
    }
    private static Set<String> tokens(String text){Set<String> result=new HashSet<>();Matcher matcher=WORD.matcher(text.toLowerCase(Locale.ROOT));while(matcher.find()&&result.size()<32)result.add(matcher.group());return result;}
    private TitleReranker(){}
}
