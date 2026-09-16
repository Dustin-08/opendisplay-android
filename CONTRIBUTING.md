# 기여 가이드 (혼자 쓰는 규칙)

`opendisplay-android` 프로젝트의 브랜치 · 커밋 · PR 규칙입니다.
혼자 개발하는 프로젝트지만, 습관을 들이고 나중에 히스토리를 훑어보기 편하도록
최소한의 규칙 + CI 게이트는 지킵니다.

---

## 1. 브랜치

- `main`은 **직접 push 지양**. 어지간하면 브랜치 → PR을 거칩니다.
  (급한 오타 수정 등 사소한 건 예외로 두되, 습관이 흐트러지지 않게 최대한 지킬 것.)
- 브랜치 이름: `<type>/<간단한-설명>` (kebab-case)

  ```
  feature/touch-scroll
  fix/decoder-sps-size
  refactor/protocol-package-split
  chore/ci-setup
  docs/roadmap-update
  ```

- `type` 종류는 아래 커밋 규칙과 동일합니다.

## 2. 커밋 메시지 — Conventional Commits

형식: `type: 요약` (요약 본문은 한글 OK)

```
feat: scroll 이벤트 전송 구현
fix: SPS에서 실제 해상도 파싱하도록 수정
refactor: 프로토콜 처리 로직 별도 패키지로 분리
docs: ROADMAP Stage 1 체크리스트 갱신
chore: CI 워크플로우 추가
```

| type       | 용도 |
|------------|------|
| `feat`     | 새 기능 |
| `fix`      | 버그 수정 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `chore`    | 빌드 · 설정 · 의존성 등 |
| `docs`     | 문서만 변경 |
| `style`    | 포맷 · 세미콜론 등 코드 의미 없는 변경 |
| `test`     | 테스트 추가 · 수정 |

## 3. Pull Request

- **PR 제목도 Conventional Commits 형식**으로 작성. Squash merge 시 이 제목이
  그대로 `main`의 커밋 메시지가 됩니다.
- PR을 열면 `.github/PULL_REQUEST_TEMPLATE.md`가 자동으로 채워집니다.
- **한 PR = 한 목적.** 프로토콜 로직 수정하면서 문서까지 같이 왕창 고치지 않기.
- **동작/화면에 영향 있는 변경이면 로그캣 캡처나 화면 녹화 첨부** (리디자인
  프로젝트의 "스크린샷 필수" 규칙을 이 프로젝트 성격에 맞게 바꾼 것 —
  네트워크/디코더 쪽은 화면보다 로그가 더 유용할 때가 많음).

## 4. 머지 전 게이트

머지 전에 로컬에서 아래가 통과해야 합니다. (CI가 PR마다 자동으로도 검사합니다)

```bash
gradle lint          # Android Lint
gradle assembleDebug # 컴파일 확인
```

(Gradle wrapper를 커밋하면 `./gradlew lint`, `./gradlew assembleDebug`로
바뀝니다 — 아직 wrapper 파일을 커밋하지 않아서 CI는 지금 Gradle을 직접
설치해서 씁니다. Android Studio에서 한 번 열면 wrapper가 생기니, 그 다음엔
커밋해서 `./gradlew`로 통일하는 걸 추천.)

## 5. 리뷰 · 머지

- 리뷰어가 없는 혼자 프로젝트라 **셀프 머지**. 대신 **CI(lint + build)는
  항상 통과**해야 머지합니다 — 이게 사실상의 리뷰어 역할.
- **머지 전략: Squash and merge**로 통일 (히스토리 깔끔하게).
- GitHub 저장소 Settings → Branches에서 `main`에
  "Require status checks to pass before merging" 켜두면 CI 실패 시
  머지 버튼이 아예 막힙니다. 혼자 하더라도 이거 하나는 켜두는 걸 추천.

## 6. 로컬 실행

```bash
# Android Studio에서 프로젝트 열기 (권장) — Gradle sync 자동으로 됨
# 또는 커맨드라인:
gradle assembleDebug
```

실기기(갤럭시 탭 S8)에 USB 디버깅으로 연결한 뒤 Run 하는 게 가장 확실합니다.
`SurfaceView`/`MediaCodec`은 에뮬레이터에서 동작이 불안정할 수 있어요.
