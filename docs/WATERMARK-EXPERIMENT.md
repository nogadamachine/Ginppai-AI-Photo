# 픽셀 재처리 실험

## 결론과 한계

**모든 AI 표시와 보이지 않는 워터마크를 제거하는 기능은 아닙니다.** 픽셀 재처리는 일부 주파수 기반 신호를 약화하는 선택적 실험입니다. 기본값은 꺼짐이며 사진의 색상, 계조와 디테일이 손상될 수 있습니다. 원본 파일은 수정하지 않습니다.

메타데이터 삭제, 픽셀 워터마크 검출, 범용 AI 분류와 플랫폼의 표시 정책은 서로 다릅니다. Instagram 등 외부 서비스에 이미 붙은 표시를 이 앱이 직접 삭제할 수 없습니다.

Google은 [SynthID](https://deepmind.google/models/synthid/)가 크롭, 필터와 손실 압축에 견디도록 설계했다고 설명합니다. [Meta의 AI 표시 안내](https://about.fb.com/news/2024/04/metas-approach-to-labeling-ai-generated-content-and-manipulated-media/)도 업계 신호와 사용자의 공개 여부 등 표시 근거를 구분합니다.

## 구현

1. 기존처럼 새 sRGB 이미지로 디코딩하고 원본 메타데이터를 복사하지 않습니다.
2. 실험 모드에서만 가로와 세로를 각각 75%로 축소한 뒤 원래 크기로 보간합니다.
3. 밝기 성분은 유지하면서 두 색차 성분을 32단위로 양자화합니다.
4. 선택한 PNG 또는 JPEG로 저장하고 메타데이터 허용 목록 검사와 재디코딩 검증을 수행합니다.

출력 픽셀 수가 같아도 세부 정보는 손실됩니다. 이는 손실 없는 워터마크 분리나 생성 모델 기반 복원이 아닙니다. 추가 AI 모델, 서버와 유료 API는 사용하지 않습니다.

## 2026-10-18 로컬 실험

- 비교 구현: [invisible-watermark](https://github.com/ShieldMnt/invisible-watermark) 0.2.0의 `dwtDct`, `dwtDctSvd`
- 이미지: 결정적 난수, 파형, 원과 글자로 만든 512 x 512 합성 이미지 6개
- 각 이미지에 두 방식으로 같은 64비트 시험 문자열을 삽입해 총 12개 입력 생성
- Android 앱의 실제 `ImageProcessor`에서 PNG와 실험 모드로 처리
- 결과를 Mac으로 회수해 동일한 공개 디코더로 전후 비트 일치율 계산
- Android 앱에는 이 디코더가 내장되어 있지 않음. 개별 사진의 제거 성공 여부를 판정하지 않음

| 방식 | 처리 전 평균 비트 일치율 | 처리 후 평균 비트 일치율 | 처리 전 이미지 대비 평균 PSNR |
| --- | --- | --- | --- |
| dwtDct | 98.44% | 52.34% | 24.55 dB |
| dwtDctSvd | 100.00% | 54.95% | 24.55 dB |

이 수치는 알려진 64비트 문자열과 복원 비트의 일치 비율입니다. **AI 판별 회피 성공률, 워터마크 제거율 또는 검출기의 확률 점수가 아닙니다.** 동일 난수 이미지에 넣은 두 워터마크의 작은 표본일 뿐이며 실제 사진, 다른 파라미터, 보정이나 오류정정을 쓰는 검출기에 일반화할 수 없습니다. PSNR 수치도 화질 손실이 있다는 뜻이며 사람의 선호도 평가를 대신하지 않습니다.

단순한 축소 후 복원만으로는 이 시험의 `dwtDctSvd`가 100% 일치 상태로 남았습니다. 색차 처리를 추가해 낮아졌지만 다른 워터마크까지 없어졌다는 증거는 아닙니다. SynthID, Stable Signature, Tree-Ring, RivaGAN과 범용 AI 분류기는 시험하지 않았습니다.

[이미지 재생성 기반 연구](https://arxiv.org/abs/2410.05470)는 별도의 생성 모델로 다시 그리는 접근입니다. 이 앱은 그 모델을 탑재하지 않았고 같은 성능을 주장하지 않습니다.

## 재현

Android 테스트는 공개 저장소의 합성 fixture만 읽습니다. 개인 사진이나 계정은 필요하지 않습니다.

```sh
python3 -m venv work/watermark-env
work/watermark-env/bin/pip install --no-deps -r tools/watermark-requirements.txt
./gradlew connectedDebugAndroidTest
adb pull /sdcard/Android/data/com.nogada.imageclean/files/watermark-reference work/watermark-android
work/watermark-env/bin/python tools/watermark_benchmark.py --android-output work/watermark-android
```

Python 스크립트는 주파수 방식의 모듈만 읽습니다. 사용하지 않는 RivaGAN 모델과 PyTorch는 설치하지 않습니다. 시험 입력은 debug 빌드에만 포함되며 릴리스 APK에는 포함하지 않습니다. [개별 측정값](watermark-results.json)도 확인할 수 있습니다.
