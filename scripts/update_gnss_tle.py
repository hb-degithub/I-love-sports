#!/usr/bin/env python3
"""刷新内置 GNSS 星表（assets/gnss_tle.txt）。

TLE 由 CelesTrak 公开的 GP 数据提供，有效期通常为数周：位置误差会随时间缓慢增大
（GPS/北斗 MEO 约每天几十米到几百米量级）。建议每次发版前或每 1-2 个月刷新一次。

用法：
    python3 scripts/update_gnss_tle.py            # 写入 app/src/main/assets/gnss_tle.txt
    python3 scripts/update_gnss_tle.py --check    # 只检查现有星表的历元新鲜度

只依赖标准库。需要能访问 https://celestrak.org/NORAD/elements/gp.php
"""

import argparse
import datetime
import os
import sys
import urllib.request

GROUPS = [("gps-ops", "GPS"), ("glo-ops", "GLONASS"), ("galileo", "Galileo"), ("beidou", "BeiDou")]
TARGET = os.path.join("app", "src", "main", "assets", "gnss_tle.txt")
STALE_DAYS = 45


def fetch(group: str) -> str:
    url = f"https://celestrak.org/NORAD/elements/gp.php?GROUP={group}&FORMAT=tle"
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode("utf-8", errors="replace")


def newest_epoch(text: str) -> datetime.datetime:
    newest = None
    for line in text.splitlines():
        if not line.startswith("1 "):
            continue
        try:
            yy = int(line[18:20])
            doy = float(line[20:32])
        except (ValueError, IndexError):
            continue
        year = 1900 + yy if yy >= 57 else 2000 + yy
        epoch = datetime.datetime(year, 1, 1, tzinfo=datetime.timezone.utc) \
            + datetime.timedelta(days=doy - 1)
        newest = epoch if newest is None or epoch > newest else newest
    return newest


def check(path: str) -> int:
    if not os.path.isfile(path):
        print(f"缺少星表：{path}")
        return 1
    text = open(path, encoding="utf-8").read()
    epoch = newest_epoch(text)
    if epoch is None:
        print("星表里解析不到 TLE 历元")
        return 1
    age = (datetime.datetime.now(datetime.timezone.utc) - epoch).days
    count = sum(1 for line in text.splitlines() if line.startswith("1 "))
    print(f"{path}: {count} 颗，最新历元 {epoch.strftime('%Y-%m-%d %H:%MZ')}（{age} 天前）")
    if age > STALE_DAYS:
        print(f"警告：星表已过期 {age} 天（阈值 {STALE_DAYS}），建议运行本脚本刷新")
        return 2
    return 0


def update() -> int:
    header = [
        "# GNSS TLE snapshot (CelesTrak GP, public data)",
        f"# fetched: {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}",
    ]
    body = []
    for group, label in GROUPS:
        try:
            data = fetch(group)
        except Exception as exc:  # noqa: BLE001 - 网络问题直接报错退出
            print(f"抓取 {group} 失败：{exc}", file=sys.stderr)
            return 1
        lines = [line.rstrip() for line in data.splitlines() if line.strip()]
        header.append(f"# {label}: {len(lines) // 3} sats")
        body.extend(lines)
        print(f"{label}: {len(lines) // 3} 颗")
    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    with open(TARGET, "w", encoding="utf-8") as handle:
        handle.write("\n".join(header) + "\n" + "\n".join(body) + "\n")
    print(f"已写入 {TARGET}")
    return check(TARGET)


def main() -> int:
    parser = argparse.ArgumentParser(description="刷新/检查内置 GNSS TLE 星表")
    parser.add_argument("--check", action="store_true", help="只检查现有星表新鲜度")
    args = parser.parse_args()
    return check(TARGET) if args.check else update()


if __name__ == "__main__":
    raise SystemExit(main())
