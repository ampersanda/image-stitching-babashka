# screensticher

CLI tool to stitch sequential screenshots into a single tall image. Built with [Babashka](https://babashka.org/) (Clojure) and [ImageMagick](https://imagemagick.org/).

Unlike general panoramic stitching, this tool is optimized for screenshots: same width, vertical scrolling only, pixel-perfect captures. It uses template matching with Sum of Absolute Differences (SAD) to find overlapping regions between consecutive images, then assembles them into one seamless result.

## Install

The install script checks that all requirements are present before downloading.

```bash
curl -fsSL https://raw.githubusercontent.com/ampersanda/image-stitching-babashka/main/install.sh | bash
```

### Requirements

The following must be installed **before** running the install script:

- [Babashka](https://github.com/babashka/babashka) (bb)
- [ImageMagick](https://imagemagick.org/) (magick)

```bash
brew install borkdude/brew/babashka imagemagick
```

The script installs `screenstitch` to `~/.local/bin`. Make sure it is in your `PATH`:

```bash
export PATH="${HOME}/.local/bin:${PATH}"
```

### Update

Re-run the install command. It detects existing installs and updates in place:

```bash
curl -fsSL https://raw.githubusercontent.com/ampersanda/image-stitching-babashka/main/install.sh | bash
```

### Uninstall

```bash
rm ~/.local/bin/screenstitch
```

## Usage

If installed via the install script:

```bash
# Stitch specific files
screenstitch shot1.png shot2.png shot3.png -o result.png

# Stitch all images in a directory (natural sort order)
screenstitch -d screenshots/ -o result.png

# Verbose output
screenstitch -v -d screenshots/ -o result.png
```

Or run directly with Babashka:

```bash
bb stitch -- shot1.png shot2.png shot3.png -o result.png
bb stitch -- -d screenshots/ -o result.png
bb -m screensticher.core shot1.png shot2.png -o result.png
```

## Options

| Flag | Description | Default |
|---|---|---|
| `-o, --output` | Output file path | `stitched.png` |
| `-d, --dir` | Read all images from directory | |
| `-p, --max-overlap` | Max overlap to search (%) | 70 |
| `-s, --scale` | Downscale factor for matching | 4 |
| `-t, --threshold` | SAD threshold per pixel (0-255) | 10 |
| `-v, --verbose` | Print progress info | |
| `-V, --version` | Show version | |
| `-h, --help` | Show help | |

## How it works

1. Loads images and extracts downscaled grayscale pixels via ImageMagick
2. Takes a thin strip (16 rows) from the bottom of each image as a template
3. Slides the template over the top region of the next image, computing SAD with early termination
4. Finds the overlap offset where pixels match best
5. Assembles the final image with a single `magick -append` command, cropping overlaps

For typical 3-5 screenshot jobs, processing takes under 5 seconds.

## Project structure

```
bb.edn                        # Project config (no external deps)
screenstitch.bb               # Entry point for uberscript bundling
src/screensticher/
  core.clj                    # CLI parsing, orchestration, validation
  imagemagick.clj             # ImageMagick interop (identify, pixel extraction, stitch)
  matcher.clj                 # Template matching algorithm (pure Clojure)
```

## References

- [ScreenStitch](https://github.com/Krocified/ScreenStitch_Image-Stitcher) - Python/OpenCV reference implementation by Michael Jong and Albertus Heronius
- [ScreenStitch article](https://medium.com/@Krocified/screenstitch-an-application-to-stitch-screenshots-a26d67ee5f45) - Medium article explaining the approach
- "Automatic Panoramic Image Stitching using Invariant Features" by Matthew Brown and David G. Lowe (IJCV 2007) - academic foundation for feature-based image stitching

## License

MIT
