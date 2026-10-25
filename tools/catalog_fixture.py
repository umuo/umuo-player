#!/usr/bin/env python3
"""给已标记的合成夹具增加电影、未刮削视频和跨季剧集；绝不操作真实媒体库。"""
import argparse
import os
from pathlib import Path
import shutil


def generate(root):
    root=Path(root).resolve()
    if not (root/'.umuo-fixture').is_file():
        raise SystemExit('Refusing unmarked fixture directory')
    sample=root/'Arrival.2024.mp4'
    if not sample.is_file():
        raise SystemExit('Generate the standard fixture first')
    target=root/'Catalog';target.mkdir(exist_ok=True)
    def video(path):
        if not path.exists():os.link(sample,path)
    for name,title,genre,tag,collection in [
        ('CinemaOne.2024','星海远航','科幻','珍藏','星海合集'),
        ('CinemaTwo.2022','城市回声','剧情','家庭','')
    ]:
        video(target/(name+'.mp4'))
        (target/(name+'.nfo')).write_text(f'<movie><title>{title}</title><year>2024</year><plot>仅用于本机影视库验证的合成视频。</plot><genre>{genre}</genre><tag>{tag}</tag><set><name>{collection}</name></set></movie>',encoding='utf-8')
    video(target/'Unmatched.2023.mp4')
    show=target/'Show';show.mkdir(exist_ok=True)
    (show/'tvshow.nfo').write_text('<tvshow><title>群星记事</title><year>2023</year><genre>科幻</genre><tag>珍藏</tag></tvshow>',encoding='utf-8')
    for season,episodes in [(1,[1,2]),(2,[1])]:
        folder=show/f'Season{season}';folder.mkdir(exist_ok=True)
        for episode in episodes:video(folder/f'S{season:02}E{episode:02}.mp4')
    art=Path(__file__).resolve().parent/'fixture-art/cinema-poster.png'
    if art.is_file():
        shutil.copyfile(art,target/'poster.png');shutil.copyfile(art,show/'poster.png')
    print('Synthetic catalog fixture ready')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('root')
    generate(parser.parse_args().root)
