#!/usr/bin/env python3
"""从 Lensfun XML 数据库提取镜头校正参数，生成紧凑 JSON 供 Android 打包。

用法：
  python tools/extract_lenses.py <lensfun_db_dir> <output.json>

只保留有畸变标定（ptlens/poly3）的镜头；每个镜头取一个焦距（focal 标定值最接近
50mm 的那组），畸变用原始系数（Android 端在 Hugin 坐标系直接用），暗角取最宽光圈。
"""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def focal_of(lens):
    """取镜头的标定焦距（畸变标定里的 focal，优先接近 50）。"""
    calib = lens.find("calibration")
    if calib is None:
        return None
    focals = []
    for d in calib.findall("distortion"):
        f = d.get("focal")
        if f:
            try:
                focals.append(float(f))
            except ValueError:
                pass
    if not focals:
        return None
    return min(focals, key=lambda f: abs(f - 50.0))


def extract(lens):
    calib = lens.find("calibration")
    if calib is None:
        return None
    focal = focal_of(lens)
    if focal is None:
        return None

    # 找该焦距下的畸变标定
    distortion = None
    for d in calib.findall("distortion"):
        if d.get("focal") and abs(float(d.get("focal")) - focal) < 1e-6:
            distortion = d
            break
    if distortion is None:
        return None

    model = distortion.get("model")
    if model == "ptlens":
        terms = [float(distortion.get("a", "0")), float(distortion.get("b", "0")),
                 float(distortion.get("c", "0"))]
    elif model == "poly3":
        terms = [float(distortion.get("k1", "0"))]
    else:
        return None  # 只支持 ptlens / poly3

    # 该焦距下的 TCA（vr/vb）
    tca = None
    for t in calib.findall("tca"):
        if t.get("focal") and abs(float(t.get("focal")) - focal) < 1e-6:
            tca = t
            break
    vr = float(tca.get("vr", "1")) if tca is not None else 1.0
    vb = float(tca.get("vb", "1")) if tca is not None else 1.0

    # 暗角：该焦距下最宽光圈（k 系数的绝对值最大那组）
    vig = None
    for v in calib.findall("vignetting"):
        if v.get("model") != "pa" or (v.get("focal") and abs(float(v.get("focal")) - focal) >= 1e-6):
            continue
        k = [float(v.get("k1", "0")), float(v.get("k2", "0")), float(v.get("k3", "0"))]
        strength = sum(abs(x) for x in k)
        if vig is None or strength > vig[1]:
            vig = (k, strength)
    vignetting = vig[0] if vig is not None else [0.0, 0.0, 0.0]

    maker = lens.findtext("maker", "").strip()
    model_name = lens.findtext("model", "").strip()
    mount = lens.findtext("mount", "").strip()
    crop = float(lens.findtext("cropfactor", "1"))
    return {
        "maker": maker,
        "model": model_name,
        "mount": mount,
        "crop": crop,
        "focal": focal,
        "dist_model": model,
        "dist_terms": terms,
        "tca_vr": vr,
        "tca_vb": vb,
        "vig_k": vignetting,
    }


def main():
    db_dir = Path(sys.argv[1])
    out_path = Path(sys.argv[2])
    profiles = []
    for xml in sorted(db_dir.glob("*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except Exception as e:
            print(f"跳过 {xml.name}: {e}", file=sys.stderr)
            continue
        for lens in root.findall("lens"):
            p = extract(lens)
            if p:
                profiles.append(p)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(profiles, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"提取 {len(profiles)} 个镜头 → {out_path}")


if __name__ == "__main__":
    main()
