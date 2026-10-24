"""Verify the production v40→v41 migration, retention SQL, deletion, paging order and rollback."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub'
schemas = root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase'
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in json.loads((schemas / '40.json').read_text())['database']['entities']:
    for sql in [entity['createSql']] + [i['createSql'] for i in entity.get('indices', [])]:
        db.execute(sql.replace('${TABLE_NAME}', entity['tableName']))
db.execute("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at,context_compaction,conversation_config) "
           "VALUES('chat','owner','title','[]',1,2,'old-summary','old-config')")
db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'owner','旧记忆🌶')")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'[{\"id\":\"selected\"}]',0)")
db.execute("INSERT INTO message_reaction VALUES('reaction','chat','selected','user','❤️','原话',1)")
db.execute("INSERT INTO usage_record(created_at,source,prompt_tokens,completion_tokens,cached_tokens) VALUES(1,'CHAT',42,3,0)")
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
before = {t: (list(db.execute(f'PRAGMA table_info("{t}")')), list(db.execute(f'SELECT * FROM "{t}"'))) for t in tables}
migration = (source / 'data/db/migrations/Migration_40_41.kt').read_text()
statements = [a or b for a,b in re.findall(r'db\.execSQL\((?:"""(.*?)"""(?:\.trimIndent\(\))?|"([^"\n]+)")\)', migration, re.S)]
assert len(statements) == 2
assert 'Migration_40_41,' in (source / 'di/DataSourceModule.kt').read_text()
db.commit()
db.execute('BEGIN')
for sql in statements: db.execute(sql)
db.rollback()
assert not db.execute("SELECT name FROM sqlite_master WHERE name='translation_history'").fetchall()
for sql in statements: db.execute(sql)
for table, (columns, rows) in before.items():
    assert list(db.execute(f'PRAGMA table_info("{table}")')) == columns, table
    assert list(db.execute(f'SELECT * FROM "{table}"')) == rows, table
assert not db.execute('PRAGMA foreign_key_list(translation_history)').fetchall()
print('PASS: v40→v41 preserves every old column/record and rolls back atomically')

def query(method):
    dao = (source / 'data/db/dao/TranslationHistoryDAO.kt').read_text()
    body = next(b for b,m in re.findall(r'@Query\((.*?)\)\s*(?:suspend\s+)?fun\s+(\w+)',dao,re.S) if m==method)
    return ''.join(json.loads('"'+v+'"') for v in re.findall(r'"((?:\\.|[^"\\])*)"',body))

for i in range(105):
    db.execute('INSERT INTO translation_history VALUES(?,?,?,?,?,?)', (f'{i:03d}','原文','result','en','model',i))
    db.execute(query('trimTo'),{'keep':100})
rows = db.execute(query('pagingAll')).fetchall()
assert len(rows)==100 and rows[0][0]=='104' and rows[-1][0]=='005'
db.execute(query('deleteById'),{'id':'104'})
assert db.execute('SELECT count(*) FROM translation_history').fetchone()==(99,)
db.execute(query('deleteAll'))
assert not db.execute(query('pagingAll')).fetchall()
for id in ['a','b']:
    db.execute('INSERT INTO translation_history VALUES(?,?,?,?,?,?)',(id,'原文','result','en','model',1))
assert [r[0] for r in db.execute(query('pagingAll'))]==['b','a']
assert not db.execute('PRAGMA foreign_key_check').fetchall()
print('PASS: latest 100 retained, deterministic timestamp ties, paging source ordering, single/all deletion')
if (schemas / '41.json').exists():
    expected = sqlite3.connect(':memory:')
    for entity in json.loads((schemas / '41.json').read_text())['database']['entities']:
        table=entity['tableName']
        expected.execute(entity['createSql'].replace('${TABLE_NAME}',table))
        for index in entity.get('indices',[]): expected.execute(index['createSql'].replace('${TABLE_NAME}',table))
        for pragma in (f'table_info("{table}")',f'index_list("{table}")',f'foreign_key_list("{table}")'):
            assert list(db.execute('PRAGMA '+pragma))==list(expected.execute('PRAGMA '+pragma)),pragma
    print('PASS: migration matches compiler-exported Room v41 schema')
