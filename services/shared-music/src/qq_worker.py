"""Private JSON-lines adapter for the pinned QQMusicApi SDK; no public listener."""

import asyncio
import base64
import json
import logging
import os
import re
import sys
from urllib.parse import urljoin, urlparse

PROTOCOL = sys.stdout
logging.disable(logging.CRITICAL)
os.umask(0o077)


class MusicError(Exception):
    """An error code safe to return to the client."""


def number(value):
    """Accept bounded platform numeric IDs; MIDs stay inside the QQ SDK adapter."""
    if not re.fullmatch(r"[0-9]{1,20}", str(value)):
        raise MusicError("MUSIC_INPUT_ERROR")
    return int(value)


def public_song(song):
    """Map SDK song/search objects to the same public shape used by the Android player."""
    album = song.album
    mid = str(getattr(album, "mid", ""))
    cover = f"https://y.gtimg.cn/music/photo_new/T002R300x300M000{mid}.jpg" if re.fullmatch(r"[A-Za-z0-9]+", mid) else ""
    return {"songId": str(number(song.id)), "title": song.title or song.name,
            "artist": "/".join(item.name for item in song.singer), "album": album.name,
            "coverUrl": cover, "durationMs": max(0, song.interval) * 1000, "platform": "qq"}


def lyric_lines(text, translated=""):
    """Parse timed LRC, including multiple timestamps; merge translations by timestamp."""
    def parse(value):
        output = {}
        for line in value.splitlines():
            tags = list(re.finditer(r"\[(\d+):(\d+)(?:\.(\d+))?\]", line))
            content = re.sub(r"\[[^\]]*\]", "", line).strip()
            if not content:
                continue
            for tag in tags:
                stamp = (int(tag[1]) * 60 + int(tag[2])) * 1000 + int((tag[3] or "0").ljust(3, "0")[:3])
                output[stamp] = content[:1000]
        return output
    trans = parse(translated)
    return [{"index": i, "timeMs": stamp, "text": line, "trans": trans.get(stamp, "")}
            for i, (stamp, line) in enumerate(sorted(parse(text).items())[:3000])]


def stream_url(cdns, path):
    """Accept QQ HTTPS streaming hosts only; an upstream path cannot switch origins."""
    for cdn in cdns:
        candidate = urljoin(cdn.replace("http://", "https://", 1), path)
        parsed = urlparse(candidate)
        if (parsed.scheme == "https" and parsed.hostname and parsed.hostname.endswith(".stream.qqmusic.qq.com")
                and not parsed.username and not parsed.password):
            return candidate
    raise MusicError("MUSIC_UNAVAILABLE")


