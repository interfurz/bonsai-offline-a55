"""Section-aware Markdown conversion; budgets estimate ceil(UTF-8 bytes / 4)."""
import hashlib
import html
import json
import math
import re
from collections import Counter
from dataclasses import dataclass

import mwparserfromhell as mw
from mwparserfromhell import nodes

# These sections mostly contain bibliography, navigation or redundant metadata.
OMIT_SECTIONS = {"einzelnachweise", "literatur", "weblinks", "siehe auch", "quellen",
                 "anmerkungen", "referenzen", "literatur und quellen"}
OMIT_NAMESPACES = {"datei", "bild", "file", "image", "media", "kategorie", "category",
                   "vorlage", "template", "hilfe", "wikipedia", "spezial", "commons"}
OMIT_TAGS = {"ref", "references", "gallery", "imagemap", "timeline", "score", "mapframe",
             "maplink", "table", "style", "script", "templatestyles"}


@dataclass(frozen=True)
class Section:
    path: tuple
    level: int
    text: str


@dataclass(frozen=True)
class Chunk:
    article_id: str
    title: str
    heading: str
    text: str
    source: str
    section_paths: tuple = ()
    stable_key: str = ""

    @property
    def id(self):
        # Positive signed int64: shared SQLite/Java/vector-file contract.
        return (int(self.stable_key[:16], 16) & ((1 << 63) - 1)) or 1


def tokens(text):
    return math.ceil(len(text.encode("utf-8")) / 4)


def _render(code):
    out = []
    for node in code.nodes:
        if isinstance(node, nodes.Text):
            out.append(str(node))
        elif isinstance(node, nodes.Wikilink):
            target = str(node.title).strip().lstrip(":")
            if target.partition(":")[0].casefold() not in OMIT_NAMESPACES:
                out.append(_render(node.text if node.text is not None else node.title))
        elif isinstance(node, nodes.ExternalLink):
            label = _render(node.title) if node.title is not None else str(node.url)
            url = str(node.url).strip().replace("(", "%28").replace(")", "%29")
            if url.startswith(("https://", "http://")):
                out.append(f"[{label}]({url})")
            else:
                out.append(label)
        elif isinstance(node, nodes.Template):
            # Preserve a small set of inline text wrappers, never nav/infoboxes.
            name = str(node.name).strip().casefold()
            param = "2" if name == "lang" else "1"
            if (name in {"lang", "nowrap", "nobr", "sic"} or name.startswith("lang-")) and node.has(param):
                out.append(_render(node.get(param).value))
        elif isinstance(node, nodes.Tag):
            tag = str(node.tag).casefold()
            if tag in OMIT_TAGS or str(node.wiki_markup) == "{|":
                continue
            attributes = {str(a.name).casefold(): str(a.value or "").casefold() for a in node.attributes}
            if set(attributes.get("class", "").split()) & {"navbox", "infobox", "metadata", "noprint", "thumb", "toc", "hatnote", "ambox", "sisterproject"} or "display:none" in attributes.get("style", "").replace(" ", ""):
                continue
            body = _render(node.contents) if node.contents else ""
            if tag in {"b", "strong", "i", "em"}:
                marker = "**" if tag in {"b", "strong"} else "*"
                out.append(marker + body + marker)
            elif tag == "math":
                out.append("$" + str(node.contents).strip() + "$")
            elif tag in {"code", "syntaxhighlight", "source", "pre"}:
                out.append("\n```\n" + str(node.contents).strip() + "\n```\n" if tag != "code" else "`" + body + "`")
            elif tag == "li" and str(node.wiki_markup) in {"*", "#"}:
                out.append("\x01" + str(node.wiki_markup) + "\x02")
            elif tag in {"p", "div", "section", "blockquote", "dl"}:
                out.append("\n\n" + body + "\n\n")
            elif tag == "br":
                out.append("\n")
            else:
                out.append(body)
        elif isinstance(node, nodes.HTMLEntity):
            out.append(node.normalize())
        # Comments, parser instructions and headings are handled elsewhere.
    return "".join(out)


