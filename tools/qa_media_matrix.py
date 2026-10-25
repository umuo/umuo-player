#!/usr/bin/env python3
"""在专用手机模拟器的已连接合成 WebDAV/Emby 来源上检查真实格式播放。"""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,default=Path('verification/local'))
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb','-s',a.serial,*args])
def tree():
    raw=adb('exec-out','uiautomator','dump','/dev/tty').decode()
    return ET.fromstring(raw[raw.index('<?xml'):raw.rindex('</hierarchy>')+12])
def tap(n):
    x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
def texts():return [n.get('text') for n in tree().iter('node') if n.get('text')]
def click(label):
    for _ in range(5):
        root=tree();parents={c:p for p in root.iter('node') for c in p}
        for n in reversed(list(root.iter('node'))):
            if n.get('text')!=label and n.get('content-desc')!=label:continue
            node=n
            while node is not None and node.get('clickable')!='true':node=parents.get(node)
            if node is not None:tap(n);return
        scrollers=[n for n in root.iter('node') if n.get('scrollable')=='true']
        if not scrollers:break
        x,y,r,b=map(int,re.findall(r'\d+',scrollers[-1].get('bounds')))
        adb('shell','input','swipe',str((x+r)//2),str(y+(b-y)*4//5),str((x+r)//2),str(y+(b-y)//5),'300')
    raise RuntimeError('Missing '+label)
def play_case(name,expected_duration,compatible=False):
    click(name);adb('logcat','-c');click('兼容播放' if compatible else '播放 / 续播');time.sleep(1)
    visible=texts()
    if expected_duration not in visible:
        video=next((n for n in tree().iter('node') if n.get('content-desc','').startswith('视频：')),None)
        if video is not None:tap(video);time.sleep(.4)
        visible=texts()
    error=next((t for t in visible if '播放失败' in t or '无法解码' in t),None)
    logs=adb('logcat','-d','-s','UmuoPlayback:I','*:S').decode()
    first=re.findall(r'first_frame_ms=(\d+)',logs)
    result={'case':name,'mode':'HLS compatibility' if compatible else 'WebDAV direct','duration':expected_duration,
            'first_frame_ms':int(first[-1]) if first else None,'error':error,'passed':expected_duration in visible and bool(first) and error is None}
    if name=='MultiAudio':
        click('音轨');result['available_audio_tracks']=[t for t in texts() if 'audio/' in t]
        adb('shell','input','keyevent','4')
    a.output.joinpath('format-'+('hls' if compatible else name)+'.png').write_bytes(adb('exec-out','screencap','-p'))
    adb('shell','input','keyevent','4');time.sleep(.7)
    return result
if not any('Fixture' in t for t in texts()):raise RuntimeError('仅允许在已连接合成夹具的测试应用运行')
results=[]
# 进入固定合成来源，避免上一次用例停留在其他来源。
click('来源');click('FixtureDAV');time.sleep(1);click('海报墙');time.sleep(.7)
for name,duration in [('HEVC','0:12'),('MultiAudio','0:20'),('HighBitrate','0:08')]:
    results.append(play_case(name,duration))
    a.output.joinpath('format-matrix.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
click('来源');click('FixtureEMBY');time.sleep(1);click('合成测试电影');time.sleep(.7)
# 兼容播放已在详情页，此处直接执行，保持同一用户操作路径。
adb('logcat','-c');click('兼容播放');time.sleep(1)
visible=texts();logs=adb('logcat','-d','-s','UmuoPlayback:I','*:S').decode();first=re.findall(r'first_frame_ms=(\d+)',logs)
results.append({'case':'HLS','mode':'Emby compatibility','first_frame_ms':int(first[-1]) if first else None,
                'passed':'0:40' in visible and bool(first) and not any('播放失败' in t for t in visible)})
adb('shell','input','keyevent','4')
a.output.joinpath('format-matrix.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print(json.dumps(results,ensure_ascii=False))
