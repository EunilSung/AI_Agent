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
