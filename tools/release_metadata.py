#!/usr/bin/env python3
"""校验唯一版本配置；标签发布不能悄悄使用另一版本号。"""
import argparse
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--tag")
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
props = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line and not line.lstrip().startswith("#"))
version = props["umuoVersionName"]
code = props["umuoVersionCode"]
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?", version):
    parser.error("umuoVersionName must be a semantic version")
if not code.isdigit() or not 0 < int(code) <= 2100000000:
    parser.error("umuoVersionCode must be a positive Android version code")
if args.tag and args.tag != "v" + version:
    parser.error("tag must exactly match v" + version)
print("version=" + version)
print("code=" + code)
print("prerelease=" + str("-" in version).lower())
