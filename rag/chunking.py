"""Section-aware chunking. Token budgets use UTF-8 bytes/4 (an estimate)."""
import math
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Chunk:
    article_id: str
    title: str
    heading: str
    text: str
    source: str


def tokens(text):
    return math.ceil(len(text.encode("utf-8")) / 4)


def clean(text):
    import mwparserfromhell
    text = re.sub(r"<!--.*?-->|<ref\b[^>]*>.*?</ref\s*>|<ref\b[^>]*/>", "", text,
                  flags=re.S | re.I)
    code = mwparserfromhell.parse(text)
    for template in code.filter_templates(recursive=False):
        code.remove(template)
    for link in code.filter_wikilinks(recursive=False):
        if str(link.title).lower().startswith(("datei:", "bild:", "file:", "kategorie:")):
            code.remove(link)
    return code.strip_code(normalize=True, collapse=True).strip()


def split_large(text, maximum):
    # Split even a single unbroken word; never exceed the UTF-8 budget.
    parts, current = [], ""
    for word in re.findall(r"\S+\s*", text):
        if tokens(current + word) > maximum and current:
            parts.append(current.strip())
            current = ""
        for char in word:
            if tokens(current + char) > maximum:
                parts.append(current.strip())
                current = ""
            current += char
    if current.strip():
        parts.append(current.strip())
    return parts


def chunk_article(article_id, title, wiki, source, minimum=80, maximum=800):
    if not 0 < minimum <= maximum:
        raise ValueError("Require 0 < minimum <= maximum")
    sections, path, start = [], [], 0
    for match in re.finditer(r"^(={2,6})\s*(.*?)\s*\1\s*$", wiki, re.M):
        sections.append((" / ".join([title] + [v for _, v in path]), clean(wiki[start:match.start()])))
        level = len(match[1])
        path = [(n, v) for n, v in path if n < level] + [(level, clean(match[2]))]
        start = match.end()
    sections.append((" / ".join([title] + [v for _, v in path]), clean(wiki[start:])))
    merged = []
    for heading, text in sections:
        if not text:
            continue
        if merged and (tokens(merged[-1][1]) < minimum or tokens(text) < minimum):
            old_heading, old_text = merged.pop()
            heading = old_heading if heading == old_heading else old_heading + " | " + heading
            text = old_text + "\n\n" + text
        merged.append((heading, text))
    result = []
    for heading, text in merged:
        pieces = split_large(text, maximum)
        # Balance a tiny final fragment with its predecessor.
        if len(pieces) > 1 and tokens(pieces[-1]) < minimum:
            combined = pieces[-2] + " " + pieces[-1]
            middle = len(combined) // 2
            boundary = combined.find(" ", middle)
            if boundary < 0:
                boundary = middle
            left, right = combined[:boundary].strip(), combined[boundary:].strip()
            if left and right and max(tokens(left), tokens(right)) <= maximum:
                pieces[-2:] = [left, right]
        result.extend(Chunk(str(article_id), title, heading, part, source) for part in pieces)
    return result