def _normalize(text):
    text = html.unescape(text).replace("\xa0", " ")
    # Wiki list markers -> Markdown. Keep indentation and paragraph boundaries.
    text = re.sub(r"(?m)^((?:\x01[*#]\x02)+)[ \t]*", lambda m: "  " * (len(m[1]) // 3 - 1) + ("1. " if "#" == m[1][-2] else "- "), text)
    text = text.replace("\x01", "").replace("\x02", "")
    text = re.sub(r"(?m)^[;:]\s*", "", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def clean(text):
    """Clean a Wikicode fragment to Markdown without expanding remote templates."""
    return _normalize(_render(mw.parse(text)))


def sections(wiki):
    """Use parser heading nodes, so headings in templates/nowiki cannot split text."""
    code = mw.parse(wiki)
    result, path, body = [], [], []
    level, omitted = 0, None

    def flush():
        text = _normalize(_render(mw.wikicode.Wikicode(body)))
        if text and omitted is None:
            result.append(Section(tuple(value for _, value in path), level, text))

    for node in code.nodes:
        if isinstance(node, nodes.Heading):
            flush()
            body = []
            level = node.level
            name = clean(str(node.title))
            path = [(n, value) for n, value in path if n < level] + [(level, name)]
            if omitted is not None and level <= omitted:
                omitted = None
            if name.casefold() in OMIT_SECTIONS:
                omitted = level if omitted is None else min(level, omitted)
        else:
            body.append(node)
    flush()
    return result


def markdown_article(title, article_sections):
    return "# " + title + "\n\n" + "\n\n".join(_section_text(s) for s in article_sections)


def _section_text(section):
    return (("#" * section.level + " " + section.path[-1] + "\n\n") if section.path else "") + section.text


def split_large(text, maximum):
    """Prefer paragraphs, then sentences/words; bound even one long Unicode word."""
    if maximum < 1:
        raise ValueError("maximum must be positive")
    if tokens(text) <= maximum:
        return [text] if text.strip() else []
    parts, current, size = [], [], 0
    # Keep separators, including newlines, in the output.
    for word in re.findall(r"\S+\s*", text):
        word_size = len(word.encode("utf-8"))
        if size + word_size > maximum * 4 and current:
            parts.append("".join(current).strip())
            current, size = [], 0
        if word_size <= maximum * 4:
            current.append(word)
            size += word_size
        else:
            for char in word:
                width = len(char.encode("utf-8"))
                if size + width > maximum * 4:
                    parts.append("".join(current).strip())
                    current, size = [], 0
                current.append(char)
                size += width
    if current:
        tail = "".join(current).strip()
        if tail:
            parts.append(tail)
    return parts


def _split_prose(text, maximum):
    # Pack paragraphs/sentences first, falling back to bounded word splitting.
    units = re.split(r"(?<=\n\n)(?=\S)|(?<=[.!?])(?=\s+\S)", text)
    pieces, current = [], ""
    for unit in units:
        if tokens(current + unit) <= maximum:
            current += unit
        else:
            if current.strip():
                pieces.append(current.strip())
            smaller = split_large(unit, maximum)
            pieces.extend(smaller[:-1])
            current = smaller[-1] if smaller else ""
    if current.strip():
        pieces.append(current.strip())
    return pieces


def chunk_article(article_id, title, wiki, source, minimum=80, maximum=800,
                  article_sections=None):
    if not 0 < minimum <= maximum:
        raise ValueError("Require 0 < minimum <= maximum")
    parsed = sections(wiki) if article_sections is None else article_sections
    groups = []
    for section in parsed:
        # Keep the introduction separate, even when it is short.
        if groups and section.path and groups[-1][-1].path:
            previous = groups[-1]
            combined = "\n\n".join(_section_text(s) for s in previous + [section])
            short = tokens("\n\n".join(_section_text(s) for s in previous)) < minimum or tokens(_section_text(section)) < minimum
            # Merge adjacent short sections only within the configured budget.
            related = section.path[0] == previous[-1].path[0] or (
                len(section.path) == 1 and all(len(s.path) == 1 for s in previous))
            if related and short and tokens(combined) <= maximum:
                previous.append(section)
                continue
        groups.append([section])
    result, occurrences = [], Counter()
    for group in groups:
        paths = tuple((title,) + s.path for s in group)
        heading = " | ".join(" / ".join(p) for p in paths)
        text = "\n\n".join(_section_text(s) for s in group)
        pieces = _split_prose(text, maximum)
        if len(pieces) > 1 and tokens(pieces[-1]) < minimum:
            # Repack the last two pieces near their byte midpoint at a word boundary.
            combined = pieces[-2] + "\n\n" + pieces[-1]
            words = list(re.finditer(r"\s+", combined))
            candidates = [m for m in words if max(tokens(combined[:m.start()]), tokens(combined[m.end():])) <= maximum]
            if candidates:
                boundary = min(candidates, key=lambda m: abs(len(combined[:m.start()].encode("utf-8")) - len(combined.encode("utf-8")) / 2))
                pieces[-2:] = [combined[:boundary.start()].strip(), combined[boundary.end():].strip()]
        for part in pieces:
            identity = json.dumps(["wiki-chunk-v2", str(article_id), paths, part], ensure_ascii=False, separators=(",", ":"))
            occurrence = occurrences[identity]
            occurrences[identity] += 1
            key = hashlib.sha256((identity + ":" + str(occurrence)).encode("utf-8")).hexdigest()
            result.append(Chunk(str(article_id), title, heading, part, source, paths, key))
    return result
