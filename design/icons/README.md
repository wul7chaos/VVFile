# 文件类型图标

列表行首那套图标不是 Material 矢量图标，而是本地用 **Qwen-Image 2.1** 生成的位图，
生成脚本、提示词和挑图记录都在这个目录里，换机器也能原样重跑。

风格照用户给的参考样例（第二种）：**白纸 + 黑描边 + 左下角彩色角标写类型**。
角标是这套图标的识别点——小到 24dp 也能一眼看出是 PDF 还是 ZIP。

## 链路

| 步骤 | 文件 | 说明 |
| --- | --- | --- |
| 生成 | [gen.py](gen.py) | 走 ComfyUI 的 `/prompt` API，模型在 `C:\Tools\ComfyUI` |
| 挑图 | `picks.json` | 每个图标选第几张（`raw/<名字>/` 里按文件名排序的下标） |
| 规整 | [build.py](build.py) | 裁 alpha 包围盒 → 统一 128×128 画布 → 写入 `app/src/main/res/drawable-nodpi/` |
| 对照表 | [preview_sheet.png](preview_sheet.png) | 深/浅底各一行，上排 96px、下排真实 24dp（×3 屏） |

```powershell
# 1) 先起 ComfyUI（127.0.0.1:8188）
# 2) 生成，每个图标 2 个候选，约 70 秒/张
C:\Tools\ComfyUI\.venv\Scripts\python.exe design\icons\gen.py --seeds 2
# 3) 挑图（改 picks.json）后规整进 res
C:\Tools\ComfyUI\.venv\Scripts\python.exe design\icons\build.py
```

## 模型与参数

```
unet   Qwen-Image-2.1-Turbo-AD-Q5_K.gguf        (UnetLoaderGGUF)
clip   qwen3vl_8b_int8_convrot.safetensors      (CLIPLoader, type=qwen_image, device=cpu)
vae    qwen_image_2.1_vae_bf16.safetensors
采样   euler + ManualSigmas 1.0 → 0（8 步，Turbo 蒸馏权重，不用 CFG、不用 LoRA）
尺寸   1024×1024
种子   781000 + 图标下标 × 100 + 候选序号
```

## 透明背景怎么来的

提示词第一句固定是 `This is an RGBA image with transparency.`，
Qwen-Image 2.1 会直接吐 4 通道图：透明区域 alpha=0，形状边缘有 1~4 的过渡像素。
生成后 `build.py` 把 `alpha<10` 归零、`alpha>250` 归到 255，再按包围盒裁切，
所以最终 PNG 边缘干净、没有半透明的灰边。

> 缩放到 128px 时走的是 **预乘 alpha**（`RGBa`），否则半透明边缘会把背景色混进来，
> 深色主题下会看到一圈脏边。

## 统一风格靠什么保证

同一段 `STYLE`（`gen.py` 顶部）逐字复用，只有类型文字、底下那行色值和图形描述是变量：

- 纸面竖版、右上角折角，底色是**该类别的浅色调**（不是全白，避免一列图标看着都一样）
- 纸的上半部分画一个**深色象形图**（山/音符/拉链/`</>`…），和角标同一个色系
- 左下角**大角标**：宽度过半、高度过五分之一，写类型缩写，字号占满角标，
  这样缩到 28dp 时字虽然糊，但色块形状仍然认得出
- 平涂、无渐变、无投影、无背景板
- 生成的图有时候带黑描边、有时候没有，`build.py` 会**统一补一圈黑描边**
  （1024 原图 18px，折到 128px 约 2px），保证整套线宽一致
- 图标占画面 76%，四周留白，规整时再统一裁到画布 95%

## 分类与角标

17 种，见 [FileKind.kt](../../app/src/main/java/com/vvfile/app/data/model/FileKind.kt)。
角标写的是**类型缩写**，不是文件真实扩展名（`.png` 和 `.jpg` 都是 `JPG`）：

| 图标 | 角标 | 覆盖 |
| --- | --- | --- |
| 文件夹 | 无 | 所有目录 |
| image | JPG | jpg/jpeg/png/gif/webp/bmp/heic/svg/avif/ico/tif… |
| video | MP4 | mp4/mkv/avi/mov/webm/3gp/flv/ts/wmv… |
| audio | MP3 | mp3/flac/wav/aac/ogg/m4a/opus/amr… |
| apk | APK | apk |
| archive | ZIP | zip/rar/7z/tar/gz/bz2/xz/zst/jar/iso… |
| text | TXT | txt/md/log/nfo/readme/rtf |
| pdf | PDF | pdf |
| doc | DOC | doc/docx/odt/wps/pages/epub |
| sheet | XLS | xls/xlsx/csv/ods/numbers |
| slides | PPT | ppt/pptx/odp/key |
| code | SRC | html/css/js/ts/kt/java/py/go/rs/c/cpp… |
| config | CFG | json/xml/yaml/yml/ini/conf/cfg/toml… |
| script | SH | sh/bash/zsh/bat/cmd/ps1 |
| db | DB | db/db3/sqlite/sql/mdb/realm |
| font | TTF | ttf/otf/ttc/woff/woff2 |
| unknown | FILE | 其余（无扩展名、乱扩展名） |

`image/webp/svg...` 这类能出缩略图的，加载出缩略图后缩略图会把图标顶掉；
缩略图没出来（格式不支持、没读权限、坏文件）就还是这张图标。
