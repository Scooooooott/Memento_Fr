"""Recover the textbook's Chinese gloss column from the saved page OCR.

The OCR is line based while many glossary entries wrap.  The French headword
transcription supplies the stable sequence: a dynamic-programming alignment
selects one OCR start line per source row, then all Han text up to the next
start line is retained as that row's Chinese gloss.
"""
from __future__ import annotations

import csv
from difflib import SequenceMatcher
import json
from pathlib import Path
import re
import unicodedata


ROOT = Path(__file__).resolve().parents[2]
HEADWORDS = ROOT / "data/raw/textbook_a1_headwords.csv"
OCR = ROOT / "data/raw/textbook_a1_glossary_ocr.json"
OUTPUT = ROOT / "data/raw/textbook_a1_chinese_glosses.csv"

# OCR corrections plus sense narrowing where the app's single core sense is
# intentionally more specific than the textbook's combined printed entry.
GLOSS_OVERRIDES = {
    16: "啊！哦！",
    32: "玩耍；娱乐",
    48: "弓",
    66: "注意；当心",
    77: "和……一起；带有；以……方式",
    82: "渡船",
    88: "篮球",
    90: "美丽的；漂亮的",
    103: "《俗》亲吻",
    105: "受伤的",
    111: "好的；恰当的；正确的",
    121: "好！妙！",
    131: "《俗》这个；那个",
    135: "平静的；安静的；冷静的",
    170: "壁炉；烟囱",
    174: "马",
    203: "满员的；满座的",
    213: "紧靠；倚着",
    214: "《俗》朋友；伙伴",
    217: "正确地；合适地",
    228: "跑；奔跑",
    243: "在……里；处于……状态",
    245: "从；自；……的",
    248: "午饭；吃午饭",
    265: "晚餐；吃晚餐",
    279: "有活力的；充满活力的",
    288: "在……里；在……时；以……方式",
    314: "试；试穿；尝试",
    315: "尝试做某事",
    339: "做；从事；制造",
    345: "奇幻的；幻想的",
    366: "《俗》足球",
    374: "赢；赢得",
    378: "左边；左面",
    394: "组；群；团体",
    419: "需要；必须；应该",
    426: "不便之处；缺点",
    430: "检查员；视察员",
    431: "警探；刑警",
    444: "一月",
    467: "那儿；那里",
    493: "星期一；周一",
    510: "玛丽安娜（法国共和国的象征）",
    519: "同样的；相同的",
    535: "部；部委",
    538: "《俗》小孩；小家伙",
    563: "姓名；名字",
    565: "不；不是；没有",
    568: "新的",
    583: "有组织的；安排好的",
    607: "说话；讲话；交谈",
    610: "同某人谈论某人或某事",
    613: "经过；路过",
    618: "《俗》咖啡休息时间",
    624: "想；思考；认为",
    627: "小的；矮小的",
    656: "手机；笔记本电脑",
    663: "给；为了；对于",
    673: "准备",
    682: "《俗》教师；教授",
    690: "《俗》套头毛衣",
    693: "什么时候；当……时",
    704: "记得；想起",
    708: "寻找；搜寻",
    715: "须预约；按预约",
    728: "餐馆；饭店",
    736: "成功；做成",
    757: "《俗》你好；再见",
    775: "诗歌朗诵表演",
    776: "法国国家铁路公司",
    784: "出去；出门；外出",
    806: "《俗》讨人喜欢的；友好的",
    815: "T恤衫",
    819: "电视；电视机；电视行业",
    830: "法国高速列车",
    839: "全部；所有；完全；很",
    857: "卖；销售",
    877: "道路；车道；轨道",
    880: "邻居；邻座的人",
}


