PRAGMA application_id=1113017159;
PRAGMA user_version=1;
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
-- Positive integers are Q IDs, negative integers P IDs. Zero means unitless.
CREATE TABLE entity(id INTEGER PRIMARY KEY, datatype TEXT, revision INTEGER, modified TEXT,
                    loaded INTEGER NOT NULL DEFAULT 0);
CREATE TABLE term(entity INTEGER NOT NULL, lang TEXT NOT NULL, kind INTEGER NOT NULL,
                  value TEXT NOT NULL, norm TEXT,
                  PRIMARY KEY(entity,lang,kind,value)) WITHOUT ROWID;
-- kind: 0 label, 1 alias, 2 description. Descriptions do not enter the name index.
CREATE INDEX term_name ON term(norm,entity) WHERE kind IN (0,1);
CREATE TABLE statement(id INTEGER PRIMARY KEY, subject INTEGER NOT NULL,
 property INTEGER NOT NULL, rank INTEGER NOT NULL, kind TEXT NOT NULL,
 target INTEGER, amount TEXT, number REAL, unit INTEGER, time TEXT,
 precision INTEGER, calendar INTEGER, detail BLOB NOT NULL);
CREATE INDEX statement_forward ON statement(subject,property,rank);
CREATE INDEX statement_reverse ON statement(property,target,subject) WHERE kind='entity';
CREATE INDEX statement_number ON statement(property,unit,number,subject) WHERE kind='quantity';
