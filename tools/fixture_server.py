#!/usr/bin/env python3
"""仅绑定回环的合成媒体服务；不连接用户媒体库。不是 Emby/Jellyfin 实服替代品。"""
import argparse
import base64
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit, parse_qs
from xml.sax.saxutils import escape


def generate(root, extended=False, ffmpeg="ffmpeg"):
    root.mkdir(parents=True, exist_ok=True)
    (root / '.umuo-fixture').write_text('synthetic only\n')
    def ff(args):
        subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y']+args, check=True)
    sample = root / 'Arrival.2024.mp4'
    if not sample.exists():
        ff(['-f','lavfi','-i','testsrc2=size=640x360:rate=24','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','40','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-movflags','+faststart',str(sample)])
    (root/'Arrival.2024.nfo').write_text('<movie><title>合成测试电影</title><year>2024</year><plot>独立播放器三源验证样片，无真实媒体或个人资料。</plot><uniqueid type="tmdb">0</uniqueid></movie>')
    (root/'Arrival.2024.srt').write_text('1\n00:00:00,000 --> 00:00:39,000\nUMUO 中文字幕 / SRT\n')
    (root/'Arrival.2024.vtt').write_text('WEBVTT\n\n00:00.000 --> 00:39.000\nUMUO 中文字幕 / VTT\n')
    (root/'Arrival.2024.ass').write_text('[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,sans-serif,28,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,20,1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:00.00,0:00:39.00,Default,,0,0,0,,UMUO ASS 中文字幕\n')
    series=root/'Series';series.mkdir(exist_ok=True)
    for ep in [1,2]:
        target=series/f'Planet.Earth.S01E{ep:02}.mp4'
        if not target.exists(): os.link(sample,target)
        target.with_suffix('.nfo').write_text(f'<episodedetails><title>第 {ep} 集</title><showtitle>合成测试剧集</showtitle><season>1</season><episode>{ep}</episode></episodedetails>')
    # 本地 PNG 海报，无外部请求。
    import struct,zlib
    def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    pixels=(b'\0'+bytes([32,100,104])*200)*300
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',200,300,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')
    (root/'poster.png').write_bytes(png);(series/'poster.png').write_bytes(png)
    import shutil
    art=Path(__file__).resolve().parent/'fixture-art'
    if art.exists():
        shutil.copy2(art/'cinema-poster.png',root/'poster.png')
        shutil.copy2(art/'cinema-poster.png',series/'poster.png')
        shutil.copy2(art/'cinema-backdrop.png',root/'Arrival.2024-fanart.png')
        shutil.copy2(art/'cinema-backdrop.png',series/'Planet.Earth.S01E01-fanart.png')
    hls=root/'hls';hls.mkdir(exist_ok=True)
    ff(['-i',str(sample),'-c','copy','-hls_time','4','-hls_list_size','0',str(hls/'index.m3u8')])
    if extended:
        ff(['-i',str(sample),'-t','12','-c:v','libx265','-preset','ultrafast','-c:a','aac',str(root/'HEVC.mkv')])
        ff(['-i',str(sample),'-map','0:v','-map','0:a','-map','0:a','-t','20','-c:v','copy','-c:a:0','aac','-c:a:1','ac3',str(root/'MultiAudio.mkv')])
        ff(['-f','lavfi','-i','testsrc2=size=1920x1080:rate=24','-t','8','-c:v','libx264','-preset','ultrafast','-b:v','12M','-minrate','12M','-maxrate','12M','-bufsize','24M',str(root/'HighBitrate.mp4')])


