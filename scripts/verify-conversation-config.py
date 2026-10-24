"""Exercise real v39→v40 SQL, config column ownership and context usage queries."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub'
schemas = root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase'
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in json.loads((schemas / '39.json').read_text())['database']['entities']:
    for sql in [entity['createSql']] + [i['createSql'] for i in entity.get('indices', [])]:
        db.execute(sql.replace('${TABLE_NAME}', entity['tableName']))
db.execute("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at,context_compaction) "
           "VALUES('chat','owner','title','[]',1,2,'existing-summary')")
db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'owner','原记忆🌶')")
original = '[{"parts":[{"url":"file:///shared.aac"}],"annotations":[{"type":"avatar_interaction"}]}]'
db.execute("INSERT INTO message_node VALUES('node','chat',0,?,0)", (original,))
db.execute("INSERT INTO message_reaction VALUES('reaction','chat','message','user','❤️','原话',1)")

def insert(source_name, prompt, chat='chat'):
    db.execute('INSERT INTO usage_record(created_at,source,model_id,conversation_id,prompt_tokens,completion_tokens,cached_tokens) '
               'VALUES(1,?,?,?,?,0,0)', (source_name, 'model', chat, prompt))

insert('CHAT', 42)
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
before = {t: (list(db.execute(f'PRAGMA table_info("{t}")')), list(db.execute(f'SELECT * FROM "{t}"'))) for t in tables}
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', (source / 'data/db/migrations/Migration_39_40.kt').read_text())
assert len(statements) == 2
assert 'Migration_39_40,' in (source / 'di/DataSourceModule.kt').read_text()
assert int(re.search(r'version\s*=\s*(\d+)', (source / 'data/db/AppDatabase.kt').read_text()).group(1)) >= 41
db.commit()
db.execute('BEGIN')
for sql in statements:
    db.execute(sql)
db.rollback()
assert list(db.execute('PRAGMA table_info(ConversationEntity)')) == before['ConversationEntity'][0]
for sql in statements:
    db.execute(sql)
for table, (columns, rows) in before.items():
    projection = ','.join('"' + c[1] + '"' for c in columns)
    assert list(db.execute(f'SELECT {projection} FROM "{table}"')) == rows, table
assert db.execute("SELECT conversation_config FROM ConversationEntity").fetchone() == ('',)
assert [r[2] for r in db.execute('PRAGMA index_info(index_usage_record_conversation_id_source_id)')] == ['conversation_id', 'source', 'id']

def query(file, method):
    bodies = re.findall(r'@Query\((.*?)\)\s*(?:suspend\s+)?fun\s+(\w+)', (source / file).read_text(), re.S)
    body = next(b for b, m in bodies if m == method)
    return ''.join(json.loads('"' + value + '"') for value in re.findall(r'"((?:\\.|[^"\\])*)"', body))

config = '{"chatModelId":null,"mcpServers":[],"enableWebSearch":false}'
db.execute(query('data/db/dao/ConversationDAO.kt', 'updateConfig'), {'id': 'chat', 'config': config})
assert db.execute("SELECT conversation_config,context_compaction FROM ConversationEntity").fetchone() == (config, 'existing-summary')
assert db.execute('SELECT messages FROM message_node').fetchone() == (original,)
context_sql = query('data/db/dao/UsageRecordDAO.kt', 'observeContext')
assert db.execute(context_sql, {'conversationId': 'chat'}).fetchone() == ('model', 42)
insert('PROACTIVE', 100)
insert('TRANSLATION', 900)
insert('CHAT', 700, 'other')
assert db.execute(context_sql, {'conversationId': 'chat'}).fetchone() == ('model', 100)
insert('CHAT', 120)
assert db.execute(context_sql, {'conversationId': 'chat'}).fetchone() == ('model', 120)
assert db.execute(context_sql, {'conversationId': 'empty'}).fetchone() is None
file_sql = query('data/db/dao/MessageNodeDAO.kt', 'isFileReferenced')
assert db.execute(file_sql, {'encodedUrl': 'file:///shared.aac'}).fetchone() == (1,)
assert db.execute(file_sql, {'encodedUrl': 'file:///unreferenced.aac'}).fetchone() == (0,)
assert not db.execute('PRAGMA foreign_key_check').fetchall()
print('PASS: v39→v40 rollback and all old rows; isolated config writes; per-conversation latest request usage; attachment references')
if (schemas / '40.json').exists():
    expected = sqlite3.connect(':memory:')
    for entity in json.loads((schemas / '40.json').read_text())['database']['entities']:
        table = entity['tableName']
        expected.execute(entity['createSql'].replace('${TABLE_NAME}', table))
        for index in entity.get('indices', []):
            expected.execute(index['createSql'].replace('${TABLE_NAME}', table))
        for pragma in (f'table_info("{table}")', f'index_list("{table}")', f'foreign_key_list("{table}")'):
            assert list(db.execute('PRAGMA ' + pragma)) == list(expected.execute('PRAGMA ' + pragma)), pragma
    print('PASS: production migration matches compiler-exported Room v40 schema')
