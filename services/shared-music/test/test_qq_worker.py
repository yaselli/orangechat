"""Protocol-independent checks; no real account or upstream network."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace as Obj
import unittest

spec = importlib.util.spec_from_file_location("qq_worker", Path(__file__).parents[1] / "src/qq_worker.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class QQAdapterTest(unittest.TestCase):
    def test_timed_lyrics_merge_translations_and_multiple_stamps(self):
        lines = worker.lyric_lines("[00:12.5][00:14.50]hello\n[00:03]start\n[ar:artist]", "[00:12.500]你好")
        self.assertEqual([3000, 12500, 14500], [line["timeMs"] for line in lines])
        self.assertEqual("你好", lines[1]["trans"])
        self.assertEqual([0, 1, 2], [line["index"] for line in lines])

    def test_stream_urls_stay_on_qq_https_hosts(self):
        self.assertEqual("https://ws.stream.qqmusic.qq.com/song.mp3?vkey=key", worker.stream_url(
            ["http://ws.stream.qqmusic.qq.com/"], "song.mp3?vkey=key"))
        for url in ("https://evil.example/music", "//evil.example/music", "file:///private/key", "https://user:pass@ws.stream.qqmusic.qq.com/music"):
            with self.assertRaises(worker.MusicError):
                worker.stream_url(["https://ws.stream.qqmusic.qq.com/"], url)

    def test_song_mapping_uses_numeric_id_and_public_album_art(self):
        song = Obj(id=123, title="title", name="fallback", singer=[Obj(name="artist")], interval=99, album=Obj(mid="ABC123", name="album"))
        result = worker.public_song(song)
        self.assertEqual(("123", "qq", 99000), (result["songId"], result["platform"], result["durationMs"]))
        self.assertIn("y.gtimg.cn", result["coverUrl"])
        with self.assertRaises(worker.MusicError):
            worker.number("../private")

    def test_errors_do_not_echo_sdk_messages(self):
        self.assertEqual("MUSIC_UPSTREAM_ERROR", worker.error_code(ValueError("musickey=secret")))
        from qqmusic_api import RatelimitedError
        limited = RatelimitedError(code=2001, data={"feedbackURL": "https://example.invalid/private-verification"})
        self.assertEqual("MUSIC_NOT_LOGGED_IN", worker.error_code(limited))
        self.assertEqual("MUSIC_QQ_VERIFICATION_REQUIRED", worker.error_code(limited, authenticated=True))

    def test_frequency_and_account_limits_are_not_mislabeled_as_verification(self):
        from qqmusic_api import LoginRateLimitError, LoginDeviceLimitError
        self.assertEqual("MUSIC_RATE_LIMITED", worker.error_code(LoginRateLimitError(code=104604)))
        self.assertEqual("MUSIC_QQ_ACCOUNT_RESTRICTED", worker.error_code(LoginDeviceLimitError(code=20279)))

    def test_pinned_sdk_credentials_round_trip_without_field_loss(self):
        from qqmusic_api import Credential, __version__
        self.assertEqual("0.7.3", __version__)
        credential = Credential.model_validate({"musicid": 123, "musickey": "ticket", "loginType": 1, "encryptUin": "encrypted"})
        saved = credential.model_dump(mode="json", by_alias=True)
        self.assertEqual(credential, Credential.model_validate(saved))


if __name__ == "__main__":
    unittest.main()
