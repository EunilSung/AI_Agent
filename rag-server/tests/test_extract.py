"""extract.py: 형식별 글 뽑기와 실패 이유. 시험 파일은 모두 테스트 안에서 만든다."""

import datetime
import io
import struct
import zipfile
import zlib

import pytest

import extract
import hwp_builder as hwp
from extract import BROKEN_FILE, ENCRYPTED_FILE, NO_TEXT, UNSUPPORTED_FILE, ExtractError, extract_text


def error_code(name: str, data: bytes) -> str:
    with pytest.raises(ExtractError) as caught:
        extract_text(name, data)
    return caught.value.code


# ---------- 시험 파일 만들기 ----------

def make_pdf(pages: list[str]) -> bytes:
    """Helvetica 글자로 쪽마다 글을 쓴 가장 작은 PDF."""
    objects = {
        1: b"<< /Type /Catalog /Pages 2 0 R >>",
        3: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    }
    kids = []
    for index, text in enumerate(pages):
        page_id, content_id = 4 + 2 * index, 5 + 2 * index
        kids.append(f"{page_id} 0 R")
        ops = ["BT /F1 12 Tf 72 720 Td 14 TL"]
        for line in text.split("\n"):
            escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            ops.append(f"({escaped}) Tj T*")
        ops.append("ET")
        stream = "\n".join(ops).encode("latin-1")
        objects[page_id] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>"
        ).encode()
        objects[content_id] = b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream"
    objects[2] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>".encode()

    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for number in sorted(objects):
        offsets[number] = len(out)
        out += b"%d 0 obj\n" % number + objects[number] + b"\nendobj\n"
    xref = len(out)
    size = max(objects) + 1
    out += b"xref\n0 %d\n0000000000 65535 f \n" % size
    for number in range(1, size):
        out += b"%010d 00000 n \n" % offsets[number]
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (size, xref)
    return bytes(out)


def encrypt_pdf(data: bytes, user_password: str) -> bytes:
    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter(clone_from=PdfReader(io.BytesIO(data)))
    writer.encrypt(user_password=user_password, owner_password="owner-secret", algorithm="AES-128")
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()


def make_docx(build) -> bytes:
    from docx import Document

    document = Document()
    build(document)
    out = io.BytesIO()
    document.save(out)
    return out.getvalue()


def make_xlsx(build) -> bytes:
    from openpyxl import Workbook

    workbook = Workbook()
    build(workbook)
    out = io.BytesIO()
    workbook.save(out)
    return out.getvalue()


def make_pptx(build) -> bytes:
    from pptx import Presentation

    presentation = Presentation()
    build(presentation)
    out = io.BytesIO()
    presentation.save(out)
    return out.getvalue()


HP = "http://www.hancom.co.kr/hwpml/2011/paragraph"
HS = "http://www.hancom.co.kr/hwpml/2011/section"


def hwpx_section(body: str) -> str:
    return f'<?xml version="1.0" encoding="UTF-8"?><hs:sec xmlns:hs="{HS}" xmlns:hp="{HP}">{body}</hs:sec>'


def hwpx_para(text: str) -> str:
    return f"<hp:p><hp:run><hp:t>{text}</hp:t></hp:run></hp:p>"


def make_hwpx(sections: dict[str, str], manifest: str = "<odf:manifest/>") -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr("mimetype", "application/hwp+zip")
        archive.writestr("META-INF/manifest.xml", manifest)
        for name, xml in sections.items():
            archive.writestr(name, xml)
    return out.getvalue()


# ---------- 글 파일 ----------

@pytest.mark.parametrize("data", [
    "회의록 첫 줄\n둘째 줄".encode("utf-8"),
    "﻿회의록 첫 줄\n둘째 줄".encode("utf-8"),
    "회의록 첫 줄\n둘째 줄".encode("cp949"),
    "회의록 첫 줄\n둘째 줄".encode("utf-16"),
])
def test_text_encodings(data):
    assert extract_text("memo.txt", data) == "회의록 첫 줄\n둘째 줄"


@pytest.mark.parametrize("name", ["a.md", "a.csv", "a.tsv", "a.log", "a.json", "a.xml", "A.TXT"])
def test_text_like_extensions(name):
    assert extract_text(name, "가,나\n1,2".encode()) == "가,나\n1,2"


def test_undecodable_text_is_broken():
    assert error_code("a.txt", b"\x80\x80\x80\xff") == BROKEN_FILE
    assert error_code("a.txt", ("정상 " * 50).encode() + b"\xff\xfe\x80" * 30) == BROKEN_FILE


KOREAN_LOG = "\n".join(f"2026-10-24 09:{i:02d} 출고 요청 {i}건 처리 완료, 담당 김하준" for i in range(40))


def test_nearly_utf8_text_with_a_stray_byte_is_read():
    data = KOREAN_LOG[:300].encode() + b"\xff" + KOREAN_LOG[300:].encode()
    text = extract_text("server.log", data)
    assert text.count("�") == 1
    assert text.replace("�", "") == KOREAN_LOG


def test_utf8_cut_in_the_middle_of_a_korean_letter_is_read():
    data = KOREAN_LOG.encode()[:-1]
    text = extract_text("cut.log", data)
    assert text.startswith(KOREAN_LOG[:-1]) and text.endswith("�")


def test_cp949_is_still_preferred_over_lenient_utf8():
    text = ("order 1234 shipped " * 40) + "담당 김하준"
    assert extract_text("cp949.csv", text.encode("cp949")) == text


def test_text_is_cut_at_max_chars(monkeypatch):
    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 100)
    result = extract.extract("long.txt", ("가" * 1000).encode())
    assert result.text == "가" * 100 and result.truncated
    whole = extract.extract("short.txt", ("가" * 100).encode())
    assert whole.text == "가" * 100 and not whole.truncated


def test_html_strips_tags_scripts_and_styles():
    html = """<html><head><title>공지</title><style>p{color:red}</style></head>
    <body><h1>제목 &amp; 소개</h1><p>첫 문단</p><script>alert('x')</script>
    <table><tr><th>항목</th><th>값</th></tr><tr><td>예산</td><td>100</td></tr></table></body></html>"""
    text = extract_text("page.html", html.encode())
    assert "제목 & 소개" in text
    assert "첫 문단" in text
    assert "항목 | 값" in text and "예산 | 100" in text
    assert "alert" not in text and "color" not in text
    assert "<" not in text


