package de.bonsai.offline;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Portable bounded ranking. Algorithm and limits match rag/fts4.py. */
final class Fts4Search {
    static final int MAX_ROWS=65536,MAX_TERMS=12;
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP=new HashSet<>(Arrays.asList(("der die das den dem des ein eine einer eines einem einen und oder aber auch als am an auf aus bei bis durch für gegen hinter im in ins mit nach neben ohne seit um unter vom von vor zum zur zu über ist sind war waren wird werden wurde wurden hat haben hatte hatten sein seine seiner sich sie es er ihr ihre ihren ihnen ich du wir man nicht noch nur so sowie wenn dann dass da was wer wen wem wessen wie wo wann warum weshalb wieso welche welcher welches welchen welchem viel viele vielen heißt heissen heißen nennt genannt werden kann können könnte soll sollten muss müssen the a an and or of to in on for with is are was were be been by from at that this it its as which what who when where why how do does did can could would should").split(" ")));
    private static final class Term {final String word;final long frequency;Term(String word,long frequency){this.word=word;this.frequency=frequency;}}
    private static final class Hit {final long id;final double score;Hit(long id,double score){this.id=id;this.score=score;}}
    private static final Comparator<Hit> BEST=Comparator.<Hit>comparingDouble(hit->-hit.score).thenComparingLong(hit->hit.id);
    private static long[] values(byte[] blob) throws IOException {
        if(blob==null || blob.length%4!=0 || blob.length>4*(9+9*MAX_TERMS))throw new IOException("Ungültige FTS4-Statistik.");
        long[] values=new long[blob.length/4];ByteBuffer data=ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder());
        for(int i=0;i<values.length;i++)values[i]=Integer.toUnsignedLong(data.getInt());return values;
    }
    static double score(byte[] blob) throws IOException {
        long[] data=values(blob);if(data.length<3)throw new IOException("Unvollständige FTS4-Statistik.");
        int phrases=(int)data[0],columns=(int)data[1];long total=data[2];
        if(phrases<1 || phrases>MAX_TERMS || columns!=3 || data.length!=3+columns*2+phrases*columns*3)throw new IOException("Unbekanntes FTS4-Statistikformat.");
        double result=0.,average=0.,length=0.;
        for(int column=0;column<columns;column++){average+=data[3+column];length+=data[3+columns+column];}average=Math.max(1.,average);
        for(int phrase=0;phrase<phrases;phrase++) {
            long hits=0,documents=0;int offset=3+columns*2+phrase*columns*3;
            for(int column=0;column<columns;column++){hits+=data[offset+column*3];documents=Math.max(documents,data[offset+column*3+2]);}
            if(hits==0)continue;
            double idf=Math.max(1e-6,Math.log(Math.max(1e-30,(total-documents+.5)/(documents+.5))));
            result+=idf*hits*2.2/(hits+1.2*(.25+.75*length/average));
        }
        return result;
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException("Abgebrochen.");}
    static List<Long> search(SQLiteDatabase db,String query,BooleanSupplier cancelled) throws IOException {
        LinkedHashSet<String> words=new LinkedHashSet<>();Matcher matcher=WORD.matcher(Normalizer.normalize(query,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        for(int i=0;i<32 && matcher.find();i++)if(!STOP.contains(matcher.group()))words.add(matcher.group());
        List<Term> terms=new ArrayList<>();
        for(String word:words) {
            check(cancelled);
            try(Cursor c=db.rawQuery("SELECT matchinfo(chunks_fts4,'pcx') FROM chunks_fts4 WHERE chunks_fts4 MATCH ? LIMIT 1",new String[]{"\""+word+"\""})) {
                if(c.moveToFirst()) {
                    long[] stats=values(c.getBlob(0));if(stats.length!=11 || stats[0]!=1 || stats[1]!=3)throw new IOException("Unbekannte FTS4-Termstatistik.");
                    terms.add(new Term(word,Math.max(stats[4],Math.max(stats[7],stats[10]))));
                }
            }
        }
        terms.sort(Comparator.<Term>comparingLong(term->term.frequency).thenComparing(term->term.word));
        if(terms.isEmpty())return new ArrayList<>();
        StringBuilder match=new StringBuilder();for(int i=0;i<Math.min(MAX_TERMS,terms.size());i++){if(i>0)match.append(" OR ");match.append('"').append(terms.get(i).word).append('"');}
        PriorityQueue<Hit> heap=new PriorityQueue<>(BEST.reversed());
        try(Cursor c=db.rawQuery("SELECT rowid,matchinfo(chunks_fts4,'pcnalx') FROM chunks_fts4 WHERE chunks_fts4 MATCH ? ORDER BY rowid LIMIT 65536",new String[]{match.toString()})) {
            while(c.moveToNext()) {
                check(cancelled);Hit hit=new Hit(c.getLong(0),score(c.getBlob(1)));
                if(heap.size()<20)heap.add(hit);else if(BEST.compare(hit,heap.peek())<0){heap.remove();heap.add(hit);}
            }
        }
        List<Hit> hits=new ArrayList<>(heap);hits.sort(BEST);List<Long> ids=new ArrayList<>();for(Hit hit:hits)ids.add(hit.id);return ids;
    }
    private Fts4Search(){}
}