def handler(root, events, auth):
    class Fixture(BaseHTTPRequestHandler):
        server_version='UmuoFixture/1'
        def log_message(self,*args): pass  # 不记录 URL、认证头或账号
        def reply(self,data=b'',status=200,content='application/json',headers=None):
            if isinstance(data,(dict,list)):data=json.dumps(data).encode()
            if isinstance(data,str):data=data.encode()
            self.send_response(status);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(data)))
            for k,v in (headers or {}).items():self.send_header(k,v)
            self.end_headers()
            if self.command!='HEAD': self.wfile.write(data)
        def decoded_path(self):return unquote(urlsplit(self.path).path)
        def file(self):
            path=self.decoded_path()
            rel=path.removeprefix('/dav/') if path.startswith('/dav/') else path.removeprefix('/media/')
            target=(root/rel).resolve()
            if root.resolve() not in target.parents and target!=root.resolve(): return None
            return target
        def authorized(self):
            if not self.decoded_path().startswith('/dav/') or auth=='none': return True
            if auth=='basic':
                ok=self.headers.get('Authorization')=='Basic '+base64.b64encode(b'fixture:fixture').decode()
                if not ok:self.reply(status=401,headers={'WWW-Authenticate':'Basic realm="fixture"'})
                return ok
            header=self.headers.get('Authorization','')
            values={m[0]:m[1] or m[2] for m in re.findall(r'(\w+)=(?:"([^"]*)"|([^, ]+))',header)}
            md5=lambda value:hashlib.md5(value.encode()).hexdigest()
            ha1=md5('fixture:fixture:fixture');ha2=md5(f'{self.command}:{values.get("uri", "")}')
            expected=md5(f'{ha1}:fixture-nonce:{values.get("nc") }:{values.get("cnonce")}:{values.get("qop")}:{ha2}') if values.get('qop') else md5(f'{ha1}:fixture-nonce:{ha2}')
            if values.get('username')=='fixture' and values.get('response')==expected:return True
            self.reply(status=401,headers={'WWW-Authenticate':'Digest realm="fixture", nonce="fixture-nonce", algorithm=MD5, qop="auth"'})
            return False
        def api_item(self):
            return {'Id':'movie1','Name':'合成测试电影','Type':'Movie','RunTimeTicks':400000000,'ImageTags':{'Primary':'fixture'},'BackdropImageTags':['fixture'],'UserData':{'PlaybackPositionTicks':0}}
        def do_PROPFIND(self):
            if not self.authorized():return
            target=self.file()
            if not target or not target.is_dir():return self.reply(status=404)
            rows=[]
            for f in [target]+sorted(target.iterdir()):
                rel=f.relative_to(root).as_posix()
                href='/dav/'+quote(rel,safe='/')+('/' if f.is_dir() else '')
                rows.append(f'<d:response><d:href>{escape(href)}</d:href><d:propstat><d:prop><d:displayname>{escape(f.name)}</d:displayname><d:resourcetype>{"<d:collection/>" if f.is_dir() else ""}</d:resourcetype><d:getetag>{f.stat().st_mtime_ns}</d:getetag><d:getcontentlength>{f.stat().st_size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>')
            self.reply('<d:multistatus xmlns:d="DAV:">'+''.join(rows)+'</d:multistatus>',207,'application/xml')
        def do_POST(self):
            n=int(self.headers.get('Content-Length',0));body=self.rfile.read(n)
            path=self.decoded_path().removeprefix('/emby')
            if path=='/Users/AuthenticateByName':return self.reply({'AccessToken':'fixture-token','User':{'Id':'fixture-user'}})
            if path.endswith('/PlaybackInfo'):
                self.reply({'PlaySessionId':'fixture-session','MediaSources':[{'Id':'fixture-version','SupportsDirectPlay':True,'SupportsDirectStream':True,'TranscodingUrl':'/media/hls/index.m3u8','MediaStreams':[{'Type':'Subtitle','IsExternal':True,'Index':2,'Codec':'srt','DeliveryUrl':'/media/Arrival.2024.srt','DisplayTitle':'中文 SRT'}]}]});return
            if path.startswith('/Sessions/Playing'):
                with events.open('a') as out:out.write(json.dumps({'path':path,'body':json.loads(body or b'{}')})+'\n')
                self.reply(status=204);return
            self.reply(status=404)
        def do_DELETE(self):self.reply(status=204)
        def do_HEAD(self):self.do_GET()
        def do_GET(self):
            path=self.decoded_path().removeprefix('/emby')
            if path.startswith('/Users/') and path.endswith(('/Views','/Items')):return self.reply({'Items':[self.api_item()],'TotalRecordCount':1})
            if path.endswith('/Items/movie1'):return self.reply(self.api_item())
            if '/Images/Primary' in path:return self.serve_file(root/'poster.png')
            if '/Images/Backdrop/' in path:return self.serve_file(root/'Arrival.2024-fanart.png')
            if path=='/Videos/movie1/stream':return self.serve_file(root/'Arrival.2024.mp4')
            if self.decoded_path().startswith(('/dav/','/media/')):
                if not self.authorized():return
                return self.serve_file(self.file())
            self.reply(status=404)
        def serve_file(self,target):
            if not target or not target.is_file():return self.reply(status=404)
            size=target.stat().st_size; start=0;end=size-1;status=200
            headers={'Accept-Ranges':'bytes'}
            if self.headers.get('Range'):
                m=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers['Range'])
                if not m:return self.reply(status=416)
                start=int(m[1]);end=min(int(m[2]) if m[2] else size-1,size-1);status=206
                if start>end:return self.reply(status=416)
                headers['Content-Range']=f'bytes {start}-{end}/{size}'
            self.send_response(status);self.send_header('Content-Type',mimetypes.guess_type(target.name)[0] or 'application/octet-stream')
            self.send_header('Content-Length',str(end-start+1))
            for k,v in headers.items():self.send_header(k,v)
            self.end_headers()
            if self.command=='HEAD':return
            try:
                with target.open('rb') as src:
                    src.seek(start);remaining=end-start+1
                    while remaining:
                        data=src.read(min(64*1024,remaining))
                        if not data:break
                        self.wfile.write(data);remaining-=len(data)
            except (BrokenPipeError,ConnectionResetError):pass
    return Fixture

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--port',type=int,default=18921);p.add_argument('--ffmpeg',default=os.environ.get('FFMPEG_EXECUTABLE','ffmpeg'));p.add_argument('--generate',action='store_true');p.add_argument('--extended',action='store_true');p.add_argument('--auth',choices=['none','basic','digest'],default='basic')
    args=p.parse_args()
    root=args.root.resolve()
    if args.generate:generate(root,args.extended,args.ffmpeg)
    if not (root/'.umuo-fixture').exists():p.error('拒绝服务非合成目录；请先 --generate')
    ThreadingHTTPServer(('127.0.0.1',args.port),handler(root,root/'events.jsonl',args.auth)).serve_forever()
