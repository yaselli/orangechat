"""Exercise registered stable/memory upgrade routes using compiler-exported schemas."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub'
schemas = root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase'
files = list((source / 'data/db/migrations').glob('*.kt'))
migrations = {}
for file in files:
    text = file.read_text()
    found = re.search(r'object (Migration_\w+)\s*:\s*Migration\((\d+),\s*(\d+)\)', text)
    if found:
        migrations[found[1]] = (int(found[2]), int(found[3]), text)
builder = (source / 'di/DataSourceModule.kt').read_text().split('.addMigrations(', 1)[1].split(')', 1)[0]
registered = [name for name in migrations if re.search(r'\b' + name + r'\b', builder)]
assert 'Migration_32_33_StableMemory' in registered
assert 'Migration_37_38' in registered
assert 'Migration_38_39' in registered
assert re.search(r'version\s*=\s*39', (source / 'data/db/AppDatabase.kt').read_text())
statement = re.compile(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)|\b(Migration_\w+)\.migrate\(db\)', re.S)
def apply(db, name):
    for match in statement.finditer(migrations[name][2]):
        if match[3]:
            apply(db, match[3])
        else:
            db.execute(match[1] or match[2])
def route(version):
    path = []
    while version < 38:
        options = [(migrations[name][1], name) for name in registered if migrations[name][0] == version and migrations[name][1] <= 38]
        assert options, ('No registered migration', version)
        version, name = max(options)
        path.append(name)
    return path
def create(version):
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    for entity in json.loads((schemas / f'{version}.json').read_text())['database']['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for idx in entity.get('indices', []):
            db.execute(idx['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return db
def metadata(db, table):
    columns = list(db.execute(f'PRAGMA table_info("{table}")'))
    # Compare field identity rather than physical ALTER TABLE column order.
    columns = sorted(row[1:] for row in columns)
    keys = sorted(tuple(row[2:]) for row in db.execute(f'PRAGMA foreign_key_list("{table}")'))
    indexes = {row[1]: (row[2], list(db.execute(f'PRAGMA index_info("{row[1]}")')))
               for row in db.execute(f'PRAGMA index_list("{table}")')}
    return columns, keys, indexes
# Expected unified schema = actual v37 memory schema + actual released v32 reaction entity.
expected = create(37)
reaction = next(e for e in json.loads((schemas/'32.json').read_text())['database']['entities'] if e['tableName'] == 'message_reaction')
expected.execute(reaction['createSql'].replace('${TABLE_NAME}', reaction['tableName']))
for idx in reaction['indices']:
    expected.execute(idx['createSql'].replace('${TABLE_NAME}', reaction['tableName']))
expected_tables = [r[0] for r in expected.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
if (schemas/'38.json').exists():
    actual38 = create(38)
    for table in expected_tables:
        assert metadata(actual38, table) == metadata(expected, table), table
for version in (29, 30, 31, 32, 36, 37):
    db = create(version)
    db.execute("INSERT INTO ConversationEntity(id,assistant_id,title,nodes,create_at,update_at) VALUES('chat','owner','title','[]',1,2)")
    db.execute("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'owner','喜欢吃辣🌶')")
    original = '[{"id":"old","parts":[]},{"id":"selected","annotations":[{"type":"avatar_interaction","actor":"user","target":"assistant"}]}]'
    db.execute("INSERT INTO message_node VALUES('node','chat',0,?,1)", (original,))
    if version == 32:
        db.execute("INSERT INTO message_reaction VALUES('reaction','chat','selected','user','❤️','原话',1)")
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    before = {table: ([r[1] for r in db.execute(f'PRAGMA table_info("{table}")')], list(db.execute(f'SELECT * FROM "{table}"'))) for table in tables}
    db.commit()
    db.execute('BEGIN')
    for name in route(version):
        apply(db, name)
    db.commit()
    for table in expected_tables:
        assert metadata(db, table) == metadata(expected, table), (version, table)
    for table, (cols, rows) in before.items():
        projection = ','.join('"' + col + '"' for col in cols)
        assert list(db.execute(f'SELECT {projection} FROM "{table}"')) == rows, (version, table)
    assert not db.execute('PRAGMA foreign_key_check').fetchall()
    if version == 32:
        db.execute("DELETE FROM ConversationEntity WHERE id='chat'")
        assert db.execute('SELECT COUNT(*) FROM message_reaction').fetchone() == (0,)
    print(f'PASS: registered v{version}→v38 path, exact unified schema, original rows/branches/annotations and reactions preserved')
# Failed upgrade rolls the bridge back as one transaction; it must not leave half-upgraded columns.
db = create(32)
before = {table: metadata(db, table) for table in expected_tables if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()}
db.commit()
db.execute('BEGIN')
for name in route(32):
    apply(db, name)
db.rollback()
for table, info in before.items():
    assert metadata(db, table) == info, table
assert not db.execute("SELECT 1 FROM pragma_table_info('MemoryEntity') WHERE name='revision'").fetchall()
print('PASS: failed stable v32 upgrade rolls back without leaving partial memory columns')
