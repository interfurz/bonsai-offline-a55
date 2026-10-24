"""Bounded FTS4 ranking, mirrored by Android Fts4Search (no FTS5 required).

Common question words must not turn an OR query into arbitrary low-rowid hits.
Choose up to twelve non-stop query terms using index statistics, then rank
at most 65536 matching rows with BM25 over the combined columns. This is approximate
lexical retrieval; report the candidate cap and evaluate the deployed profile.
"""
import heapq
import math
import re
import struct
import unicodedata

MAX_ROWS=65536
MAX_TERMS=12
PROFILE='fts4-bm25-v1'
STOP=set('der die das den dem des ein eine einer eines einem einen und oder aber auch als am an auf aus bei bis durch für gegen hinter im in ins mit nach neben ohne seit um unter vom von vor zum zur zu über ist sind war waren wird werden wurde wurden hat haben hatte hatten sein seine seiner sich sie es er ihr ihre ihren ihnen ich du wir man nicht noch nur so sowie wenn dann dass da was wer wen wem wessen wie wo wann warum weshalb wieso welche welcher welches welchen welchem viel viele vielen heißt heissen heißen nennt genannt werden kann können könnte soll sollten muss müssen the a an and or of to in on for with is are was were be been by from at that this it its as which what who when where why how do does did can could would should'.split())


def values(blob):
    if len(blob)%4:raise ValueError('Malformed FTS4 matchinfo')
    return struct.unpack('='+str(len(blob)//4)+'I',blob)


def score(blob):
    data=values(blob)
    if len(data)<3:raise ValueError('Truncated FTS4 matchinfo')
    phrases,columns,total=data[:3]
    if not 1<=phrases<=MAX_TERMS or columns!=3 or len(data)!=3+columns*2+phrases*columns*3:raise ValueError('Unexpected FTS4 matchinfo layout')
    result=0.;average=max(1,sum(data[3:3+columns]));length=sum(data[3+columns:3+columns*2])
    for phrase in range(phrases):
        offset=3+columns*2+phrase*columns*3
        hits=sum(data[offset+column*3] for column in range(columns))
        documents=max(data[offset+column*3+2] for column in range(columns))
        if not hits:continue
        idf=max(1e-6,math.log(max(1e-30,(total-documents+.5)/(documents+.5))))
        result+=idf*hits*2.2/(hits+1.2*(.25+.75*length/average))
    return result


def search(db,query,limit=20):
    if not 1<=limit<=100:raise ValueError('Bounded top-k required')
    words=[word for word in dict.fromkeys(re.findall(r'[^\W_]+',unicodedata.normalize('NFKC',query).lower())[:32]) if word not in STOP];frequencies=[]
    for word in words:
        row=db.execute("SELECT matchinfo(chunks_fts4,'pcx') FROM chunks_fts4 WHERE chunks_fts4 MATCH ? LIMIT 1",('"'+word+'"',)).fetchone()
        if row:
            stats=values(row[0]);columns=stats[1]
            if stats[0]!=1 or columns!=3 or len(stats)!=2+columns*3:raise ValueError('Unexpected FTS4 term statistics')
            frequencies.append((max(stats[4+column*3] for column in range(columns)),word))
    frequencies.sort()
    if not frequencies:return [],dict(profile=PROFILE,terms=[],scanned=0,candidate_cap=MAX_ROWS,possibly_truncated=False)
    terms=[word for frequency,word in frequencies][:MAX_TERMS]
    match=' OR '.join('"'+word+'"' for word in terms)
    heap=[];scanned=0
    for identity,blob in db.execute("SELECT rowid,matchinfo(chunks_fts4,'pcnalx') FROM chunks_fts4 WHERE chunks_fts4 MATCH ? ORDER BY rowid LIMIT 65536",(match,)):
        item=(score(blob),-identity);scanned+=1
        if len(heap)<limit:heapq.heappush(heap,item)
        elif item>heap[0]:heapq.heapreplace(heap,item)
    return [-identity for _,identity in sorted(heap,reverse=True)],dict(profile=PROFILE,terms=terms,scanned=scanned,candidate_cap=MAX_ROWS,possibly_truncated=scanned==MAX_ROWS)
