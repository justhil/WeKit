"""上下文评测：同一句话、只差一个语境因素的成对样例，比较几种上下文组法。

key 只从环境变量 TYPESAFE_API_KEY 读取，不会打印，也不会写进结果文件。
    python context_eval.py --dry-run   离线检查请求格式，不需要 key
    python context_eval.py             全量，报告和原始结果写到 results/<时间>-context/

组法：prod6 = 手机上现有的最近 6 条纯文本；session = 按对话段落取，加占位符、引用、回复间隔；
+rel / +after / +all 在 session 上加两人关系、之后的回复或两者都加；long40 = 不分段取最近 40 条。
"""

import argparse
import copy
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from jev_eval import INCOMING, OUTGOING, USD_PER_MTOK, call, fmt, irony_by_rule, ratio, validate

HERE = Path(__file__).resolve().parent
SESSION_GAP_MIN = 6 * 60
MAX_MESSAGES = 30
NEAR, NEAR_CHARS, FAR_CHARS = 5, 300, 80
GAP_NOTE_MIN = 30
PLACEHOLDERS = {"image": "[图片]", "sticker": "[表情包]", "voice": "[语音]", "video": "[视频]", "transfer": "[转账]",
                "red_packet": "[红包]", "pat": "[拍了拍]", "recall": "[撤回了一条消息]", "call": "[语音通话]"}
MENTIONS = {"quote": "`target.quote`（被引用的消息）", "relationship": "`relationship`（两人关系）",
            "after": "`after`（目标之后的相关消息）"}
INCOMING_Q = {k: INCOMING[k] for k in ("yygq_zh_ex", "teasing_zh_ex", "slang_zh", "perfunctory_zh_ex", "emotion_zh")}
OUTGOING_Q = {k: OUTGOING[k] for k in ("respond_zh", "warmth_zh", "tone_zh", "risk_cold_zh", "risk_yygq_zh")}
VARIANTS = {
    "prod6": {"window": "prod"},
    "session": {"window": "session"},
    "session+rel": {"window": "session", "relationship": True},
    "session+after": {"window": "session", "after": True},
    "session+all": {"window": "session", "relationship": True, "after": True},
    "long40": {"window": "long"},
}


def gap_text(minutes):
    if minutes >= 1440:
        return f"隔了 {minutes // 1440} 天"
    if minutes >= 60:
        return f"隔了 {minutes // 60} 小时"
    return f"隔了 {minutes} 分钟"


def pick(messages, mode):
    if mode == "prod":
        return [m for m in messages if "type" not in m][-6:]
    if mode == "long":
        return messages[-40:]
    # 目标消息前的那一条无论隔多久都保留（迟到的回复仍在回应它），再往前遇到超过 6 小时的空档就算另一段对话
    picked = messages[-1:]
    for m in reversed(messages[:-1]):
        if len(picked) >= MAX_MESSAGES or picked[-1]["t"] - m["t"] > SESSION_GAP_MIN:
            break
        picked.append(m)
    return picked[::-1]


def build_state(sample, variant):
    prod = variant["window"] == "prod"
    picked = pick(sample["messages"], variant["window"])
    conversation = []
    for i, m in enumerate(picked):
        if prod:
            conversation.append({"from": m["from"], "text": m["text"][:FAR_CHARS]})
            continue
        item = {"from": m["from"],
                "text": PLACEHOLDERS.get(m.get("type")) or m["text"][:NEAR_CHARS if i >= len(picked) - NEAR else FAR_CHARS]}
        if i and m["t"] - picked[i - 1]["t"] >= GAP_NOTE_MIN:
            item["gap"] = gap_text(m["t"] - picked[i - 1]["t"])
        conversation.append(item)
    target = {"from": sample["target"]["from"], "text": sample["target"]["text"]}
    if not prod:
        if picked and -picked[-1]["t"] >= GAP_NOTE_MIN:
            target["gap"] = gap_text(-picked[-1]["t"])
        if "quote" in sample["target"]:
            target["quote"] = sample["target"]["quote"]
    state = {"conversation": conversation, "target": target}
    if variant.get("relationship") and sample.get("relationship"):
        state["relationship"] = sample["relationship"]
    if variant.get("after") and sample.get("after"):
        later = sample["after"]
        if sample["target"]["from"] != "我":
            later = [m for m in later if m["from"] == sample["target"]["from"]]
        if later:
            state["after"] = [{"from": m["from"], "text": m["text"]} for m in later]
    return state