def test_html_stops_feeding_after_enough_text(monkeypatch):
    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 1000)
    monkeypatch.setattr(extract, "HTML_FEED_CHARS", 500)
    html = "".join(f"<p>{i}번 문단 내용</p>" for i in range(5000))
    fed = []
    original_feed = extract._HtmlText.feed
    monkeypatch.setattr(extract._HtmlText, "feed", lambda self, data: fed.append(len(data)) or original_feed(self, data))
    result = extract.extract("page.html", html.encode())
    assert result.text.lstrip().startswith("0번 문단 내용") and len(result.text) <= 1000
    assert result.truncated
    assert sum(fed) < len(html) // 10


def test_empty_and_blank_files_have_no_text():
    assert error_code("a.txt", b"") == NO_TEXT
    assert error_code("a.docx", b"") == NO_TEXT
    assert error_code("a.txt", b" \n\t\n ") == NO_TEXT


@pytest.mark.parametrize("name", ["old.doc", "old.xls", "old.ppt", "photo.png", "photo.jpg", "bundle.zip", "song.mp3", "file.exe"])
def test_unsupported_types(name):
    assert error_code(name, b"\x89PNG\r\n\x1a\n" + b"\0" * 32) == UNSUPPORTED_FILE


def test_extension_of():
    assert extract.extension_of("보고서.final.PDF") == "pdf"
    assert extract.extension_of("dir/sub/파일.hwpx") == "hwpx"
    assert extract.extension_of("README") == ""
    assert extract.extension_of(".bashrc") == ""


def test_sniff_when_name_has_no_extension():
    assert extract_text("문서", make_pdf(["Quarterly report for the sales team"])).startswith("Quarterly report")
    docx = make_docx(lambda d: d.add_paragraph("확장자 없는 워드 문서"))
    assert extract_text("문서", docx) == "확장자 없는 워드 문서"
    assert extract_text("메모", "그냥 글".encode()) == "그냥 글"
    assert error_code("사진", b"\x89PNG\r\n\x1a\n\0\0\0\0IHDR") == UNSUPPORTED_FILE


# ---------- PDF ----------

def test_pdf_text_from_all_pages():
    text = extract_text("report.pdf", make_pdf(["Sales grew 12 percent in Q3", "Next review is on 2026-11-02"]))
    assert "Sales grew 12 percent in Q3" in text
    assert "Next review is on 2026-11-02" in text
    assert text.index("Sales") < text.index("Next review")


def test_pdf_with_owner_password_only_is_readable():
    data = encrypt_pdf(make_pdf(["Owner password only restricts printing"]), user_password="")
    assert "Owner password only" in extract_text("locked.pdf", data)


def test_pdf_with_user_password_is_encrypted():
    data = encrypt_pdf(make_pdf(["Top secret plan"]), user_password="1234")
    assert error_code("secret.pdf", data) == ENCRYPTED_FILE


def test_scanned_pdf_without_text_has_no_text():
    from pypdf import PdfWriter

    writer = PdfWriter()
    for _ in range(3):
        writer.add_blank_page(width=595, height=842)
    out = io.BytesIO()
    writer.write(out)
    assert error_code("scan.pdf", out.getvalue()) == NO_TEXT


def test_pdf_with_only_page_numbers_has_no_text():
    assert error_code("scan.pdf", make_pdf(["1", "2", "3", "4"])) == NO_TEXT


@pytest.mark.parametrize("data", [b"not a pdf at all", b"%PDF-1.4\n1 0 obj << /Type /Catalog"])
def test_broken_pdf(data):
    assert error_code("broken.pdf", data) == BROKEN_FILE


def test_pdf_stops_reading_after_enough_text(monkeypatch):
    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 60)
    data = make_pdf([f"Page {i} has a fairly long sentence about the budget." for i in range(1, 6)])
    result = extract.extract("long.pdf", data)
    assert "Page 1" in result.text and "Page 2" in result.text
    assert "Page 3" not in result.text
    assert len(result.text) <= 60 and result.truncated


def test_pdf_stops_after_max_pages(monkeypatch):
    monkeypatch.setattr(extract, "MAX_PDF_PAGES", 2)
    data = make_pdf([f"Page {i} has a fairly long sentence about the budget." for i in range(1, 6)])
    result = extract.extract("many.pdf", data)
    assert "Page 2" in result.text and "Page 3" not in result.text
    assert result.truncated
    assert not extract.extract("two.pdf", make_pdf(["First page of the plan", "Second page"])).truncated


# ---------- docx ----------

def test_docx_paragraphs_and_tables_in_order():
    def build(document):
        document.add_heading("주간 보고", level=1)
        document.add_paragraph("이번 주 요약입니다.")
        table = document.add_table(rows=3, cols=3)
        for r, row in enumerate([("항목", "담당", "기한"), ("배포", "김하준", "11월 5일"), ("합친 칸", "", "")]):
            for c, value in enumerate(row):
                table.cell(r, c).text = value
        table.cell(2, 0).merge(table.cell(2, 2))
        document.add_paragraph("표 다음 문단입니다.")

    text = extract_text("weekly.docx", make_docx(build))
    lines = [line for line in text.split("\n") if line]
    assert lines == ["주간 보고", "이번 주 요약입니다.", "항목 | 담당 | 기한", "배포 | 김하준 | 11월 5일", "합친 칸", "표 다음 문단입니다."]


def test_broken_docx():
    assert error_code("a.docx", b"PK\x03\x04" + b"\x00garbage" * 50) == BROKEN_FILE
    assert error_code("a.docx", b"plain text pretending to be docx") == BROKEN_FILE


def test_zip_that_is_not_a_docx_is_broken():
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr("hello.txt", "hi")
    assert error_code("a.docx", out.getvalue()) == BROKEN_FILE


def test_password_protected_office_file_is_encrypted():
    data = hwp.build_ole({"EncryptionInfo": b"\x04\x00\x04\x00" + b"\0" * 200, "EncryptedPackage": b"\1" * 5000})
    for name in ("secret.docx", "secret.xlsx", "secret.pptx"):
        assert error_code(name, data) == ENCRYPTED_FILE


def test_legacy_binary_renamed_to_docx_is_unsupported():
    data = hwp.build_ole({"WordDocument": b"\0" * 600})
    assert error_code("renamed.docx", data) == UNSUPPORTED_FILE


def test_zip_bomb_is_refused(monkeypatch):
    monkeypatch.setattr(extract, "MAX_UNZIPPED_BYTES", 1000)
    docx = make_docx(lambda d: d.add_paragraph("가" * 2000))
    assert error_code("big.docx", docx) == BROKEN_FILE


