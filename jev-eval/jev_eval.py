"""Jev 中文评测：比较几种题目写法和 state 格式在阴阳怪气、网络黑话、敷衍、情绪、回复质量上的表现。

key 只从环境变量 TYPESAFE_API_KEY 读取，不会打印，也不会写进结果文件。
    python jev_eval.py --dry-run   离线检查请求格式，不需要 key
    python jev_eval.py --limit 5   只跑前 5 条样例
    python jev_eval.py             全量，报告和原始结果写到 results/<时间>/

state 类型：prod = 手机上现有的字符串格式；ctx = 结构化 JSON 带上下文；noctx = 结构化 JSON 不带上下文。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from itertools import combinations
from pathlib import Path
from statistics import mean

ENDPOINT = "https://api.typesafe.ai/v1/systemone"
USD_PER_MTOK = 0.042
HERE = Path(__file__).resolve().parent

# 与 JevProtocol.kt 当前版本一致
FOCUS = "只评价待评价消息的发送者；上下文只用于理解语境，不评价其他人的消息。"
PROD_EMOTIONS = {"平静": "语气中性或平静", "愉快": "表达高兴或感谢", "难过": "表达悲伤或失落",
                 "生气": "表达愤怒或不满", "焦虑": "表达担忧或紧张"}
PROD_EMOTION_OF = {"平静": "平静", "开心": "愉快", "感激": "愉快", "难过": "难过", "不满": "生气", "焦虑": "焦虑"}
PROD_GRADES = ["C：没有回应重点或语气明显不妥", "B：回应了重点，但表达比较敷衍",
               "A：清晰、得体地回应了对方", "SSS：切合上下文，体贴且有效地解决问题"]

# 例句刻意避开 samples.json 里的句子，免得把答案写进题目
SINCERE = ["你今天气色真好", "多亏你提醒，不然我就忘了"]
TEASE = ["哈哈你这手气也太背了吧", "行啊你，深藏不露嘛"]
YYGQ_YES = ["哟，稀客啊，还知道回来", "您老人家终于想起我了", "是是是，你永远是对的", "那可真是谢谢你了，帮了个大倒忙"]
YYGQ_NO = SINCERE + ["哈哈你这手气也太背了吧（朋友间开玩笑）"]
PERF_YES = ["噢", "知道了", "随你"]
PERF_NO = ["好，明天见", "这个想法不错，细说说"]
SLANG = ["awsl", "夺笋", "破防", "社死", "芭比Q了"]
EMOTIONS_ZH_V1 = {"平静": "语气中性、平静", "开心": "高兴、兴奋", "感激": "感谢对方", "难过": "悲伤、失落",
                  "不满": "生气、不满、埋怨，包括用反话表达的", "焦虑": "担忧、紧张", "其他": "以上都不是"}
EMOTIONS_ZH = EMOTIONS_ZH_V1 | {"不满": "生气、不满、埋怨"}
IRONY = {"真心": "字面意思就是真实意图，没有说反话", "调侃": "朋友间开玩笑，用反话或夸张逗对方，没有真实敌意",
         "阴阳怪气": "用反话、嘲讽或假客气挖苦对方、表达不满"}
IRONY_EX = {option: {"含义": meaning, "例子": examples}
            for (option, meaning), examples in zip(IRONY.items(), (SINCERE, TEASE, YYGQ_YES))}


def noul(instructions, criteria=None):
    return {"type": "noul", "instructions": instructions} | ({"criteria": criteria} if criteria else {})


def score(instructions, levels):
    return {"type": "score", "instructions": instructions, "criteria": levels}


def choice(instructions, options):
    return {"type": "choice", "instructions": instructions, "criteria": options}


IRONY_Q = "结合 `conversation`，`target.text` 是真心话、朋友间的调侃，还是阴阳怪气？"
YYGQ_Q = "`target.text` 是不是在阴阳怪气：用反话、嘲讽或假客气挖苦对方、表达不满？结合 `conversation` 判断。"
EMOTION_Q = "结合 `conversation`，`target.text` 的发送者真实的情绪是什么？看真实意图，不看字面。"

INCOMING = {
    "irony_zh": choice(IRONY_Q, IRONY),
    "irony_zh_ex": choice(IRONY_Q, IRONY_EX),
    "yygq_zh_ex": noul(YYGQ_Q, {
        "true": {"含义": "用反话、嘲讽或假客气挖苦对方、表达不满", "例子": YYGQ_YES},
        "false": {"含义": "字面意思就是真实意图，或朋友间没有敌意的玩笑", "例子": YYGQ_NO},
    }),
    "teasing_en": noul("Is `target.text` friendly teasing: ironic or joking words between close people, "
                       "without real hostility? Use `conversation` for context."),
    "teasing_zh_ex": noul("`target.text` 是不是朋友间的善意调侃：用反话或夸张逗对方，但没有真实敌意？结合 `conversation` 判断。", {
        "true": {"含义": "开玩笑地逗对方，没有敌意", "例子": TEASE},
        "false": {"含义": "真心话，或带敌意的挖苦", "例子": [SINCERE[0], YYGQ_YES[2]]},
    }),
    "slang_zh": noul(f"`target.text` 是否用了网络流行语、黑话、拼音或数字缩写、网络梗（例如 {'、'.join(SLANG)}）？"),
    "perfunctory_en": noul("Is `target.text` a perfunctory or dismissive reply to `conversation`: "
                           "minimal effort, cold, or brushing the other person off?"),
    "perfunctory_zh_ex": noul("`target.text` 是不是在敷衍：只用很少的字应付，没有接住 `conversation` 里对方的话？", {
        "true": {"含义": "字很少、没接话、没投入，本身不带嘲讽或敌意", "例子": PERF_YES},
        "false": {"含义": "认真回应；或虽然短但切题；带嘲讽或不满的反话不算敷衍", "例子": PERF_NO},
    }),
    "emotion_zh_v1": choice(EMOTION_Q, EMOTIONS_ZH_V1),
    "emotion_zh": choice(EMOTION_Q, EMOTIONS_ZH),
}

OUTGOING = {
    "respond_zh": score("`target.text` 在多大程度上回应了 `conversation` 里对方最后说的话？", ["没有回应，答非所问", "部分回应", "完整回应"]),
    "warmth_zh": score("`target.text` 给了对方多少温度和情绪支持？", ["冷淡、打发", "中性", "温暖、体贴"]),
    "tone_zh": score("在这个情境下，`target.text` 的语气是否得体？", ["带敌意或不得体", "还算得体", "体贴周到"]),
    "risk_cold_zh": noul("对方读到 `target.text` 会不会觉得冷淡、敷衍？"),
    "risk_yygq_zh": noul("对方读到 `target.text` 会不会觉得是在阴阳怪气、说反话？"),
}

BINARY = {"yygq_zh_ex": "yygq", "teasing_en": "teasing", "teasing_zh_ex": "teasing", "slang_zh": "slang",
          "perfunctory_en": "perfunctory", "perfunctory_zh_ex": "perfunctory",
          "risk_cold_zh": "risk_cold", "risk_yygq_zh": "risk_yygq"}


def is_incoming(sample):
    return sample["target"]["from"] != "我"


def irony_gold(sample):
    gold = sample["gold"]
    return "阴阳怪气" if gold["yygq"] else "调侃" if gold["teasing"] else "真心"


def irony_by_rule(answers, teasing):
    y, t = answers["yygq_zh_ex"]["noul"], answers[teasing]["noul"]
    return "阴阳怪气" if y >= 0.5 and y > t else "调侃" if t >= 0.5 else "真心"


def prod_payload(sample, model):
    history = "\n".join(f"{m['from']}：{m['text'][:80]}" for m in sample["conversation"])
    target = sample["target"]
    state = (f"上下文（按时间顺序，仅供参考）：\n{history}\n" if history else "") + \
        f"待评价消息（发送者：{target['from']}）：\n{target['text']}"
    if is_incoming(sample):
        questions = {"emotion": choice(f"{FOCUS}\n结合上下文，判断这条消息主要表达了哪种情绪", PROD_EMOTIONS)}
    else:
        questions = {"rating": score(f"{FOCUS}\n结合上下文，评价这条回复是否得体、清楚并切合对方的消息", PROD_GRADES)}
    return {"model": model, "state": state, "questions": questions}


def json_payload(sample, model, with_context):
    state = {"conversation": sample["conversation"] if with_context else [], "target": sample["target"]}
    return {"model": model, "state": state, "questions": INCOMING if is_incoming(sample) else OUTGOING}


def validate(questions):
    for qid, q in questions.items():
        criteria = q.get("criteria")
        ok = q["type"] in ("choice", "score", "noul") and q["instructions"] and {
            "choice": lambda: isinstance(criteria, dict) and 2 <= len(criteria) <= 255,
            "score": lambda: isinstance(criteria, list) and len(criteria) >= 2,
            "noul": lambda: criteria is None or set(criteria) <= {"true", "false"},
        }[q["type"]]()
        if not ok:
            raise ValueError(f"题目格式不对：{qid}")


def call(key, payload):
    body = json.dumps(payload, ensure_ascii=False).encode()
    for attempt in range(6):
        request = urllib.request.Request(ENDPOINT, data=body, method="POST", headers={
            "Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = json.load(response)
            data["latency_ms"] = round((time.monotonic() - started) * 1000)
            return data
        except urllib.error.HTTPError as e:
            if e.code != 429 and e.code < 500:
                raise RuntimeError(f"HTTP {e.code}：{e.read().decode(errors='replace')[:500]}") from None
            retry_after = e.headers.get("Retry-After", "")
            time.sleep(float(retry_after) if retry_after.isdigit() else 2 ** attempt)
        except (urllib.error.URLError, TimeoutError):
            time.sleep(2 ** attempt)
    raise RuntimeError("重试 6 次仍失败")


def binary_metrics(pairs):
    tp = sum(p >= 0.5 and g for p, g in pairs)
    fp = sum(p >= 0.5 and not g for p, g in pairs)
    fn = sum(p < 0.5 and g for p, g in pairs)
    pos = [p for p, g in pairs if g]
    neg = [p for p, g in pairs if not g]
    auc = mean(1.0 if a > b else 0.5 if a == b else 0.0 for a in pos for b in neg) if pos and neg else None
    return [len(pairs), (len(pairs) - fp - fn) / len(pairs), tp / (tp + fp) if tp + fp else None,
            tp / (tp + fn) if tp + fn else None, mean(pos) if pos else None, mean(neg) if neg else None, auc]


def pairwise_agreement(pairs):
    scored = [1.0 if (a - b) * (ga - gb) > 0 else 0.5 if a == b else 0.0
              for (a, ga), (b, gb) in combinations(pairs, 2) if ga != gb]
    return mean(scored) if scored else None


def ratio(flags):
    return mean(map(float, flags)) if flags else None


def fmt(value):
    return "-" if value is None else f"{value:.2f}" if isinstance(value, float) else str(value)


def report(results):
    answers = lambda r: r["response"]["answers"]
    tokens = sum(r["response"]["usage"]["input_tokens"] for r in results)
    models = ", ".join(sorted({r["response"]["model"] for r in results}))
    lines = [f"模型 {models}；请求 {len(results)} 个；输入 {tokens} token，约 ${tokens / 1e6 * USD_PER_MTOK:.4f}；"
             f"平均耗时 {mean(r['response']['latency_ms'] for r in results):.0f} ms", "",
             "## 是/否题（≥ 0.5 记为是）", "",
             "| 题目 | state | n | 准确率 | 精确率 | 召回率 | 正例均值 | 负例均值 | AUC |", "|---|---|---|---|---|---|---|---|---|"]
    misses = []
    for qid, field in BINARY.items():
        for kind in ("ctx", "noctx"):
            pairs = []
            for r in results:
                gold = r["sample"]["gold"].get(field)
                if r["kind"] != kind or qid not in answers(r) or gold is None:
                    continue
                p = answers(r)[qid]["noul"]
                pairs.append((p, gold))
                if (p >= 0.5) != gold:
                    misses.append(f"- {qid}/{kind} {r['sample']['id']}「{r['sample']['target']['text']}」"
                                  f"p={p:.2f}，应为{'是' if gold else '否'}")
            if pairs:
                lines.append(f"| {qid} | {kind} | " + " | ".join(map(fmt, binary_metrics(pairs))) + " |")

    lines += ["", "## 反话类型（真心 / 调侃 / 阴阳怪气；rule = 阴阳怪气题与调侃题比大小）", "",
              "| 做法 | state | n | 准确率 | 困难样例准确率 | 阴阳怪气精确率 | 阴阳怪气召回率 |", "|---|---|---|---|---|---|---|"]
    approaches = {
        "irony_zh": lambda a: a["irony_zh"]["choice"],
        "irony_zh_ex": lambda a: a["irony_zh_ex"]["choice"],
        "rule+teasing_zh_ex": lambda a: irony_by_rule(a, "teasing_zh_ex"),
        "rule+teasing_en": lambda a: irony_by_rule(a, "teasing_en"),
    }
    for name, predict in approaches.items():
        for kind in ("ctx", "noctx"):
            rows = [(predict(answers(r)), irony_gold(r["sample"]), r["sample"].get("hard", False))
                    for r in results if r["kind"] == kind and is_incoming(r["sample"])]
            if rows:
                lines.append(f"| {name} | {kind} | {len(rows)} | {fmt(ratio(p == g for p, g, _ in rows))} | "
                             f"{fmt(ratio([p == g for p, g, hard in rows if hard]))} | "
                             f"{fmt(ratio([g == '阴阳怪气' for p, g, _ in rows if p == '阴阳怪气']))} | "
                             f"{fmt(ratio([p == '阴阳怪气' for p, g, _ in rows if g == '阴阳怪气']))} |")

    lines += ["", "## 情绪（多选一；prod 为手机上现有的 5 类，开心和感激都算“愉快”，不满算“生气”；v1 的“不满”含“包括用反话表达的”）", "",
              "| 题目 | state | n | 准确率 | 阴阳怪气组准确率 | 平均置信度 |", "|---|---|---|---|---|---|"]
    for qid, kind in (("emotion", "prod"), ("emotion_zh_v1", "ctx"), ("emotion_zh", "ctx"),
                      ("emotion_zh_v1", "noctx"), ("emotion_zh", "noctx")):
        rows = []
        for r in results:
            gold = r["sample"]["gold"].get("emotion")
            if r["kind"] != kind or qid not in answers(r) or gold is None:
                continue
            answer = answers(r)[qid]
            rows.append((answer["choice"] == (PROD_EMOTION_OF[gold] if kind == "prod" else gold),
                         r["sample"]["group"] == "阴阳怪气", answer["confidence"]))
        if rows:
            lines.append(f"| {qid} | {kind} | {len(rows)} | {fmt(ratio(h for h, _, _ in rows))} | "
                         f"{fmt(ratio([h for h, in_group, _ in rows if in_group]))} | {fmt(mean(c for _, _, c in rows))} |")

    lines += ["", "## 我的回复质量（与标准答案高低排序的两两一致率；composite = 回应、温度、语气三项平均）", "",
              "| 题目 | state | n | 一致率 |", "|---|---|---|---|"]
    variants = [("rating", "prod")] + [(f"{d}_zh", kind) for kind in ("ctx", "noctx")
                                        for d in ("respond", "warmth", "tone", "composite")]
    for qid, kind in variants:
        pairs = []
        for r in results:
            if r["kind"] != kind or is_incoming(r["sample"]):
                continue
            if qid == "composite_zh":
                value = mean(answers(r)[f"{d}_zh"]["score"] / (len(answers(r)[f"{d}_zh"]["legend"]) - 1)
                             for d in ("respond", "warmth", "tone"))
            else:
                value = answers(r)[qid]["score"]
            pairs.append((value, r["sample"]["gold"]["quality"]))
        if pairs:
            lines.append(f"| {qid} | {kind} | {len(pairs)} | {fmt(pairwise_agreement(pairs))} |")

    lines += ["", "## 同一句话、不同上下文（阴阳怪气题概率 / 三分类里阴阳怪气的概率；反话在前）", ""]
    by_pair = {}
    for r in results:
        if r["kind"] != "prod" and is_incoming(r["sample"]) and "pair" in r["sample"]:
            by_pair.setdefault((r["sample"]["pair"], r["kind"]), []).append(r)
    for (pair, kind), rs in sorted(by_pair.items()):
        rs.sort(key=lambda r: not r["sample"]["gold"]["yygq"])
        lines.append(f"- {pair}｜{kind}｜" + " vs ".join(
            f"{r['sample']['id']} {answers(r)['yygq_zh_ex']['noul']:.2f} / "
            f"{answers(r)['irony_zh_ex']['probabilities']['阴阳怪气']:.2f}" for r in rs))

    lines += ["", "## 困难样例逐条（ctx）", ""]
    for r in results:
        s, a = r["sample"], answers(r)
        if r["kind"] != "ctx" or not s.get("hard"):
            continue
        if is_incoming(s):
            irony = a["irony_zh_ex"]
            lines.append(f"- {s['id']}「{s['target']['text']}」应为{irony_gold(s)}"
                         f"{'、敷衍' if s['gold']['perfunctory'] else ''}{'、黑话' if s['gold']['slang'] else ''}"
                         f"｜三分类 {irony['choice']} {irony['probabilities'][irony['choice']]:.2f}"
                         f"｜阴阳 {a['yygq_zh_ex']['noul']:.2f} 调侃 {a['teasing_zh_ex']['noul']:.2f} "
                         f"敷衍 {a['perfunctory_zh_ex']['noul']:.2f} 黑话 {a['slang_zh']['noul']:.2f}"
                         f"｜情绪 {a['emotion_zh']['choice']}")
        else:
            g = s["gold"]
            lines.append(f"- {s['id']}「{s['target']['text']}」应为质量 {g['quality']}"
                         f"{'、冷淡' if g['risk_cold'] else ''}{'、像阴阳怪气' if g['risk_yygq'] else ''}"
                         f"｜回应 {a['respond_zh']['score']:.1f} 温度 {a['warmth_zh']['score']:.1f} "
                         f"语气 {a['tone_zh']['score']:.1f}｜冷淡 {a['risk_cold_zh']['noul']:.2f} "
                         f"阴阳 {a['risk_yygq_zh']['noul']:.2f}")

    lines += ["", "## 判错的是/否题", ""] + (misses or ["无"])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="只检查请求格式，不联网")
    parser.add_argument("--limit", type=int, help="只跑前 N 条样例")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--model", default="jev-latest")
    args = parser.parse_args()
    sys.stdout.reconfigure(errors="replace")

    samples = json.loads((HERE / "samples.json").read_text(encoding="utf-8"))[:args.limit]
    jobs = [(s, kind, payload) for s in samples for kind, payload in (
        ("prod", prod_payload(s, args.model)),
        ("ctx", json_payload(s, args.model, True)),
        ("noctx", json_payload(s, args.model, False)))]
    for _, _, payload in jobs:
        validate(payload["questions"])
    if args.dry_run:
        print(f"{len(samples)} 条样例，{len(jobs)} 个请求，题目格式检查通过。示例请求：")
        for sample, kind, payload in jobs:
            if kind == "ctx" and sample["id"] in ("yy01", "my01"):
                print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    key = os.environ.get("TYPESAFE_API_KEY")
    if not key:
        sys.exit("请先在当前终端设置环境变量 TYPESAFE_API_KEY")
    with ThreadPoolExecutor(args.workers) as pool:
        futures = [pool.submit(call, key, payload) for _, _, payload in jobs]
        try:
            for done, future in enumerate(as_completed(futures), 1):
                future.result()
                print(f"\r已完成 {done}/{len(futures)}", end="", flush=True)
        except Exception:
            pool.shutdown(cancel_futures=True)
            raise
    print()

    results = [{"sample": s, "kind": kind, "payload": payload, "response": future.result()}
               for (s, kind, payload), future in zip(jobs, futures)]
    text = report(results)
    out = HERE / "results" / datetime.now().strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True)
    (out / "raw.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "report.md").write_text(text, encoding="utf-8")
    print(text)
    print(f"\n报告：{out / 'report.md'}")


if __name__ == "__main__":
    main()
