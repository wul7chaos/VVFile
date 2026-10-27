"""用本地 ComfyUI 的 Qwen-Image 2.1 生成 VVFile 文件类型图标。

统一风格由 STYLE 段落保证，每个图标只替换 SUBJECT。
生成结果落在 raw/<name>/<seed>.png，所有原图带 alpha 透明背景。
"""
import argparse
import json
import os
import shutil
import sys
import time
import urllib.request
import uuid

HOST = "http://127.0.0.1:8188"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(HERE, "raw")

UNET = "Qwen-Image-2.1-Turbo-AD-Q5_K.gguf"
CLIP = "qwen3vl_8b_int8_convrot.safetensors"
VAE = "qwen_image_2.1_vae_bf16.safetensors"
SIGMAS = "1.0, 0.978453, 0.95418, 0.926626, 0.89508, 0.845148, 0.704534, 0.414568, 0.0"

STYLE = (
    "This is an RGBA image with transparency. "
    "A flat file-type icon for a file manager, centered, filling about 76% of the frame. "
    "The icon is one portrait document page, its fill colour is {page}, with a solid black outline around it "
    "of even thickness, about 7 pixels wide, including around the small folded corner at its top right; "
    "the folded corner is a slightly darker shade of the same page colour. "
    "In the upper half of the page there is one large bold pictogram drawn with thick strokes, "
    "coloured {accent}, occupying about 40% of the page width: {glyph}. "
    "A large rounded rectangle plate overlaps the bottom left corner of the page, "
    "the plate is wider than half of the page and taller than a fifth of the page, "
    "it is filled {plate} and has the same solid black outline of the same thickness. "
    "The very bold black uppercase text \"{label}\" is printed on the plate in a heavy condensed sans-serif font, "
    "it fills most of the plate height, spelled exactly like that, centred on the plate. "
    "Strict icon-set style: identical page shape, identical outline weight, identical plate geometry in every icon, "
    "flat vector coloring, no gradient, no shadow, no reflection, no watermark, no frame, "
    "no other text or letters anywhere else in the image, "
    "crisp clean edges against a fully transparent canvas. "
)


def subject(page, accent, glyph, plate, label):
    return STYLE.format(page=page, accent=accent, glyph=glyph, plate=plate, label=label) \
        + " The object is isolated on a fully transparent background."


# name -> (中文名, 角标文字, 纸面底色, 主色, 纸上的图形)；folder 没有角标
ICONS = {
    "folder": ("文件夹", None, None, None, None),
    "image": ("图片", "JPG", "a very light mint green (#DFF7E6)", "a strong green (#1FA97B)",
              "a mountain with a round sun above its left slope"),
    "video": ("视频", "MP4", "a very light violet (#E9E4FF)", "a strong violet (#5B4BE0)",
              "a play triangle"),
    "audio": ("音频", "MP3", "a very light pink (#FFE3EA)", "a strong pink (#D6335C)",
              "a music note with a single flag"),
    "apk": ("安装包", "APK", "a very light teal (#D9FBF3)", "a strong teal (#0E9E94)",
              "an android robot head with two antennae and two eyes"),
    "archive": ("压缩包", "ZIP", "a very light purple (#F3E4FF)", "a strong purple (#8B4FD6)",
              "a vertical zipper with teeth on both sides"),
    "text": ("文本", "TXT", "a very light blue grey (#E4EAF3)", "a strong blue grey (#4A6B94)",
             "four thick horizontal text lines of different lengths"),
    "pdf": ("PDF", "PDF", "a very light red (#FFE1E1)", "a strong red (#D42B2B)",
            "a page shape with a bookmark ribbon hanging from its top edge"),
    "doc": ("Word 文档", "DOC", "a very light royal blue (#E1ECFF)", "a strong royal blue (#1E4FC4)",
            "a pen nib pointing down"),
    "sheet": ("表格", "XLS", "a very light green (#DCF6E4)", "a strong green (#12813F)",
              "a two by two table grid with thick lines"),
    "slides": ("演示文稿", "PPT", "a very light orange (#FFEAD5)", "a strong orange (#D06A12)",
               "a bar chart of three rising bars"),
    "code": ("源码", "SRC", "a very light cyan (#DBF9FF)", "a strong cyan (#0E8FA8)",
             "a pair of angle brackets with a slash between them"),
    "config": ("配置", "CFG", "a very light yellow (#FFF6C9)", "a dark amber (#A67C00)",
               "a pair of curly braces with a dot between them"),
    "script": ("脚本", "SH", "a very light lime (#EEFBD2)", "a strong olive green (#5E8A0F)",
               "a greater-than chevron followed by an underscore, like a terminal prompt"),
    "db": ("数据库", "DB", "a very light indigo (#E4E5FF)", "a strong indigo (#3F46C8)",
           "a stack of three database cylinders"),
    "font": ("字体", "TTF", "a very light brown (#F7EBDD)", "a dark brown (#8A5A32)",
             "a single capital letter T with small serifs"),
    "unknown": ("未知类型", "FILE", "a very light grey (#EDF1F6)", "a slate grey (#5A6B80)",
                "a large question mark"),
}
FOLDER_PROMPT = (
    "This is an RGBA image with transparency. "
    "A flat file-manager icon: a single closed file folder, centred, filling about 72% of the frame, "
    "warm amber (#FFC24B) fill surrounded by a solid black outline of the same thickness as a document file icon, "
    "a small tab on the top left, "
    "a clean seam where the front flap overlaps the back. "
    "Same line weight as the document icons, flat vector coloring, no gradient, no shadow, "
    "no label plate, no text, no letters, no watermark, no frame, "
    "crisp clean edges against a fully transparent canvas."
)