def test_media_is_not_counted_as_xml(monkeypatch):
    import random

    from PIL import Image
    from pptx.util import Inches

    random.seed(3)
    picture = io.BytesIO()
    Image.frombytes("RGB", (300, 300), bytes(random.getrandbits(8) for _ in range(300 * 300 * 3))).save(picture, "PNG")

    def build(presentation):
        slide = presentation.slides.add_slide(presentation.slide_layouts[5])
        slide.shapes.title.text = "사진 슬라이드"
        slide.shapes.add_picture(io.BytesIO(picture.getvalue()), Inches(1), Inches(1))

    data = make_pptx(build)
    xml = sum(i.file_size for i in zipfile.ZipFile(io.BytesIO(data)).infolist() if i.filename.endswith((".xml", ".rels")))
    monkeypatch.setattr(extract, "MAX_UNZIPPED_BYTES", xml + 100)
    assert extract_text("photo.pptx", data) == "## 슬라이드 1\n사진 슬라이드"
    monkeypatch.setattr(extract, "MAX_UNZIPPED_OTHER_BYTES", len(picture.getvalue()) // 2)
    assert error_code("photo.pptx", data) == BROKEN_FILE


def test_unzipped_limits_are_tens_of_megabytes():
    assert extract.MAX_ZIP_PART_BYTES <= 16 * 1024 * 1024
    assert extract.MAX_UNZIPPED_BYTES <= 64 * 1024 * 1024


def test_each_body_xml_has_a_size_limit(monkeypatch):
    monkeypatch.setattr(extract, "MAX_ZIP_PART_BYTES", 500_000)
    small = make_docx(lambda d: d.add_paragraph("짧은 문단"))
    assert extract_text("small.docx", small) == "짧은 문단"
    big_docx = make_docx(lambda d: [d.add_paragraph("가" * 3000) for _ in range(100)])
    assert error_code("big.docx", big_docx) == BROKEN_FILE

    monkeypatch.setattr(extract, "MAX_ZIP_PART_BYTES", 30_000)

    def big_slides(presentation):
        slide = presentation.slides.add_slide(presentation.slide_layouts[5])
        for i in range(400):
            slide.shapes.add_textbox(0, 0, 100, 100).text_frame.text = f"{i}번 상자"

    assert error_code("big.pptx", make_pptx(big_slides)) == BROKEN_FILE


def test_streamed_xlsx_sheet_is_not_limited_like_body_xml(monkeypatch):
    monkeypatch.setattr(extract, "MAX_ZIP_PART_BYTES", 30_000)

    def build(workbook):
        for i in range(1500):
            workbook.active.append([f"행 {i}", i, "메모"])

    data = make_xlsx(build)
    assert max(i.file_size for i in zipfile.ZipFile(io.BytesIO(data)).infolist()) > 30_000
    assert extract_text("rows.xlsx", data).split("\n")[1] == "행 0 | 0 | 메모"
    monkeypatch.setattr(extract, "MAX_UNZIPPED_OTHER_BYTES", 30_000)
    assert error_code("rows.xlsx", data) == BROKEN_FILE


XLSX_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
XLSX_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
OPC_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
XLSX_SHARED_STRINGS_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"
XLSX_WORKBOOK_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
XLSX_WORKSHEET_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"


def content_types(*overrides: tuple[str, str], extra: str = "") -> str:
    body = "".join(f'<Override PartName="/{name}" ContentType="{kind}"/>' for name, kind in overrides)
    return (
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        f"{body}{extra}</Types>"
    )


def zip_of(files: dict[str, str | bytes]) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, body in files.items():
            archive.writestr(name, body)
    return out.getvalue()


def hand_xlsx(sheets: dict[str, str], strings: str | None = None, *, workbook: str = "xl/workbook.xml",
              strings_part: str = "xl/sharedStrings.xml", types: str | None = None, workbook_extra: str = "",
              workbook_tail: str = "", extra_rels: str = "", extra_files: dict[str, str | bytes] | None = None) -> bytes:
    """손으로 만든 xlsx. sheets 는 {시트 이름: 시트 XML 의 sheetData 안쪽}, strings 는 공유 문자열 `<sst>` 안쪽이다.

    통합 문서·공유 문자열 항목 이름(workbook·strings_part)과 [Content_Types].xml(types)을 바꿔 볼 수 있다.
    """
    folder, base = workbook.rsplit("/", 1)
    sheet_list = "".join(f'<sheet name="{title}" sheetId="{i}" r:id="rId{i}"/>' for i, title in enumerate(sheets, 1))
    rels = "".join(
        f'<Relationship Id="rId{i}" Type="{XLSX_REL}/worksheet" Target="worksheets/sheet{i}.xml"/>' for i in range(1, len(sheets) + 1)
    )
    if strings is not None:
        rels += f'<Relationship Id="rIdS" Type="{XLSX_REL}/sharedStrings" Target="/{strings_part}"/>'
    if types is None:
        overrides = [(workbook, XLSX_WORKBOOK_TYPE)]
        overrides += [(f"{folder}/worksheets/sheet{i}.xml", XLSX_WORKSHEET_TYPE) for i in range(1, len(sheets) + 1)]
        if strings is not None:
            overrides.append((strings_part, XLSX_SHARED_STRINGS_TYPE))
        types = content_types(*overrides)
    files = {
        "[Content_Types].xml": types,
        "_rels/.rels": f'<Relationships xmlns="{OPC_REL}"><Relationship Id="rId1" Type="{XLSX_REL}/officeDocument" Target="{workbook}"/></Relationships>',
        workbook: f'<workbook xmlns="{XLSX_MAIN}" xmlns:r="{XLSX_REL}">{workbook_extra}<sheets>{sheet_list}</sheets>{workbook_tail}</workbook>',
        f"{folder}/_rels/{base}.rels": f'<Relationships xmlns="{OPC_REL}">{rels}{extra_rels}</Relationships>',
    }
    for i, body in enumerate(sheets.values(), 1):
        files[f"{folder}/worksheets/sheet{i}.xml"] = f'<worksheet xmlns="{XLSX_MAIN}"><sheetData>{body}</sheetData></worksheet>'
    if strings is not None:
        files[strings_part] = f'<sst xmlns="{XLSX_MAIN}">{strings}</sst>'
    files.update(extra_files or {})
    return zip_of(files)


def shared_strings_xlsx(count: int = 1500, part: str = "xl/sharedStrings.xml", types: str | None = None) -> bytes:
    """공유 문자열을 쓰는 xlsx 를 손으로 만든다(openpyxl 은 글을 칸 안에 바로 적어 공유 문자열을 만들지 않는다)."""
    strings = "".join(f"<si><t>문자열 {i} {'가' * 20}</t></si>" for i in range(count))
    rows = "".join(f'<row r="{i + 1}"><c r="A{i + 1}" t="s"><v>{i}</v></c></row>' for i in range(count))
    return hand_xlsx({"목록": rows}, strings, strings_part=part, types=types)


def openpyxl_text(data: bytes) -> str:
    """예전 xlsx_text 처럼 openpyxl(read_only, data_only)로 읽은 글. 직접 흘려 읽는 xlsx_text 와 견주는 데 쓴다."""
    from openpyxl import load_workbook

    sink = extract.TextSink(extract.MAX_TEXT_CHARS)
    workbook = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    try:
        scan_left, first_sheet = extract.MAX_SCAN_ROWS, True
        for sheet in workbook.worksheets:
            if scan_left <= 0:
                break
            rows = scanned = 0
            for values in sheet.iter_rows(values_only=True, max_col=extract.MAX_SHEET_COLUMNS, max_row=scan_left):
                scanned += 1
                line = extract.row_line(extract.cell_text(v) for v in values)
                if all(v is None for v in values) or not line:
                    continue
                if not rows:
                    if not first_sheet:
                        sink.add("")
                    first_sheet = False
                    sink.add(f"## 시트: {sheet.title}")
                rows += 1
                if rows > extract.MAX_SHEET_ROWS:
                    sink.add(f"… ({extract.MAX_SHEET_ROWS}행 이후 생략)")
                    break
                sink.add(line)
            scan_left -= scanned
    finally:
        workbook.close()
    return sink.text()


def test_xlsx_shared_strings_are_read_with_count_and_char_limits(monkeypatch):
    """공유 문자열은 흘려 읽고, 들고 있는 문자열 수·글자 수에 끝이 있다. 넘는 문자열을 가리키는 칸은 빈칸이고 truncated 다."""
    data = shared_strings_xlsx()
    whole = extract.extract("strings.xlsx", data)
    assert whole.text.split("\n")[1].startswith("문자열 0") and "문자열 1499 " in whole.text and not whole.truncated
    assert whole.text == openpyxl_text(data)

    monkeypatch.setattr(extract, "MAX_XLSX_STRINGS", 10)
    few = extract.extract("strings.xlsx", data)
    assert "문자열 9 " in few.text and "문자열 10 " not in few.text and few.truncated
    monkeypatch.setattr(extract, "MAX_XLSX_STRINGS", 10**6)

    monkeypatch.setattr(extract, "MAX_XLSX_STRING_CHARS", 100)
    short = extract.extract("strings.xlsx", data)
    assert "문자열 2 " in short.text and "문자열 3 " not in short.text and short.truncated
    monkeypatch.setattr(extract, "MAX_XLSX_STRING_CHARS", 10**8)

    monkeypatch.setattr(extract, "MAX_XLSX_STRINGS_READ_BYTES", 5000)
    cut = extract.extract("strings.xlsx", data)
    assert cut.text.split("\n")[1].startswith("문자열 0") and "문자열 1499 " not in cut.text and cut.truncated


@pytest.mark.parametrize("child", ["<々/>", "<ꯍ/>", "<\U00010000/>"])
def test_xlsx_content_types_do_not_decide_what_is_read(monkeypatch, child):
    """어느 항목을 어떻게 읽을지는 관계(.rels)로 정한다. [Content_Types].xml 은 읽지 않는다 — 해석기마다 받아 주는 글자가 달라
    (expat 은 거절하고 lxml 은 받는 요소 이름) 그 차이로 상한을 피하던 길이 없다. 이름을 바꾼 공유 문자열에도 같은 끝이 걸린다."""
    types = content_types(("xl/strs.bin", XLSX_SHARED_STRINGS_TYPE), extra=child)
    data = shared_strings_xlsx(part="xl/strs.bin", types=types)
    assert extract_text("renamed.xlsx", data).split("\n")[1].startswith("문자열 0")
    monkeypatch.setattr(extract, "MAX_XLSX_STRINGS", 10)
    result = extract.extract("renamed.xlsx", data)
    assert "문자열 9 " in result.text and "문자열 10 " not in result.text and result.truncated
    broken_types = shared_strings_xlsx(count=3, types="this is not xml")
    assert extract_text("odd.xlsx", broken_types) == "## 시트: 목록\n" + "\n".join(f"문자열 {i} {'가' * 20}" for i in range(3))


def test_xlsx_renamed_workbook_is_found_through_the_package_relationships(monkeypatch):
    rows = '<row r="1"><c r="A1" t="inlineStr"><is><t>이름 바꾼 통합 문서</t></is></c></row>'
    padding = "<definedNames>" + "".join(f'<definedName name="n{i}">1</definedName>' for i in range(200)) + "</definedNames>"
    data = hand_xlsx({"표": rows}, workbook="xl/wb.bin", workbook_extra="<workbookPr/>", workbook_tail=padding)
    assert extract_text("wb.xlsx", data) == "## 시트: 표\n이름 바꾼 통합 문서"
    infos = {i.filename: i.file_size for i in zipfile.ZipFile(io.BytesIO(data)).infolist()}
    assert infos["xl/wb.bin"] > max(size for name, size in infos.items() if name.endswith(".rels"))
    monkeypatch.setattr(extract, "MAX_ZIP_PART_BYTES", max(size for name, size in infos.items() if name.endswith(".rels")))
    assert extract.extract("wb.xlsx", data).truncated, "통합 문서도 읽는 바이트 수에 끝이 있다"


def test_xlsx_chartsheets_are_not_read():
    rows = '<row r="1"><c r="A1" t="inlineStr"><is><t>표 시트</t></is></c></row>'
    data = hand_xlsx(
        {"표": rows},
        workbook_extra="",
        extra_rels=f'<Relationship Id="rIdC" Type="{XLSX_REL}/chartsheet" Target="chart.bin"/>',
        extra_files={"xl/chart.bin": "<chartsheet>" + "<x/>" * 1000},
    )
    archive = zipfile.ZipFile(io.BytesIO(data))
    workbook = archive.read("xl/workbook.xml").decode().replace("</sheets>", '<sheet name="차트" sheetId="9" r:id="rIdC"/></sheets>')
    data = zip_of({**{n: archive.read(n) for n in archive.namelist()}, "xl/workbook.xml": workbook})
    assert extract_text("chart.xlsx", data) == "## 시트: 표\n표 시트"


def test_xlsx_is_read_without_building_a_tree():
    """행 밖의 요소·칸이 아주 많은 행·같은 번호가 되풀이되는 행이 있어도 트리를 만들지 않아 메모리가 늘지 않는다.

    openpyxl 은 read_only 에서도 이런 요소를 버리지 않아, 이 시트 하나에 수백 MB 를 쓴다.
    """
    import tracemalloc

    noise = "<x/>" * 300_000
    wide = '<row r="1">' + "<c><v>1</v></c>" * 100_000 + "</row>"
    repeated = '<row r="2"><c r="A2" t="inlineStr"><is><t>둘째 행</t></is></c></row>' * 50_000
    body = f'{noise}{wide}{repeated}<row r="3"><c r="B3"><v>3</v></c></row>'
    data = hand_xlsx({"큰 시트": body})
    tracemalloc.start()
    try:
        text = extract_text("noisy.xlsx", data)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    first = " | ".join(["1"] * extract.MAX_SHEET_COLUMNS)
    assert text == f"## 시트: 큰 시트\n{first}\n둘째 행\n | 3"
    assert peak < 4 * 1024 * 1024, peak


def test_xlsx_rows_are_numbered_like_openpyxl():
    """앞서 내놓은 행 번호보다 작거나 같은 번호의 행은 건너뛰고, 번호가 없는 행은 앞 행 + 1 이다(openpyxl 과 같다)."""
    def row(text, number=None):
        attribute = f' r="{number}"' if number is not None else ""
        return f'<row{attribute}><c t="inlineStr"><is><t>{text}</t></is></c></row>'

    data = hand_xlsx({"번호": row("가", 1) + row("나", 1) + row("다", 2) + row("라") + row("마", 2) + row("바", 5)})
    assert extract_text("rows.xlsx", data) == "## 시트: 번호\n가\n다\n라\n바"
    assert extract_text("rows.xlsx", data) == openpyxl_text(data)


def test_xlsx_cell_values_match_openpyxl():
    from openpyxl import Workbook
    from openpyxl.utils.datetime import CALENDAR_MAC_1904

    def build(epoch=None):
        workbook = Workbook()
        if epoch is not None:
            workbook.epoch = epoch
        sheet = workbook.active
        sheet.title = "값"
        sheet.append(["글", "줄\n바꿈", 7, 2.5, 1e20, True, False, None, "끝"])
        sheet.append([datetime.date(2026, 10, 24), datetime.datetime(2026, 10, 24, 9, 30), datetime.time(13, 5)])
        sheet.append([datetime.timedelta(hours=26, minutes=3), "=1+1", -0.0])
        sheet["BZ4"] = "60칸 밖"
        sheet["A5"] = "넷째 다음"
        workbook.create_sheet("빈 시트")
        out = io.BytesIO()
        workbook.save(out)
        return out.getvalue()

    for data in (build(), build(CALENDAR_MAC_1904)):
        assert extract_text("values.xlsx", data) == openpyxl_text(data)

    strings = (
        "<si><t>보통</t></si>"
        "<si><r><rPr><b/></rPr><t>굵은 </t></r><r><t>조각</t></r></si>"
        "<si><t>漢字</t><rPh sb=\"0\" eb=\"2\"><t>かんじ</t></rPh></si>"
        "<si><t>x005F_밑줄</t></si>"
    )
    cells = (
        '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c>'
        '<c r="D1" t="s"><v>3</v></c><c r="E1" t="b"><v>1</v></c><c r="F1" t="e"><v>#DIV/0!</v></c>'
        '<c r="G1" t="str"><f>A1</f><v>수식 값</v></c><c r="H1" t="inlineStr"><is><r><t>안 </t></r><r><t>글</t></r></is></c></row>'
        '<row r="2"><c r="C2"><v>12.50</v></c><c r="A2" t="d"><v>2026-10-24T08:00:00</v></c></row>'
    )
    data = hand_xlsx({"손": cells}, strings)
    assert extract_text("hand.xlsx", data) == openpyxl_text(data)
    assert extract_text("hand.xlsx", data).split("\n")[1] == "보통 | 굵은 조각 | 漢字 | 밑줄 | True | #DIV/0! | 수식 값 | 안 글"


def test_xlsx_cell_text_and_bad_values_are_kept_as_text(monkeypatch):
    monkeypatch.setattr(extract, "MAX_CELL_CHARS", 5)
    cells = (
        '<row r="1"><c r="A1" t="inlineStr"><is><t>가나다라마바사</t></is></c><c r="B1"><v>숫자 아님</v></c>'
        '<c r="C1" t="s"><v>99</v></c><c r="ZZZZ1"><v>1</v></c></row>'
    )
    assert extract_text("bad.xlsx", hand_xlsx({"이상": cells})) == "## 시트: 이상\n가나다라마 | 숫자 아님 |  | 1"


def test_xlsx_read_limits_are_tens_of_megabytes():
    assert extract.MAX_XLSX_STRINGS_READ_BYTES <= 64 * 1024 * 1024
    assert extract.MAX_XLSX_SHEETS_READ_BYTES <= 128 * 1024 * 1024
    assert extract.MAX_XLSX_STRINGS <= 1_000_000 and extract.MAX_XLSX_STRING_CHARS <= 8_000_000


def renamed_part(data: bytes, old: str, new: str) -> bytes:
    """zip 항목 old 를 new 로 바꾸고, [Content_Types].xml·관계 파일 속 이름도 함께 바꾼다(라이브러리는 그대로 읽는다)."""
    source = zipfile.ZipFile(io.BytesIO(data))
    old_base, new_base = old.rsplit("/", 1)[1], new.rsplit("/", 1)[1]
    files = {}
    for name in source.namelist():
        body = source.read(name)
        if name.endswith(".rels") or name == "[Content_Types].xml":
            body = body.replace(old.encode(), new.encode()).replace(old_base.encode(), new_base.encode())
        files[new if name == old else name] = body
    return zip_of(files)


@pytest.mark.parametrize("kind", ["docx", "pptx"])
def test_renamed_office_xml_is_still_counted_as_xml(monkeypatch, kind):
    """python-docx·python-pptx 는 [Content_Types].xml 이 정한 형식으로 읽으므로 이름을 .bin 으로 바꾼 XML 도 트리로 펼친다.
    그래서 이름이 아니라 첫머리로 XML 인지 가려 한 항목 상한을 건다."""
    if kind == "docx":
        data = renamed_part(make_docx(lambda d: [d.add_paragraph("가" * 300) for _ in range(1500)]), "word/document.xml", "word/document.bin")
        part = "word/document.bin"
    else:
        data = renamed_part(make_pptx(lambda p: titled_slides(p, ["가" * 30000])), "ppt/slides/slide1.xml", "ppt/slides/slide1.bin")
        part = "ppt/slides/slide1.bin"
    infos = zipfile.ZipFile(io.BytesIO(data)).infolist()
    size = next(i.file_size for i in infos if i.filename == part)
    others = max(i.file_size for i in infos if i.filename != part and i.filename.endswith((".xml", ".rels")))
    assert size > others
    assert extract_text(f"renamed.{kind}", data).replace("## 슬라이드 1\n", "").startswith("가가가")
    monkeypatch.setattr(extract, "MAX_ZIP_PART_BYTES", others)
    assert error_code(f"renamed.{kind}", data) == BROKEN_FILE


@pytest.mark.parametrize("head", [
    b'<?xml version="1.0"?><a/>', b" \r\n\t<a/>", b"\xef\xbb\xbf <a/>", " <a/>".encode("utf-16"),
    "﻿<a/>".encode("utf-16-be"), "<?xml?>".encode("utf-16-le"), "<a/>".encode("utf-32-le"),
    "﻿<a/>".encode("utf-32-be"), b"\x4c\x6f\xa7\x94", b"   ", b"",
])
def test_may_be_xml(head):
    assert extract.may_be_xml(head)


@pytest.mark.parametrize("head", [
    b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff\xe0", b"GIF89a", b"PK\x03\x04", b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1",
    b"\x00\x00\x00\x18ftypmp42", b"%PDF-1.7", b"\x01\x00\x00\x00", b"hello <a/>",
])
def test_binary_heads_are_not_xml(head):
    assert not extract.may_be_xml(head)


def test_zip_with_too_many_entries_is_broken(monkeypatch):
    docx = make_docx(lambda d: d.add_paragraph("짧은 문단"))
    monkeypatch.setattr(extract, "MAX_ZIP_ENTRIES", len(zipfile.ZipFile(io.BytesIO(docx)).infolist()) - 1)
    assert error_code("many.docx", docx) == BROKEN_FILE


def titled_slides(presentation, titles):
    for title in titles:
        presentation.slides.add_slide(presentation.slide_layouts[5]).shapes.title.text = title


@pytest.mark.parametrize("kind", ["txt", "html", "pdf", "docx", "xlsx", "pptx", "hwpx", "hwp"])
def test_every_format_stops_at_max_text_chars(monkeypatch, kind):
    lines = [f"Line {i} of the quarterly plan" for i in range(200)]
    data = {
        "txt": lambda: "\n".join(lines).encode(),
        "html": lambda: "".join(f"<p>{line}</p>" for line in lines).encode(),
        "pdf": lambda: make_pdf(["\n".join(lines[i:i + 20]) for i in range(0, 200, 20)]),
        "docx": lambda: make_docx(lambda d: [d.add_paragraph(line) for line in lines]),
        "xlsx": lambda: make_xlsx(lambda w: [w.active.append([line]) for line in lines]),
        "pptx": lambda: make_pptx(lambda p: titled_slides(p, lines[:40])),
        "hwpx": lambda: make_hwpx({"Contents/section0.xml": hwpx_section("".join(hwpx_para(line) for line in lines))}),
        "hwp": lambda: hwp.build_hwp([[hwp.paragraph(line) for line in lines]]),
    }[kind]()
    whole = extract.extract(f"plan.{kind}", data)
    assert not whole.truncated and len(whole.text) > 300

    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 300)
    cut = extract.extract(f"plan.{kind}", data)
    assert cut.truncated
    assert 0 < len(cut.text) <= 300
    assert whole.text.startswith(cut.text)


