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
query = """SELECT g.* FROM memory_graph g JOIN MemoryEntity m ON m.id=g.memoryId
    WHERE g.owner=:owner AND g.scope=:scope AND m.assistant_id=g.scope AND m.revision=g.revision"""
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

# Upgrade the historical graph schema, retaining all source/evidence/vector tables byte-for-byte.
db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(8,'scope','retained long-term fact')")
db.execute("INSERT INTO memory_graph VALUES(8,'owner','scope',1,'hash','null','',1)")
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'") if r[0] != 'memory_graph']
source_rows = {table:list(db.execute('SELECT * FROM "' + table + '"')) for table in tables}
migrate('Migration_35_36.kt')
assert not db.execute("SELECT name FROM sqlite_master WHERE name='memory_graph'").fetchall()
assert source_rows == {table:list(db.execute('SELECT * FROM "' + table + '"')) for table in tables}
assert [(r[1],r[2],r[3],r[5]) for r in db.execute('PRAGMA table_info(memory_index_attempt)')] == [
    ('owner','TEXT',1,1),('namespace','TEXT',1,2),('sliceId','TEXT',1,3),('batchId','TEXT',1,0),
    ('state','TEXT',1,0),('detail','TEXT',1,0),('createdAt','INTEGER',1,0)]
db.execute("INSERT INTO memory_index_attempt VALUES('owner','model','slice','batch','failed','safe',1)")
db.execute("INSERT OR IGNORE INTO memory_index_attempt VALUES('owner','model','slice','batch','running','retry',2)")
assert db.execute('SELECT state FROM memory_index_attempt').fetchone() == ('failed',)
print('PASS: v35→v36 retires graph only, preserves every other table, exact attempt schema and durable no-retry claims')

# Compare the actual migration with Room's compiler-exported schema, including every retained table.
expected_schema = json.loads((root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase/36.json').read_text())
expected = sqlite3.connect(':memory:')
for entity in expected_schema['database']['entities']:
    table = entity['tableName']
    expected.execute(entity['createSql'].replace('${TABLE_NAME}', table))
    for index in entity.get('indices', []):
        expected.execute(index['createSql'].replace('${TABLE_NAME}', table))
    def metadata(connection):
        columns = list(connection.execute('PRAGMA table_info("' + table + '")'))
        keys = sorted(tuple(row[2:]) for row in connection.execute('PRAGMA foreign_key_list("' + table + '")'))
        indexes = {row[1]: (row[2], list(connection.execute('PRAGMA index_info("' + row[1] + '")')))
                   for row in connection.execute('PRAGMA index_list("' + table + '")')}
        return columns, keys, indexes
    assert metadata(db) == metadata(expected), table
print('PASS: all v36 table, foreign key and index metadata matches compiler-exported Room schema')

# Additive float32 storage upgrade: preserve legacy vector bytes and every original row.
db.execute("INSERT INTO memory_vector VALUES('legacy','long','scope',8,1,'hash','[1,0]')")
db.execute("INSERT INTO history_memory_vector VALUES('legacy','short','scope','chat','node','message','hash',0,8,'[1,0]')")
db.execute("INSERT INTO memory_recall_snapshot VALUES('retry','chat','scope','message','hash','[]',1)")
tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")]
prior = {table: list(db.execute('SELECT * FROM "' + table + '"')) for table in tables}
migrate('Migration_36_37.kt')
for table, rows in prior.items():
    current = list(db.execute('SELECT * FROM "' + table + '"'))
    if table in ('memory_vector', 'history_memory_vector'):
        assert [row[:-1] for row in current] == rows and all(row[-1] is None for row in current)
        assert list(db.execute('PRAGMA table_info("' + table + '")'))[-1][1:3] == ('vectorBytes', 'BLOB')
    else:
        assert current == rows, table
assert any(row[1] == 'index_history_memory_vector_scope_namespace_sliceId'
           for row in db.execute('PRAGMA index_list(history_memory_vector)'))
new_schema = root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase/37.json'
if new_schema.exists():
    expected37 = sqlite3.connect(':memory:')
    for entity in json.loads(new_schema.read_text())['database']['entities']:
        table = entity['tableName']
        expected37.execute(entity['createSql'].replace('${TABLE_NAME}', table))
        for idx in entity.get('indices', []):
            expected37.execute(idx['createSql'].replace('${TABLE_NAME}', table))
        assert metadata(db) == metadata(expected37), table
    print('PASS: v37 migration matches compiler-exported columns, foreign keys and indexes')
else:
    print('NOTE: v37 compiler schema will be checked after Room compilation')
print('PASS: v36→v37 adds nullable BLOB columns and scoped paging index without touching original or legacy data')

# Conversion is conditional: a deleted or concurrently changed row is never recreated/overwritten.
packed = bytes.fromhex('0000803f00000000')
sql = "UPDATE memory_vector SET vector='',vectorBytes=? WHERE namespace=? AND sliceId=? AND vector=? AND vectorBytes IS NULL"
assert db.execute(sql,(packed,'legacy','long','[0,1]')).rowcount == 0
assert db.execute(sql,(packed,'legacy','long','[1,0]')).rowcount == 1
assert db.execute('SELECT vector,vectorBytes FROM memory_vector WHERE sliceId="long"').fetchone() == ('',packed)
db.execute("DELETE FROM memory_vector WHERE sliceId='long'")
assert db.execute(sql,(packed,'legacy','long','[1,0]')).rowcount == 0
assert db.execute('PRAGMA foreign_key_check').fetchall() == []
print('PASS: conditional encoding conversion preserves concurrent updates and cannot resurrect deleted vectors')
