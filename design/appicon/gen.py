"""VVFile 启动器图标：用本地 ComfyUI 的 Qwen-Image 2.1 出主体图形。

与文件类型图标同一套管线（同样的模型、同样的透明背景技巧），
只是提示词换成一个字标：紫色文件夹 + 白色 VV 字标。
"""
import argparse
import json
import os
import shutil
import time
import urllib.request
import uuid

HOST = "http://127.0.0.1:8188"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")

UNET = "Qwen-Image-2.1-Turbo-AD-Q5_K.gguf"
CLIP = "qwen3vl_8b_int8_convrot.safetensors"
VAE = "qwen_image_2.1_vae_bf16.safetensors"
SIGMAS = "1.0, 0.978453, 0.95418, 0.926626, 0.89508, 0.845148, 0.704534, 0.414568, 0.0"

BASE = (
    "This is an RGBA image with transparency. "
    "The app icon artwork of a file manager, drawn as one object centred in the frame, "
    "filling about 72% of the frame. "
)

FOLDER = (
    "A single closed file folder seen from a slight three-quarter angle, "
    "filled with vivid violet (#7C6CFF), rounded corners, a soft lighter band along its top edge, "
    "a slightly darker front flap with a clean seam, "
    "and the bold white uppercase monogram \"VV\" printed large and centred on the front flap, "
    "drawn as two wide geometric V letters with sharp corners. "
)

VARIANTS = {
    # 名字: 主体描述
    "vv": FOLDER,
    "chevron": (
        "A single closed file folder seen from a slight three-quarter angle, "
        "filled with vivid violet (#7C6CFF), rounded corners, a soft lighter band along its top edge, "
        "a slightly darker front flap with a clean seam, "
        "and two bold white chevrons pointing right, stacked one above the other, "
        "centred on the front flap, like a fast-forward symbol. "
    ),
    "panes": (
        "A single closed file folder seen from a slight three-quarter angle, "
        "filled with vivid violet (#7C6CFF), rounded corners, a soft lighter band along its top edge, "
        "and on the front flap two white rounded rectangles side by side, separated by a thin violet gap, "
        "like two file panes, with the right one slightly taller. "
    ),
    # 反色版：白文件夹 + 紫色双窗格，配紫色渐变背景用
    "inverse": (
        "A single closed file folder seen from a slight three-quarter angle, "
        "filled with pure white, rounded corners, a soft light grey band along its top edge, "
        "and on the front flap two vivid violet (#6C5BFF) rounded rectangles side by side, "
        "separated by a thin white gap, like two file panes, with the right one slightly taller. "
    ),
    # 白文件夹 + 紫色 VV 字标（内嵌深色字母，主题图标可挖空）
    "vv_white": (
        "A single closed file folder seen from a slight three-quarter angle, "
        "filled with pure white, rounded corners, a soft light grey band along its top edge, "
        "and on the front flap the two capital letters V and V side by side in near-black charcoal (#141821), "
        "very bold geometric sans-serif, with a clear wide gap between the two letters, "
        "each V is drawn with two straight diagonal strokes meeting at a sharp point. "
    ),    # 双窗格 + 右侧窗格里一条薄荷色高亮行，呼应应用里的选中色
    "panes_mint": (
        "A single closed file folder seen from a slight three-quarter angle, "
        "filled with vivid violet (#7C6CFF), rounded corners, a soft lighter band along its top edge, "
        "and on the front flap two rounded rectangles side by side, separated by a thin violet gap: "
        "the left one pure white and the right one pure white with a mint green (#34D399) bar across its lower half. "
    ),
}

TAIL = (
    "Flat vector style, crisp clean edges, no outline, no drop shadow, no reflection, "
    "no gradient across the background, no background plate, no watermark, "
    "no other elements, no text other than the described mark, "
    "the rest of the canvas is fully transparent."
)


def post(path, payload):
    req = urllib.request.Request(path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def get(path):
    with urllib.request.urlopen(HOST + path, timeout=60) as r:
        return json.loads(r.read())


def graph(name, seed, text):
    return {
        "2": {"class_type": "UnetLoaderGGUF", "inputs": {"unet_name": UNET}},
        "4": {"class_type": "CLIPLoader", "inputs": {"clip_name": CLIP, "type": "qwen_image", "device": "cpu"}},
        "5": {"class_type": "VAELoader", "inputs": {"vae_name": VAE}},
        "6": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": text}},
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
        "25": {"class_type": "SaveImage", "inputs": {"images": ["24", 0], "filename_prefix": "vvfile_appicon/" + name}},
    }


def run_one(name, text, seed):
    out = os.path.join(RAW, name)
    os.makedirs(out, exist_ok=True)
    if any(f.endswith(".png") and "_%06d" % (seed % 1_000_000) in f for f in os.listdir(out)):
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
        raise RuntimeError(json.dumps(entry["status"], ensure_ascii=False)[:600])
    names = []
    for node in entry["outputs"].values():
        for img in node.get("images", []):
            src = os.path.join(r"C:\Tools\ComfyUI\output", img["subfolder"], img["filename"])
            shutil.copy2(src, os.path.join(out, img["filename"]))
            names.append(img["filename"])
    return "%ds %s" % (int(time.time() - t0), ",".join(names))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--seeds", type=int, default=2)
    ap.add_argument("--seed-base", type=int, default=811000)
    args = ap.parse_args()
    for i, name in enumerate(args.only or list(VARIANTS)):
        for k in range(args.seeds):
            seed = args.seed_base + i * 100 + k
            try:
                print("%-8s %s" % (name, run_one(name, BASE + VARIANTS[name] + TAIL, seed)), flush=True)
            except Exception as e:  # noqa
                print("FAIL", name, type(e).__name__, str(e)[:300], flush=True)


if __name__ == "__main__":
    main()
