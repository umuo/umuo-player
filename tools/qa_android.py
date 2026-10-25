#!/usr/bin/env python3
"""通过 UI 树定位控件的合成服务冒烟测试。只在专用测试模拟器执行。"""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--kind',choices=['WEBDAV','JELLYFIN','EMBY'],default='WEBDAV')
p.add_argument('--tv',action='store_true');p.add_argument('--port',type=int,default=18921)
p.add_argument('--output',type=Path,default=Path('verification/local'))
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(['adb','-s',a.serial,*args])
def tree():
    for _ in range(5):
        raw=adb('exec-out','uiautomator','dump','/dev/tty').decode()
        if '<?xml' in raw:return ET.fromstring(raw[raw.index('<?xml'):raw.rindex('</hierarchy>')+12])
        time.sleep(.5)
    raise RuntimeError('UI tree unavailable')
def nodes():return list(tree().iter('node'))
def tap_node(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def scroll_down():
    candidates=[n for n in nodes() if n.get('scrollable')=='true']
    if not candidates:return False
    target=max(candidates,key=lambda n: int(re.findall(r"\d+",n.attrib["bounds"])[3])-int(re.findall(r"\d+",n.attrib["bounds"])[1]))
    x1,y1,x2,y2=map(int,re.findall(r'\d+',target.attrib['bounds']))
    adb('shell','input','swipe',str((x1+x2)//2),str(y1+(y2-y1)*4//5),str((x1+x2)//2),str(y1+(y2-y1)//4),'350')
    return True
def click(text):
    if a.tv:
        # TV Material 控件按遥控/键盘焦点激活；触摸 tap 不能代替确认键。
        for _ in range(30):
            root=tree()
            focused=next((n for n in root.iter('node') if n.get('focused')=='true'),None)
            if focused is not None and any(n.get('text','').removeprefix('● ')==text for n in focused.iter('node')):
                adb('shell','input','keyevent','23');return
            adb('shell','input','keyevent','61')
        raise RuntimeError('TV focus could not reach: '+text)
    for _ in range(2):
        root=tree();parents={c:p for p in root.iter('node') for c in p}
        matches=[n for n in root.iter('node') if n.get('text')==text or n.get('content-desc')==text]
        def clickable_ancestor(n):
            while n is not None:
                if n.get('clickable')=='true':return True
                n=parents.get(n)
            return False
        match=next((n for n in matches if clickable_ancestor(n)),None)
        if match is not None:tap_node(match);return
        scroll_down();time.sleep(.5)
    raise RuntimeError('Missing control: '+text)
def field(label,value):
    for _ in range(4):
        target=next((n for n in nodes() if n.get('class')=='android.widget.EditText' and n.get('bounds')!='[0,0][0,0]' and any(c.get('text')==label for c in n.iter('node'))),None)
        if target is not None:
            tap_node(target);adb('shell','input','keyevent','123');adb('shell','input','keyevent',*(['67']*(len(target.get('text',''))+6)));adb('shell','input','text',value);adb('shell','input','keyevent','4');return
        scroll_down()
    raise RuntimeError('Missing input: '+label)
def texts():return [n.get('text','') for n in nodes() if n.get('text')]
def screenshot(name):a.output.joinpath(name+'.png').write_bytes(adb('exec-out','screencap','-p'))
package='app.umuo.player'+('.tv' if a.tv else '')
activity='app.umuo.tv.MainActivity' if a.tv else 'app.umuo.mobile.MainActivity'
adb('logcat','-c')
adb('reverse',f'tcp:{a.port}',f'tcp:{a.port}')
adb('shell','input','keyevent','224');adb('shell','wm','dismiss-keyguard')
adb('shell','am','start','-n',package+'/'+activity)
time.sleep(1)
for _ in range(3):
    if any(n.get('text')=='添加来源' or n.get('content-desc')=='添加来源' for n in nodes()):break
    adb('shell','input','keyevent','4');time.sleep(.5)
click('添加来源')
if a.tv:
    click(a.kind)
    field('名称','Fixture'+a.kind)
    field('服务器地址',f'http://127.0.0.1:{a.port}/'+('dav/' if a.kind=='WEBDAV' else ''))
else:
    if a.kind!='WEBDAV':click(a.kind)
    field('名称（选填）','Fixture'+a.kind)
    field('服务器地址',f'http://127.0.0.1:{a.port}/'+('dav/' if a.kind=='WEBDAV' else ''))
field('用户名','fixture');field('密码','fixture');click('连接并保存' if a.tv else '保存并连接');time.sleep(1)
if a.kind=='WEBDAV':
    click('影视库');time.sleep(.5);click('扫描 / 恢复');time.sleep(1);screenshot(('tv' if a.tv else 'phone')+'-wall')
    click('合成测试电影')
else:click('合成测试电影')
if a.tv:
    click('返回媒体库');time.sleep(.6)
    # 返回后的焦点应回到原卡片，确认键再次进入详情。
    adb('shell','input','keyevent','23')
    assert '播放 / 续播' in texts(),'TV focus restoration failed'
click('播放 / 续播');time.sleep(2)
all_text=texts()
assert '0:40' in all_text or any(re.search(r'0:[0-9]{2} / 0:40',v) for v in all_text),all_text
assert not any('播放失败' in v for v in all_text),all_text
if a.tv:
    adb('shell','input','keyevent','22');time.sleep(.5);adb('shell','input','keyevent','23')
else:
    click('+10 秒')
    if a.kind=='WEBDAV':
        click('字幕');click('Arrival.2024.srt');adb('shell','input','keyevent','4')
time.sleep(.5);all_text=texts();screenshot(('tv' if a.tv else 'phone')+'-'+a.kind.lower()+'-player')
adb('shell','input','keyevent','4');time.sleep(.5)
log=adb('logcat','-d','-s','UmuoPlayback:I','*:S').decode()
a.output.joinpath(('tv' if a.tv else 'phone')+'-'+a.kind.lower()+'-metrics.log').write_text(log)
a.output.joinpath(('tv' if a.tv else 'phone')+'-'+a.kind.lower()+'.json').write_text(json.dumps({'source':a.kind,'ui':'tv' if a.tv else 'mobile','playback_text':all_text,'result':'passed','server':'synthetic fixture; not real server compatibility'},ensure_ascii=False,indent=2))
print(a.kind, 'TV' if a.tv else 'mobile','fixture flow passed')
