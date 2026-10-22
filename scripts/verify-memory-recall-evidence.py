"""Execute the new recall evidence and vector-maintenance DAO queries against the exported Room v38 schema."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/me/rerere/rikkahub/data/memory"
schema = json.loads((root / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/38.json").read_text())
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for entity in schema["database"]["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))


def query(filename, name):
    text = (source / filename).read_text()
    for annotation, method in re.findall(r"@Query\((.*?)\)\s*suspend fun (\w+)", text, re.S):
        if method == name:
            return "".join(a or b for a, b in re.findall(r'"""(.*?)"""|"([^"\n]*)"', annotation, re.S))
    raise AssertionError(f"Query not found: {filename}/{name}")


db.execute("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES('chat','a','','[]',1,2)")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'[]',0)")
for memory_id, owner, revision, kind in [(7, "a", 1, "automatic"), (8, "a", 2, "automatic"),
                                          (9, "b", 1, "automatic"), (10, "a", 1, "tool")]:
    db.execute("INSERT INTO MemoryEntity(id,assistant_id,content,revision,source_kind) VALUES(?,?,?,?,?)",
               (memory_id, owner, "original memory", revision, kind))
    db.execute("INSERT INTO memory_evidence VALUES(?,?,?,?,?,?,?,?,?)",
               (memory_id, owner, "chat", "node", "message", "hash", "user", "date", "original quote"))

batch = query("MemoryOrganizationStorage.kt", "evidenceForRecall").replace("(:ids)", "(7,8,9,10)")
assert [row[0] for row in db.execute(batch, {"scope": "a"})] == [7]
assert [row[0] for row in db.execute(batch, {"scope": "b"})] == [9]
assert not list(db.execute(batch, {"scope": "foreign"}))

before = {table: list(db.execute(f"SELECT * FROM {table}"))
          for table in ["MemoryEntity", "memory_evidence", "message_node", "ConversationEntity"]}
for namespace, slice_id, owner in [("model-a", "v1", "a"), ("model-b", "v2", "a"),
                                    ("obsolete", "v3", "a"), ("obsolete", "foreign", "b")]:
    db.execute("INSERT INTO memory_vector(namespace,sliceId,scope,memoryId,revision,sourceHash,vector) VALUES(?,?,?,?,?,?,?)",
               (namespace, slice_id, owner, 7 if owner == "a" else 9, 1, "hash", "[1,0]"))
cleanup = query("MemoryIndex.kt", "removeUnusedModels").replace("(:namespaces)", "('model-a','model-b')")
db.execute(cleanup, {"scope": "a"})
assert list(db.execute("SELECT sliceId FROM memory_vector WHERE scope='a' ORDER BY sliceId")) == [("v1",), ("v2",)]
assert db.execute("SELECT COUNT(*) FROM memory_vector WHERE scope='b'").fetchone() == (1,)

for namespace in ["model-a", "model-b"]:
    db.execute("INSERT INTO history_memory_vector(namespace,sliceId,scope,conversationId,nodeId,messageId,sourceHash,start,end,vector) "
               "VALUES(?,?,?,?,?,?,?,?,?,?)", (namespace, "old", "a", "chat", "node", "message", "hash", 0, 1, "[1,0]"))
page = query("HistoryMemoryIndex.kt", "maintenancePage")
records = list(db.execute(page, {"scope": "a", "after": ""}))
assert len(records) == 1, "Maintenance projects identity once without loading or duplicating vector payloads"
assert records[0] == ("old", "chat", "node", "message", "hash")
remove = query("HistoryMemoryIndex.kt", "removeSlices").replace("(:ids)", "('old')")
db.execute(remove, {"scope": "a"})
assert db.execute("SELECT COUNT(*) FROM history_memory_vector").fetchone() == (0,)
assert before == {table: list(db.execute(f"SELECT * FROM {table}")) for table in before}
assert not list(db.execute("PRAGMA foreign_key_check"))
print("PASS: batched evidence scope/revision gate; obsolete-model cleanup preserves active shared models; "
      "lightweight history paging; cleanup preserves memories, evidence and chat; unchanged Room v38 schema")
