"""Opt-in profiler for the pinned zipapp. stdout is reserved for extractor JSON."""
import sys
import time

try:
    clock_id = getattr(time, "CLOCK_BOOTTIME", 7)
    time.clock_gettime_ns(clock_id)
    clock = lambda: time.clock_gettime_ns(clock_id)
except (AttributeError, OSError):
    clock = time.monotonic_ns
    clock_id = -1

def event(label, duration=0):
    print(f"SAOREO_PROFILE {clock_id} {clock()} {duration} {label}", file=sys.stderr, flush=True)

event("python.entry")
started = clock()
archive = sys.argv[1]
sys.argv = sys.argv[1:]
sys.path.insert(0, archive)

import http.client
import inspect
from contextlib import contextmanager
import os
import subprocess
from functools import wraps
from urllib.parse import urlsplit
from yt_dlp import YoutubeDL, main
from yt_dlp.extractor.youtube._video import YoutubeIE
from yt_dlp.networking.common import Response

event("yt_dlp.imports", clock() - started)

def timed(owner, attribute, label):
    original = getattr(owner, attribute, None)
    if original is None:
        event("hook.unavailable." + attribute)
        return

    @contextmanager
    def measured(name):
        if name is None:
            yield
            return
        at = clock()
        event(name + ".start")
        outcome = ".ok"
        try:
            yield
        except BaseException:
            outcome = ".error"
            raise
        finally:
            event(name + outcome, clock() - at)

    def name_for(args, kwargs):
        return label(*args, **kwargs) if callable(label) else label

    if inspect.isgeneratorfunction(original):
        @wraps(original)
        def invocation(*args, **kwargs):
            with measured(name_for(args, kwargs)):
                return (yield from original(*args, **kwargs))
    else:
        @wraps(original)
        def invocation(*args, **kwargs):
            with measured(name_for(args, kwargs)):
                return original(*args, **kwargs)

    setattr(owner, attribute, invocation)

def request_group(_, req):
    endpoint = urlsplit(req if isinstance(req, str) else getattr(req, "url", ""))
    host = (endpoint.hostname or "").lower()
    if host == "youtube.com" or host.endswith(".youtube.com") or host == "youtubei.googleapis.com":
        path = endpoint.path
        if path.endswith("/youtubei/v1/player"):
            return "http.player_api"
        if path.startswith("/s/player/"):
            return "http.player_js"
        if path.startswith("/watch"):
            return "http.watch_page"
        return "http.youtube_other"
    if host.endswith(".googlevideo.com"):
        return "http.googlevideo"
    if host == "manifest.googlevideo.com":
        return "http.googlevideo"
    return "http.other"

timed(YoutubeDL, "urlopen", request_group)
timed(Response, "read", "http.body_read")
timed(http.client.HTTPConnection, "request", "http.wire_send")
timed(http.client.HTTPConnection, "getresponse", "http.wire_headers")
timed(YoutubeIE, "_extract_player_responses", "youtube.player_responses")
timed(YoutubeIE, "_extract_formats_and_subtitles", "youtube.formats_and_subtitles")
timed(time, "sleep", "sleep.intentional")

original_communicate = subprocess.Popen.communicate
@wraps(original_communicate)
def communicate(process, *args, **kwargs):
    argv = process.args
    binary = os.path.basename(argv[0]) if isinstance(argv, (tuple, list)) and argv else ""
    if binary not in {"libqjs.so", "qjs", "node", "deno", "bun"}:
        return original_communicate(process, *args, **kwargs)
    probe = len(argv) > 1 and argv[1] in {"-h", "--help", "-v", "--version"}
    label = "js.runtime_probe" if probe else "js.challenge_process"
    at = clock()
    event(label + ".start")
    try:
        return original_communicate(process, *args, **kwargs)
    finally:
        event(label + ".finished", clock() - at)

subprocess.Popen.communicate = communicate
event("yt_dlp.cli.start")
main()