def build_questions(sample, state):
    base = OUTGOING_Q if sample["target"]["from"] == "我" else INCOMING_Q
    extra = [MENTIONS[key] for key, present in (("quote", "quote" in state["target"]),
                                               ("relationship", "relationship" in state),
                                               ("after", "after" in state)) if present]
    if not extra:
        return base
    mention = "、".join(["`conversation`", *extra])
    pointed = copy.deepcopy(base)
    for q in pointed.values():
        if "结合 `conversation`" in q["instructions"]:
            q["instructions"] = q["instructions"].replace("结合 `conversation`", f"结合 {mention}")
        else:
            q["instructions"] += f"结合 {mention} 判断。"
    return pointed


def judge(sample, answers):
    field, gold = next(iter(sample["gold"].items()))
    if field == "irony":
        predicted = irony_by_rule(answers, "teasing_zh_ex")
        return predicted == gold, (f"{predicted} 阴{answers['yygq_zh_ex']['noul']:.2f}/"
                                   f"调{answers['teasing_zh_ex']['noul']:.2f}")
    p = answers[f"{field}_zh"]["noul"]
    return (p >= 0.5) == gold, f"{p:.2f}"


def report(samples, results):
    lines = []
    tokens = sum(r["response"]["usage"]["input_tokens"] for r in results.values())
    lines += [f"唯一请求 {len(results)} 个；输入 {tokens} token，约 ${tokens / 1e6 * USD_PER_MTOK:.4f}", "",
              "## 汇总（成对 = 一对样例两条都判对）", "", "| 组法 | 准确率 | 成对判对 |", "|---|---|---|"]
    table = {}
    for name in VARIANTS:
        rows = {s["id"]: judge(s, results[key(s, name)]["response"]["answers"]) for s in samples}
        table[name] = rows
        pairs = {}
        for s in samples:
            if "pair" in s:
                pairs.setdefault(s["pair"], []).append(rows[s["id"]][0])
        lines.append(f"| {name} | {fmt(ratio(ok for ok, _ in rows.values()))} | "
                     f"{sum(all(v) for v in pairs.values())}/{len(pairs)} |")
    lines += ["", "## 逐条（✓ 判对，✗ 判错；反话题显示判定、阴阳怪气与调侃概率）", "",
              "| 样例 | 考察 | 标准 | " + " | ".join(VARIANTS) + " |", "|---|---|---|" + "---|" * len(VARIANTS)]
    for s in samples:
        gold = next(iter(s["gold"].values()))
        cells = [f"{'✓' if table[name][s['id']][0] else '✗'} {table[name][s['id']][1]}" for name in VARIANTS]
        lines.append(f"| {s['id']} | {s['element']} | {gold} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def key(sample, variant_name):
    return json.dumps(payload(sample, VARIANTS[variant_name], "jev-latest"), ensure_ascii=False, sort_keys=True)


def payload(sample, variant, model):
    state = build_state(sample, variant)
    return {"model": model, "state": state, "questions": build_questions(sample, state)}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="只检查请求格式，不联网")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    sys.stdout.reconfigure(errors="replace")

    samples = json.loads((HERE / "context_samples.json").read_text(encoding="utf-8"))
    unique = {key(s, name): payload(s, VARIANTS[name], "jev-latest") for s in samples for name in VARIANTS}
    for p in unique.values():
        validate(p["questions"])
    if args.dry_run:
        print(f"{len(samples)} 条样例 × {len(VARIANTS)} 种组法，去重后 {len(unique)} 个请求，题目格式检查通过。示例：")
        for s in samples:
            if s["id"] in ("c01a", "c05a", "c09a"):
                print(s["id"], "prod6 / session+all:")
                print(json.dumps(build_state(s, VARIANTS["prod6"]), ensure_ascii=False))
                print(json.dumps(payload(s, VARIANTS["session+all"], "jev-latest"), ensure_ascii=False, indent=1))
        return

    api_key = os.environ.get("TYPESAFE_API_KEY")
    if not api_key:
        sys.exit("请先在当前终端设置环境变量 TYPESAFE_API_KEY")
    with ThreadPoolExecutor(args.workers) as pool:
        futures = {pool.submit(call, api_key, p): k for k, p in unique.items()}
        try:
            for done, future in enumerate(as_completed(futures), 1):
                future.result()
                print(f"\r已完成 {done}/{len(futures)}", end="", flush=True)
        except Exception:
            pool.shutdown(cancel_futures=True)
            raise
    print()

    results = {k: {"payload": unique[k], "response": f.result()} for f, k in futures.items()}
    text = report(samples, results)
    out = HERE / "results" / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-context")
    out.mkdir(parents=True)
    (out / "raw.json").write_text(json.dumps(list(results.values()), ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "report.md").write_text(text, encoding="utf-8")
    print(text)
    print(f"\n报告：{out / 'report.md'}")


if __name__ == "__main__":
    main()
