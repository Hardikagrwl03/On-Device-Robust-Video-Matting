import importlib

import torch
from torch import nn

# "original" traces RobustVideoMatting.model (unmodified upstream source).
# "gpu" traces RobustVideoMatting.model_gpu, a parallel copy edited to avoid
# ops the TFLite GPU delegate doesn't support (e.g. lraspp.py's
# AdaptiveAvgPool2d -> GATHER_ND swapped for a plain MEAN reduction).
SOURCE_PACKAGES = {
    "original": "RobustVideoMatting.model",
    "gpu": "RobustVideoMatting.model_gpu",
}


def _normalize_no_data_dependent_guard(tensor, mean, std, inplace: bool = False):
    """
    Drop-in replacement for torchvision.transforms.functional.normalize,
    monkeypatched onto the (unmodified) mobilenetv3 module rather than
    edited into it. The original does `if (std == 0).any(): raise
    ValueError(...)` as an input-validation safeguard; under torch.export
    tracing, converting that data-dependent bool to a Python `if` triggers
    GuardOnDataDependentSymNode, even though `std` here is always the
    constant, non-zero ImageNet std baked into mobilenetv3.py, so the check
    can never fire.
    """
    mean = torch.as_tensor(mean, dtype=tensor.dtype, device=tensor.device).view(-1, 1, 1)
    std = torch.as_tensor(std, dtype=tensor.dtype, device=tensor.device).view(-1, 1, 1)
    if not inplace:
        tensor = tensor.clone()
    return tensor.sub_(mean).div_(std)


class RVMWrapper(nn.Module):
    """
    Wraps MattingNetwork for edge export. The underlying model works in
    NCHW, [0, 1]-normalized float tensors; this wrapper accepts/returns the
    NHWC, [0, 255]-ranged tensors that on-device pipelines typically produce
    and consume, so the conversion lives inside the exported graph.
    """

    def __init__(
        self, variant: str, checkpoint: str, downsample_ratio: float, height: int, width: int, source: str = "original"
    ):
        super().__init__()
        if source not in SOURCE_PACKAGES:
            raise ValueError(f"Unknown source {source!r}; expected one of {list(SOURCE_PACKAGES)}")

        package = SOURCE_PACKAGES[source]
        mobilenetv3_module = importlib.import_module(f"{package}.mobilenetv3")
        mobilenetv3_module.normalize = _normalize_no_data_dependent_guard
        MattingNetwork = importlib.import_module(f"{package}.model").MattingNetwork

        self.model = MattingNetwork(variant).eval().to('cpu')
        self.model.load_state_dict(torch.load(checkpoint, map_location='cpu'))
        self.downsample_ratio = (
            downsample_ratio if downsample_ratio is not None else self.auto_downsample_ratio(height, width)
        )

    def forward(self, src, r1, r2, r3, r4):
        src = src.permute(0, 3, 1, 2) / 255.0  # [B, H, W, 3] 0-255 -> [B, 3, H, W] 0-1

        # 4D input hits MattingNetwork's forward_single_frame path throughout
        # (backbone/decoder branch on ndim==5 vs 4), so no time dimension is
        # needed here -- one frame per call matches on-device inference.
        fgr, pha, r1o, r2o, r3o, r4o = self.model(
            src, r1, r2, r3, r4,
            downsample_ratio=self.downsample_ratio
        )

        fgr = fgr.permute(0, 2, 3, 1) * 255.0  # [B, 3, H, W] 0-1 -> [B, H, W, 3] 0-255
        pha = pha.permute(0, 2, 3, 1)          # [B, 1, H, W] 0-1 -> [B, H, W, 1] 0-1

        return fgr, pha, r1o, r2o, r3o, r4o

    def auto_downsample_ratio(self, h, w):
        """
        Automatically find a downsample ratio so that the largest side of the resolution be 512px.
        """
        return min(512 / max(h, w), 1)

    def init_recurrent_state(self, src):
        """
        Zero-initialized r1-r4 for a [B, H, W, 3] 0-255 `src` at this
        wrapper's downsample_ratio. Shapes come from actually running the
        model with r1-r4=None (ConvGRU self-sizes its hidden state off the
        input it receives, see decoder.py) rather than re-deriving them from
        the encoder/decoder stride arithmetic: strides don't collapse to
        plain integer division of height/width at every resolution -- e.g.
        resnet50's last stage can round differently than a straight // 16 --
        so hand-computed shapes silently drift off by one at some sizes.
        """
        with torch.no_grad():
            nchw = src.permute(0, 3, 1, 2) / 255.0
            _, _, r1, r2, r3, r4 = self.model(
                nchw, None, None, None, None, downsample_ratio=self.downsample_ratio
            )
        return tuple(torch.zeros_like(r) for r in (r1, r2, r3, r4))
