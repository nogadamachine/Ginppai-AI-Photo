import json
import argparse
import importlib.util
import sysconfig
from pathlib import Path

import cv2
import numpy as np

def load_class(filename, classname):
    source = Path(sysconfig.get_paths()["purelib"]) / "imwatermark" / filename
    spec = importlib.util.spec_from_file_location(classname, source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return getattr(module, classname)

methods = {
    "dwtDct": load_class("maxDct.py", "EmbedMaxDct"),
    "dwtDctSvd": load_class("dwtDctSvd.py", "EmbedDwtDctSvd"),
}

arguments = argparse.ArgumentParser()
arguments.add_argument("--output", type=Path, default=Path("work/watermark-evaluation"))
arguments.add_argument("--android-output", type=Path)
options = arguments.parse_args()
root = options.output
root.mkdir(parents=True, exist_ok=True)
message = b"Ginppai!"
expected = np.unpackbits(np.frombuffer(message, dtype=np.uint8))
results = []

for seed in range(6):
    random = np.random.default_rng(seed + 1900)
    grid_y, grid_x = np.mgrid[0:512, 0:512]
    channels = []
    for channel in range(3):
        texture = cv2.GaussianBlur(random.normal(0, 28, (512, 512)), (0, 0), 0.65)
        texture += 128 + 55 * np.sin(grid_x / (15 + seed * 4 + channel * 7))
        texture += 28 * np.cos(grid_y / (23 + channel * 5))
        channels.append(np.clip(texture, 10, 245).astype(np.uint8))
    original = np.stack(channels, axis=2)
    cv2.circle(original, (180 + seed * 8, 240), 120, (90, 145, 205), 6)
    cv2.putText(original, f"LOCAL TEST {seed + 1}", (45, 390), cv2.FONT_HERSHEY_SIMPLEX, 1, (30, 50, 60), 2)
    for method in ["dwtDct", "dwtDctSvd"]:
        codec = methods[method](list(expected), wmLen=len(expected))
        marked = codec.encode(original)
        cv2.imwrite(str(root / f"sample-{seed}-{method}.png"), marked)
        variants = {"unmarked-control": original, "marked-original": marked}
        for scale in [0.9, 0.75, 0.5]:
            reduced = cv2.resize(marked, (round(512 * scale), round(512 * scale)), interpolation=cv2.INTER_LINEAR)
            restored = cv2.resize(reduced, (512, 512), interpolation=cv2.INTER_LINEAR)
            variants[f"resample-{scale}"] = restored
        for step in [8, 16, 32]:
            yuv = cv2.cvtColor(marked, cv2.COLOR_BGR2YUV)
            for channel in [1, 2]:
                yuv[:, :, channel] = np.clip(np.round(yuv[:, :, channel].astype(float) / step) * step, 0, 255).astype(np.uint8)
            variants[f"chroma-quantize-{step}"] = cv2.cvtColor(yuv, cv2.COLOR_YUV2BGR)
        for name, candidate in variants.items():
            detected = np.array(codec.decode(candidate), dtype=np.uint8)
            results.append({"seed": seed, "method": method, "variant": name,
                            "bit_agreement": float(np.mean(detected == expected)),
                            "psnr_to_marked": float(cv2.PSNR(candidate, marked))})

(root / "prototype-results.json").write_text(json.dumps(results, indent=2))
for method in ["dwtDct", "dwtDctSvd"]:
    for variant in ["unmarked-control", "marked-original", "resample-0.9", "resample-0.75", "resample-0.5", "chroma-quantize-8", "chroma-quantize-16", "chroma-quantize-32"]:
        selected = [row for row in results if row["method"] == method and row["variant"] == variant]
        print(method, variant, "bit agreement", round(np.mean([row["bit_agreement"] for row in selected]), 3),
              "PSNR", round(np.mean([row["psnr_to_marked"] for row in selected]), 2))

if options.android_output:
    android_results = []
    for seed in range(6):
        for method in ["dwtDct", "dwtDctSvd"]:
            filename = f"sample-{seed}-{method}.png"
            marked = cv2.imread(str(root / filename))
            processed = cv2.imread(str(options.android_output / filename))
            if processed is None:
                raise FileNotFoundError(options.android_output / filename)
            codec = methods[method](list(expected), wmLen=len(expected))
            before = np.array(codec.decode(marked), dtype=np.uint8)
            after = np.array(codec.decode(processed), dtype=np.uint8)
            android_results.append({
                "seed": seed, "method": method,
                "before_bit_agreement": float(np.mean(before == expected)),
                "after_bit_agreement": float(np.mean(after == expected)),
                "psnr_to_marked": float(cv2.PSNR(processed, marked))
            })
    (root / "android-results.json").write_text(json.dumps(android_results, indent=2))
    for method in methods:
        selected = [row for row in android_results if row["method"] == method]
        print("ANDROID", method,
              "before", round(np.mean([row["before_bit_agreement"] for row in selected]), 4),
              "after", round(np.mean([row["after_bit_agreement"] for row in selected]), 4),
              "PSNR", round(np.mean([row["psnr_to_marked"] for row in selected]), 2))