async def dispatch(message):
    """Execute one platform request with credentials scoped to this device only."""
    from qqmusic_api import Client, Credential
    from qqmusic_api.models.login import QR, QRLoginType
    from qqmusic_api.modules.song import SongFileInfo, SongFileType, SongQueryInfo

    method = message["method"]
    if method in ("song", "url", "lyrics"):
        number(message.get("songId"))
    credential = Credential.model_validate(message.get("credential") or {})
    if method not in ("qr", "poll") and not credential.musickey:
        raise MusicError("MUSIC_NOT_LOGGED_IN")
    async with Client(credential=credential, device_path=message["devicePath"]) as client:
        if credential.musickey and credential.key_expires_in > 0 and credential.is_expired():
            credential = await client.login.refresh_credential(credential)
            client.credential = credential
        if method == "qr":
            qr = await client.login.get_qrcode(QRLoginType.QQ)
            result = {"identifier": qr.identifier, "qrImage": "data:image/png;base64," + base64.b64encode(qr.data).decode()}
        elif method == "poll":
            qr = QR(data=b"", qr_type=QRLoginType.QQ, mimetype="image/png", identifier=message["identifier"])
            login = await client.login.check_qrcode(qr)
            result = {"status": {"DONE": "success", "SCAN": "waiting", "CONF": "scanned",
                                 "TIMEOUT": "expired", "REFUSE": "expired"}[login.event.name]}
            if login.credential:
                result["credential"] = login.credential.model_dump(mode="json", by_alias=True)
        elif method == "search":
            response = await client.search.search_by_type(message["query"][:80], num=min(20, message.get("limit", 20)), highlight=False)
            result = [public_song(song) for song in response.song]
        elif method in ("song", "url"):
            response = await client.song.query_song([SongQueryInfo(id=number(message["songId"]))])
            if not response.tracks:
                raise MusicError("MUSIC_UNAVAILABLE")
            song = response.tracks[0]
            result = public_song(song)
            if method == "url":
                # Only ordinary MP3 playback with an explicit successful entitlement result.
                urls = await client.song.get_song_urls([SongFileInfo(mid=song.mid, media_mid=song.file.media_mid)], SongFileType.MP3_128)
                if not urls.data or urls.data[0].result != 0 or not urls.data[0].purl:
                    raise MusicError("MUSIC_UNAVAILABLE")
                cdn = await client.song.get_cdn_dispatch()
                result = {"url": stream_url(cdn.sip, urls.data[0].purl), "trial": False}
        elif method == "lyrics":
            lyrics = await client.lyric.get_lyric(number(message["songId"]), trans=True)
            result = lyric_lines(lyrics.lyric, lyrics.trans)
        elif method == "playlists":
            created = await client.user.get_created_songlist(credential.musicid)
            lists = list(created.playlists)
            if credential.encrypt_uin:
                favourites = await client.user.get_fav_songlist(credential.encrypt_uin, num=100)
                lists += favourites.playlists
            unique = {str(item.id): {"id": str(item.id), "name": item.title,
                      "coverUrl": item.picurl.replace("http://", "https://", 1), "trackCount": item.songnum}
                      for item in lists if item.id > 0}
            result = list(unique.values())[:500]
        elif method == "source":
            source = str(message["source"])
            if source == "daily":
                response = await client.recommend.get_guess_recommend()
            elif source == "radar":
                response = await client.recommend.get_radar_recommend()
            elif source == "liked" or source.isdecimal():
                if source == "liked" and not credential.encrypt_uin:
                    raise MusicError("MUSIC_LOGIN_EXPIRED")
                songs = []
                for page in range(1, 6):
                    response = (await client.user.get_fav_song(credential.encrypt_uin, num=100, page=page)
                                if source == "liked" else
                                await client.songlist.get_detail(number(source), num=100, page=page))
                    songs.extend(response.songs)
                    if not response.songs or (not response.hasmore and response.total <= len(songs)):
                        break
                result = [public_song(song) for song in songs[:500]]
                return {"result": result, "credential": client.credential.model_dump(mode="json", by_alias=True)}
            else:
                raise MusicError("MUSIC_INPUT_ERROR")
            result = [public_song(song) for song in response.songs[:500]]
        else:
            raise MusicError("MUSIC_INPUT_ERROR")
        return {"result": result, "credential": client.credential.model_dump(mode="json", by_alias=True)}


def error_code(error, authenticated=False):
    """Do not serialize SDK exceptions: they may contain request URLs or credentials."""
    if isinstance(error, MusicError):
        return str(error)
    name = type(error).__name__
    if "Credential" in name or name == "LoginAuthExpiredError":
        return "MUSIC_LOGIN_EXPIRED"
    if name == "RatelimitedError":
        return "MUSIC_QQ_VERIFICATION_REQUIRED" if authenticated else "MUSIC_NOT_LOGGED_IN"
    if name in ("LoginAccountRestrictedError", "LoginDeviceLimitError"):
        return "MUSIC_QQ_ACCOUNT_RESTRICTED"
    if name == "LoginRateLimitError":
        return "MUSIC_RATE_LIMITED"
    return "MUSIC_QQ_UNAVAILABLE" if isinstance(error, ImportError) else "MUSIC_UPSTREAM_ERROR"


async def main():
    """Serve bounded concurrent requests until the parent closes stdin."""
    semaphore = asyncio.Semaphore(4)
    tasks = set()

    async def handle(message):
        try:
            async with asyncio.timeout(14):
                async with semaphore:
                    response = {"id": message["id"], "ok": True, **await dispatch(message)}
        except Exception as error:
            authenticated = bool((message.get("credential") or {}).get("musickey"))
            response = {"id": message.get("id"), "ok": False, "error": error_code(error, authenticated)}
        encoded = json.dumps(response, ensure_ascii=True, separators=(",", ":"))
        if len(encoded) > 2_000_000:
            encoded = json.dumps({"id": message.get("id"), "ok": False, "error": "MUSIC_UPSTREAM_ERROR"})
        PROTOCOL.write(encoded + "\n")
        PROTOCOL.flush()

    while line := await asyncio.to_thread(sys.stdin.buffer.readline, 128_001):
        if len(line) > 128_000 or len(tasks) >= 32:
            break
        try:
            message = json.loads(line)
            task = asyncio.create_task(handle(message))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
        except (ValueError, TypeError):
            break
    await asyncio.gather(*tasks, return_exceptions=True)


if __name__ == "__main__":
    sys.stdout = sys.stderr  # All SDK diagnostics stay outside the protocol.
    asyncio.run(main())