# ---------- xlsx ----------

def test_xlsx_sheets_rows_and_values():
    def build(workbook):
        sheet = workbook.active
        sheet.title = "예산"
        sheet.append(["부서", "금액", "날짜", "비율"])
        sheet.append(["개발팀", 4200.0, datetime.datetime(2026, 11, 2), 0.25])
        sheet.append([None, None, None, None])
        sheet.append(["디자인팀", 1500, None, None])
        workbook.create_sheet("빈 시트")
        other = workbook.create_sheet("일정")
        other.append(["배포", "11월 5일"])

    text = extract_text("budget.xlsx", make_xlsx(build))
    assert text == (
        "## 시트: 예산\n부서 | 금액 | 날짜 | 비율\n개발팀 | 4200 | 2026-11-02 | 0.25\n디자인팀 | 1500\n\n"
        "## 시트: 일정\n배포 | 11월 5일"
    )


def test_xlsx_row_limit(monkeypatch):
    monkeypatch.setattr(extract, "MAX_SHEET_ROWS", 3)

    def build(workbook):
        for i in range(10):
            workbook.active.append([f"행 {i}"])

    lines = extract_text("many.xlsx", make_xlsx(build)).split("\n")
    assert lines[1:4] == ["행 0", "행 1", "행 2"]
    assert "행 3" not in lines
    assert len(lines) == 5


