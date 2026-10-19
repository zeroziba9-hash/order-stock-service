# 컬렉션 디자인

## 구성과 범위

첫 화면의 풍경 배경과 큰 제목에서 상품 목록으로 이동하고, 목록 아래 소개 영역과 내 주문 링크를 제공합니다.
크림색 바탕·짙은 녹색·직선형 버튼을 사용하며 상품 목록의 검색·필터·정렬과 주문 동작은 기존 API를 유지합니다.
넓은 이미지와 소개 콘텐츠의 흐름은 [National Park Foundation](https://www.nationalparks.org/)을 참고했습니다.
해당 사이트의 소스·사진·문구·로고를 가져오지 않았습니다.

- 화면: `src/main/resources/static/index.html`
- 전용 스타일: `src/main/resources/static/assets/editorial.css`
- 공통 컴포넌트: 기존 `store.css`, `store.js`, `accessibility.js`
- 상품 자료: 기존 데모 데이터를 유지하며 빈 사진·설명·규격을 임의로 채우지 않습니다.

첫 화면의 제목·설명·버튼은 이미지 위에 HTML로 배치합니다. 장식용 이미지는 빈 대체 텍스트로 제공하고
이미지 로딩이 실패해도 녹색 배경에서 내용을 읽고 컬렉션으로 이동할 수 있습니다.
휴대폰에서는 한 열로 배치하고 메뉴 줄바꿈을 허용합니다. 배경 이미지는 움직이지 않으며 자동 재생·스크롤 애니메이션을 추가하지 않습니다.

## 배경 이미지 제작

내장 이미지 생성 도구로 새로 제작한 가상의 풍경입니다. 실제 공원·촬영 장소·상품 사진을 나타내지 않습니다.
외부 이미지 주소나 API 키 없이 정적 파일로 제공합니다.

| 파일 | 크기 | 용도 |
|---|---|---|
| `assets/images/stockroom-forest-hero.webp` | 1536 × 1024, 211,044바이트 | 기본 배경 |
| `assets/images/stockroom-forest-mobile.webp` | 768 × 512, 55,100바이트 | 화면 너비 600px 이하 |

브라우저의 `picture`가 화면 너비에 맞는 이미지를 선택합니다. 첫 화면 이미지는 우선 로딩하며,
텍스트 대비는 그라데이션 오버레이로 확보합니다. WebP 파일은 생성 원본에서 크기·포맷만 최적화했습니다.

사용한 생성 프롬프트:

```text
Use case: photorealistic-natural. Asset type: full width website hero background for STOCKROOM, a Korean small lifestyle collection storefront, inspired by editorial nature photography. Create an original cinematic photograph of misty forested mountains at dawn, deep evergreen woodland in foreground, layered soft sage hills receding into warm ivory sky. Subtle sun rays from upper right, calm and tactile like a quiet early morning outdoors, natural photographic grain. Landscape wide 3:2 or wider. Composition: left half is relatively dark softly textured forest with plenty of uninterrupted space where real HTML white headline will be overlaid; strongest pale mist and mountain silhouettes on right half. Image must still work center-cropped on mobile. Colors: deep pine green, muted olive, warm cream light. No text, no lettering, no logos, no watermark, no buildings, no people, no products. This is brand atmosphere, not a product photo. Return a usable image file.
```

## 화면 확인

기존 [브라우저 검사](browser-tests.md)로 상품 조회·로그인·주문·장바구니·계정·모달 초점·접근성을 확인합니다.
레이아웃 변경 시 아래도 확인합니다.

1. 1440px·390px·320px에서 가로 넘침과 잘린 메뉴가 없는지 확인합니다.
2. 첫 화면과 소개 영역의 컬렉션 링크가 목록으로 이동하며 제목에 키보드 초점이 도달하는지 확인합니다.
3. 이미지 로딩 실패와 200% 텍스트 확대에서도 제목·버튼·검색·상품 목록을 사용할 수 있는지 확인합니다.
4. 상품 상세·장바구니·내 주문으로 이동했을 때 소개 영역이 함께 숨겨지고, 상단과 하단 메뉴로 복귀할 수 있는지 확인합니다.

WebKit 자동 검사는 실제 iPhone Safari의 실기기 확인과 구분합니다.
