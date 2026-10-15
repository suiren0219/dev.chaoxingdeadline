#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pull a whole semester of Chaoxing timetable data from a schedule.html URL.

Why this exists
---------------
The mobile timetable page (`/res/app/curriculum/schedule.html?curriculumUuid=...`)
loads its data from `/curriculum/getOtherLessons`, which answers **one teaching
week per request** — it cannot return a semester in a single call. Asking without
a `week` parameter returns only the current week, and every lesson in the answer
carries just that week's number in its `weeks` field. This script sweeps the
weeks, then merges the answers the same way the module does:

  * merge key is the lesson *slot* (name + weekday + first section), never the
    server's lessonConfigId — verified: that id differs between weeks for the
    same class, so id-keyed merging duplicates every lesson once per week;
  * week lists are unioned, so a course running weeks 1-16 arrives as "1","2",…
    and is stored as "1-16";
  * sweeping stops after two consecutive empty weeks (one empty week can be a
    holiday mid-semester), or at the curriculum's own `maxWeek`.

Usage
-----
    # from the page URL (curriculumUuid is extracted for you)
    python tools/fetch_semester.py "https://kb.chaoxing.com/res/app/curriculum/schedule.html?curriculumUuid=XXXX"

    # or from a previously saved JSON response
    python tools/fetch_semester.py --json response.json

    # extra options
    --cookie "JSESSIONID=..."   # only needed if the endpoint asks for a login
    --out lessons.json          # default: lessons-<uuid>.json
    --ics timetable.ics         # also write an ICS calendar
    --weeks 1-20                # restrict the sweep range

No third-party packages: urllib only, so it runs anywhere Python 3 does.
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

BASE = "https://kb.chaoxing.com"
ENDPOINT = "/curriculum/getOtherLessons"
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 16) Language/zh_CN "
    "com.chaoxing.mobile/ChaoXingStudy_3_6.4_android_phone"
)
# The same pause the module uses between weeks: one request per week is already
# gentle, but a tight loop is what trips Chaoxing's anti-spider guard.
WEEK_PAUSE_SECONDS = 1.2
# Hard ceiling for the sweep when the curriculum does not state a maxWeek.
DEFAULT_MAX_WEEK = 24


def extract_uuid(text):
    """Accepts a full schedule.html URL, a query string, or a bare uuid."""
    if not text:
        return ""
    match = re.search(r"curriculumUuid=([0-9a-fA-F\-]{8,})", text)
    if match:
        return match.group(1)
    if re.fullmatch(r"[0-9a-fA-F\-]{8,}", text.strip()):
        return text.strip()
    return ""


def http_get(path, params, cookie):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url)
    request.add_header("User-Agent", USER_AGENT)
    request.add_header("Accept", "application/json,text/plain,*/*")
    request.add_header("Accept-Language", "zh_CN")
    request.add_header("Referer", BASE + "/")
    if cookie:
        request.add_header("Cookie", cookie)
    with urllib.request.urlopen(request, timeout=20) as response:
        body = response.read()
    return json.loads(body.decode("utf-8", "replace"))


def lesson_slot(lesson):
    """The identity the module merges on. Never the server's per-week lesson id."""
    name = (lesson.get("name") or lesson.get("courseName") or "").strip()
    return "%s|%s|%s" % (name, lesson.get("dayOfWeek"), lesson.get("beginNumber"))


def to_lesson(raw):
    """Normalises one API entry into the shape the module stores."""
    name = (raw.get("name") or raw.get("courseName") or raw.get("title") or "").strip()
    day = raw.get("dayOfWeek") or 0
    begin = raw.get("beginNumber") or raw.get("beginSection") or raw.get("startSection") or 0
    if not name or not day or not begin:
        return None
    length = raw.get("length") or raw.get("sections") or raw.get("sectionLength") or 1
    try:
        length = int(length)
    except (TypeError, ValueError):
        length = 1
    return {
        "id": str(raw.get("lessonConfigId") or raw.get("lessonId") or raw.get("id") or ""),
        "name": name,
        "teacher": (raw.get("teacherName") or raw.get("teacher") or "").strip(),
        "location": (raw.get("location") or raw.get("classRoom") or "").strip(),
        "dayOfWeek": int(day),
        "beginSection": int(begin),
        "sections": max(1, length),
        "weeks": str(raw.get("weeks") or "").strip(),
    }