def test_xlsx_row_limit_marks_truncated(monkeypatch):
    monkeypatch.setattr(extract, "MAX_SHEET_ROWS", 3)
    many = make_xlsx(lambda w: [w.active.append([f"행 {i}"]) for i in range(10)])
    assert extract.extract("many.xlsx", many).truncated
    few = make_xlsx(lambda w: [w.active.append([f"행 {i}"]) for i in range(3)])
    assert not extract.extract("few.xlsx", few).truncated


def test_xlsx_far_away_row_does_not_scan_a_million_empty_rows(monkeypatch):
    def build(workbook):
        workbook.active["A1"] = "제목"
        workbook.active["A1048576"] = "끝"
        workbook.create_sheet("둘째")["A1"] = "둘째 시트"

    data = make_xlsx(build)
    monkeypatch.setattr(extract, "MAX_SCAN_ROWS", 100)
    assert extract_text("far.xlsx", data) == "## 시트: Sheet\n제목"


def test_xlsx_with_only_empty_sheets_has_no_text():
    assert error_code("empty.xlsx", make_xlsx(lambda workbook: None)) == NO_TEXT


# ---------- pptx ----------

def test_pptx_slides_shapes_tables_groups_and_notes():
    from pptx.util import Inches

    def build(presentation):
        first = presentation.slides.add_slide(presentation.slide_layouts[1])
        first.shapes.title.text = "도입 배경"
        first.placeholders[1].text = "대기 시간 18분\n잔반 42kg"
        first.notes_slide.notes_text_frame.text = "9월 자료 기준"

        second = presentation.slides.add_slide(presentation.slide_layouts[5])
        second.shapes.title.text = "일정"
        table = second.shapes.add_table(2, 2, Inches(1), Inches(2), Inches(6), Inches(1)).table
        for r, row in enumerate([("단계", "기간"), ("개발", "11월")]):
            for c, value in enumerate(row):
                table.cell(r, c).text = value
        group = second.shapes.add_group_shape()
        group.shapes.add_textbox(Inches(1), Inches(4), Inches(3), Inches(1)).text_frame.text = "묶음 속 글"

    text = extract_text("plan.pptx", make_pptx(build))
    assert text == (
        "## 슬라이드 1\n도입 배경\n대기 시간 18분\n잔반 42kg\n발표자 노트: 9월 자료 기준\n\n"
        "## 슬라이드 2\n일정\n단계 | 기간\n개발 | 11월\n묶음 속 글"
    )


