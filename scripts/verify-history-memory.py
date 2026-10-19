"""Execute production migration, selected-text extraction and scoped FTS SQL against SQLite."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub'
schema = json.loads((root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json').read_text())
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in schema['database']['entities']:
    db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for index in entity.get('indices', []):
        db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

def conversation(key, assistant):
    db.execute('INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES(?,?,?,?,1,2)',
               (key, assistant, 'title', '[]'))

def message(key, text, role='user', extras=()):
    return dict(id=key, role=role, createdAt='2020-01-01T00:00', parts=[dict(type='text', text=text), *extras])

conversation('past', 'assistant-a')
conversation('chat', 'assistant-a')
conversation('private', 'assistant-b')
selected = message('selected', 'oat milk', extras=[dict(type='reasoning', reasoning='private reasoning'),
    dict(type='text', text='secret system background', metadata=dict(orangechat_extra_info=True)),
    dict(type='image', url='data:image/png;base64,do not extract')])
for key, chat, index, messages, selection in [
    ('node', 'past', 0, [message('hidden', 'forbidden hidden branch'), selected], 1),
    ('visible', 'chat', 1, [message('visible-message', 'oat milk')], 0),
    ('future', 'chat', 4, [message('future-message', 'oat milk')], 0),
    ('private-node', 'private', 0, [message('private-message', 'oat milk')], 0),
    ('tool', 'past', 2, [message('tool-message', 'tool secret', 'tool')], 0),
]:
    db.execute('INSERT INTO message_node VALUES(?,?,?,?,?)', (key, chat, index, json.dumps(messages), selection))
db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant-a','original memory')")
db.execute("INSERT INTO memory_vector VALUES('model','memory-slice','assistant-a',7,1,'hash','[1,0]')")
db.execute("INSERT INTO memory_recall_snapshot VALUES('key','chat','assistant-a','user','hash','[]',1)")
before = list(db.execute('SELECT * FROM message_node'))
migration = (source / 'data/db/migrations/Migration_31_33_HistoryMemory.kt').read_text()
statements = [a or b for a,b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', migration, re.S)]
assert len(statements) == 3
for statement in statements:
    db.execute(statement)
assert list(db.execute('SELECT * FROM message_node')) == before
assert db.execute('SELECT content FROM MemoryEntity').fetchone() == ('original memory',)
assert db.execute('SELECT COUNT(*) FROM memory_vector').fetchone() == (1,)
assert db.execute('SELECT COUNT(*) FROM memory_recall_snapshot').fetchone() == (1,)
repository = (source / 'data/memory/HistoryMemoryRepository.kt').read_text()
source_sql = re.search(r'internal val SOURCE_SQL = """(.*?)"""', repository, re.S)[1]
rows = db.execute(source_sql, ('assistant-a',)).fetchall()
assert {row[2] for row in rows} == {0,1,4}  # Only selected USER/ASSISTANT nodes.
assert next(row for row in rows if row[0] == 'node')[3:] == ('assistant-a', 'selected', 'user', '2020-01-01T00:00', 'oat milk')
assert all('secret' not in row[-1] and 'forbidden' not in row[-1] for row in rows)
# Keep the test using the exact production source SQL; malformed or oversized sources must be skipped.
db.execute("INSERT INTO message_node VALUES('broken','past',6,'broken json',0)")
assert len(db.execute(source_sql, ('assistant-a',)).fetchall()) == 3
db.execute("INSERT INTO message_node VALUES('negative','past',7,?,-1)", (json.dumps([selected]),))
db.execute("INSERT INTO message_node VALUES('oversized','past',8,?,0)",
           (json.dumps([message('oversized-message', 'x' * 64001)]),))
assert len(db.execute(source_sql, ('assistant-a',)).fetchall()) == 3
# Room's node REPLACE is already the source-write path: it must cascade every old derived vector.
insert_vector = "INSERT INTO history_memory_vector VALUES('model','slice','assistant-a','past','node','selected','hash',0,8,'[1,0]')"
db.execute(insert_vector)
dao = (source / 'data/memory/HistoryMemoryIndex.kt').read_text()
vector_sql = re.search(r'@Query\("""(SELECT v\.\*.*?)"""\)', dao, re.S)[1]
params = dict(scope='assistant-a',namespace='model',after='',conversationId='chat',maxNodeIndex=1,latestTime='2026')
assert len(db.execute(vector_sql, params).fetchall()) == 1
assert not db.execute(vector_sql, {**params, 'scope':'assistant-b'}).fetchall()
db.execute("UPDATE message_node SET select_index=0 WHERE id='node'")
assert not db.execute(vector_sql, params).fetchall()
db.execute("UPDATE message_node SET select_index=1 WHERE id='node'")
db.execute("INSERT OR REPLACE INTO message_node VALUES('node','past',0,?,0)", (json.dumps([selected]),))
assert db.execute('SELECT COUNT(*) FROM history_memory_vector').fetchone() == (0,)
db.execute(insert_vector)
db.execute("DELETE FROM ConversationEntity WHERE id='past'")
assert db.execute('SELECT COUNT(*) FROM history_memory_vector').fetchone() == (0,)
# Test FTS permission/branch/time filters BEFORE its candidate LIMIT. jieba_query is a production
# native tokenizer; use identity here to check SQL binding/selection independently of Chinese tokenization.
db.execute('CREATE VIRTUAL TABLE message_fts USING fts5(text,node_id UNINDEXED,message_id UNINDEXED,conversation_id UNINDEXED,title UNINDEXED,update_at UNINDEXED)')
db.create_function('jieba_query', 1, lambda term: '"' + term.replace('"','""') + '"')
for key,chat in [('private-node','private'),('future','chat'),('visible','chat')]:
    row=db.execute("SELECT messages FROM message_node WHERE id=?",(key,)).fetchone()
    msg=json.loads(row[0])[0]
    db.execute('INSERT INTO message_fts VALUES(?,?,?,?,?,?)',('oat milk',key,msg['id'],chat,'title','1'))
# A hidden alternative and a tool node must not crowd out real visible sources.
for key, role, chosen in [('hidden-fts','user',1),('tool-fts','tool',0)]:
    msgs=[message(key+'-old','oat milk',role),message(key+'-selected','unrelated')]
    db.execute('INSERT INTO message_node VALUES(?,?,?,?,?)',(key,'chat',0,json.dumps(msgs),chosen))
    db.execute('INSERT INTO message_fts VALUES(?,?,?,?,?,?)',('oat milk',key,msgs[0]['id'],'chat','title','1'))
for n in range(100):
    key='private-extra-'+str(n)
    msg=message(key+'-message','oat milk')
    db.execute('INSERT INTO message_node VALUES(?,?,?,?,?)',(key,'private',0,json.dumps([msg]),0))
    db.execute('INSERT INTO message_fts VALUES(?,?,?,?,?,?)',('oat milk',key,msg['id'],'private','title','1'))
fts_sql = re.search(r'"""(SELECT DISTINCT n\.id FROM message_fts.*?)"""', repository, re.S)[1]
fts_sql = fts_sql.replace('$match', "jieba_query(?)")
assert db.execute(fts_sql, ('milk','assistant-a','2026','chat',2,0,0)).fetchall() == [('visible',)]
print('PASS: v31→v33 preserves messages/memories/snapshots; selected text excludes hidden/tool/reasoning/background/attachments; scope/branch/time filters; edit and conversation cascades; bound FTS SQL')

# Expired lexical hits must be removed before LIMIT, not allowed to crowd recent matches out.
db.execute("UPDATE message_node SET messages=json_set(messages,'$[0].createdAt','2025-01-01T00:00:00Z') WHERE id='visible'")
for n in range(100):
    key='expired-'+str(n)
    msg=message(key+'-message','oat milk')
    msg['createdAt']='2020-01-01T00:00:00Z'
    db.execute('INSERT INTO message_node VALUES(?,?,?,?,?)',(key,'chat',0,json.dumps([msg]),0))
    db.execute('INSERT INTO message_fts VALUES(?,?,?,?,?,?)',('oat milk',key,msg['id'],'chat','title','1'))
cutoff=int(db.execute("SELECT strftime('%s','2024-01-01T00:00:00Z')").fetchone()[0])
assert db.execute(fts_sql, ('milk','assistant-a','2026','chat',2,1,cutoff)).fetchall() == [('visible',)]
db.execute("UPDATE message_node SET messages=json_set(messages,'$[0].createdAt','2025-01-01T08:00:00+08:00') WHERE id='visible'")
assert db.execute(fts_sql, ('milk','assistant-a','2026','chat',2,1,cutoff)).fetchall() == [('visible',)]
db.execute("UPDATE message_node SET messages=json_set(messages,'$[0].createdAt','2025-01-01T00:00:00') WHERE id='visible'")
assert db.execute(fts_sql, ('milk','assistant-a','2026','chat',2,1,cutoff)).fetchall() == [('visible',)]
print('PASS: short-term keyword expiration is applied before candidate LIMIT, including offset-aware timestamps')