def weeks_of(text):
    """Parses a weeks string into a set: "1-8,10" -> {1..8,10}."""
    result = set()
    if not text:
        return result
    for token in re.split(r"[,;，；]", str(text)):
        cleaned = re.sub(r"[^0-9\-]", "", token)
        if not cleaned:
            continue
        if "-" in cleaned:
            parts = cleaned.split("-", 1)
            try:
                start, end = int(parts[0]), int(parts[1])
            except ValueError:
                continue
            if start > 0 and end >= start:
                result.update(range(start, min(end, start + 39) + 1))
        else:
            try:
                value = int(cleaned)
            except ValueError:
                continue
            if value > 0:
                result.add(value)
    return result


def format_weeks(weeks):
    """Formats a week set the compact way the servers do: {1..8,10} -> "1-8,10"."""
    ordered = sorted(weeks)
    if not ordered:
        return ""
    parts = []
    start = end = ordered[0]
    for index in range(1, len(ordered) + 1):
        current = ordered[index] if index < len(ordered) else None
        if current is not None and current == end + 1:
            end = current
            continue
        parts.append(str(start) if start == end else "%d-%d" % (start, end))
        if current is not None:
            start = end = current
    return ",".join(parts)


def merge(lessons, raw_lessons, week):
    """Folds one week's lessons into the collection, by slot, unioning weeks."""
    by_slot = {lesson_slot(item): item for item in lessons}
    for raw in raw_lessons:
        item = to_lesson(raw)
        if item is None:
            continue
        slot = lesson_slot(item)
        existing = by_slot.get(slot)
        if existing is None:
            by_slot[slot] = item
            continue
        merged_weeks = weeks_of(existing["weeks"]) | weeks_of(item["weeks"])
        # The mobile payload stamps each lesson with the queried week only, so make
        # sure this week is present even if the field was missing.
        if week > 0:
            merged_weeks.add(week)
        existing["weeks"] = format_weeks(merged_weeks)
        if not existing["teacher"] and item["teacher"]:
            existing["teacher"] = item["teacher"]
        if not existing["location"] and item["location"]:
            existing["location"] = item["location"]
    return list(by_slot.values())


def fetch_week(uuid, week, cookie):
    return http_get(ENDPOINT, {"curriculumUuid": uuid, "week": week}, cookie)


def sweep(uuid, cookie, first, last, verbose=True):
    lessons = []
    meta = {}
    empty_run = 0
    for week in range(first, last + 1):
        try:
            payload = fetch_week(uuid, week, cookie)
        except urllib.error.HTTPError as error:
            print("week %d: HTTP %s" % (week, error.code), file=sys.stderr)
            break
        except Exception as error:  # noqa: BLE001 - keep sweeping on a single failure
            print("week %d: %s" % (week, error), file=sys.stderr)
            break
        if payload.get("result") != 1:
            print("week %d: server said %r" % (week, payload.get("msg")), file=sys.stderr)
            break
        data = payload.get("data") or {}
        curriculum = data.get("curriculum") or {}
        if curriculum:
            meta = {
                "uuid": curriculum.get("uuid") or uuid,
                "firstWeekDate": curriculum.get("firstWeekDateReal")
                or curriculum.get("firstWeekDate"),
                "maxWeek": curriculum.get("maxWeek"),
                "currentWeek": curriculum.get("realCurrentWeek") or curriculum.get("currentWeek"),
                "schoolYear": curriculum.get("schoolYear"),
                "sectionTimes": curriculum.get("lessonTimeConfigArray") or [],
            }
        raw = data.get("lessonArray") or []
        before = len(lessons)
        lessons = merge(lessons, raw, week)
        added = len(lessons) - before
        if verbose:
            print("week %2d: %2d entries, %2d new -> %d stored"
                  % (week, len(raw), added, len(lessons)))
        # Emptiness is judged on what the server returned, not on whether new slots
        # appeared: once every slot is known, later weeks legitimately add nothing new
        # while still having classes, and "no growth" would end the sweep in week 3.
        if not raw:
            empty_run += 1
            if empty_run >= 2:
                if verbose:
                    print("two weeks in a row came back empty; stopping at week %d" % week)
                break
        else:
            empty_run = 0
        if week < last:
            time.sleep(WEEK_PAUSE_SECONDS)
    return lessons, meta


