"""후속 질문 검색 비교: 마지막 질문만 임베딩 vs 재작성한 질문으로 임베딩.

사용: .venv/bin/python eval_followup.py
"""

import asyncio
import time

import app

# (앞 대화, 후속 질문, 정답 문서·키워드)
CASES = [
    ([("user", "갑자기 아프면 출근 못 한다고 어떻게 알려야 해?"),
      ("assistant", "오전 9시 30분까지 팀장에게 메신저로 알리고, 출근 후 3일 이내에 그룹웨어로 사후 신청합니다.")],
     "그거 어느 메뉴에서 해?", "01_휴가규정.md", "근태 > 휴가 신청"),
    ([("user", "갑자기 아프면 출근 못 한다고 어떻게 알려야 해?"),
      ("assistant", "오전 9시 30분까지 팀장에게 메신저로 알리고, 출근 후 3일 이내에 그룹웨어로 사후 신청합니다.")],
     "Which groupware menu do I use for that?", "01_휴가규정.md", "근태 > 휴가 신청"),
    ([("user", "재택근무는 일주일에 몇 번 할 수 있어?"),
      ("assistant", "수습이 끝난 정규직은 주 2회까지 가능하고, 월요일은 안 됩니다.")],
     "신청은 언제까지 해야 돼?", "02_재택근무.md", "전날 오후 6시"),
    ([("user", "테스트 기기는 어떻게 빌려?"),
      ("assistant", "장비 관리 담당자에게 대여 신청서를 내고 빌립니다.")],
     "얼마나 오래 쓸 수 있어?", "03_장비지원.md", "최대 2주"),
    ([("user", "연차는 며칠 받아?"),
      ("assistant", "입사 1년 이상이면 매년 15일이고, 근속 3년차부터 2년마다 1일씩 늘어 최대 25일입니다.")],
     "다 못 쓰면 어떻게 돼?", "01_휴가규정.md", "이월"),
]


async def rank(query: str, want_source: str, want_text: str):
    """정답 조각의 순위 (TOP_K 안에 없으면 None)"""
    for i, h in enumerate(await app.search(query), 1):
        if h["source"] == want_source and want_text in h["doc"]:
            return i
    return None


async def main():
    stats = {"before": [0, 0, 0], "after": [0, 0, 0]}  # [1위, 2위 안, TOP_K 안]
    for history, question, want_source, want_text in CASES:
        messages = [app.Message(role=r, content=c) for r, c in history] + [app.Message(role="user", content=question)]
        t0 = time.time()
        rewritten = await app.rewrite_query(messages) if hasattr(app, "rewrite_query") else question
        took = time.time() - t0

        before = await rank(question, want_source, want_text)
        after = await rank(rewritten, want_source, want_text)
        for key, r in (("before", before), ("after", after)):
            stats[key][0] += r == 1
            stats[key][1] += r is not None and r <= 2
            stats[key][2] += r is not None
        print(f"Q: {question}")
        print(f"   재작성  {rewritten!r} ({took:.2f}s)")
        print(f"   정답 조각 순위: 변경 전 {before or '-'}위 → 변경 후 {after or '-'}위")
    n = len(CASES)
    print(f"\n정답 조각 1위: 변경 전 {stats['before'][0]}/{n}, 변경 후 {stats['after'][0]}/{n}")
    print(f"정답 조각 2위 안: 변경 전 {stats['before'][1]}/{n}, 변경 후 {stats['after'][1]}/{n}")
    print(f"정답 조각 상위 {app.TOP_K}개 안: 변경 전 {stats['before'][2]}/{n}, 변경 후 {stats['after'][2]}/{n}")


asyncio.run(main())