def test_broken_pptx():
    assert error_code("a.pptx", b"PK\x03\x04broken") == BROKEN_FILE


# ---------- hwpx ----------

def test_hwpx_sections_in_number_order_with_tables():
    table = (
        "<hp:p><hp:run><hp:t>표 앞 글</hp:t><hp:tbl><hp:tr>"
        "<hp:tc><hp:subList><hp:p><hp:run><hp:t>칸 하나</hp:t></hp:run></hp:p></hp:subList></hp:tc>"
        "<hp:tc><hp:subList><hp:p><hp:run><hp:t>칸 둘</hp:t></hp:run></hp:p></hp:subList></hp:tc>"
        "</hp:tr></hp:tbl></hp:run></hp:p>"
    )
    data = make_hwpx({
        "Contents/section10.xml": hwpx_section(hwpx_para("열한째 구역")),
        "Contents/section0.xml": hwpx_section(
            hwpx_para("첫 문단") + "<hp:p><hp:run><hp:t>탭<hp:tab/>뒤 글<hp:lineBreak/>다음 줄</hp:t></hp:run></hp:p>" + table
        ),
        "Contents/section2.xml": hwpx_section(hwpx_para("셋째 구역")),
    })
    assert extract_text("notice.hwpx", data).split("\n") == [
        "첫 문단", "탭\t뒤 글", "다음 줄", "표 앞 글", "칸 하나", "칸 둘", "셋째 구역", "열한째 구역",
    ]


