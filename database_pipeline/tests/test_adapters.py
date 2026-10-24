from contextlib import closing
from decimal import Decimal
import gzip
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET

from database_pipeline.dictionary import entries,normalize,ingest,lookup
from database_pipeline.stackexchange import markdown,select,export,rows,tags
from database_pipeline.tables import products,constants,convert
from database_pipeline.packages import build as package
from rag.corpus import build


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    def test_dictionary_language_sense_forms_translation_and_umlauts(self):
        text='== Baum ({{Sprache|Deutsch}}) ==\n=== {{Wortart|Substantiv|Deutsch}} ===\n{{Deutsch Substantiv Übersicht|Genus=m|Nominativ Singular=Baum|Nominativ Plural=Bäume}}\n{{Bedeutungen}}\n:[1] verholzte Pflanze\n{{Gegenwörter}}\n:[1] [[Kraut]]\n==== Übersetzungen ====\n{{Ü-Tabelle|1|Ü-Liste={{Ü|en|tree}}}}'
        result=entries(text)[0]
        self.assertEqual(('Deutsch','Substantiv'),(result['language'],result['pos']))
        self.assertEqual('verholzte Pflanze',result['data']['definitions'][0]['text'])
        self.assertEqual('1',result['data']['translations'][0]['sense'])
        self.assertIn({'value':'Bäume','feature':'Nominativ Plural'},result['data']['forms'])
        self.assertEqual(normalize(' BÄUME '),normalize('Ba\u0308ume'))

    def test_local_model_lock_detects_same_size_mutation_and_wrong_revision(self):
        from database_pipeline.model import verify_local
        from database_pipeline.common import digest
        folder=self.root/'model';folder.mkdir();files=[]
        for name in ('model.safetensors','tokenizer.json','tokenizer.model','config.json','modules.json'):
            file=folder/name;file.write_bytes(b'fixture bytes');files.append(dict(file=name,bytes=file.stat().st_size,sha256=digest(file)))
        (folder/'model-lock.json').write_text(json.dumps(dict(model='google/embeddinggemma-2',revision='f'*40,files=files)),encoding='utf-8')
        self.assertEqual('f'*40,verify_local(folder,'f'*40)['revision'])
        with self.assertRaisesRegex(ValueError,'revision'):verify_local(folder,'a'*40)
        (folder/'tokenizer.json').write_bytes(b'corrupt bytes')
        with self.assertRaisesRegex(ValueError,'checksum'):verify_local(folder,'f'*40)

    def test_wikidata_entry_refuses_bad_source_proof_before_creating_a_generation(self):
        from unittest.mock import patch
        from database_pipeline.__main__ import main
        source=self.root/'dump.bz2';source.write_bytes(b'fixture')
        receipt=self.root/'receipt.json';receipt.write_text(json.dumps(dict(upstream_verified=True,bytes=7,sha256='0'*64,url='https://dumps.wikimedia.org/wikidatawiki/entities/20261019/wikidata-20261019-all.json.bz2')),encoding='utf-8')
        output=self.root/'kg.sqlite'
        with patch('sys.argv',['database_pipeline','wikidata-import',str(source),str(output),'--config',str(self.root/'unused-profile.json'),'--receipt',str(receipt),'--date','2026-10-19']),patch('wikidata.pipeline.build',side_effect=AssertionError('unverified dump reached importer')):
            with self.assertRaisesRegex(ValueError,'whole-file verification'):main()
        self.assertFalse(output.exists());self.assertFalse(output.with_name(output.name+'.building').exists())

    def test_disk_reserve_stop_retains_committed_import_for_resume(self):
        from unittest.mock import patch
        source=self.root/'articles.jsonl'
        source.write_text('\n'.join(json.dumps(dict(id=str(n),title='Artikel '+str(n),source='fixture://'+str(n),text='Ein langer fachlicher Inhalt mit Beispielen. '*10)) for n in (1,2)),encoding='utf-8')
        output=self.root/'corpus.sqlite'
        with patch('database_pipeline.common.require_space',side_effect=OSError('Insufficient disk')):
            with self.assertRaisesRegex(OSError,'Insufficient disk'):build(source,output,commit_every=1,progress=False,disk_reserve_bytes=1024**3)
        with closing(sqlite3.connect(output)) as db:self.assertEqual(1,db.execute('SELECT count(*) FROM articles').fetchone()[0])
        result=build(source,output,resume=True,progress=False)
        self.assertEqual(2,result['articles']);self.assertEqual(0,result['errors'])

    def test_dictionary_upgrade_preserves_original_resumes_and_resolves_variant_chain(self):
        from unittest.mock import patch
        from database_pipeline.common import digest
        from database_pipeline.dictionary_upgrade import upgrade
        source=self.root/'dictionary.xml';root=ET.Element('mediawiki')
        for number,title,text,target in [(1,'Straße','== Straße ({{Sprache|Deutsch}}) ==\n=== {{Wortart|Substantiv|Deutsch}} ===\n{{Bedeutungen}}\n:[1] befestigter Verkehrsweg',None),(2,'Strasse','== Strasse ({{Sprache|Deutsch}}) ==\n{{Schweizer und Liechtensteiner Schreibweise|Straße}}',None),(3,'Strassen','#WEITERLEITUNG [[Strasse]]','Strasse'),(4,'Rätsel','ungegliederter Quelltext',None)]:
            page=ET.SubElement(root,'page');ET.SubElement(page,'title').text=title;ET.SubElement(page,'id').text=str(number);ET.SubElement(page,'ns').text='0'
            if target:ET.SubElement(page,'redirect',title=target)
            rev=ET.SubElement(page,'revision');ET.SubElement(rev,'id').text=str(number+10);ET.SubElement(rev,'text').text=text
        source.write_bytes(ET.tostring(root,encoding='utf-8'))
        original=self.root/'old.sqlite';ingest(source,original,date='2026-10-01')
        with closing(sqlite3.connect(original)) as db,db:
            db.executescript("DROP TABLE lexical_alias;DROP TABLE unparsed;DROP INDEX entry_page;DELETE FROM page WHERE id='3';")
        old_hash=digest(original);output=self.root/'new.sqlite'
        with patch('database_pipeline.dictionary_upgrade.require_space',side_effect=[100*1024**3,OSError('Insufficient disk')]):
            with self.assertRaisesRegex(OSError,'Insufficient disk'):upgrade(source,original,output,dump_sha256=digest(source))
        self.assertFalse(output.exists())
        result=upgrade(source,original,output,dump_sha256=digest(source),resume=True)
        self.assertEqual(old_hash,digest(original));self.assertEqual(0,result['validation']['pages_without_any_lookup'])
        self.assertEqual('Straße',lookup(output,'Strassen','Deutsch')[0]['lemma'])
        self.assertEqual('befestigter Verkehrsweg',lookup(output,'Strasse','Deutsch')[0]['data']['definitions'][0]['text'])

    def test_stack_selection_context_code_math_attribution_and_shared_ingestion(self):
        posts=self.root/'Posts.xml';root=ET.Element('posts')
        for row in [dict(Id='1',PostTypeId='1',Score='20',AcceptedAnswerId='3',Title='How to fix Python?',Body='<p>Why is this Python code failing?</p>',Tags='|python|python-3.x|',OwnerUserId='5',ContentLicense='CC BY-SA 4.0'),
                    dict(Id='2',PostTypeId='1',Score='1',AcceptedAnswerId='4',Title='Low quality',Body='bad'),
                    dict(Id='3',PostTypeId='2',ParentId='1',Score='10',Body='<p>Use the function below. Keep $x^2$.</p><pre><code>if a &lt; b:\n    print(a)</code></pre>',OwnerUserId='6',ContentLicense='CC BY-SA 4.0')]:ET.SubElement(root,'row',row)
        posts.write_bytes(ET.tostring(root));users=self.root/'Users.xml';users.write_text('<users><row Id="5" DisplayName="Author Q"/><row Id="6" DisplayName="Author A"/></users>',encoding='utf-8')
        selected=self.root/'selected.sqlite';self.assertEqual(dict(questions=1,answers=1),select(posts,selected,site='stackoverflow.com'))
        output=self.root/'posts.jsonl';export(selected,output,site='stackoverflow.com',users=users)
        row=json.loads(output.read_text(encoding='utf-8'));self.assertTrue(row['post_metadata']['accepted']);self.assertEqual('Author A',row['post_metadata']['answer_author']['name'])
        self.assertEqual(['python','python-3.x'],row['post_metadata']['tags'])
        self.assertIn('if a < b:\n    print(a)',row['text']);self.assertIn('$x^2$',row['text'])
        database=self.root/'corpus.sqlite';result=build(output,database,wiki='stackexchange',progress=False)
        self.assertGreater(result['chunks'],0)
        with closing(sqlite3.connect(database)) as db:self.assertEqual(1,db.execute('SELECT count(*) FROM post_metadata').fetchone()[0])
        with closing(sqlite3.connect(database)) as db,db:
            old=json.loads(db.execute('SELECT data_json FROM post_metadata').fetchone()[0]);old['tags']=[]
            db.execute('UPDATE post_metadata SET data_json=?',(json.dumps(old),))
        from database_pipeline.repair import stack_tags
        upgraded=self.root/'tags-upgraded.sqlite';self.assertEqual(1,stack_tags(database,upgraded)['tag_upgrade']['updated'])
        with closing(sqlite3.connect(upgraded)) as db:self.assertEqual(['python','python-3.x'],json.loads(db.execute('SELECT data_json FROM post_metadata').fetchone()[0])['tags'])
        with closing(sqlite3.connect(database)) as db:self.assertEqual([],json.loads(db.execute('SELECT data_json FROM post_metadata').fetchone()[0])['tags'])

    def test_stack_tag_formats_and_empty_production_selection_are_not_silently_accepted(self):
        self.assertEqual(['c#','sql'],tags('|c#|sql|'))
        self.assertEqual(['c++','python'],tags('&lt;c++&gt;&lt;python&gt;'))
        with self.assertRaises(ValueError):tags('unsupported;encoding')
        posts=self.root/'empty.xml';posts.write_text('<posts><row Id="1" PostTypeId="1" Score="1" Tags="|python|"/></posts>',encoding='utf-8')
        output=self.root/'empty-selected.sqlite'
        with self.assertRaisesRegex(ValueError,'no eligible questions'):select(posts,output,site='stackoverflow.com')
        self.assertFalse(output.exists())

    def test_nutrition_keeps_unknown_separate_from_zero_and_rejects_invalid(self):
        source=self.root/'off.jsonl.gz'
        with gzip.open(source,'wt',encoding='utf-8') as stream:
            for code,protein in [('1',None),('2',0),('3',-3)]:stream.write(json.dumps({'code':code,'product_name':'Haferflocken '+code,'countries_tags':['en:germany'],'nutriments':{'proteins_100g':protein,'fat_100g':5,'energy_100g':1560}})+'\n')
        output=self.root/'off.sqlite';self.assertEqual(3,products(source,output,date='2026-10-23')['products'])
        with closing(sqlite3.connect(output)) as db:
            self.assertEqual([('1',None,'missing'),('2','0','reported'),('3',None,'invalid_negative')],db.execute("SELECT food_id,value,status FROM nutrient WHERE name='proteins' ORDER BY food_id").fetchall())
            self.assertEqual(3,db.execute("SELECT count(*) FROM food_fts WHERE food_fts MATCH 'Haferflocken'").fetchone()[0])
            self.assertEqual(3,db.execute("SELECT count(*) FROM nutrient WHERE name='energy-kj' AND value='1560'").fetchone()[0])

    def test_archive_stream_two_pass_selection_matches_xml_without_extraction(self):
        import py7zr
        source=self.root/'Posts.xml'
        source.write_text('<posts><row Id="1" PostTypeId="1" Score="9" AcceptedAnswerId="2"/><row Id="2" PostTypeId="2" ParentId="1" Score="8"/></posts>',encoding='utf-8')
        archive=self.root/'posts.7z'
        with py7zr.SevenZipFile(archive,'w') as target:target.write(source,arcname='Posts.xml')
        expected=list(rows(source));source.unlink()
        self.assertEqual(expected,list(rows(archive)))
        self.assertFalse(source.exists())
        self.assertEqual(dict(questions=1,answers=1),select(archive,self.root/'selected.sqlite',site='physics.stackexchange.com'))
        with self.assertRaisesRegex(ValueError,'Missing archive member'):list(rows(archive,member='Users.xml'))

    def test_top_question_budget_keeps_late_high_scores_and_stable_ties(self):
        posts=self.root/'Posts.xml'
        with posts.open('w',encoding='utf-8') as stream:
            stream.write('<posts>')
            for number in range(1,10004):stream.write(f'<row Id="{number}" PostTypeId="1" Score="{100 if number>10000 else 5}" AcceptedAnswerId="{100000+number}"/>')
            for number in range(10001,10004):stream.write(f'<row Id="{100000+number}" PostTypeId="2" ParentId="{number}" Score="2"/>')
            stream.write('</posts>')
        output=self.root/'selected.sqlite'
        self.assertEqual(dict(questions=2,answers=2),select(posts,output,site='physics.stackexchange.com',max_questions=2))
        with closing(sqlite3.connect(output)) as db:
            self.assertEqual([(10001,),(10002,)],db.execute('SELECT id FROM question ORDER BY id').fetchall())
            self.assertEqual('10003',dict(db.execute('SELECT * FROM metadata'))['quality_eligible_questions'])

    def test_noncontent_repair_keeps_original_errors_and_rejects_content_errors(self):
        from database_pipeline.common import digest
        from database_pipeline.repair import noncontent
        source=self.root/'books.xml'
        source.write_text('<mediawiki><page><title>Book/styles.css</title><ns>0</ns><id>7</id><revision><id>9</id><model>sanitized-css</model><text>.nav { color: red; }</text></revision></page></mediawiki>',encoding='utf-8')
        original=self.root/'old.sqlite';build(source,original,wiki='dewikibooks',progress=False,dump_sha256=digest(source))
        # Reproduce the older importer checkpoint, which treated this ancillary
        # page as an attempted article with a conversion error.
        with closing(sqlite3.connect(original)) as db,db:
            state=json.loads(db.execute("SELECT value FROM metadata WHERE key='ingest_checkpoint'").fetchone()[0])
            state.update(attempted=1,errors=1,skipped=0)
            db.execute('DELETE FROM skipped_pages')
            db.execute('INSERT INTO ingest_errors VALUES(1,?,?,?)',('7','Book/styles.css','ValueError: unsupported content model'))
            db.execute("UPDATE metadata SET value=? WHERE key='ingest_checkpoint'",(json.dumps(state),))
        old_hash=digest(original);output=self.root/'repaired.sqlite'
        self.assertEqual(1,noncontent(source,original,output)['reclassified_noncontent_pages'])
        self.assertEqual(old_hash,digest(original))
        with closing(sqlite3.connect(output)) as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM repair_audit').fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM ingest_errors').fetchone()[0])
        with closing(sqlite3.connect(original)) as db,db:db.execute("UPDATE ingest_errors SET title='Different article'")
        with self.assertRaisesRegex(ValueError,'not a proven ancillary'):noncontent(source,original,self.root/'refused.sqlite')

    def test_constants_exact_uncertainty_truncated_values_and_unit_dimensions(self):
        source=self.root/'codata.txt';lines=['2022 CODATA adjustment']
        for n in range(355):
            value='1.234... e-3' if n==0 else '299 792 458' if n==1 else '6.644 657 3450 e-27'
            uncertainty='(exact)' if n<2 else '0.000 000 0021 e-27'
            lines.append(f'{"fixture "+str(n):<60}{value:<25}{uncertainty:<25}m')
        source.write_text('\n'.join(lines),encoding='ascii');output=self.root/'constants.sqlite';self.assertEqual(355,constants(source,output,date='2026-10-23')['constants'])
        self.assertEqual('28382191417742400',convert(output,'3','Lichtjahre','m')['value'])
        self.assertEqual('273.15',convert(output,'0','Celsius','K')['value'])
        self.assertEqual(Decimal('1000000000'),Decimal(convert(output,'1','Ms','ms')['value']))
        self.assertEqual('0.001',convert(output,'1','J','kJ')['value'])
        self.assertEqual(Decimal('1E+30'),Decimal(convert(output,'1','Qm','m')['value']))
        with self.assertRaisesRegex(ValueError,'case'):convert(output,'1','MM','m')
        with self.assertRaisesRegex(ValueError,'dimensions'):convert(output,'1','m','kg')
        with self.assertRaisesRegex(ValueError,'dimensions'):convert(output,'1','Gy','Sv')
        with closing(sqlite3.connect(output)) as db:self.assertEqual(('1.234...e-3',None,'exact_truncated_display'),db.execute("SELECT value_text,decimal_text,status FROM reference_value WHERE name='fixture 0'").fetchone())
        result=package(output,self.root/'constants.bonsai',package_id='constants-test',version='v1',kind='tables',source=dict(dataset='self-authored fixture',url='fixture://constants',date='2026-10-23',license='test fixture'))
        self.assertGreater(result['bytes'],0)
        with self.assertRaises(FileExistsError):package(output,self.root/'constants.bonsai',package_id='constants-test',version='v2',kind='tables',source=dict(dataset='fixture',url='fixture://constants',date='2026-10-23',license='fixture'))
