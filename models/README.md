# EveryVideo AI models

앱이 처음 쓸 때 내려받는 기기 내 AI 모델 (Android, Windows 공용). 앱은 SHA-256 으로 파일을 확인한다.

| 파일 | 용도 | 원본 | 라이선스 |
|---|---|---|---|
| `colorize.onnx` | 흑백 → 컬러 (입력 L 1×1×H×W, 0~100 / 출력 ab 1×2×H×W) | Zhang et al., "Real-Time User-Guided Image Colorization with Learned Deep Priors" (SIGGRAPH 2017), [richzhang/colorization](https://github.com/richzhang/colorization) `siggraph17` 가중치. 힌트 입력 없이 자동 컬러화로 내보내고 Conv 가중치를 채널별 int8 로 줄임 | BSD 2-Clause |
| `upscale_x4.onnx` | 4배 확대 (입력 RGB 0~1 1×3×H×W / 출력 1×3×4H×4W) | [xinntao/Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) `realesr-general-x4v3` (SRVGGNetCompact) | BSD 3-Clause |

SHA-256
- colorize.onnx `7322dac83f7c8e1a4a38d684b22bdf89cf23c76cbf79b71eaa773533ec96c545`
- upscale_x4.onnx `8272c563563a7dd005b47a94389ff2ab0dd89ffc96a50b0063f45f11ef040b7c`
