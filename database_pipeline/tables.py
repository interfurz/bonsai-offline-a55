"""Typed constants, exact unit transforms and nullable product nutrients."""
from contextlib import closing
from decimal import Decimal, localcontext
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata

from .common import digest, publish
from rag.corpus import _writer_lock, _fingerprint

SCHEMA = """
PRAGMA user_version=1;
PRAGMA application_id=1112820044;
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE reference_value(id TEXT PRIMARY KEY,kind TEXT NOT NULL,name TEXT NOT NULL,norm TEXT NOT NULL,
 value_text TEXT,decimal_text TEXT,unit TEXT,uncertainty_text TEXT,exact INTEGER NOT NULL,status TEXT NOT NULL,source TEXT NOT NULL,source_version TEXT NOT NULL);
CREATE INDEX reference_name ON reference_value(norm,kind);
CREATE TABLE reference_alias(norm TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(norm,id)) WITHOUT ROWID;
CREATE TABLE unit(symbol TEXT PRIMARY KEY,dimension TEXT NOT NULL,scale TEXT NOT NULL,offset TEXT NOT NULL,source TEXT NOT NULL);
CREATE TABLE unit_alias(norm TEXT PRIMARY KEY,symbol TEXT NOT NULL);
CREATE TABLE food(id TEXT PRIMARY KEY,name TEXT NOT NULL,norm TEXT NOT NULL,brands TEXT NOT NULL,basis TEXT NOT NULL,source TEXT NOT NULL,modified TEXT,raw_nutrients TEXT NOT NULL);
CREATE INDEX food_name ON food(norm,id);
CREATE VIRTUAL TABLE food_fts USING fts4(id,name,brands,tokenize=unicode61);
CREATE TABLE nutrient(food_id TEXT NOT NULL,name TEXT NOT NULL,value TEXT,unit TEXT NOT NULL,status TEXT NOT NULL,PRIMARY KEY(food_id,name)) WITHOUT ROWID;
"""
NIST='https://physics.nist.gov/cuu/Constants/Table/allascii.txt'
BIPM='https://www.bipm.org/en/measurement-units/si-defining-constants'
IAU='https://iauarchive.eso.org/public/themes/measuring/'


def norm(value):return ' '.join(unicodedata.normalize('NFKC',value).lower().split())


def number(value):
    if value is None or isinstance(value,bool):return None
    try:
        d=Decimal(str(value))
        return str(d) if d.is_finite() else None
    except (ValueError,ArithmeticError):return None


