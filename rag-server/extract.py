"""파일 바이트에서 요약할 글을 뽑는다. Talk 에서 받은 문서를 요약하기 전 단계다.

지원 형식
  글     txt md csv tsv log json xml html htm   UTF-8(BOM 포함) → UTF-16(BOM) → cp949 → 거의 맞는 UTF-8 순서로 읽는다.
                                                html 은 태그를 걷는다
  PDF    pdf                                    pypdf. 암호가 걸렸으면 encrypted_file, 글자가 거의 없으면(스캔) no_text
  Office docx xlsx pptx                          python-docx · openpyxl · python-pptx
  한글   hwpx hwp(5.0)                           hwpx 는 zip 속 XML, hwp 는 OLE 속 레코드(olefile). 옛 HWP 3.x 는 지원하지 않는다

상한
  요약은 앞부분 조각까지만 읽으므로 글은 MAX_TEXT_CHARS 자까지만 뽑고 멈춘다(모든 형식). 작은 파일이 엄청나게 커지는
  압축 폭탄도 막는다 — HWP 본문은 풀린 크기, zip 은 항목 하나·전체 크기, 흘려 읽는 XML 은 읽는 바이트 수에 끝이 있다.
  상한에 닿아 뒷부분을 읽지 않았으면 Extracted.truncated 로 알린다.

실패는 ExtractError(code, message) 로 알린다. code 는 앱이 이유를 보여 줄 수 있는 SummaryErrorCode 와 같은 값이다.
LLM 을 부르지 않고 chroma 도 쓰지 않는다 — 테스트에서 따로 불러 쓸 수 있다.
"""

import io
import re
import struct
import zipfile
import zlib
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from html.parser import HTMLParser
from xml.etree import ElementTree

UNSUPPORTED_FILE = "unsupported_file"
NO_TEXT = "no_text"
ENCRYPTED_FILE = "encrypted_file"
BROKEN_FILE = "broken_file"

TEXT_EXTENSIONS = {"txt", "md", "markdown", "csv", "tsv", "log", "json", "xml"}
HTML_EXTENSIONS = {"html", "htm"}
SUPPORTED_EXTENSIONS = TEXT_EXTENSIONS | HTML_EXTENSIONS | {"pdf", "docx", "xlsx", "pptx", "hwpx", "hwp"}

# 뽑는 글의 끝(모든 형식). 요약은 앞 12조각(약 84,000자)까지만 읽으므로, 공백을 줄인 뒤에도 넉넉히 남는 길이에서 멈춘다.
MAX_TEXT_CHARS = 200_000
# 글 파일이 UTF-8 로도 cp949 로도 딱 맞지 않을 때, 못 읽는 글자(�)가 이 비율 이하면 UTF-8 로 보고 그 바이트만 � 로 바꾼다.
# 여러 출처를 이어 붙인 로그, 한글 중간에서 잘린 파일처럼 몇 바이트만 어긋난 경우다.
MAX_BAD_CHAR_RATIO = 0.01
# html 은 이만큼씩 나눠 파서에 넣고, 모은 글이 MAX_TEXT_CHARS 의 이 배수를 넘으면 그만 넣는다(공백·줄을 줄이기 전 길이라 넉넉히).
HTML_FEED_CHARS = 64 * 1024
HTML_RAW_FACTOR = 2
# 엑셀은 시트마다 이 행 수까지만 읽는다.
MAX_SHEET_ROWS = 2000
MAX_SHEET_COLUMNS = 60
# 엑셀은 모든 시트를 합쳐 빈 행을 포함해 이 행 수까지만 훑는다. openpyxl 은 건너뛴 행 번호를 빈 행으로 채워 내놓으므로,
# 백만 번째 행에 값이 하나 있는 5KB 파일도 빈 행 백만 개를 돈다. 데이터가 그보다 뒤에서 시작하는 시트는 사실상 없다.
MAX_SCAN_ROWS = 50_000
# PDF 는 쪽마다 평균 이 글자 수보다 적으면 스캔한 그림 문서로 본다(쪽 번호·머리말 정도만 글자로 남는다).
MIN_PDF_CHARS_PER_PAGE = 10
MIN_PDF_CHARS = 20
# PDF 는 이 쪽 수까지만 읽는다. 빈 쪽 수만 장짜리 파일에서 글자 상한에 닿지 않고 계속 도는 일을 막는다.
MAX_PDF_PAGES = 2_000
# zip 으로 싼 문서를 풀었을 때 크기의 끝(작은 파일이 엄청나게 커지는 압축 폭탄).
# 라이브러리가 통째로 트리로 펼치는 XML(docx·pptx 의 모든 XML, xlsx 의 통합 문서·스타일 등)은 메모리에서 열 배 가까이
# 불어나므로 하나가 MAX_ZIP_PART_BYTES, 합쳐서 MAX_UNZIPPED_BYTES 까지다. 나머지 — 앞에서부터 흘려 읽는 XML(xlsx 시트·공유
# 문자열, hwpx 구역)과 XML 이 아닌 항목(그림 등, 읽어도 제 크기만큼만 차지한다) — 는 합쳐서 MAX_UNZIPPED_OTHER_BYTES 까지다.
# 20MB 짜리 엑셀은 시트가 200MB 가까이 풀리기도 한다.
MAX_ZIP_PART_BYTES = 16 * 1024 * 1024
MAX_UNZIPPED_BYTES = 48 * 1024 * 1024
MAX_UNZIPPED_OTHER_BYTES = 256 * 1024 * 1024
# hwpx 구역 XML 은 통째로 펼치지 않고 흘려 읽으며, 구역을 합쳐 이 바이트 수까지만 읽는다(넘으면 앞부분만).
MAX_HWPX_READ_BYTES = 32 * 1024 * 1024
# HWP 본문 구역을 풀었을 때 크기 합의 끝. 넘는 부분은 풀지 않는다 — deflate 는 1000 배 가까이 줄어들어 20MB 파일이 수 GB 로 풀린다.
MAX_HWP_BODY_BYTES = 32 * 1024 * 1024

OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
ZIP_MAGIC = b"PK\x03\x04"
HWP_SIGNATURE = b"HWP Document File"


class ExtractError(Exception):
    """글을 뽑지 못했다. code 는 SummaryErrorCode 중 하나, message 는 서버 기록용 영어 설명이다."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Extracted:
    """뽑은 글. truncated 는 글자 수·크기 상한에 닿아 문서 뒷부분을 읽지 않았다는 뜻이다."""

    text: str
    truncated: bool


class TextSink:
    """뽑은 글을 줄마다 모은다. 합친 길이가 limit 자에 닿으면 넘는 부분을 버리고, 그 뒤로는 받지 않는다."""

    def __init__(self, limit: int):
        self.limit = limit
        self.lines: list[str] = []
        self.length = 0
        self.full = False
        self.truncated = False

    def add(self, line: str) -> bool:
        """줄 하나를 더한다. 더 받을 수 있으면 True, 상한에 닿아 그만 읽어야 하면 False."""
        if self.full:
            return False
        separator = 1 if self.lines else 0
        room = self.limit - self.length - separator
        if len(line) > room:
            if room > 0:
                self.lines.append(line[:room])
                self.length = self.limit
            self.full = self.truncated = True
            return False
        self.lines.append(line)
        self.length += separator + len(line)
        return True

    def text(self) -> str:
        return "\n".join(self.lines)


def extension_of(name: str) -> str:
    """경로를 뗀 파일 이름의 확장자(소문자). 없으면 빈 문자열."""
    base = name.replace("\\", "/").rsplit("/", 1)[-1]
    return base.rsplit(".", 1)[-1].lower() if "." in base.strip(".") else ""


def extract_text(name: str, data: bytes) -> str:
    """extract 의 글만 돌려준다."""
    return extract(name, data).text


def extract(name: str, data: bytes) -> Extracted:
    """파일 이름의 확장자로 형식을 가려 글을 뽑는다. 확장자가 없으면 내용 첫머리로 짐작한다.

    이름이 hwp 인데 내용이 zip 이면(hwpx 를 .hwp 로 저장한 경우) hwpx 로 읽는다.
    """
    if not data:
        raise ExtractError(NO_TEXT, "empty file")
    ext = extension_of(name) or sniff(data)
    if ext == "hwp" and data.startswith(ZIP_MAGIC):
        ext = "hwpx"
    if ext not in SUPPORTED_EXTENSIONS:
        raise ExtractError(UNSUPPORTED_FILE, f"unsupported file type: {ext or 'unknown'}")

    sink = TextSink(MAX_TEXT_CHARS)
    try:
        if ext in TEXT_EXTENSIONS:
            sink.add(decode_text(data))
        elif ext in HTML_EXTENSIONS:
            html_text(decode_text(data), sink)
        elif ext == "pdf":
            pdf_text(data, sink)
        elif ext == "hwp":
            hwp_text(data, sink)
        else:
            if data.startswith(OLE_MAGIC):
                office_in_ole(data)
            check_zip(data, LOADED_WHOLE[ext])
            {"docx": docx_text, "xlsx": xlsx_text, "pptx": pptx_text, "hwpx": hwpx_text}[ext](data, sink)
    except ExtractError:
        raise
    except Exception as e:
        raise ExtractError(BROKEN_FILE, f"cannot read {ext}: {e.__class__.__name__}: {e}") from e

    text = sink.text()
    if not re.search(r"\S", text):
        raise ExtractError(NO_TEXT, "no text in file")
    return Extracted(text, sink.truncated)


def sniff(data: bytes) -> str:
    """확장자가 없는 파일의 형식을 첫머리로 짐작한다. 모르면 빈 문자열."""
    if b"%PDF-" in data[:1024]:
        return "pdf"
    if data.startswith(OLE_MAGIC):
        return "hwp" if HWP_SIGNATURE in data else ""
    if data.startswith(ZIP_MAGIC):
        try:
            names = set(zipfile.ZipFile(io.BytesIO(data)).namelist())
        except zipfile.BadZipFile:
            return ""
        if "word/document.xml" in names:
            return "docx"
        if "xl/workbook.xml" in names:
            return "xlsx"
        if "ppt/presentation.xml" in names:
            return "pptx"
        if any(n.startswith("Contents/section") for n in names):
            return "hwpx"
        return ""
    return "" if b"\x00" in data[:4096] else "txt"


# ---------- 글 파일 ----------

def decode_text(data: bytes) -> str:
    """UTF-8(BOM 포함) → UTF-16(BOM 이 있을 때) → cp949 순서로 읽는다.

    셋 다 딱 맞지 않아도 못 읽는 글자가 MAX_BAD_CHAR_RATIO 이하면 UTF-8 로 읽고 그 바이트만 � 로 바꾼다.
    cp949 를 먼저 보므로, 영문 사이에 한글이 조금 섞인 cp949 파일을 UTF-8 로 잘못 읽지 않는다.
    """
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        try:
            return data.decode("utf-16")
        except UnicodeDecodeError:
            pass
    for encoding in ("utf-8-sig", "cp949"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    text = data.decode("utf-8-sig", errors="replace")
    if text.count("�") <= len(text) * MAX_BAD_CHAR_RATIO:
        return text
    raise ExtractError(BROKEN_FILE, "text is neither UTF-8 nor CP949")


class _HtmlText(HTMLParser):
    """html 의 보이는 글만 모은다. script·style 은 버리고, 덩어리 태그 앞뒤에서 줄을 바꾼다."""

    BLOCKS = {
        "p", "div", "br", "li", "ul", "ol", "tr", "table", "section", "article", "header", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "hr", "dd", "dt", "title",
    }
    HIDDEN = {"script", "style", "noscript", "template"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.hidden = 0
        self.length = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.HIDDEN:
            self.hidden += 1
        if tag in self.BLOCKS:
            self.parts.append("\n")
        elif tag in ("td", "th"):
            self.parts.append(" | ")

    def handle_endtag(self, tag):
        if tag in self.HIDDEN:
            self.hidden = max(0, self.hidden - 1)
        if tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)
            self.length += len(data)


def html_text(html: str, sink: TextSink):
    """html 을 조금씩 파서에 넣어 보이는 글을 줄마다 sink 에 담는다. 모은 글이 넉넉히 쌓이면 남은 html 은 읽지 않는다."""
    parser = _HtmlText()
    for start in range(0, len(html), HTML_FEED_CHARS):
        if parser.length > sink.limit * HTML_RAW_FACTOR:
            sink.truncated = True
            break
        parser.feed(html[start:start + HTML_FEED_CHARS])
    else:
        parser.close()
    for line in "".join(parser.parts).splitlines():
        line = re.sub(r"[ \t ]+", " ", line).strip()
        if not sink.add(re.sub(r"^\|\s*", "", line)):
            return


# ---------- PDF ----------

def pdf_text(data: bytes, sink: TextSink):
    """쪽마다 글을 뽑아 빈 줄을 사이에 두고 담는다. 글자 상한이나 MAX_PDF_PAGES 에 닿으면 남은 쪽은 읽지 않는다."""
    from pypdf import PdfReader
    from pypdf.errors import DependencyError, FileNotDecryptedError

    if b"%PDF-" not in data[:1024]:
        raise ExtractError(BROKEN_FILE, "not a PDF file")
    pages = visible = 0
    try:
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted and not reader.decrypt(""):
            raise ExtractError(ENCRYPTED_FILE, "PDF is password protected")
        for page in reader.pages:
            if pages >= MAX_PDF_PAGES:
                sink.truncated = True
                break
            text = (page.extract_text() or "").strip()
            visible += len(re.sub(r"\s", "", text))
            pages += 1
            if (pages > 1 and not sink.add("")) or not sink.add(text):
                break
    except ExtractError:
        raise
    except (FileNotDecryptedError, DependencyError) as e:
        raise ExtractError(ENCRYPTED_FILE, f"PDF is encrypted: {e}") from e
    except Exception as e:
        raise ExtractError(BROKEN_FILE, f"cannot read PDF: {e.__class__.__name__}: {e}") from e

    if visible < max(MIN_PDF_CHARS, MIN_PDF_CHARS_PER_PAGE * pages):
        raise ExtractError(NO_TEXT, f"PDF has almost no text ({visible} chars in {pages} pages)")


# ---------- Office (docx · xlsx · pptx) ----------

def office_in_ole(data: bytes):
    """docx·xlsx·pptx 인데 OLE 로 싸여 있으면 암호 걸린 Office 문서이거나 이름만 바꾼 옛 형식이다."""
    import olefile

    try:
        ole = olefile.OleFileIO(io.BytesIO(data))
    except Exception as e:
        raise ExtractError(BROKEN_FILE, f"broken OLE container: {e}") from e
    with ole:
        if ole.exists("EncryptionInfo") or ole.exists("EncryptedPackage"):
            raise ExtractError(ENCRYPTED_FILE, "Office document is password protected")
    raise ExtractError(UNSUPPORTED_FILE, "legacy binary Office document")


XLSX_LOADED_WHOLE = re.compile(
    r"\[Content_Types\]\.xml|(.*/)?_rels/[^/]*\.rels|xl/workbook\.xml|xl/styles\.xml"
    r"|xl/theme/[^/]*|docProps/[^/]*|xl/externalLinks/[^/]*"
)

# 형식마다, 라이브러리가 통째로 트리로 펼치는 zip 속 XML 인지. python-docx·python-pptx 는 모든 XML 을 펼치고,
# openpyxl(read_only)은 시트·공유 문자열을 흘려 읽으며, hwpx 는 manifest 말고는 직접 흘려 읽는다.
LOADED_WHOLE: dict[str, Callable[[str], bool]] = {
    "docx": lambda name: True,
    "pptx": lambda name: True,
    "xlsx": lambda name: XLSX_LOADED_WHOLE.fullmatch(name) is not None,
    "hwpx": lambda name: name == "META-INF/manifest.xml",
}


def check_zip(data: bytes, loaded_whole: Callable[[str], bool]):
    """zip 으로 싼 문서인지, 풀었을 때 지나치게 커지지 않는지 본다(작은 파일이 엄청나게 커지는 압축 폭탄).

    loaded_whole(이름) 이 참인 XML 은 하나가 MAX_ZIP_PART_BYTES, 합쳐서 MAX_UNZIPPED_BYTES 까지.
    나머지는 합쳐서 MAX_UNZIPPED_OTHER_BYTES 까지. 크기는 zip 이 밝힌 값이고, zipfile 은 그보다 많이 풀어내지 않는다.
    """
    try:
        infos = zipfile.ZipFile(io.BytesIO(data)).infolist()
    except zipfile.BadZipFile as e:
        raise ExtractError(BROKEN_FILE, f"not a zip package: {e}") from e
    tree = other = 0
    for info in infos:
        if not (info.filename.endswith((".xml", ".rels")) and loaded_whole(info.filename)):
            other += info.file_size
            continue
        if info.file_size > MAX_ZIP_PART_BYTES:
            raise ExtractError(BROKEN_FILE, f"zip entry {info.filename} is too large when unpacked")
        tree += info.file_size
    if tree > MAX_UNZIPPED_BYTES or other > MAX_UNZIPPED_OTHER_BYTES:
        raise ExtractError(BROKEN_FILE, "zip package is too large when unpacked")


def row_line(cells) -> str:
    values = [c.strip() for c in cells]
    while values and not values[-1]:
        values.pop()
    return " | ".join(values)


def docx_text(data: bytes, sink: TextSink):
    """본문 문단과 표를 문서 순서대로. 표는 행마다 한 줄, 칸은 ` | ` 로 잇는다(합친 칸은 한 번만)."""
    from docx import Document
    from docx.table import Table

    document = Document(io.BytesIO(data))
    for block in document.iter_inner_content():
        lines = ["", *table_lines(block), ""] if isinstance(block, Table) else [block.text]
        for line in lines:
            if not sink.add(line):
                return


def table_lines(table) -> Iterator[str]:
    for row in table.rows:
        seen, cells = set(), []
        for cell in row.cells:
            if id(cell._tc) in seen:
                continue
            seen.add(id(cell._tc))
            cells.append(cell.text.replace("\n", " "))
        line = row_line(cells)
        if line:
            yield line


def cell_text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if hasattr(value, "isoformat"):
        text = value.isoformat()
        return text[:-9] if text.endswith("T00:00:00") else text
    return str(value).replace("\n", " ")


def xlsx_text(data: bytes, sink: TextSink):
    """시트마다 `## 시트: 이름` 아래 행을 ` | ` 로 잇는다. 값은 수식이 아니라 마지막으로 계산된 값을 쓴다.

    값이 있는 행이 없는 시트는 건너뛴다. 시트마다 MAX_SHEET_ROWS 행, 모든 시트를 합쳐 MAX_SCAN_ROWS 행(빈 행 포함)까지 본다.
    """
    from openpyxl import load_workbook

    workbook = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    try:
        scan_left = MAX_SCAN_ROWS
        first_sheet = True
        for sheet in workbook.worksheets:
            if scan_left <= 0:
                break
            rows = scanned = 0
            for values in sheet.iter_rows(values_only=True, max_col=MAX_SHEET_COLUMNS, max_row=scan_left):
                scanned += 1
                if all(value is None for value in values):
                    continue
                line = row_line(cell_text(v) for v in values)
                if not line:
                    continue
                if not rows:
                    if not first_sheet and not sink.add(""):
                        return
                    first_sheet = False
                    if not sink.add(f"## 시트: {sheet.title}"):
                        return
                rows += 1
                if rows > MAX_SHEET_ROWS:
                    sink.truncated = True
                    if not sink.add(f"… ({MAX_SHEET_ROWS}행 이후 생략)"):
                        return
                    break
                if not sink.add(line):
                    return
            scan_left -= scanned
    finally:
        workbook.close()


def pptx_text(data: bytes, sink: TextSink):
    """슬라이드마다 `## 슬라이드 N` 아래 도형 글·표·발표자 노트를 적는다."""
    from pptx import Presentation

    presentation = Presentation(io.BytesIO(data))
    for number, slide in enumerate(presentation.slides, 1):
        lines = [f"## 슬라이드 {number}"] if number == 1 else ["", f"## 슬라이드 {number}"]
        for shape in slide.shapes:
            lines.extend(shape_lines(shape))
        if slide.has_notes_slide:
            notes = slide.notes_slide.notes_text_frame
            if notes is not None and notes.text.strip():
                lines.append(f"발표자 노트: {notes.text.strip()}")
        for line in lines:
            if not sink.add(line):
                return


def shape_lines(shape) -> list[str]:
    from pptx.shapes.group import GroupShape

    if isinstance(shape, GroupShape):
        return [line for child in shape.shapes for line in shape_lines(child)]
    if getattr(shape, "has_table", False) and shape.has_table:
        lines = []
        for row in shape.table.rows:
            line = row_line(cell.text.replace("\n", " ") for cell in row.cells)
            if line:
                lines.append(line)
        return lines
    if getattr(shape, "has_text_frame", False) and shape.has_text_frame:
        return [p.strip() for p in shape.text_frame.text.splitlines() if p.strip()]
    return []


# ---------- 한글 (hwpx · hwp) ----------

def local_name(tag) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


class _ByteBudget:
    """여러 스트림에 걸쳐 읽을 수 있는 바이트 수. cut 은 남은 내용이 있는데 끝에 닿아 읽지 않았다는 뜻이다."""

    def __init__(self, limit: int):
        self.left = limit
        self.cut = False


class _LimitedReader:
    """stream 을 budget 이 남은 만큼만 읽는다. 다 쓰면 끝(빈 바이트)을 돌려준다."""

    def __init__(self, stream, budget: _ByteBudget):
        self.stream = stream
        self.budget = budget

    def read(self, size: int = -1) -> bytes:
        if self.budget.left <= 0:
            if self.stream.read(1):
                self.budget.cut = True
            return b""
        size = self.budget.left if size is None or size < 0 else min(size, self.budget.left)
        chunk = self.stream.read(size)
        self.budget.left -= len(chunk)
        return chunk


def hwpx_text(data: bytes, sink: TextSink):
    """`Contents/section*.xml` 을 번호 순서대로 흘려 읽어 문단(`<hp:p>`)마다 한 줄로. 표 칸 속 문단도 각자 한 줄이 된다.

    구역 XML 을 통째로 펼치지 않고, 다 읽은 요소는 버린다. 구역을 합쳐 MAX_HWPX_READ_BYTES 까지만 읽는다.
    """
    archive = zipfile.ZipFile(io.BytesIO(data))
    names = archive.namelist()
    if "META-INF/manifest.xml" in names and b"encryption-data" in archive.read("META-INF/manifest.xml"):
        raise ExtractError(ENCRYPTED_FILE, "HWPX document is password protected")
    sections = sorted(
        (n for n in names if re.fullmatch(r"Contents/section\d+\.xml", n)),
        key=lambda n: int(re.search(r"\d+", n.rsplit("/", 1)[-1]).group()),
    )
    if not sections:
        raise ExtractError(BROKEN_FILE, "HWPX has no sections")

    budget = _ByteBudget(MAX_HWPX_READ_BYTES)
    for name in sections:
        if budget.left <= 0:
            sink.truncated = True
            return
        with archive.open(name) as stream:
            reader = _LimitedReader(stream, budget)
            try:
                complete = hwpx_section_lines(reader, sink)
            except ElementTree.ParseError:
                if not budget.cut:
                    raise
                complete = False
        if budget.cut:
            sink.truncated = True
        if not complete:
            return


def hwpx_section_lines(source, sink: TextSink) -> bool:
    """구역 XML 하나를 흘려 읽어 문단마다 sink 에 담는다. sink 가 차서 멈췄으면 False."""
    current: list[str] = []
    stack: list[ElementTree.Element] = []

    def flush() -> bool:
        line = "".join(current).strip()
        current.clear()
        return sink.add(line) if line else True

    for event, element in ElementTree.iterparse(source, events=("start", "end")):
        tag = local_name(element.tag)
        if event == "start":
            stack.append(element)
            if tag == "p" and not flush():
                return False
            continue
        stack.pop()
        if tag == "t":
            current.append(element.text or "")
            for child in element:
                child_tag = local_name(child.tag)
                if child_tag == "tab":
                    current.append("\t")
                elif child_tag == "lineBreak":
                    current.append("\n")
                elif child_tag in ("fwSpace", "nbSpace"):
                    current.append(" ")
                current.append(child.tail or "")
        elif tag == "p" and not flush():
            return False
        if stack and local_name(stack[-1].tag) != "t":
            stack[-1].remove(element)
    return flush()


HWPTAG_PARA_TEXT = 67
HWP_COMPRESSED = 0x1
HWP_PASSWORD = 0x2
HWP_DISTRIBUTION = 0x4


def hwp_text(data: bytes, sink: TextSink):
    """HWP 5.0: `FileHeader` 의 속성을 보고 `BodyText/Section{n}` 의 문단 글(HWPTAG_PARA_TEXT)을 순서대로 모은다.

    구역을 풀었을 때 크기는 합쳐서 MAX_HWP_BODY_BYTES 까지만 푼다(압축 폭탄). OLE 가 아닌 옛 HWP 3.x 는 지원하지 않는다.
    """
    import olefile

    if not data.startswith(OLE_MAGIC):
        if data.startswith(HWP_SIGNATURE):
            raise ExtractError(UNSUPPORTED_FILE, "HWP 3.x or older binary document")
        raise ExtractError(BROKEN_FILE, "not an HWP 5.0 (OLE) file")
    try:
        ole = olefile.OleFileIO(io.BytesIO(data))
    except Exception as e:
        raise ExtractError(BROKEN_FILE, f"broken HWP container: {e}") from e

    with ole:
        if not ole.exists("FileHeader"):
            raise ExtractError(BROKEN_FILE, "HWP has no FileHeader")
        header = ole.openstream("FileHeader").read()
        if len(header) < 40 or not header.startswith(HWP_SIGNATURE):
            raise ExtractError(BROKEN_FILE, "bad HWP FileHeader")
        (flags,) = struct.unpack_from("<I", header, 36)
        if flags & (HWP_PASSWORD | HWP_DISTRIBUTION):
            raise ExtractError(ENCRYPTED_FILE, "HWP is password protected or distribution-only")

        sections = []
        for entry in ole.listdir(streams=True, storages=False):
            if len(entry) == 2 and entry[0] == "BodyText" and re.fullmatch(r"Section\d+", entry[1]):
                sections.append((int(entry[1][7:]), "/".join(entry)))
        if not sections:
            raise ExtractError(BROKEN_FILE, "HWP has no BodyText sections")

        left = MAX_HWP_BODY_BYTES
        for _, path in sorted(sections):
            if left <= 0:
                sink.truncated = True
                return
            raw = ole.openstream(path).read()
            if flags & HWP_COMPRESSED:
                inflater = zlib.decompressobj(-15)
                try:
                    body = inflater.decompress(raw, left)
                except zlib.error as e:
                    raise ExtractError(BROKEN_FILE, f"cannot inflate HWP section: {e}") from e
                cut = bool(inflater.unconsumed_tail) or len(body) >= left
            else:
                body, cut = raw[:left], len(raw) > left
            left -= len(body)
            if not hwp_section_paragraphs(body, sink):
                return
            if cut:
                sink.truncated = True
                return


def hwp_section_paragraphs(raw: bytes, sink: TextSink) -> bool:
    """레코드 헤더(tag 10bit · level 10bit · size 12bit, size 가 0xFFF 면 뒤 4바이트가 크기)를 따라가며 문단 글을 꺼낸다.

    sink 가 차서 멈췄으면 False.
    """
    pos = 0
    while pos + 4 <= len(raw):
        (head,) = struct.unpack_from("<I", raw, pos)
        pos += 4
        tag, size = head & 0x3FF, (head >> 20) & 0xFFF
        if size == 0xFFF:
            if pos + 4 > len(raw):
                break
            (size,) = struct.unpack_from("<I", raw, pos)
            pos += 4
        body = raw[pos:pos + size]
        pos += size
        if tag == HWPTAG_PARA_TEXT:
            text = hwp_para_text(body).strip()
            if text and not sink.add(text):
                return False
    return True


def hwp_para_text(body: bytes) -> str:
    """UTF-16LE 문단 글에서 제어 문자를 걸러 낸다.

    0·10·13·24~31 은 한 글자짜리 문자 제어(10·13 은 줄바꿈), 나머지 1~31 은 여덟 글자(16바이트)를 차지하는
    인라인·확장 제어(표·그림·각주 자리 등)라 건너뛴다. 9(탭)는 인라인 제어지만 칸을 띄우려고 탭으로 남긴다.
    """
    count = len(body) // 2
    codes = struct.unpack_from(f"<{count}H", body)
    chars, i = [], 0
    while i < count:
        code = codes[i]
        if code >= 32:
            chars.append(code)
            i += 1
        elif code in (10, 13):
            chars.append(10)
            i += 1
        elif code == 0 or 24 <= code <= 31:
            if code in (30, 31):
                chars.append(32)
            i += 1
        else:
            if code == 9:
                chars.append(9)
            i += 8
    return struct.pack(f"<{len(chars)}H", *chars).decode("utf-16-le", errors="replace")
