"""Prepare a documented direct-P31 subset using verified official class IDs.

API data only prepares the selection and its P279 ancestor list. Productive
facts still come exclusively from the separately checksum-verified dated dump.
"""
import json
from pathlib import Path
import time
from urllib.parse import urlencode
from .common import atomic_json,digest
from .download import json_document

ROOTS={'Q6256':'country','Q3624078':'sovereign state','Q5107':'continent','Q515':'city',
       'Q4022':'river','Q8502':'mountain','Q23397':'lake','Q165':'sea','Q9430':'ocean',
       'Q11344':'chemical element','Q173227':'physical constant','Q107715':'physical quantity',
       'Q223662':'SI base unit','Q47574':'unit of measurement','Q208469':'SI derived unit',
       'Q65943':'theorem','Q11538':'mathematical proof','Q8366':'algorithm','Q9143':'programming language',
       'Q271680':'software framework','Q9135':'operating system','Q175263':'data structure',
       'Q11345':'equation','Q28640':'profession','Q634':'planet','Q2537':'natural satellite',
       'Q214070':'physical law','Q24034552':'mathematical concept','Q11348':'function','Q12136':'disease'}
SEEDS=['Q17','Q183','Q30','Q142','Q145','Q148','Q159','Q937','Q935','Q7186','Q9036','Q1035','Q9021','Q7099','Q7251','Q7259','Q41585','Q6722','Q7604','Q243','Q2','Q525','Q405','Q111','Q319','Q193','Q324','Q332','Q313','Q308','Q339','Q121750','Q3012','Q64','Q1490']
PROPERTIES=['P31','P279','P17','P131','P36','P47','P1082','P2046','P625','P569','P570','P571','P576',
            'P580','P582','P585','P577','P2048','P2049','P2067','P1086','P274','P2101','P2102','P2054',
            'P186','P527','P361','P138','P19','P20','P106','P800','P101','P40','P26','P27','P21',
            'P275','P178','P277','P306','P348','P2534','P397']


def prepare(root,output,date):
    __import__('datetime').date.fromisoformat(date)
    output=Path(output)
    if output.exists():raise FileExistsError(output)
    folder=Path(root)/'manifests'/('wikidata-profile-'+date);folder.mkdir(parents=True,exist_ok=True)
    objects={};requests=[]
    def fetch(ids):
        missing=sorted(set(ids)-objects.keys())
        for start in range(0,len(missing),50):
            batch=missing[start:start+50]
            url='https://www.wikidata.org/w/api.php?'+urlencode(dict(action='wbgetentities',format='json',ids='|'.join(batch),props='info|labels|descriptions|claims|datatype',languages='de|en'))
            import hashlib
            file=folder/(hashlib.sha256(url.encode()).hexdigest()+'.json')
            result=json_document(url,file)
            if 'error' in result:raise ValueError('Wikidata API error: '+str(result['error']))
            for id in batch:
                obj=result['entities'].get(id)
                if not obj or 'missing' in obj:raise ValueError('Missing explicit profile ID '+id)
                objects[id]=obj
            requests.append(dict(url=url,sha256=digest(file)))
            time.sleep(.5)
    fetch(set(ROOTS)|set(SEEDS)|set(PROPERTIES))
    for id,expected in ROOTS.items():
        actual=objects[id].get('labels',{}).get('en',{}).get('value')
        if actual!=expected:raise ValueError('Class identity changed: '+id+' '+str(actual))
    for id,expected in {'Q3012':'Ulm','Q64':'Berlin','Q1490':'Tokyo','Q937':'Albert Einstein','Q17':'Japan','Q183':'Germany'}.items():
        if objects[id].get('labels',{}).get('en',{}).get('value')!=expected:raise ValueError('Critical path entity changed: '+id)
    ancestors=set(ROOTS)|{'Q121750'};pending=set(ancestors);visited=set()
    while pending:
        fetch(pending)
        next_ids=set()
        for id in pending:
            for claim in objects[id].get('claims',{}).get('P279',[]):
                target=claim.get('mainsnak',{}).get('datavalue',{}).get('value',{}).get('id')
                if target and target.startswith('Q') and claim.get('rank')!='deprecated':next_ids.add(target)
        visited.update(pending);ancestors.update(next_ids)
        if len(ancestors)>2048:raise ValueError('Unexpectedly large ancestor selection; review before importing')
        pending=next_ids-visited
    config=dict(entities=sorted(set(SEEDS)|ancestors),classes=sorted(ROOTS),exclude_classes=[],properties=PROPERTIES,qualifiers=True,references=True)
    from wikidata.pipeline import validate_config
    validate_config(config)
    atomic_json(output,config)
    receipt=dict(date=date,license='CC0 Wikidata structured data',scope='Direct P31 selection; explicitly listed P279 ancestors. Not all descendants, people, taxa, stars or publications.',
                 source_dump_required='20261019 checksum-verified all.json.bz2; API claims are not imported into the productive DB',
                 roots={id:objects[id]['labels']['en']['value'] for id in ROOTS},properties={id:objects[id].get('labels',{}).get('en',{}).get('value') for id in PROPERTIES},
                 explicit_entities=len(config['entities']),class_roots=len(ROOTS),profile_sha256=digest(output),requests=requests)
    atomic_json(str(output)+'.sources.json',receipt)
    return receipt
