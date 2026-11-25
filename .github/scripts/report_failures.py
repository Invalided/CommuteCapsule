#!/usr/bin/env python3
"""汇总 Gradle 构建失败原因：Kotlin/Java 编译错误与失败的单元测试。

用法：report_failures.py <构建日志> [测试结果目录 ...]

输出三处：
  1. 标准输出（Actions 日志里直接可见）；
  2. GitHub 注解（::error），在提交和 PR 页面上定位到源码行；
  3. GITHUB_STEP_SUMMARY（运行摘要页）。
脚本本身总是以 0 退出，不改变作业结果。
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX_ITEMS = 50
MAX_TRACE_LINES = 12

# Kotlin：e: file:///path/Foo.kt:12:5 message 或 e: /path/Foo.kt: (12, 5): message
KT_URI = re.compile(r"^e: (?:file://)?(?P<file>[^\s:]+\.kts?):(?P<line>\d+):(?P<col>\d+) (?P<msg>.*)$")
KT_OLD = re.compile(r"^e: (?P<file>[^\s:]+\.kts?): \((?P<line>\d+), (?P<col>\d+)\): (?P<msg>.*)$")
JAVA = re.compile(r"^(?P<file>[^\s:]+\.java):(?P<line>\d+): error: (?P<msg>.*)$")
GRADLE_WHAT = re.compile(r"^\* What went wrong:")


def rel(path):
    ws = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
    return os.path.relpath(path, ws) if path.startswith("/") else path


def escape(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def compile_errors(log_path):
    errors, what = [], []
    if not os.path.exists(log_path):
        return errors, what
    with open(log_path, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    for i, line in enumerate(lines):
        line = line.rstrip()
        m = KT_URI.match(line) or KT_OLD.match(line) or JAVA.match(line)
        if m:
            errors.append((rel(m.group("file")), m.group("line"), m.group("col") if "col" in m.groupdict() else "1",
                           m.group("msg")))
        elif GRADLE_WHAT.match(line):
            block = []
            for nxt in lines[i + 1:i + 12]:
                if nxt.startswith("* Try:") or nxt.startswith("* Exception is:"):
                    break
                if nxt.strip():
                    block.append(nxt.rstrip())
            what.append("\n".join(block))
    seen, uniq = set(), []
    for e in errors:
        if e not in seen:
            seen.add(e)
            uniq.append(e)
    return uniq, what


def test_failures(dirs):
    out = []
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, "**", "TEST-*.xml"), recursive=True)):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                for kind in ("failure", "error"):
                    node = case.find(kind)
                    if node is None:
                        continue
                    trace = (node.text or "").strip().splitlines()
                    out.append({
                        "name": "%s.%s" % (case.get("classname", "?"), case.get("name", "?")),
                        "message": (node.get("message") or (trace[0] if trace else "")).strip(),
                        "trace": trace[:MAX_TRACE_LINES],
                    })
    return out


def main():
    if len(sys.argv) < 2:
        print("usage: report_failures.py <build.log> [test-results-dir ...]")
        return
    log_path, test_dirs = sys.argv[1], sys.argv[2:]
    errors, what = compile_errors(log_path)
    tests = test_failures(test_dirs)
    summary = []

    if errors:
        print("==== 编译错误（%d） ====" % len(errors))
        summary.append("### 编译错误（%d）\n" % len(errors))
        for f, ln, col, msg in errors[:MAX_ITEMS]:
            print("%s:%s:%s  %s" % (f, ln, col, msg))
            print("::error file=%s,line=%s,col=%s::%s" % (f, ln, col, escape(msg)))
            summary.append("- `%s:%s` %s" % (f, ln, msg))
        if len(errors) > MAX_ITEMS:
            summary.append("- ……另有 %d 条" % (len(errors) - MAX_ITEMS))
        summary.append("")

    if tests:
        print("==== 失败的单元测试（%d） ====" % len(tests))
        summary.append("### 失败的单元测试（%d）\n" % len(tests))
        for t in tests[:MAX_ITEMS]:
            print("- %s\n  %s" % (t["name"], t["message"]))
            for tl in t["trace"][1:]:
                print("    " + tl)
            print("::error title=%s::%s" % (escape(t["name"]), escape(t["message"])))
            summary.append("- `%s`：%s" % (t["name"], t["message"].replace("\n", " ")))
        summary.append("")

    if not errors and not tests and what:
        print("==== Gradle 失败原因 ====")
        summary.append("### Gradle 失败原因\n")
        for w in what:
            print(w)
            summary.append("```\n%s\n```" % w)

    if not errors and not tests and not what:
        print("构建日志中未识别到编译错误或失败用例，请查看上一步的完整输出。")

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary and summary:
        with open(step_summary, "a", encoding="utf-8") as f:
            f.write("\n".join(summary) + "\n")


if __name__ == "__main__":
    main()
