"""Run the actual additive migration and durable automatic-memory claims/evidence SQL in SQLite."""
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
history_migration = (source / 'data/db/migrations/Migration_31_33_HistoryMemory.kt').read_text()
for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', history_migration, re.S):
    db.execute(a or b)
db.execute("INSERT INTO conversationentity(id,assistant_id,title,nodes,create_at,update_at) VALUES('chat','a','','[]',1,2)")
db.execute("INSERT INTO memoryentity(id,assistant_id,content) VALUES(7,'a','preserved memory')")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'[]',0)")
originals = list(db.execute('SELECT * FROM memoryentity')), list(db.execute('SELECT * FROM message_node'))
migration = (source / 'data/db/migrations/Migration_33_34.kt').read_text()
statements = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', migration, re.S)]
assert len(statements) == 4
for statement in statements:
    db.execute(statement)
assert originals == (list(db.execute('SELECT * FROM memoryentity')), list(db.execute('SELECT * FROM message_node')))
assert [r[1] for r in db.execute('PRAGMA table_info(memory_evidence)')] == [
    'memoryId', 'owner', 'conversationId', 'nodeId', 'messageId', 'sourceHash', 'role', 'sourceCreatedAt', 'quote']
assert {r[1] for r in db.execute('PRAGMA index_list(memory_organization_batch)')} == {
    'sqlite_autoindex_memory_organization_batch_1', 'index_memory_organization_batch_owner',
    'index_memory_organization_batch_conversationId'}
claim = "INSERT OR IGNORE INTO memory_organization_batch VALUES('batch','a','a','chat','[\"message\"]','running','',1)"
db.execute(claim)
db.execute("UPDATE memory_organization_batch SET state='failed' WHERE id='batch'")
db.execute(claim)
assert db.execute('SELECT COUNT(*),state FROM memory_organization_batch').fetchone() == (1, 'failed')
assert db.execute('SELECT messageIds FROM memory_organization_batch').fetchone() == ('["message"]',)
db.execute("INSERT INTO memoryentity(id,assistant_id,content,source_kind) VALUES(8,'a','new memory','automatic')")
db.execute("INSERT INTO memory_evidence VALUES(8,'a','chat','node','message','hash','user','date','actual quote')")
# Exact production evidence scope/revision gate; no claims of evidence after a manual/tool edit.
dao = (source / 'data/memory/MemoryOrganizationStorage.kt').read_text()
query = re.search(r'@Query\(("SELECT e\.\*.*?\n\s*".*?")\)', dao, re.S).group(1)
query = ''.join(re.findall(r'"([^"\n]*)"', query))
assert db.execute(query, dict(id=8, scope='a')).fetchone() is not None
assert db.execute(query, dict(id=8, scope='b')).fetchone() is None
db.execute('UPDATE memoryentity SET revision=2 WHERE id=8')
assert db.execute(query, dict(id=8, scope='a')).fetchone() is None
db.execute('DELETE FROM memoryentity WHERE id=8')
assert db.execute('SELECT COUNT(*) FROM memory_evidence').fetchone() == (0,)
# A failed transaction cannot leave a memory without evidence or a falsely completed journal.
db.commit()
try:
    db.execute('BEGIN')
    db.execute("INSERT INTO memoryentity(id,assistant_id,content) VALUES(9,'a','must roll back')")
    db.execute("INSERT INTO memory_evidence VALUES(999,'a','chat','node','message','hash','user','date','bad')")
except sqlite3.IntegrityError:
    db.rollback()
assert db.execute('SELECT COUNT(*) FROM memoryentity WHERE id=9').fetchone() == (0,)
assert db.execute('SELECT state FROM memory_organization_batch').fetchone() == ('failed',)
db.execute("DELETE FROM conversationentity WHERE id='chat'")
assert db.execute('SELECT COUNT(*) FROM memory_organization_batch').fetchone() == (0,)
assert db.execute('SELECT content FROM memoryentity WHERE id=7').fetchone() == ('preserved memory',)
print('PASS: v33 -> v34 additive migration, durable failed claims, exact scoped evidence, revision gate, rollback, cascades')