def constants(source,output,*,date):
    output=Path(output).resolve();output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists():raise FileExistsError(output)
    stage=output.with_name(output.name+'.building')
    with _writer_lock(output):
        if stage.exists():raise FileExistsError(stage)
        with closing(sqlite3.connect(stage)) as db,db:
            db.executescript(SCHEMA)
            text=Path(source).read_text(encoding='ascii')
            version=re.search(r'(20\d\d) CODATA adjustment',text)
            if not version:raise ValueError('Unrecognized CODATA release')
            count=0
            for line in text.splitlines():
                if len(line)<85 or not re.search(r'\d',line[60:85]):continue
                name,value,uncertainty,unit=line[:60].strip(),line[60:85].strip(),line[85:110].strip(),line[110:].strip()
                if not name or name.startswith(('Quantity','---')):continue
                raw=value.replace(' ','');exact=uncertainty=='(exact)'
                numeric=number(raw)
                if numeric is None and '...' not in raw:raise ValueError('Unparsed CODATA quantity: '+line)
                status='exact_truncated_display' if '...' in raw else 'exact_defined' if exact else 'measured'
                uncertainty_value='0' if exact else number(uncertainty.replace(' ',''))
                if uncertainty_value is None:raise ValueError('Invalid CODATA uncertainty: '+name)
                db.execute('INSERT INTO reference_value VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',('codata:'+hashlib.sha256(name.encode()).hexdigest()[:24],'constant',name,norm(name),raw,numeric,unit,uncertainty_value,int(exact),status,NIST,'CODATA '+version[1]))
                count+=1
            aliases={'speed of light in vacuum':['Lichtgeschwindigkeit','c'],'Planck constant':['Planck-Konstante','Plancksches Wirkungsquantum'],'Boltzmann constant':['Boltzmann-Konstante'],'Avogadro constant':['Avogadro-Konstante'],'Newtonian constant of gravitation':['Gravitationskonstante'],'elementary charge':['Elementarladung']}
            for english,words in aliases.items():
                row=db.execute('SELECT id FROM reference_value WHERE norm=?',(norm(english),)).fetchone()
                if row:
                    db.executemany('INSERT INTO reference_alias VALUES(?,?)',[(norm(word),row[0]) for word in words])
            with localcontext() as context:
                context.prec=80
                coefficient=426880*Decimal(10005).sqrt();m=1;linear=13591409;power=1;k=6;series=Decimal(linear)
                for iteration in range(1,8):
                    m=m*(k*k*k-16*k)//(iteration*iteration*iteration)
                    linear+=545140134;power*=-262537412640768000
                    series+=Decimal(m*linear)/Decimal(power);k+=12
                context.prec=50
                math_values=[('pi',+(coefficient/series),['π','Kreiszahl'],'Chudnovsky series, 8 terms'),('e',Decimal(1).exp(),['Eulersche Zahl'],'Decimal exp(1)')]
                for name,value,words,algorithm in math_values:
                    db.execute('INSERT INTO reference_value VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',('math:'+name,'constant',name,norm(name),str(value),str(value),'1',None,1,'exact_definition_decimal_approximation','computed: '+algorithm,'50 significant decimal digits'))
                    db.executemany('INSERT INTO reference_alias VALUES(?,?)',[(norm(word),'math:'+name) for word in words])
            if count<300:raise ValueError('CODATA parsing unexpectedly incomplete')
            units=[('m','length','1','0',['meter','metre','Meter'],BIPM),('km','length','1000','0',['Kilometer'],BIPM),('cm','length','.01','0',['Zentimeter'],BIPM),('mm','length','.001','0',['Millimeter'],BIPM),
                   ('ly','length','9460730472580800','0',['Lichtjahr','Lichtjahre','light-year','Lj'],IAU),('au','length','149597870700','0',['astronomische Einheit','AE'],IAU),
                   ('s','time','1','0',['Sekunde','Sekunden'],BIPM),('min','time','60','0',['Minute','Minuten'],BIPM),('h','time','3600','0',['Stunde','Stunden'],BIPM),('d','time','86400','0',['Tag','Tage'],IAU),('a_j','time','31557600','0',['julianisches Jahr'],IAU),
                   ('kg','mass','1','0',['Kilogramm'],BIPM),('g','mass','.001','0',['Gramm'],BIPM),('mg','mass','.000001','0',['Milligramm'],BIPM),
                   ('K','temperature','1','0',['Kelvin'],BIPM),('degC','temperature','1','273.15',['Celsius','°C'],BIPM)]
            from .units import extend
            units=extend(units)
            for symbol,dimension,scale,offset,aliases,url in units:
                db.execute('INSERT INTO unit VALUES(?,?,?,?,?)',(symbol,dimension,scale,offset,url))
                for alias in set(aliases+[symbol]):db.execute('INSERT OR IGNORE INTO unit_alias VALUES(?,?)',(norm(alias),symbol))
            db.executemany('INSERT INTO metadata VALUES(?,?)',[('complete','true'),('dump_date',date),('source_sha256',digest(source)),('dataset','CODATA '+version[1]+'; SI/IAU defined unit transforms'),('license','NIST US federal government data; factual SI/IAU definitions')])
            db.commit()
            if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:raise ValueError('Constants integrity failure')
        publish(stage,output)
        return dict(constants=count,mathematical_constants=2,units=len(units),bytes=output.stat().st_size)


