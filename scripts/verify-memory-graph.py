"""Execute production migration/DAO SQL; exact Room metadata and source-safe graph invalidation."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub/data'
schema = json.loads((root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json').read_text())
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in schema['database']['entities']:
    db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for index in entity.get('indices', []):
        db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

def migrate(name):
    text = (source / 'db/migrations' / name).read_text()
    for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', text, re.S):
        db.execute(a or b)

migrate('Migration_31_33_HistoryMemory.kt')
migrate('Migration_33_34.kt')
db.execute("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,context_compaction) VALUES('chat','','[]',1,2,'summary')")
db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'scope','original memory')")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'original and alternate branches',1)")
before = [list(db.execute('SELECT * FROM ' + table)) for table in ['MemoryEntity', 'ConversationEntity', 'message_node']]
migrate('Migration_34_35.kt')
assert before == [list(db.execute('SELECT * FROM ' + table)) for table in ['MemoryEntity', 'ConversationEntity', 'message_node']]
assert list(db.execute('PRAGMA foreign_key_list(memory_graph)')) == [
    (0, 0, 'MemoryEntity', 'memoryId', 'id', 'NO ACTION', 'CASCADE', 'NONE')]
info = list(db.execute('PRAGMA table_info(memory_graph)'))
assert [(r[1], r[2], r[3], r[5]) for r in info] == [
    ('memoryId', 'INTEGER', 1, 2), ('owner', 'TEXT', 1, 1), ('scope', 'TEXT', 1, 0),
    ('revision', 'INTEGER', 1, 0), ('sourceHash', 'TEXT', 1, 0), ('relation', 'TEXT', 1, 0),
    ('quote', 'TEXT', 1, 0), ('createdAt', 'INTEGER', 1, 0)]
assert {r[1] for r in db.execute('PRAGMA index_list(memory_graph)')} == {
    'sqlite_autoindex_memory_graph_1', 'index_memory_graph_memoryId', 'index_memory_graph_owner_scope'}
db.execute("INSERT INTO memory_graph VALUES(7,'owner','scope',1,'hash','null','',1)")
db.execute("INSERT INTO memory_graph VALUES(7,'other','scope',1,'hash','null','',1)")
dao = (source / 'memory/MemoryGraphStorage.kt').read_text()
query = re.search(r'@Query\("""(SELECT g\.\*.*?)"""\)', dao, re.S)[1]
assert len(db.execute(query, dict(owner='owner', scope='scope')).fetchall()) == 1
assert len(db.execute(query, dict(owner='other', scope='scope')).fetchall()) == 1
assert not db.execute(query, dict(owner='owner', scope='wrong')).fetchall()
db.execute("UPDATE MemoryEntity SET revision=2 WHERE id=7")
assert not db.execute(query, dict(owner='owner', scope='scope')).fetchall()
db.execute('DELETE FROM MemoryEntity WHERE id=7')
assert db.execute('SELECT COUNT(*) FROM memory_graph').fetchone() == (0,)
assert db.execute('SELECT context_compaction FROM ConversationEntity').fetchone() == ('summary',)
assert db.execute('SELECT messages,select_index FROM message_node').fetchone() == ('original and alternate branches', 1)
try:
    db.execute("INSERT INTO memory_graph VALUES(999,'owner','scope',1,'hash','null','',1)")
    raise AssertionError('Deleted memories cannot acquire new graph records')
except sqlite3.IntegrityError:
    pass
print('PASS: v34→v35 exact Room graph metadata, preserved chat/branches/summary/memory, owner/scope isolation, revision invalidation, delete cascade')
