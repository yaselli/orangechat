"""Run actual v29 Room schema and Kotlin migration/DAO SQL against SQLite without an emulator."""
import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/me/rerere/rikkahub/data"
schema = json.loads((ROOT / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/29.json").read_text())
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for entity in schema["database"]["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity.get("indices", entity.get("indexes", [])):
        db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

db.execute("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','title','[]',1,2)")
nodes = '[{"id":"selected","parts":[{"text":"original"}]},{"id":"alternate"}]'
db.execute("INSERT INTO message_node VALUES('node','chat',0,?,1)", (nodes,))
db.executemany("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(?,?,?)", [
    (7, "assistant-a", "original memory"), (19, "__global__", "global memory"),
])
before = list(db.execute("SELECT id,assistant_id,content FROM MemoryEntity ORDER BY id"))
old_conversation = db.execute("SELECT * FROM ConversationEntity").fetchone()
migration = (SOURCE / "db/migrations/Migration_29_30.kt").read_text()
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
assert len(statements) == 7
for statement in statements:
    db.execute(statement)
assert list(db.execute("SELECT id,assistant_id,content FROM MemoryEntity ORDER BY id")) == before
assert db.execute("SELECT messages,select_index FROM message_node").fetchone() == (nodes, 1)
assert db.execute("SELECT * FROM ConversationEntity").fetchone()[:-1] == old_conversation
assert db.execute("SELECT context_compaction FROM ConversationEntity").fetchone() == ("",)
assert db.execute("SELECT source_kind,source_conversation_id,source_message_id,revision,created_at,updated_at "
                  "FROM MemoryEntity WHERE id=7").fetchone() == ("legacy", "", "", 1, 0, 0)

dao = (SOURCE / "db/dao/MemoryDAO.kt").read_text(encoding="utf-8-sig")
update = re.search(r'@Query\("""(.*?)"""\)\s*suspend fun updateContentIfCurrent', dao, re.S)[1]
args = dict(id=7, assistantId="other-assistant", expectedRevision=1, content="updated",
            updatedAt=1234, sourceKind="tool", conversationId="chat", messageId="selected")
assert db.execute(update, args).rowcount == 0  # Cross-assistant write is rejected.
args["assistantId"] = "assistant-a"
assert db.execute(update, args).rowcount == 1
assert db.execute(update, args).rowcount == 0  # A stale revision cannot overwrite a newer edit.
assert db.execute("SELECT content,revision,source_message_id FROM MemoryEntity WHERE id=7").fetchone() == (
    "updated", 2, "selected")
delete = re.search(r'@Query\("([^"]+)"\)\s*suspend fun deleteMemoryInScope', dao)[1]
assert db.execute(delete, dict(id=7, assistantId="__global__")).rowcount == 0
assert db.execute(delete, dict(id=7, assistantId="assistant-a")).rowcount == 1
args["expectedRevision"] = 2
assert db.execute(update, args).rowcount == 0  # Deleted records are not recreated by late edits.
assert db.execute("SELECT id FROM MemoryEntity").fetchall() == [(19,)]
conversation_dao = (SOURCE / "db/dao/ConversationDAO.kt").read_text(encoding="utf-8-sig")
compact = re.search(r'@Query\("([^"]+)"\)\s*suspend fun updateCompaction', conversation_dao)[1]
assert db.execute(compact, dict(id="chat", value="summary-json")).rowcount == 1
assert db.execute("SELECT messages,select_index FROM message_node").fetchone() == (nodes, 1)
db.execute("DELETE FROM ConversationEntity WHERE id='chat'")
assert db.execute(compact, dict(id="chat", value="obsolete")).rowcount == 0
print("PASS: v29→v30 data preservation, legacy provenance, scoped revision writes/deletes, summary-only updates")

# Verify the next additive migration against the actual KSP-generated v30 schema.
schema30 = json.loads((ROOT / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/30.json").read_text())
db31 = sqlite3.connect(":memory:")
db31.execute("PRAGMA foreign_keys=ON")
for entity in schema30["database"]["entities"]:
    db31.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity.get("indices", entity.get("indexes", [])):
        db31.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
db31.execute("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','title','[]',1,2)")
db31.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'a','original')")
db31.execute("INSERT INTO message_node VALUES('node','chat',0,?,1)", (nodes,))
before31 = list(db31.execute("SELECT * FROM MemoryEntity"))
source31 = (SOURCE / "db/migrations/Migration_30_31.kt").read_text()
statements31 = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', source31, re.S)]
assert len(statements31) == 6
for statement in statements31:
    db31.execute(statement)
assert list(db31.execute("SELECT * FROM MemoryEntity")) == before31
assert db31.execute("SELECT messages,select_index FROM message_node").fetchone() == (nodes, 1)
db31.execute("INSERT INTO memory_vector VALUES('model-a','slice','a',7,1,'hash','[1,0]')")
db31.execute("INSERT INTO memory_recall_snapshot VALUES('key','chat','a','user','hash','[]',1)")
# Duplicate retries cannot overwrite a frozen snapshot.
db31.execute("INSERT OR IGNORE INTO memory_recall_snapshot VALUES('key','chat','a','user','changed','bad',2)")
assert db31.execute("SELECT messageHash,excerpts FROM memory_recall_snapshot").fetchone() == ("hash", "[]")
# Deleting a source cascades vectors; deleting a conversation cascades only its derived snapshots.
db31.execute("DELETE FROM MemoryEntity WHERE id=7")
assert db31.execute("SELECT COUNT(*) FROM memory_vector").fetchone() == (0,)
assert db31.execute("SELECT COUNT(*) FROM memory_recall_snapshot").fetchone() == (1,)
try:
    db31.execute("INSERT INTO memory_vector VALUES('model-a','late','a',7,1,'hash','[1,0]')")
    raise AssertionError("Deleted memory must not be resurrected by late vector results")
except sqlite3.IntegrityError:
    pass
db31.execute("DELETE FROM ConversationEntity WHERE id='chat'")
assert db31.execute("SELECT COUNT(*) FROM memory_recall_snapshot").fetchone() == (0,)
print("PASS: v30→v31 source preservation, snapshot freeze, vector/snapshot cascades, deleted-source rejection")