def convert(database,value,source,target):
    value=number(value)
    if value is None or len(value)>128:raise ValueError('Finite bounded decimal required')
    with closing(sqlite3.connect(Path(database).resolve().as_uri()+'?mode=ro',uri=True)) as db:
        rows=[]
        for symbol in (source,target):
            canonical=unicodedata.normalize('NFKC',symbol).strip()
            row=db.execute('SELECT symbol,dimension,scale,offset,source FROM unit WHERE symbol=?',(canonical,)).fetchone()
            if not row:
                row=db.execute('SELECT u.symbol,u.dimension,u.scale,u.offset,u.source FROM unit u JOIN unit_alias a ON a.symbol=u.symbol WHERE a.norm=?',(norm(symbol),)).fetchone()
                if row and norm(symbol)==norm(row[0]) and canonical!=unicodedata.normalize('NFKC',row[0]):raise ValueError('Unit symbol case is significant')
            if not row:raise ValueError('Unknown unit: '+symbol)
            rows.append(row)
        a,b=rows
        if a[1]!=b[1]:raise ValueError('Incompatible unit dimensions')
        with localcontext() as ctx:
            ctx.prec=50
            result=(Decimal(value)*Decimal(a[2])+Decimal(a[3])-Decimal(b[3]))/Decimal(b[2])
        return dict(value=str(result),unit=b[0],dimension=b[1],decimal_precision=50,source=[a[4],b[4]])


def products(source,output,*,date,resume=False,country='en:germany',max_line_bytes=16*1024**2):
    """OFF JSONL export; replay compressed input to the committed line on resume."""
    output=Path(output).resolve();output.parent.mkdir(parents=True,exist_ok=True);stage=output.with_name(output.name+'.building')
    identity=json.dumps(dict(source=_fingerprint(source),country=country,date=date,version=1),sort_keys=True)
    nutrient_units={'energy-kj':'kJ','energy-kcal':'kcal','proteins':'g','carbohydrates':'g','fat':'g','fiber':'g','sugars':'g','salt':'g','sodium':'g'}
    with _writer_lock(output):
        if output.exists():raise FileExistsError(output)
        if stage.exists()!=resume:raise ValueError('Resume flag must match existing staging generation')
        with closing(sqlite3.connect(stage)) as db,db:
            if not resume:
                db.executescript(SCHEMA);db.executemany('INSERT INTO metadata VALUES(?,?)',[('identity',identity),('checkpoint','0'),('complete','false'),('dump_date',date),('license','ODbL-1.0; Open Food Facts contributors'),('coverage',country+' market products; missing nutrients remain NULL')]);db.commit()
            metadata=dict(db.execute('SELECT * FROM metadata'))
            if metadata['identity']!=identity:raise ValueError('Product source/selection changed')
            db.execute('PRAGMA cache_size=-16384');db.execute('PRAGMA journal_mode=WAL')
            after=int(metadata['checkpoint']);position=0
            with (gzip.open if str(source).endswith('.gz') else open)(source,'rb') as stream:
                while True:
                    line=stream.readline(max_line_bytes+1)
                    if not line:break
                    position+=1
                    if len(line)>max_line_bytes:raise ValueError('OFF product exceeds configured bounded line size')
                    if position<=after:continue
                    row=json.loads(line)
                    if country in row.get('countries_tags',[]):
                        code=str(row.get('code') or row.get('_id') or '')
                        name=row.get('product_name_de') or row.get('product_name') or row.get('generic_name_de') or row.get('generic_name')
                        if code and name:
                            nutrients=row.get('nutriments') or {}
                            # OFF *_100g can be per 100 ml for liquids; retain the
                            # declared basis rather than silently assuming grams.
                            basis='100 ml' if row.get('nutrition_data_per')=='100ml' else 'OFF *_100g (100 g or 100 ml; source does not distinguish reliably)'
                            db.execute('INSERT OR REPLACE INTO food VALUES(?,?,?,?,?,?,?,?)',(code,name,norm(name),row.get('brands',''),basis,'https://world.openfoodfacts.org/product/'+code,str(row.get('last_modified_t','')),json.dumps(nutrients,ensure_ascii=False)))
                            db.execute('DELETE FROM nutrient WHERE food_id=?',(code,))
                            fields=dict(nutrient_units)
                            for key in nutrients:
                                base=key.removesuffix('_100g')
                                if key.endswith('_100g') and (base.startswith('vitamin-') or base in ('calcium','iron','magnesium','zinc','potassium','phosphorus','iodine','selenium')):
                                    fields[base]='g'  # OFF normalizes these stored nutriments to grams.
                            for field,unit in fields.items():
                                raw_value=nutrients.get(field+'_100g')
                                if field=='energy-kj' and raw_value is None:raw_value=nutrients.get('energy_100g')
                                value=number(raw_value)
                                status='missing' if value is None else 'invalid_negative' if Decimal(value)<0 else 'reported'
                                if status=='invalid_negative':value=None
                                db.execute('INSERT INTO nutrient VALUES(?,?,?,?,?)',(code,field,value,unit,status))
                            db.execute('INSERT OR REPLACE INTO reference_value VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',('off:'+code,'food',name,norm(name),None,None,None,None,0,'product_record','https://world.openfoodfacts.org/product/'+code,date))
                    if position%5000==0:
                        db.execute("UPDATE metadata SET value=? WHERE key='checkpoint'",(str(position),));db.commit();print(json.dumps(dict(lines=position,bytes=stage.stat().st_size)),flush=True)
            db.execute('DELETE FROM food_fts')
            db.execute('INSERT INTO food_fts(id,name,brands) SELECT id,name,brands FROM food')
            db.execute("UPDATE metadata SET value='true' WHERE key='complete'");db.execute("UPDATE metadata SET value=? WHERE key='checkpoint'",(str(position),));db.commit()
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.execute('PRAGMA journal_mode=DELETE')
            if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:raise ValueError('OFF integrity failure')
            count=db.execute('SELECT count(*) FROM food').fetchone()[0]
        publish(stage,output)
        return dict(products=count,scanned=position,bytes=output.stat().st_size)


