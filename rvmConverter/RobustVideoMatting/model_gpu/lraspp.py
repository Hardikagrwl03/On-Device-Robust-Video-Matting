from torch import nn


class GlobalAvgPool2d(nn.Module):
    """
    Equivalent to nn.AdaptiveAvgPool2d(1) for the global-pool (output size 1)
    case used here. AdaptiveAvgPool2d's general implementation exports to a
    GATHER_ND op that the TFLite GPU delegate doesn't support; a plain
    spatial mean instead lowers to a MEAN reduction, which it does support.
    """

    def forward(self, x):
        return x.mean(dim=(-2, -1), keepdim=True)


class LRASPP(nn.Module):
    def __init__(self, in_channels, out_channels):
        super().__init__()
        self.aspp1 = nn.Sequential(
            nn.Conv2d(in_channels, out_channels, 1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.ReLU(True)
        )
        self.aspp2 = nn.Sequential(
            GlobalAvgPool2d(),
            nn.Conv2d(in_channels, out_channels, 1, bias=False),
            nn.Sigmoid()
        )
        
    def forward_single_frame(self, x):
        return self.aspp1(x) * self.aspp2(x)
    
    def forward_time_series(self, x):
        B, T = x.shape[:2]
        x = self.forward_single_frame(x.flatten(0, 1)).unflatten(0, (B, T))
        return x
    
    def forward(self, x):
        if x.ndim == 5:
            return self.forward_time_series(x)
        else:
            return self.forward_single_frame(x)