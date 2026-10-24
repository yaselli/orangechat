"""Exercise the production usage migration and aggregate SQL without an Android SDK."""
import json
import re
import sqlite3
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub'
schema_dir = root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase'
migration = (source / 'data/db/migrations/Migration_38_39.kt').read_text()
dao = (source / 'data/db/dao/UsageRecordDAO.kt').read_text()
statements = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', migration, re.S)]
assert len(statements) == 2
assert 'Migration_38_39,' in (source / 'di/DataSourceModule.kt').read_text()


def create_v38():
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    for entity in json.loads((schema_dir / '38.json').read_text())['database']['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return db


def query(name):
    matches = re.findall(r'@Query\((.*?)\)\s*(?:suspend\s+)?fun\s+(\w+)', dao, re.S)
    body = next(body for body, method in matches if method == name)
    return ''.join(json.loads('"' + value + '"') for value in re.findall(r'"((?:\\.|[^"\\])*)"', body))


db = create_v38()
db.execute("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES('chat','owner','title','[]',1,2)")
db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'owner','旧记忆🌶')")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'[{\"parts\":[]}]',0)")
db.execute("INSERT INTO message_reaction VALUES('reaction','chat','message','user','❤️','原话',1)")
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
before = {table: (list(db.execute(f'PRAGMA table_info("{table}")')), list(db.execute(f'SELECT * FROM "{table}"')))
          for table in tables}
db.commit()
db.execute('BEGIN')
for statement in statements:
    db.execute(statement)
db.rollback()
assert not db.execute("SELECT name FROM sqlite_master WHERE name='usage_record'").fetchall()
for statement in statements:
    db.execute(statement)
for table, (columns, rows) in before.items():
    assert list(db.execute(f'PRAGMA table_info("{table}")')) == columns
    assert list(db.execute(f'SELECT * FROM "{table}"')) == rows
assert not db.execute('PRAGMA foreign_key_list(usage_record)').fetchall()
assert db.execute(query('getTotals'), {'since': 0}).fetchone() == (0, 0, 0, 0)
print('PASS: v38→v39 preserves all existing tables and rows; failed migration rolls back atomically')

midnight = int(time.mktime(time.strptime('2026-10-23', '%Y-%m-%d')) * 1000)


def insert(at, source, provider='provider', prompt=20):
    db.execute('INSERT INTO usage_record(created_at,source,provider_id,model_id,model_name,assistant_id,'
               'conversation_id,prompt_tokens,completion_tokens,cached_tokens) VALUES(?,?,?,?,?,?,?,?,?,?)',
               (at, source, provider, 'model', 'deleted-model', 'owner', 'chat', prompt, 5, 10))


insert(midnight - 1, 'CHAT')
insert(midnight, 'CHAT')
insert(midnight + 1, 'TRANSLATION', 'another')
assert db.execute(query('getTotals'), {'since': midnight}).fetchone() == (2, 40, 10, 20)
assert db.execute(query('getTotalsByBucket'), {'since': midnight, 'format': '%Y-%m-%d'}).fetchall() == [
    ('2026-10-23', 40, 10, 20)]
models = db.execute(query('getTotalsByModel'), {'since': midnight}).fetchall()
assert {row[0] for row in models} == {'provider', 'another'}
assert all(row[2] == 'deleted-model' for row in models)
assert {row[0] for row in db.execute(query('getTotalsBySource'), {'since': midnight})} == {'CHAT', 'TRANSLATION'}
db.execute("DELETE FROM ConversationEntity WHERE id='chat'")
assert db.execute(query('getTotals'), {'since': 0}).fetchone() == (3, 60, 15, 30)
insert(midnight, 'MEMORY', prompt=2**31-1)
insert(midnight, 'MEMORY', prompt=2**31-1)
assert db.execute(query('getTotals'), {'since': 0}).fetchone()[1] == 60 + 2 * (2**31-1)
print('PASS: date boundaries, input/cache semantics, sources, deleted model snapshots, provider isolation, chat deletion and 64-bit sums')

if (schema_dir / '39.json').exists():
    entity = next(item for item in json.loads((schema_dir / '39.json').read_text())['database']['entities']
                  if item['tableName'] == 'usage_record')
    expected = sqlite3.connect(':memory:')
    expected.execute(entity['createSql'].replace('${TABLE_NAME}', 'usage_record'))
    for index in entity.get('indices', []):
        expected.execute(index['createSql'].replace('${TABLE_NAME}', 'usage_record'))
    for pragma in ['table_info(usage_record)', 'foreign_key_list(usage_record)', 'index_list(usage_record)',
                   'index_info(index_usage_record_created_at)']:
        assert list(db.execute('PRAGMA ' + pragma)) == list(expected.execute('PRAGMA ' + pragma))
    print('PASS: production migration matches compiler-exported Room v39 usage schema')
