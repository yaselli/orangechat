"""Execute the production FTS mapping SQL on host SQLite (native tokenizer covered on device)."""
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/me/rerere/rikkahub/data/db/fts"
tables = (source / "MessageFtsTables.kt").read_text()
manager = (source / "MessageFtsManager.kt").read_text(encoding="utf-8-sig")


def statements(function):
    body = tables.split("internal fun " + function + "(", 1)[1].split("\ninternal fun ", 1)[0]
    return [a or b for a, b in re.findall(r'db\.execSQL\(\s*(?:"""(.*?)"""|"([^"\n]+)")', body, re.S)]


create = statements("createMessageFtsTables")
record, = statements("recordMessageFtsRow")
delete_node = statements("deleteMessageFtsNode")
delete_chat = statements("deleteMessageFtsConversation")
db = sqlite3.connect(":memory:")
# The mapping/query plan does not depend on the Android libsimple tokenizer.
db.execute(create[0].replace("tokenize = 'simple'", "tokenize = 'unicode61'"))
db.execute("CREATE TABLE ConversationEntity(id TEXT PRIMARY KEY, title TEXT, update_at INTEGER, nodes TEXT)")
db.execute("CREATE TABLE MemoryEntity(id INTEGER PRIMARY KEY, content TEXT)")
db.execute("INSERT INTO ConversationEntity VALUES('a','fresh title',900,'original branches')")
db.execute("INSERT INTO ConversationEntity VALUES('b','other title',800,'other source')")
db.execute("INSERT INTO MemoryEntity VALUES(7,'retained memory')")
db.execute("INSERT INTO message_fts(rowid,text,node_id,message_id,conversation_id,title,update_at) "
           "VALUES(42,'apple legacy','same-node','m1','a','stale title',1)")
db.execute("INSERT INTO message_fts(text,node_id,message_id,conversation_id) VALUES('apple other','same-node','m2','b')")
prior = list(db.execute("SELECT rowid,* FROM message_fts"))
source_rows = list(db.execute("SELECT * FROM ConversationEntity")), list(db.execute("SELECT * FROM MemoryEntity"))
db.commit()
with db:
    for sql in create[1:]:
        db.execute(sql)
assert prior == list(db.execute("SELECT rowid,* FROM message_fts"))
assert list(db.execute("SELECT fts_rowid,conversation_id,node_id FROM message_fts_rowid")) == [
    (42, 'a', 'same-node'), (43, 'b', 'same-node')]

plan = " ".join(str(row) for row in db.execute(
    "EXPLAIN QUERY PLAN SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id=? AND node_id=?",
    ('a', 'same-node')))
assert "SEARCH" in plan and "index_message_fts_rowid_conversation_node" in plan, plan

# Reindex a single node using the actual DELETE/INSERT statements.
insert = re.search(r'"(INSERT INTO message_fts\(text, node_id, message_id, conversation_id\)[^"]+)"', manager)[1]
with db:
    for sql in delete_node:
        db.execute(sql, ('a', 'same-node'))
    db.execute(insert, ('apple replacement', 'same-node', 'm3', 'a'))
    db.execute(record, ('a', 'same-node'))
assert db.execute("SELECT text FROM message_fts WHERE conversation_id='b'").fetchone() == ('apple other',)
assert db.execute("SELECT COUNT(*) FROM message_fts_rowid").fetchone() == (2,)

# Run the production search query with native-only functions replaced by standard equivalents.
query = re.search(r'"""\s*(SELECT message_fts.node_id.*?)"""', manager, re.S)[1]
query = query.replace("jieba_query(?)", "?").replace(
    "simple_snippet(message_fts, 0, '[', ']', '...', 30)", "snippet(message_fts, 0, '[', ']', '...', 30)")
results = list(db.execute(query, ('apple',)))
assert [(row[3], row[4]) for row in results] == [('fresh title', 900), ('other title', 800)]

# The mapping and virtual table roll back together on interruption.
before = list(db.execute("SELECT rowid,* FROM message_fts")), list(db.execute("SELECT * FROM message_fts_rowid"))
try:
    with db:
        for sql in delete_chat:
            db.execute(sql, ('a',))
        raise RuntimeError("interrupted")
except RuntimeError:
    pass
assert before == (list(db.execute("SELECT rowid,* FROM message_fts")), list(db.execute("SELECT * FROM message_fts_rowid")))
with db:
    for sql in delete_chat:
        db.execute(sql, ('a',))
assert db.execute("SELECT conversation_id FROM message_fts").fetchall() == [('b',)]
assert db.execute("SELECT conversation_id FROM message_fts_rowid").fetchall() == [('b',)]
assert source_rows == (list(db.execute("SELECT * FROM ConversationEntity")), list(db.execute("SELECT * FROM MemoryEntity")))
# Selected-message repair uses the mapped node rows and leaves alternative branches intact.
repair = tables.split("internal fun deleteMessageFtsMessage(", 1)[1]
lookup = re.search(r'"""\s*(SELECT .*?)"""', repair, re.S)[1]
repair_deletes = statements("deleteMessageFtsMessage")
with db:
    db.execute(insert, ('alternate pear', 'same-node', 'alternative', 'b'))
    db.execute(record, ('b', 'same-node'))
    rowids = list(db.execute(lookup, ('b', 'same-node', 'm2')))
    assert len(rowids) == 1
    for (rowid,) in rowids:
        for sql in repair_deletes:
            db.execute(sql, (rowid,))
assert db.execute("SELECT message_id FROM message_fts").fetchall() == [('alternative',)]
assert db.execute("SELECT COUNT(*) FROM message_fts_rowid").fetchone() == (1,)
print("PASS: selected-message repair preserves alternative branches and mapping consistency")
print("PASS: old-index backfill preserves rowids/source/memory; indexed node lookup; scoped reindex/delete; fresh search metadata; atomic rollback")
