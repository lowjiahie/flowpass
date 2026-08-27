from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table
from docx.text.paragraph import Paragraph


ROOT = Path(r"C:\Users\admin\Documents\ChatGPT\Approval System")
SOURCE = ROOT / "审批流微服务_第一阶段技术方案_V0.1.docx"
OUTPUT = ROOT / "审批流微服务_第一阶段技术方案_V0.1.md"


def iter_blocks(document):
    for child in document.element.body.iterchildren():
        if child.tag == qn("w:p"):
            yield Paragraph(child, document)
        elif child.tag == qn("w:tbl"):
            yield Table(child, document)


def paragraph_shading(paragraph):
    p_pr = paragraph._p.pPr
    if p_pr is None:
        return None
    shd = p_pr.find(qn("w:shd"))
    return shd.get(qn("w:fill")) if shd is not None else None


def paragraph_num_id(paragraph):
    p_pr = paragraph._p.pPr
    if p_pr is None or p_pr.numPr is None or p_pr.numPr.numId is None:
        return None
    return p_pr.numPr.numId.val


def escape_table_text(text):
    return text.replace("|", r"\|").replace("\n", "<br>").strip()


def table_to_markdown(table):
    rows = []
    for row in table.rows:
        rows.append([escape_table_text(cell.text) for cell in row.cells])
    if not rows:
        return []
    width = len(rows[0])
    lines = ["| " + " | ".join(rows[0]) + " |"]
    lines.append("| " + " | ".join(["---"] * width) + " |")
    for row in rows[1:]:
        normalized = row[:width] + [""] * max(0, width - len(row))
        lines.append("| " + " | ".join(normalized) + " |")
    return lines


def paragraph_to_markdown(paragraph):
    text = paragraph.text.strip()
    if not text:
        return []

    style = paragraph.style.name if paragraph.style else ""
    shading = paragraph_shading(paragraph)
    num_id = paragraph_num_id(paragraph)

    if style == "Title":
        return [f"# {text}"]
    if style == "Subtitle":
        return [f"*{text}*"]
    if style.startswith("Heading "):
        try:
            level = int(style.split()[-1]) + 1
        except ValueError:
            level = 2
        return [f"{'#' * level} {text}"]

    if shading == "F7F8FA" or any(run.font.name == "Consolas" for run in paragraph.runs):
        return ["```text", paragraph.text.rstrip(), "```"]

    if shading:
        return ["> " + text.replace("  ", " — ", 1)]

    if num_id is not None:
        marker = "-" if str(num_id) == "100" else "1."
        return [f"{marker} {text}"]

    if style == "Small Note":
        return [f"*{text}*"]

    if text == "技术方案 / PHASE 1":
        return [f"**{text}**"]
    return [text]


def convert():
    doc = Document(SOURCE)
    output = []
    for block in iter_blocks(doc):
        if isinstance(block, Paragraph):
            lines = paragraph_to_markdown(block)
        else:
            lines = table_to_markdown(block)
        if not lines:
            continue
        if output and output[-1] != "":
            output.append("")
        output.extend(lines)

    content = "\n".join(output).rstrip() + "\n"
    OUTPUT.write_text(content, encoding="utf-8")
    print("Markdown created")


if __name__ == "__main__":
    convert()
