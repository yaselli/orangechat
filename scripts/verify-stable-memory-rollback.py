"""Pin the stable behavior and ensure memory services cannot run in this recovery APK."""
import hashlib
from pathlib import Path
root = Path(__file__).resolve().parents[1]
expected = {'app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt': 'b16bb6e2e84f984c5ecafb799dcb668dbe79ba5f509f7a499c5c0bae89f977bc', 'app/src/main/java/me/rerere/rikkahub/data/ai/GenerationModelTurn.kt': '6c780c9af8e3b0b3ba0039700c31891eb47bbf4f0e45f209ed4158648202c0eb', 'app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt': 'e23463bc34abc43f535c1a28124b0b413ec4340da1caf1e98c567ea3efd9be59', 'app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt': '13eb3c36d2a0a261c68d88f4d87ef68a07a8c17d97e413c0dc6caf54c6d03e84', 'app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt': '431653d64e606e884b664d2b0700ce05d042d9c33c67e43131db5e979ed6e85d', 'app/src/main/java/me/rerere/rikkahub/data/service/ProactiveMessageService.kt': '9781e8c06b015aededf129657f26e9314cf4ab547dabff6e5afe1fde41c41780', 'app/src/main/java/me/rerere/rikkahub/di/AppModule.kt': '701c0916442c6a026932d6053d97c90a13f991835bf2cc54f1d0ff738aeb7bb5', 'app/src/main/java/me/rerere/rikkahub/service/ChatService.kt': '279a5f86899e97157f13a6fa2a7fa2389e1d59fa8dec9809a837fc76742100b2', 'app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt': '3023101a9f61b44f140ea454795b94f28a919674f046b400f730f8229204dc77', 'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt': 'fc25be0d6678374cfbebc7e684ba0d803a38f13a7dfc8a04686edae4ea9f87ab', 'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt': '3b18def67874f52e792266950dbb7b4b979efd623a5514aa08f77b54fee3d6c1', 'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt': '33f76b82e276b091bc63267ca7ba45f31bc265c6bf6d9ad9e9d846b6bf1c7c8d', 'app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt': 'f1a15893ab251170b1d0a313c7b86ac9d30309f396b75ec87d22511b16337d2b', 'app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt': '54d302137bec84202f7353a7bca689e398afa2c1e3801501574324b3b2b7c97e', 'app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt': '64eb216772dd5764299de4f9d2eeab3a6c4d41523aca236a84663f588117a329'}
for path, digest in expected.items():
    assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, path
source = root / 'app/src/main/java/me/rerere/rikkahub'
assert sorted(p.name for p in (source / 'data/memory').glob('*.kt')) == [
    'HistoryMemoryIndex.kt', 'MemoryAutoIndexStorage.kt', 'MemoryIndex.kt', 'MemoryOrganizationStorage.kt']
for path in (source / 'data/memory').glob('*.kt'):
    assert '@Dao' not in path.read_text(), path
    assert 'suspend fun' not in path.read_text(), path
for path in (source / 'di').glob('*.kt'):
    assert 'data.memory.' not in path.read_text(), path
assert 'version = 38' in (source / 'data/db/AppDatabase.kt').read_text()
print('PASS: stable generation, proactive chat, tool surface and UI restored; memory workers absent; schema 38 retained')
