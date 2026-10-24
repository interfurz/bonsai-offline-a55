from contextlib import closing
import sqlite3
import unittest
from rag.fts4 import search,score


class Fts4RankingTests(unittest.TestCase):
    def test_common_question_words_cannot_bury_rare_subject_under_arbitrary_ids(self):
        with closing(sqlite3.connect(':memory:')) as db:
            db.execute('CREATE VIRTUAL TABLE chunks_fts4 USING fts4(title,heading,text,tokenize=unicode61)')
            for id in range(1,31):db.execute('INSERT INTO chunks_fts4(rowid,title,heading,text) VALUES(?,?,?,?)',(id,'Unrelated','Intro','was ist die und wenn Menschen'))
            db.execute("INSERT INTO chunks_fts4(rowid,title,heading,text) VALUES(100,'Antenne','Polarisation','Wenn die Polarisation nicht übereinstimmt sinkt die Signalstärke')")
            changes=db.total_changes;ids,metrics=search(db,'Was ist die Polarisation einer Antenne?')
            self.assertEqual(100,ids[0]);self.assertNotIn('die',metrics['terms']);self.assertEqual(changes,db.total_changes)
            self.assertEqual([],search(db,'zzzunbekannt')[0])
            with self.assertRaises(ValueError):score(b'broken')
