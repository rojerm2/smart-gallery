"""Generate the test-only public-domain NASA/blank video; no private media is used."""
import argparse
import subprocess
from pathlib import Path
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument("--ffmpeg", required=True)
p.add_argument("--astronaut", default="app/src/androidTest/assets/astronaut.png")
p.add_argument("--output", default="app/src/androidTest/assets/later-appearance.mp4")
args = p.parse_args()
image = Image.open(args.astronaut).convert("RGB").resize((512, 512))
blank = Image.new("RGB", (512, 512), (40, 60, 80))
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
command = [args.ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "512x512",
           "-r", "2", "-i", "-", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p",
           "-preset", "fast", "-crf", "24", "-g", "2", "-movflags", "+faststart", str(output)]
with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE) as process:
    for index in range(24):
        process.stdin.write((image if 8 <= index < 20 else blank).tobytes())
    process.stdin.close()
    error = process.stderr.read()
    if process.wait() != 0:
        raise RuntimeError(error.decode("utf-8", errors="replace"))
print(f"Wrote {output} ({output.stat().st_size} bytes); face appears at 4–10 seconds.")