def prompt_for(name):
    if name == "folder":
        return FOLDER_PROMPT
    _, label, page, accent, glyph = ICONS[name]
    return subject(page, accent, glyph, accent, label)



def post(path, payload):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def get(path):
    with urllib.request.urlopen(HOST + path, timeout=60) as r:
        return json.loads(r.read())


def graph(name, seed, prompt_text):
    return {
        "2": {"class_type": "UnetLoaderGGUF", "inputs": {"unet_name": UNET}},
        "4": {"class_type": "CLIPLoader", "inputs": {"clip_name": CLIP, "type": "qwen_image", "device": "cpu"}},
        "5": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "6": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": prompt_text}},
        "8": {"class_type": "EmptyLatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "18": {"class_type": "TextEncodeQwenImage21",
               "inputs": {"clip": ["4", 0], "vae": ["5", 0], "prompt": ["6", 0],
                          "negative_prompt": "", "resolution": 1024}},
        "19": {"class_type": "BasicGuider", "inputs": {"model": ["2", 0], "conditioning": ["18", 0]}},
        "20": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "21": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "22": {"class_type": "ManualSigmas", "inputs": {"sigmas": SIGMAS}},
        "23": {"class_type": "SamplerCustomAdvanced",
               "inputs": {"noise": ["20", 0], "guider": ["19", 0], "sampler": ["21", 0],
                          "sigmas": ["22", 0], "latent_image": ["8", 0]}},
        "24": {"class_type": "VAEDecode", "inputs": {"samples": ["23", 0], "vae": ["5", 0]}},
        "25": {"class_type": "SaveImage", "inputs": {"images": ["24", 0], "filename_prefix": "vvfile_icons/" + name}},
    }


def run_one(name, text, seed):
    out_dir = os.path.join(RAW_DIR, name)
    os.makedirs(out_dir, exist_ok=True)
    # 已经生成过就跳过（重跑成本高）
    for f in os.listdir(out_dir):
        if f.startswith("%s_%06d" % (name, seed % 1_000_000)) and f.endswith(".png"):
            return "skip"
    res = post(HOST + "/prompt", {"prompt": graph(name, seed, text), "client_id": str(uuid.uuid4())})
    pid = res["prompt_id"]
    t0 = time.time()
    while True:
        time.sleep(1.5)
        h = get("/history/" + pid)
        if pid in h:
            break
        if time.time() - t0 > 900:
            raise RuntimeError("timeout " + name)
    entry = h[pid]
    if entry.get("status", {}).get("status_str") == "error":
        raise RuntimeError(json.dumps(entry["status"], ensure_ascii=False)[:800])
    files = []
    for node in entry["outputs"].values():
        for img in node.get("images", []):
            src = os.path.join(r"C:\Tools\ComfyUI\output", img["subfolder"], img["filename"])
            dst = os.path.join(out_dir, img["filename"])
            shutil.copy2(src, dst)
            files.append(dst)
    return "%ds %s" % (int(time.time() - t0), ",".join(os.path.basename(f) for f in files))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--seeds", type=int, default=1)
    ap.add_argument("--seed-base", type=int, default=730000)
    ap.add_argument("--raw-dir", default="raw")
    args = ap.parse_args()
    global RAW_DIR
    RAW_DIR = os.path.join(HERE, args.raw_dir)
    names = args.only or list(ICONS)
    for i, name in enumerate(names):
        cn, label, _, _, _ = ICONS[name]
        for k in range(args.seeds):
            seed = args.seed_base + i * 100 + k
            try:
                r = run_one(name, prompt_for(name), seed)
            except Exception as e:  # noqa
                print("FAIL", name, type(e).__name__, str(e)[:300], flush=True)
                continue
            print("%-8s %-6s %-5s %s" % (name, cn, label or "-", r), flush=True)


if __name__ == "__main__":
    main()
