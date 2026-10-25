#!/usr/bin/env python3
"""Emby/Jellyfin 观看功能的内存合成服务；仅绑定回环、仅操作已标记夹具。"""
import argparse
from http.server import ThreadingHTTPServer
from pathlib import Path
from threading import Lock
from urllib.parse import parse_qs, urlsplit
from fixture_server import handler


def client_handler(root):
    if not (root/'.umuo-fixture').is_file():raise ValueError('Refusing unmarked fixture')
    base=handler(root,root/'client-events.jsonl','basic')
    states={}
    guard=Lock()
    rows=[
        {'Id':'movies','Name':'电影库','Type':'CollectionFolder','IsFolder':True},
        {'Id':'shows','Name':'剧集库','Type':'CollectionFolder','IsFolder':True},
        {'Id':'movie1','Name':'合成测试电影','Type':'Movie','Genres':['科幻'],'Tags':['珍藏'],'ProductionYear':2024},
        {'Id':'series1','Name':'合成测试剧集','Type':'Series','IsFolder':True},
        {'Id':'season1','Name':'第 1 季','Type':'Season','IsFolder':True},
        {'Id':'episode1','Name':'第一集','Type':'Episode','SeriesName':'合成测试剧集','ParentIndexNumber':1,'IndexNumber':1},
        {'Id':'episode2','Name':'第二集','Type':'Episode','SeriesName':'合成测试剧集','ParentIndexNumber':1,'IndexNumber':2},
        {'Id':'set1','Name':'合成合集','Type':'BoxSet','IsFolder':True}
    ]
    def item(row):
        with guard:
            user=dict(states.get(row['Id'],{'IsFavorite':False,'Played':False,'PlaybackPositionTicks':120000000 if row['Id']=='movie1' else 0}))
        return dict(row,UserData=user,RunTimeTicks=400000000,ImageTags={'Primary':'fixture'},Overview='仅用于观看客户端验证的合成内容。')
    class Client(base):
        def user_flag(self,value):
            path=self.decoded_path().removeprefix('/emby')
            if '/FavoriteItems/' not in path and '/PlayedItems/' not in path:return False
            self.rfile.read(int(self.headers.get('Content-Length',0)))
            if (root/'.client-deny').exists():self.reply(status=403);return True
            identity=path.split('/')[-1]
            with guard:
                current=states.setdefault(identity,{'IsFavorite':False,'Played':False,'PlaybackPositionTicks':120000000 if identity=='movie1' else 0})
                if '/FavoriteItems/' in path:current['IsFavorite']=value
                else:current.update(Played=value,PlaybackPositionTicks=0)
                result=dict(current)
            self.reply(result);return True
        def do_POST(self):
            if not self.user_flag(True):super().do_POST()
        def do_DELETE(self):
            if not self.user_flag(False):super().do_DELETE()
        def do_GET(self):
            path=self.decoded_path().removeprefix('/emby');query=parse_qs(urlsplit(self.path).query)
            def param(key,default=''):return query.get(key,[default])[0]
            if path.endswith('/Views'):return self.reply({'Items':[item(r) for r in rows[:2]],'TotalRecordCount':2})
            if path.endswith(('/Items','/Items/Resume','/Shows/NextUp')):
                data=rows[2:]
                parent=param('ParentId')
                if parent:data=[r for r in data if r['Id'] in {'movies':['movie1'],'shows':['series1'],'series1':['season1'],'season1':['episode1','episode2'],'set1':['movie1']}.get(parent,[])]
                elif param('IncludeItemTypes'):data=[r for r in data if r['Type'] in param('IncludeItemTypes').split(',')]
                data=[item(r) for r in data]
                if path.endswith('/Resume') or 'IsResumable' in param('Filters'):data=[r for r in data if r['UserData']['PlaybackPositionTicks']>0 and not r['UserData']['Played']]
                if 'IsFavorite' in param('Filters'):data=[r for r in data if r['UserData']['IsFavorite']]
                if path.endswith('/Shows/NextUp'):data=[r for r in data if r['Type']=='Episode' and not r['UserData']['Played']][:1]
                if param('SearchTerm'):data=[r for r in data if param('SearchTerm').casefold() in r['Name'].casefold()]
                count=len(data);start=int(param('StartIndex','0'));limit=int(param('Limit','60'))
                return self.reply({'Items':data[start:start+limit],'TotalRecordCount':count})
            if '/Items/' in path:
                row=next((r for r in rows if r['Id']==path.split('/')[-1]),None)
                if row:return self.reply(item(row))
            if path.startswith('/Videos/') and path.endswith('/stream'):return self.serve_file(root/'Arrival.2024.mp4')
            super().do_GET()
    return Client


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,required=True);parser.add_argument('--port',type=int,default=18925)
    args=parser.parse_args();ThreadingHTTPServer(('127.0.0.1',args.port),client_handler(args.root.resolve())).serve_forever()