def latin_key(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode().casefold()
    return "".join(character for character in value if character.isalnum())


def headword_keys(value: str) -> list[str]:
    candidates = {
        latin_key(value),
        latin_key(value.split("(", 1)[0]),
        latin_key(re.sub(r"\([^)]*\)", "", value)),
    }
    return [candidate for candidate in candidates if candidate]


def match_score(headword: str, ocr_text: str) -> float:
    observed = latin_key(ocr_text)
    best = 0.0
    for expected in headword_keys(headword):
        for extra in (0, 1, 2):
            prefix = observed[:max(1, len(expected) + extra)]
            best = max(best, SequenceMatcher(None, expected, prefix).ratio())
        if observed.startswith(expected):
            best = max(best, 1.4)
        if len(expected) >= 4 and expected in observed[:len(expected) + 4]:
            best = max(best, 1.1)
    return best


def start_score(headword: str, item: dict) -> float:
    score = match_score(headword, item["text"])
    # A wrapped continuation can itself begin with an English cognate (for
    # example "basketball").  The printed entry's first line almost always
    # contains its Chinese column and starts at the column margin.
    if re.search(r"[\u3400-\u9fff]", item["text"]):
        score += 0.25
    baseline = 60 if item["column"] == "left" else 533
    score -= min(abs(float(item["bbox"][0][0]) - baseline), 80.0) * 0.002
    return score


def align(headwords: list[dict[str, str]], items: list[dict]) -> list[tuple[int, float]]:
    """Return (OCR item index, score) for every headword in order."""
    word_count, item_count = len(headwords), len(items)
    impossible = -1e9
    scores = [[impossible] * (item_count + 1) for _ in range(word_count + 1)]
    chosen = [[False] * (item_count + 1) for _ in range(word_count + 1)]
    scores[0] = [0.0] * (item_count + 1)
    for word_index in range(1, word_count + 1):
        headword = headwords[word_index - 1]["法语词条"]
        for item_index in range(1, item_count + 1):
            skip = scores[word_index][item_index - 1] - 0.002
            use = scores[word_index - 1][item_index - 1] + start_score(headword, items[item_index - 1])
            if use >= skip:
                scores[word_index][item_index] = use
                chosen[word_index][item_index] = True
            else:
                scores[word_index][item_index] = skip

    assignments: list[tuple[int, float]] = []
    word_index, item_index = word_count, item_count
    while word_index:
        if not item_index:
            raise ValueError("OCR alignment exhausted before all headwords were matched")
        if chosen[word_index][item_index]:
            assignments.append((item_index - 1, start_score(
                headwords[word_index - 1]["法语词条"], items[item_index - 1])))
            word_index -= 1
            item_index -= 1
        else:
            item_index -= 1
    assignments.reverse()
    return assignments


def chinese_only(value: str) -> str:
    # Latin text is the printed English column.  Retaining Han characters and
    # Chinese punctuation cleanly isolates the Chinese column even when OCR has
    # joined all three printed columns into one line.
    value = re.sub(r"[^\u3400-\u9fff，。；、！？：（）《》【】…]+", "", value)
    value = re.sub(r"([，。；、！？：…])\1+", r"\1", value)
    for opening, closing in (("（", "）"), ("《", "》"), ("【", "】")):
        stack: list[int] = []
        remove: set[int] = set()
        for index, character in enumerate(value):
            if character == opening:
                stack.append(index)
            elif character == closing:
                if stack:
                    stack.pop()
                else:
                    remove.add(index)
        remove.update(stack)
        value = "".join(character for index, character in enumerate(value) if index not in remove)
    value = value.replace("（）", "").replace("《》", "").replace("【】", "")
    value = value.strip("，。；、！？：…")
    return value


def main() -> None:
    rows = list(csv.DictReader(HEADWORDS.open(encoding="utf-8-sig", newline="")))
    document = json.loads(OCR.read_text(encoding="utf-8"))
    output_rows: list[dict[str, str]] = []

    for page in document["pages"]:
        page_number = int(page["book_page"])
        page_rows = [row for row in rows if int(row["原书页码"]) == page_number]
        items = sorted(page["items"], key=lambda item: (0 if item["column"] == "left" else 1, item["y"]))
        assignments = align(page_rows, items)
        for index, (row, (start, score)) in enumerate(zip(page_rows, assignments)):
            end = assignments[index + 1][0] if index + 1 < len(assignments) else len(items)
            joined = "".join(item["text"] for item in items[start:end])
            gloss = chinese_only(joined)
            gloss = GLOSS_OVERRIDES.get(int(row["序号"]), gloss)
            if not gloss:
                raise ValueError(f"No Chinese text recovered for row {row['序号']}: {row['法语词条']}")
            if score < 0.49:
                raise ValueError(f"Unreliable OCR alignment for row {row['序号']}: {row['法语词条']} ({score:.2f})")
            output_rows.append({
                "序号": row["序号"],
                "原书页码": row["原书页码"],
                "法语词条": row["法语词条"],
                "中文释义": gloss,
                "对齐分数": f"{score:.2f}",
            })

    if [int(row["序号"]) for row in output_rows] != list(range(1, 892)):
        raise ValueError("Chinese gloss rows do not cover textbook rows 1–891 exactly")
    with OUTPUT.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=("序号", "原书页码", "法语词条", "中文释义", "对齐分数"))
        writer.writeheader()
        writer.writerows(output_rows)
    print(json.dumps({"rows": len(output_rows), "output": str(OUTPUT)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