def upgrade_energy(source,output):
    """Create a new generation fixing the documented OFF energy_100g alias."""
    import shutil
    source,output=Path(source).resolve(),Path(output).resolve()
    if output.exists():raise FileExistsError(output)
    stage=output.with_name(output.name+'.building')
    with _writer_lock(output):
        if stage.exists():raise FileExistsError(stage)
        shutil.copyfile(source,stage);updated=0
        with closing(sqlite3.connect(stage)) as db,db:
            if dict(db.execute('SELECT * FROM metadata')).get('complete')!='true':raise ValueError('Source product import incomplete')
            cursor=db.execute("SELECT f.id,f.raw_nutrients FROM food f JOIN nutrient n ON n.food_id=f.id WHERE n.name='energy-kj' AND n.value IS NULL")
            while rows:=cursor.fetchmany(1000):
                for code,raw in rows:
                    value=number(json.loads(raw).get('energy_100g'))
                    if value is not None and Decimal(value)>=0:
                        db.execute("UPDATE nutrient SET value=?,status='reported_energy_100g_alias' WHERE food_id=? AND name='energy-kj'",(value,code));updated+=1
            db.execute("INSERT OR REPLACE INTO metadata VALUES('normalization_version','off-energy-alias-v2')")
            db.commit()
            if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:raise ValueError('Nutrition integrity failure')
            if db.execute('SELECT id FROM food EXCEPT SELECT id FROM food_fts LIMIT 1').fetchone() or db.execute('SELECT id FROM food_fts EXCEPT SELECT id FROM food LIMIT 1').fetchone():raise ValueError('Food FTS ID mismatch')
        publish(stage,output);return dict(updated_energy_records=updated,bytes=output.stat().st_size)