LINESEG = (
    '<hp:linesegarray><hp:lineseg textpos="0" vertpos="0" vertsize="1000" textheight="1000" baseline="850" '
    'spacing="600" horzpos="0" horzsize="42520" flags="393216"/></hp:linesegarray>'
)


def test_hwpx_section_is_streamed_not_loaded_whole(monkeypatch):
    import tracemalloc

    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 10**9)
    count = 4 * 1024 * 1024 // len(f"<hp:p><hp:run><hp:t>x</hp:t></hp:run>{LINESEG}</hp:p>")
    xml = hwpx_section("".join(f"<hp:p><hp:run><hp:t>{i}</hp:t></hp:run>{LINESEG}</hp:p>" for i in range(count)))
    data = make_hwpx({"Contents/section0.xml": xml})
    tracemalloc.start()
    try:
        text = extract_text("big.hwpx", data)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert text.split("\n")[:3] == ["0", "1", "2"] and text.endswith(str(count - 1))
    assert peak < len(xml) // 2


def test_hwpx_elements_inside_text_are_not_kept_in_memory():
    """`<hp:t>` 안에 요소를 아주 많이 넣어도 트리를 만들지 않아 메모리가 늘지 않는다. 표시 요소(탭 등)는 그대로 글이 된다."""
    import tracemalloc

    xml = hwpx_section(f"<hp:p><hp:run><hp:t>시작{'<hp:x/>' * 300_000}<hp:tab/>끝</hp:t></hp:run></hp:p>")
    data = make_hwpx({"Contents/section0.xml": xml})
    tracemalloc.start()
    try:
        text = extract_text("noisy.hwpx", data)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert text == "시작\t끝"
    assert peak < len(xml) // 4, peak


def test_hwpx_long_paragraph_is_cut_at_max_chars(monkeypatch):
    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 50)
    data = make_hwpx({"Contents/section0.xml": hwpx_section(hwpx_para("가" * 200) + hwpx_para("다음"))})
    result = extract.extract("long.hwpx", data)
    assert result.text == "가" * 50 and result.truncated


def test_hwpx_reads_only_up_to_the_byte_limit(monkeypatch):
    monkeypatch.setattr(extract, "MAX_HWPX_READ_BYTES", 5000)
    paragraphs = "".join(hwpx_para(f"{i}번 문단") + LINESEG for i in range(300))
    data = make_hwpx({
        "Contents/section0.xml": hwpx_section(paragraphs),
        "Contents/section1.xml": hwpx_section(hwpx_para("둘째 구역")),
    })
    result = extract.extract("long.hwpx", data)
    assert result.text.startswith("0번 문단\n1번 문단")
    assert "299번 문단" not in result.text and "둘째 구역" not in result.text
    assert result.truncated


def test_hwpx_encrypted_and_broken():
    manifest = '<odf:manifest xmlns:odf="urn:x"><odf:file-entry><odf:encryption-data/></odf:file-entry></odf:manifest>'
    encrypted = make_hwpx({"Contents/section0.xml": hwpx_section(hwpx_para("x"))}, manifest)
    assert error_code("a.hwpx", encrypted) == ENCRYPTED_FILE
    assert error_code("a.hwpx", make_hwpx({"Contents/header.xml": "<x/>"})) == BROKEN_FILE
    assert error_code("a.hwpx", make_hwpx({"Contents/section0.xml": "<not closed"})) == BROKEN_FILE


# ---------- hwp (5.0) ----------

@pytest.mark.parametrize("compressed", [True, False])
def test_hwp_paragraphs_in_section_order(compressed):
    data = hwp.build_hwp([
        [hwp.paragraph("업무 협조 요청"), hwp.paragraph("표 앞", prefix=hwp.extended_control())],
        [hwp.paragraph("둘째 구역 문단", level=1)],
    ], compressed=compressed)
    assert extract_text("request.hwp", data) == "업무 협조 요청\n표 앞\n둘째 구역 문단"


def test_hwp_control_characters():
    tab = struct.pack("<H", 9) + b"\0" * 12 + struct.pack("<H", 9)
    body = "가".encode("utf-16-le") + tab + "나".encode("utf-16-le") + struct.pack("<H", 10) + "다".encode("utf-16-le")
    body += struct.pack("<H", 30) + "라".encode("utf-16-le") + struct.pack("<H", 24) + struct.pack("<H", 13)
    data = hwp.build_hwp([[hwp.record(hwp.HWPTAG_PARA_TEXT, body)]])
    assert extract_text("a.hwp", data) == "가\t나\n다 라"


