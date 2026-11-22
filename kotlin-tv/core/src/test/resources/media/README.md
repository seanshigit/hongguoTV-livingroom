# Playback resource fixtures

`red.mp4` and `blue.mp4` are generated one-second, 32 × 32 AVC videos.
They have the same file size and identical MP4 indexes, but different video
samples, so the regression checks cannot pass by comparing only the moov box
or content length. The tests append a valid `free` box to exercise reads beyond
the initial 256 KiB network range without storing large fixtures.

They were generated with FFmpeg:

```sh
ffmpeg -f lavfi -i color=c=red:s=32x32:r=2:d=1 -c:v libx264 -pix_fmt yuv420p -movflags +faststart -map_metadata -1 red.mp4
ffmpeg -f lavfi -i color=c=blue:s=32x32:r=2:d=1 -c:v libx264 -pix_fmt yuv420p -movflags +faststart -map_metadata -1 blue.mp4
```

No third-party video content is included.
