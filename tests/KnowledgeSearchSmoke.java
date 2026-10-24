package de.bonsai.offline;

import java.io.File;
import org.json.JSONArray;

/** Run with Android app_process: host JVM regex accepts flags that Android rejects. */
public final class KnowledgeSearchSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Path to the RAG fixture directory required.");
        File directory=new File(args[0]);
        boolean failed=false;
        for(int attempt=1;attempt<=2;attempt++) {
            try {
                KnowledgeSearch search=new KnowledgeSearch(new File(directory,"knowledge.sqlite"),null);
                JSONArray hits=search.search("München",()->false).getJSONArray("hits");
                if(hits.length()==0 || !hits.getJSONObject(0).getString("title").equals("München"))
                    throw new AssertionError("Unicode lexical search failed.");
                if(search.search("Quantenphysik",()->false).getJSONArray("hits").length()!=0)
                    throw new AssertionError("Expected no quantum physics entries in the fixture.");
                // Python HashEncoder reference: German, Chinese and Arabic-Indic digits.
                float[] expected=new float[256];
                expected[106]=1;expected[200]=1;expected[207]=1;expected[254]=-1;
                if(!java.util.Arrays.equals(expected,new KnowledgeSearch.SampleEncoder().encode("Überblick Quantenphysik 量子物理 ١٢٣")))
                    throw new AssertionError("Unicode fixture encoding differs from Python.");
                System.out.println("KnowledgeSearch attempt "+attempt+": passed");
            } catch(LinkageError error) {
                // Check the retry too: a failed initializer poisons the class for this process.
                failed=true;error.printStackTrace();
            }
        }
        if(failed)throw new AssertionError("KnowledgeSearch class initialization failed on Android.");
    }
    private KnowledgeSearchSmoke() {}
}
