#!/usr/bin/env python3
"""复现 OpenList 播放跳转；仅服务已标记合成目录，CDN 拒收任何来源认证头。"""
import argparse
import json
import hashlib
import ssl
import threading
import time
from pathlib import Path
from http.server import ThreadingHTTPServer
from urllib.parse import quote, urlsplit, parse_qs
from fixture_server import handler


def run(root,port,cdn_port,tls_port,cert,key):
    root=root.resolve()
    if not (root/'.umuo-fixture').exists():raise SystemExit('Refusing unmarked fixture')
    evidence=root/'redirect-evidence.jsonl'
    base=handler(root,root/'redirect-play-events.jsonl','basic')
    class Cdn(base):
        def do_GET(self):
            auth=any(self.headers.get(name) for name in ['Authorization','X-Emby-Token','Cookie'])
            with evidence.open('a') as out:out.write(json.dumps({'role':'cdn','authorization_present':auth,'range':bool(self.headers.get('Range')),'time_ms':int(time.time()*1000)})+'\n')
            if auth:return self.reply(status=403)
            if (root/'.cdn-forbidden').exists():return self.reply(status=403)
            if (root/'.cdn-playback-forbidden').exists() and self.headers.get('Range')!='bytes=0-0':return self.reply(status=403)
            if (root/'.ua-bound').exists():
                expected=hashlib.sha256(self.headers.get('User-Agent','').encode()).hexdigest()
                if parse_qs(urlsplit(self.path).query).get('ua')!=[expected]:return self.reply(status=403)
            super().do_GET()
    class Origin(base):
        def record_scan(self):
            with evidence.open('a') as out:out.write(json.dumps({'role':'origin','scan_request':True,'time_ms':int(time.time()*1000)})+'\n')
        def do_PROPFIND(self):
            self.record_scan()
            if (root/'.scan-limit').exists():return self.reply(status=429,headers={'Retry-After':'65'})
            super().do_PROPFIND()
        def do_GET(self):
            if self.decoded_path().lower().endswith('.nfo'):self.record_scan()
            if self.decoded_path().startswith('/dav/') and self.decoded_path().lower().endswith(('.mp4','.mkv')):
                if not self.authorized():return
                if (root/'.origin-forbidden').exists():return self.reply(status=403)
                file=self.file()
                if not file or not file.is_file():return self.reply(status=404)
                bad_tls=(root/'.redirect-cert-error').exists()
                protocol='https' if bad_tls else 'http';target=tls_port if bad_tls else cdn_port
                ua=hashlib.sha256(self.headers.get('User-Agent','').encode()).hexdigest()
                binding=f'&ua={ua}' if (root/'.ua-bound').exists() else ''
                return self.reply(status=302,headers={'Location':f'{protocol}://127.0.0.1:{target}/media/{quote(file.relative_to(root).as_posix(),safe="/")}?sign=fixture-only{binding}'})
            super().do_GET()
    cdn=ThreadingHTTPServer(('127.0.0.1',cdn_port),Cdn)
    threading.Thread(target=cdn.serve_forever,daemon=True).start()
    if cert and key:
        tls=ThreadingHTTPServer(('127.0.0.1',tls_port),Cdn)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,key)
        tls.socket=context.wrap_socket(tls.socket,server_side=True)
        threading.Thread(target=tls.serve_forever,daemon=True).start()
    print('Synthetic redirect fixture ready',flush=True)
    ThreadingHTTPServer(('127.0.0.1',port),Origin).serve_forever()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--port',type=int,default=18926);p.add_argument('--cdn-port',type=int,default=18927);p.add_argument('--tls-port',type=int,default=18928);p.add_argument('--cert');p.add_argument('--key')
    a=p.parse_args();run(a.root,a.port,a.cdn_port,a.tls_port,a.cert,a.key)
