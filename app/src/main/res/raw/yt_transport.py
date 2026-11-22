"""Run the bundled extractor with bounded, credential-free HTTP evidence on stderr."""
import sys
import json
import time
import os
import signal

# Native subprocesses share this isolated session. Pause/cancel terminates QuickJS and FFmpeg too.
try:
    os.setsid()
    def terminate_group(signum, frame):
        signal.signal(signal.SIGTERM, signal.SIG_DFL)
        os.killpg(os.getpgrp(), signal.SIGTERM)
    signal.signal(signal.SIGTERM, terminate_group)
except OSError:
    pass
from urllib.parse import urlsplit, parse_qs

archive = sys.argv.pop(1)
sys.path.insert(0, archive)
from yt_dlp import YoutubeDL, main

original = YoutubeDL.urlopen
seen = set()

def urlopen(self, request):
    url = request if isinstance(request, str) else getattr(request, 'url', '')
    endpoint = urlsplit(url)
    host = endpoint.hostname or ''
    query = parse_qs(endpoint.query)
    headers = dict(getattr(request, 'headers', {}) or {})
    lower = {key.lower(): value for key, value in headers.items()}
    started = time.monotonic()
    record = {
        'method': getattr(request, 'method', 'GET'), 'host': host,
        'path': endpoint.path[:180], 'query_keys': sorted(query),
        'url_ip_family': (6 if ':' in query['ip'][0] else 4) if query.get('ip') else None,
        'client': query.get('c', [None])[0], 'itag': query.get('itag', [None])[0],
        'expires_in': int(query['expire'][0]) - int(time.time()) if query.get('expire', [''])[0].isdigit() else None,
        'range': lower.get('range'), 'user_agent': lower.get('user-agent', '')[:220],
        'referer_present': 'referer' in lower, 'cookie_header_present': 'cookie' in lower,
    }
    try:
        response = original(self, request)
        record.update(status=response.status, content_type=response.headers.get('content-type'),
                      content_length=response.headers.get('content-length'), redirect=response.url != url)
        return response
    except Exception as error:
        record.update(status=getattr(error, 'status', None), error=type(error).__name__)
        raise
    finally:
        record['elapsed_ms'] = round((time.monotonic() - started) * 1000)
        key = (host, endpoint.path, record.get('status'), record['itag'])
        if key not in seen or record.get('status', 0) not in (200, 206):
            seen.add(key)
            print('SAOREO_HTTP ' + json.dumps(record, separators=(',', ':')), file=sys.stderr, flush=True)

YoutubeDL.urlopen = urlopen
main()
