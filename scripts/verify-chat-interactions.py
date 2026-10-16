"""Validate the actual Room v31 schema and new reaction SQL without needing an Android emulator."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/me/rerere/rikkahub"
schema = json.loads((root / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json").read_text())
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for entity in schema["database"]["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
db.execute("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','title','[]',1,2)")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'original branches',0)")
before = db.execute("SELECT * FROM message_node").fetchall()
migration = (source / "data/db/migrations/Migration_31_32.kt").read_text()
statements = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', migration, re.S)]
assert len(statements) == 2
for sql in statements:
    db.execute(sql)
assert db.execute("SELECT * FROM message_node").fetchall() == before
row = ("user-key", "chat", "message", "user", "❤️", "喜欢牛奶", 1)
insert = "INSERT OR IGNORE INTO message_reaction VALUES(?,?,?,?,?,?,?)"
db.execute(insert, row)
db.execute(insert, row)  # Retry is idempotent.
db.execute(insert, ("assistant-key", "chat", "message", "assistant", "❤️", "喜欢牛奶", 2))
assert db.execute("SELECT COUNT(*) FROM message_reaction").fetchone() == (2,)
db.execute("DELETE FROM message_reaction WHERE id='user-key'")
assert db.execute("SELECT actor FROM message_reaction").fetchall() == [("assistant",)]
# Copy to a fork must use a separate primary key and a real conversation.
db.execute("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('fork','fork','[]',1,2)")
db.execute(insert, ("fork-key", "fork", "message", "assistant", "❤️", "喜欢牛奶", 2))
dao = (source / "data/interaction/MessageReaction.kt").read_text()
delete = re.search(r'@Query\("([^"\n]+)"\)\s*suspend fun deleteForMessage', dao)[1]
db.execute(delete, dict(conversationId="chat", messageId="message"))
assert db.execute("SELECT conversationId FROM message_reaction").fetchall() == [("fork",)]
db.execute("DELETE FROM ConversationEntity WHERE id='fork'")
assert db.execute("SELECT COUNT(*) FROM message_reaction").fetchone() == (0,)
assert db.execute("SELECT * FROM message_node").fetchall() == before
print("PASS: v31→v32 source preservation, idempotent reactions, actor/fork isolation, message/conversation deletion")