def write_ics(path, lessons, meta):
    """Writes one VEVENT per lesson/week so the data can be eyeballed in a calendar."""
    first_week = meta.get("firstWeekDate") or 0
    try:
        first_week = datetime.fromtimestamp(int(first_week) / 1000.0)
    except (TypeError, ValueError, OSError):
        first_week = None
    section_times = meta.get("sectionTimes") or []

    def section_minutes(index):
        """Start/end minutes since midnight for a 1-based section, from the school table."""
        if 0 < index <= len(section_times):
            text = str(section_times[index - 1])
            match = re.match(r"\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})", text)
            if match:
                start = int(match.group(1)) * 60 + int(match.group(2))
                end = int(match.group(3)) * 60 + int(match.group(4))
                return start, end
        start = 8 * 60 + (index - 1) * 55
        return start, start + 45

    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//chaoxingdeadline//tools//CN",
             "CALSCALE:GREGORIAN"]
    stamp = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    written = 0
    for lesson in lessons:
        if first_week is None:
            continue
        weeks = weeks_of(lesson["weeks"])
        if not weeks:
            # Empty means "every week" in the server's vocabulary; 1-20 is a safe read.
            weeks = set(range(1, 21))
        for week in sorted(weeks):
            day = first_week + timedelta(days=(week - 1) * 7 + (lesson["dayOfWeek"] - 1))
            start_min, _ = section_minutes(lesson["beginSection"])
            last = lesson["beginSection"] + lesson["sections"] - 1
            _, end_min = section_minutes(last)
            begin = day.replace(hour=start_min // 60, minute=start_min % 60)
            finish = day.replace(hour=end_min // 60, minute=end_min % 60)
            summary = lesson["name"]
            if lesson["teacher"]:
                summary += " · " + lesson["teacher"]
            lines += [
                "BEGIN:VEVENT",
                "UID:%s-w%d@chaoxingdeadline" % (abs(hash(lesson_slot(lesson))), week),
                "DTSTAMP:" + stamp,
                "DTSTART:" + begin.strftime("%Y%m%dT%H%M%S"),
                "DTEND:" + finish.strftime("%Y%m%dT%H%M%S"),
                "SUMMARY:" + summary,
                "LOCATION:" + (lesson["location"] or ""),
                "DESCRIPTION:第 %d 周 · 第 %d-%d 节" % (week, lesson["beginSection"], last),
                "END:VEVENT",
            ]
            written += 1
    lines.append("END:VCALENDAR")
    with open(path, "w", encoding="utf-8", newline="\r\n") as handle:
        handle.write("\n".join(lines) + "\n")
    return written


def main():
    parser = argparse.ArgumentParser(description="拉取学习通整学期课表")
    parser.add_argument("source", nargs="?", help="schedule.html URL（含 curriculumUuid）")
    parser.add_argument("--uuid", help="直接给 curriculumUuid")
    parser.add_argument("--json", help="改用已保存的接口响应 JSON（跳过网络请求）")
    parser.add_argument("--cookie", default="", help="需要登录时提供的 Cookie")
    parser.add_argument("--out", help="输出 JSON 路径（默认 lessons-<uuid>.json）")
    parser.add_argument("--ics", help="同时导出的 ICS 日历路径")
    parser.add_argument("--weeks", default="", help="限定周次范围，例如 1-20")
    parser.add_argument("--quiet", action="store_true", help="不打印每周进度")
    args = parser.parse_args()

    if args.json:
        with open(args.json, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
        data = payload.get("data") or payload
        curriculum = data.get("curriculum") or {}
        meta = {
            "uuid": curriculum.get("uuid") or "",
            "firstWeekDate": curriculum.get("firstWeekDateReal") or curriculum.get("firstWeekDate"),
            "maxWeek": curriculum.get("maxWeek"),
            "currentWeek": curriculum.get("realCurrentWeek") or curriculum.get("currentWeek"),
            "schoolYear": curriculum.get("schoolYear"),
            "sectionTimes": curriculum.get("lessonTimeConfigArray") or [],
        }
        lessons = merge([], data.get("lessonArray") or [], meta.get("currentWeek") or 0)
        print("从 JSON 读取：%d 条课程" % len(lessons))
    else:
        uuid = args.uuid or extract_uuid(args.source or "")
        if not uuid:
            parser.error("需要 curriculumUuid：给一个 schedule.html 链接或 --uuid")
        first, last = 1, DEFAULT_MAX_WEEK
        if args.weeks:
            match = re.match(r"(\d+)\s*-\s*(\d+)", args.weeks)
            if match:
                first, last = int(match.group(1)), int(match.group(2))
            else:
                first = last = int(re.sub(r"\D", "", args.weeks) or 1)
        print("课表 uuid：%s" % uuid)
        print("开始逐周拉取（每周间隔 %.1fs，遇连续两周无新数据即停止）" % WEEK_PAUSE_SECONDS)
        lessons, meta = sweep(uuid, args.cookie, first, last, verbose=not args.quiet)
        # The curriculum states the real semester length; extend the sweep if it does.
        stated = meta.get("maxWeek")
        if isinstance(stated, int) and stated > last and not args.weeks:
            print("课程表显示学期共 %d 周，继续拉取剩余周次" % stated)
            more, extra = sweep(uuid, args.cookie, last + 1, stated, verbose=not args.quiet)
            lessons = merge(lessons, [l for l in more], 0)
            meta.update({k: v for k, v in extra.items() if v})

    if not lessons:
        print("没有获取到任何课程。可能原因：uuid 有误、需要登录（加 --cookie）、或学期尚未开始。",
              file=sys.stderr)
        return 1

    lessons.sort(key=lambda item: (item["dayOfWeek"], item["beginSection"], item["name"]))
    out = args.out or ("lessons-%s.json" % (meta.get("uuid") or "unknown"))
    document = {"curriculum": meta, "lessonArray": lessons}
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)

    covered = set()
    for lesson in lessons:
        covered |= weeks_of(lesson["weeks"])
    print("")
    print("已保存 %d 门课到 %s" % (len(lessons), out))
    if covered:
        print("周次覆盖：%s" % format_weeks(covered))
    if meta.get("maxWeek"):
        print("学期长度：%s 周（接口 maxWeek）" % meta["maxWeek"])
    if meta.get("firstWeekDate"):
        try:
            stamp = int(meta["firstWeekDate"]) / 1000.0
            print("开学日期：%s" % datetime.fromtimestamp(stamp).strftime("%Y-%m-%d"))
        except (TypeError, ValueError, OSError):
            pass
    missing = sorted(set(range(1, (meta.get("maxWeek") or 0) + 1)) - covered)
    if missing:
        print("未覆盖周次：%s（这些周可能确实没课，或接口未返回）" % format_weeks(set(missing)))

    if args.ics:
        written = write_ics(args.ics, lessons, meta)
        print("已导出 %d 条日历事件到 %s" % (written, args.ics))
    return 0


if __name__ == "__main__":
    sys.exit(main())
