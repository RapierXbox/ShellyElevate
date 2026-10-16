"""Convert a ReDimNet2 speaker model to LiteRT for the wall display.

The graph takes 2 s of 16 kHz pcm in [-1, 1] and returns the raw 192 dim
embedding which the display l2 normalises. The released checkpoints use the
convolution mel frontend so the display needs no feature code of its own.

  docker build -t se-speaker-conv tools/speaker-models
  git clone https://github.com/PalabraAI/redimnet2 /tmp/redimnet2
  docker run --rm -v /tmp/redimnet2:/repo:ro -v "$PWD/out:/out" se-speaker-conv b2

The checkpoints are pickles so the conversion stays inside the container.
"""

import argparse
import hashlib
import math
import sys
import urllib.request

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

URL = "https://github.com/PalabraAI/redimnet2/releases/download/v1.0.0/{}-vox2-lm.pt"
CHECKPOINTS = {
    "b0": "4b5a8c00f813ab0d92fc81ea7c504475d8201bed608fb2c6aaba65be723e9c61",
    "b2": "0545a29679a87fe1c662d2bbd05e3b3fe0d1b392832729abaa135e4079a2f77a",
    "b3": "02d14efdc184573873b5c3f62bea847fcdb6919fbba19a8da7af7c1ba36b76a2",
}
WINDOW = 32000


def load(repo, name):
    path = f"/tmp/{name}.pt"
    urllib.request.urlretrieve(URL.format(name), path)
    with open(path, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != CHECKPOINTS[name]:
            raise SystemExit("checkpoint hash mismatch")

    sys.path.insert(0, repo)
    from redimnet2.redimnet2 import ReDimNet2Wrap

    state = torch.load(path, map_location="cpu", weights_only=False)
    if state["model_config"].get("feat_type") not in ("tf", "tf_mel"):
        raise SystemExit("only the convolution mel frontend converts")
    model = ReDimNet2Wrap(**state["model_config"])
    model.load_state_dict(state["state_dict"])
    # the exporter takes no string padding, in place relu corrupts the residuals and the
    # stft kernels are permuted numpy views that were now and then written out garbled
    for m in model.modules():
        for key, buf in list(m.named_buffers(recurse=False)):
            setattr(m, key, buf.contiguous().clone())
        if isinstance(m, nn.ReLU):
            m.inplace = False
        if isinstance(m, (nn.Conv1d, nn.Conv2d)) and m.padding == "same":
            if any(k % 2 == 0 for k in m.kernel_size):
                raise SystemExit("same padding with an even kernel")
            m.padding = tuple(d * (k - 1) // 2 for k, d in zip(m.kernel_size, m.dilation))
    return model.eval()


def speech_like(seed):
    """a gliding pulse train with noise so the parity check sees speech like spectra."""
    g = torch.Generator().manual_seed(seed)
    t = torch.arange(WINDOW) / 16000
    f0 = 110 + 40 * torch.sin(2 * math.pi * 0.7 * t)
    voiced = torch.sign(torch.sin(2 * math.pi * torch.cumsum(f0, 0) / 16000))
    x = 0.3 * voiced + 0.05 * torch.randn(WINDOW, generator=g)
    return (x * (0.5 + 0.5 * torch.sin(2 * math.pi * 3 * t)).abs()).unsqueeze(0)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("name", choices=sorted(CHECKPOINTS))
    p.add_argument("--repo", default="/repo", help="checkout of PalabraAI/redimnet2")
    p.add_argument("--out", default="/out")
    args = p.parse_args()

    import litert_torch
    from ai_edge_litert.interpreter import Interpreter

    model = load(args.repo, args.name)
    probes = [speech_like(seed) for seed in range(3)]
    with torch.no_grad():
        wants = [model(probe) for probe in probes]
    out = f"{args.out}/redimnet2_{args.name}.tflite"
    # a wrong graph is never shipped
    litert_torch.convert(model, (torch.zeros(1, WINDOW),)).export(out)
    lite = Interpreter(model_path=out)
    lite.allocate_tensors()
    for probe, want in zip(probes, wants):
        lite.set_tensor(lite.get_input_details()[0]["index"], probe.numpy())
        lite.invoke()
        got = torch.from_numpy(np.asarray(lite.get_tensor(lite.get_output_details()[0]["index"])))
        cos = F.cosine_similarity(want, got).item()
        if cos < 0.999:
            raise SystemExit(f"tflite does not match the reference model (cosine {cos:.4f})")

    with open(out, "rb") as f:
        data = f.read()
    print(f"{out} size={len(data)} sha256={hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
