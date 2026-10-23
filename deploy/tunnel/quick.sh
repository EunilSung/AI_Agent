#!/bin/sh
# 임시 터널 — 계정·도메인 없이 https://<무작위>.trycloudflare.com 주소를 받아 브릿지(8095)에 잇는다.
# 실행할 때마다 주소가 바뀌고, 주소를 아는 사람은 누구나 로그인 화면까지 닿는다. 시연·확인용으로만 짧게 쓰고 Ctrl+C 로 닫는다.
# Cloudflare 공식 문서상 임시 터널은 SSE(답변 스트리밍)를 지원하지 않는다 — 운영에는 고정 주소 터널을 쓴다.
exec cloudflared tunnel --no-autoupdate --url http://localhost:8095
