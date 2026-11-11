"""Keep the implementation ledger aligned with every work package in the plan."""
from pathlib import Path
import argparse
import json
import re

ROOT = Path(__file__).resolve().parents[1]
PLAN = ROOT / "docs/upstream-2.3.2-android-implementation-plan.md"
LEDGER = ROOT / "docs/development-progress.json"
OUTPUT = ROOT / "docs/development-progress.md"
STATES = {"pending": "未开发", "in_progress": "进行中", "verified": "已验收"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate the Markdown view")
    args = parser.parse_args()
    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    planned = set(re.findall(r"^\| (F\d{2}) \|", PLAN.read_text(encoding="utf-8"), re.M))
    items = ledger["features"]
    ids = [item["id"] for item in items]
    assert len(ids) == len(set(ids)), "Duplicate work-package ID"
    assert set(ids) == planned, f"Missing: {planned - set(ids)}; unexpected: {set(ids) - planned}"
    for item in items:
        assert item["status"] in STATES, item["id"]
        assert item["remaining"] or item["status"] == "verified", item["id"]
        if item["status"] == "verified":
            assert item["evidence"] and not item["remaining"], item["id"]
        for source in item.get("sources", []):
            assert (ROOT / source).is_file(), f"Missing implementation: {source}"
    planned_tasks = set(re.findall(r"\*\*(T\d{2}) ", PLAN.read_text(encoding="utf-8")))
    tasks = ledger["tasks"]
    assert len(tasks) == len({task["id"] for task in tasks}), "Duplicate task ID"
    assert {task["id"] for task in tasks} == planned_tasks, "Missing first-batch task"
    for task in tasks:
        assert task["status"] in STATES and task["detail"], task["id"]
    lines = ["# Theia 开发进度核对", "", f"更新日期：{ledger['date']}。", "",
             "本文件由 `docs/development-progress.json` 生成。每批开发结束更新事实、证据和剩余项，运行 `python tools/check_development_progress.py --write`；校验会拒绝漏掉方案中的工作包。", "",
             "## 当前批次", "", ledger["batch"], "", "## 验证与限制", ""]
    lines += [f"- {entry}" for entry in ledger["validation"]]
    def cell(value: str) -> str:
        return value.replace("|", " / ").replace("\n", " ")
    lines += ["", "## 首批任务核对", "", "| 编号 | 任务 | 状态 | 核对结果 |", "| --- | --- | --- | --- |"]
    for task in tasks:
        lines.append("| " + " | ".join(map(cell, [task["id"], task["name"], STATES[task["status"]], task["detail"]])) + " |")
    lines += ["", "## 全部工作包", "", "| 编号 | 功能 | 状态 | 本轮完成/已有证据 | 仍需开发或验收 |",
              "| --- | --- | --- | --- | --- |"]
    for item in items:
        lines.append("| " + " | ".join(map(cell, [item["id"], item["name"], STATES[item["status"]],
            "；".join(item["evidence"]) or "—", "；".join(item["remaining"]) or "—"])) + " |")
    lines += ["", "## 下一步", ""] + [f"{i}. {entry}" for i, entry in enumerate(ledger["next"], 1)]
    expected = "\n".join(lines) + "\n"
    if args.write:
        OUTPUT.write_text(expected, encoding="utf-8", newline="\n")
    else:
        assert OUTPUT.read_text(encoding="utf-8") == expected, "Progress Markdown is stale; run --write"
    print(f"Progress verified: all {len(items)} planned work packages present; " +
          ", ".join(f"{sum(i['status'] == state for i in items)} {label}" for state, label in STATES.items()))


if __name__ == "__main__":
    main()