def test_hwp_long_record_and_big_stream():
    long_text = "긴 문단 " * 1200
    data = hwp.build_hwp([[hwp.paragraph("시작"), hwp.paragraph(long_text), hwp.paragraph("끝")]], compressed=False)
    assert extract_text("long.hwp", data) == f"시작\n{long_text.strip()}\n끝"


@pytest.mark.parametrize("flags", [extract.HWP_PASSWORD, extract.HWP_DISTRIBUTION])
def test_hwp_password_or_distribution_is_encrypted(flags):
    assert error_code("locked.hwp", hwp.build_hwp([[hwp.paragraph("비밀")]], flags=flags)) == ENCRYPTED_FILE


def test_broken_hwp():
    assert error_code("a.hwp", b"not an ole file") == BROKEN_FILE
    assert error_code("a.hwp", hwp.build_ole({"Other": b"x" * 100})) == BROKEN_FILE
    no_body = hwp.build_ole({"FileHeader": b"HWP Document File".ljust(256, b"\0")})
    assert error_code("a.hwp", no_body) == BROKEN_FILE
    bad_header = hwp.build_ole({"FileHeader": b"Something Else".ljust(256, b"\0"), "BodyText/Section0": b"x"})
    assert error_code("a.hwp", bad_header) == BROKEN_FILE
    header = b"HWP Document File".ljust(32, b"\0") + struct.pack("<II", 0x05000300, 1)
    corrupt = hwp.build_ole({"FileHeader": header.ljust(256, b"\0"), "BodyText/Section0": b"\xff" * 64})
    assert error_code("a.hwp", corrupt) == BROKEN_FILE


def test_hwp_decompression_bomb_is_capped(monkeypatch):
    monkeypatch.setattr(extract, "MAX_HWP_BODY_BYTES", 64 * 1024)
    inflated = []
    original = zlib.decompressobj

    class Spy:
        def __init__(self, wbits):
            self.inner = original(wbits)
            self.unconsumed_tail = b""

        def decompress(self, data, max_length=0):
            out = self.inner.decompress(data, max_length)
            self.unconsumed_tail = self.inner.unconsumed_tail
            inflated.append(len(out))
            return out

    monkeypatch.setattr(extract.zlib, "decompressobj", Spy)
    raw = hwp.record(hwp.HWPTAG_PARA_HEADER, b"\0" * 22) * (8 * 1024 * 1024 // 26)
    data = hwp.build_hwp([[hwp.paragraph("앞 문단"), raw, hwp.paragraph("뒤 문단")]])
    assert len(data) < 100_000
    result = extract.extract("bomb.hwp", data)
    assert result.text == "앞 문단" and result.truncated
    assert sum(inflated) <= 64 * 1024


def test_hwp_text_stops_at_max_chars_before_inflating_everything(monkeypatch):
    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 1000)
    data = hwp.build_hwp([[hwp.paragraph(f"{i}번 문단") for i in range(5000)], [hwp.paragraph("둘째 구역")]])
    result = extract.extract("long.hwp", data)
    assert result.text.startswith("0번 문단\n1번 문단") and len(result.text) <= 1000
    assert "둘째 구역" not in result.text and result.truncated


def test_hwp_body_limit_spans_sections(monkeypatch):
    first = [hwp.paragraph(f"{i}번") for i in range(40)]
    data = hwp.build_hwp([first, [hwp.paragraph("둘째 구역")]], compressed=False)
    assert extract.extract("two.hwp", data).text.endswith("둘째 구역")
    monkeypatch.setattr(extract, "MAX_HWP_BODY_BYTES", len(b"".join(first)))
    result = extract.extract("two.hwp", data)
    assert result.text.endswith("39번") and result.truncated


def test_old_hwp3_is_unsupported():
    data = b"HWP Document File V3.00 \x1a\x01\x02\x03\x04\x05" + b"\0" * 200
    assert error_code("옛문서.hwp", data) == UNSUPPORTED_FILE


def test_hwpx_saved_with_hwp_name_is_read_as_hwpx():
    data = make_hwpx({"Contents/section0.xml": hwpx_section(hwpx_para("이름만 hwp 인 hwpx"))})
    assert extract_text("이름만바뀜.hwp", data) == "이름만 hwp 인 hwpx"


def test_hwp_without_text_has_no_text():
    assert error_code("blank.hwp", hwp.build_hwp([[hwp.record(hwp.HWPTAG_PARA_HEADER, b"\0" * 22)]])) == NO_TEXT


def test_hwp_builder_compresses_like_hwp():
    data = hwp.build_hwp([[hwp.paragraph("압축")]], compressed=True)
    import olefile

    with olefile.OleFileIO(io.BytesIO(data)) as ole:
        raw = zlib.decompressobj(-15).decompress(ole.openstream("BodyText/Section0").read())
    assert "압축".encode("utf-16-le") in raw


def test_zip_directory_size_is_read_like_zipfile():
    docx = make_docx(lambda d: d.add_paragraph("짧은 문단"))
    with_comment = io.BytesIO()
    with zipfile.ZipFile(with_comment, "w") as archive:
        archive.writestr("a.txt", "가")
        archive.comment = b"comment PK"
    for data in (docx, with_comment.getvalue()):
        assert extract.zip_directory_size(data) == zipfile._EndRecData(io.BytesIO(data))[zipfile._ECD_SIZE]
    assert extract.zip_directory_size(b"not a zip") is None


def test_zip_with_a_huge_directory_is_refused_before_opening(monkeypatch):
    """빈 항목 수만 개를 담은 zip 은 zipfile 이 열기만 해도 항목마다 ZipInfo 를 만든다. 끝 레코드로 크기를 보고 열지 않는다.

    항목이 65535 개를 넘으면 ZIP64 끝 레코드에 크기가 적힌다 — 그 값도 같은 방법으로 읽는다.
    """
    import tracemalloc

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("word/document.xml", "<w/>")
        for i in range(70_000):
            archive.writestr(str(i), b"")
    data = out.getvalue()
    size = extract.zip_directory_size(data)
    assert size == zipfile._EndRecData(io.BytesIO(data))[zipfile._ECD_SIZE] and size > extract.MAX_ZIP_DIRECTORY_BYTES
    tracemalloc.start()
    try:
        assert error_code("many.docx", data) == BROKEN_FILE
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert peak < 1024 * 1024, peak
    monkeypatch.setattr(extract, "MAX_ZIP_DIRECTORY_BYTES", size)
    monkeypatch.setattr(extract, "MAX_ZIP_ENTRIES", 10**6)
    assert error_code("many.docx", data) == BROKEN_FILE, "열리지만 docx 가 아니다"
