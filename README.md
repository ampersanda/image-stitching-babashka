# screensticher

CLI tool to stitch sequential screenshots into a single tall image. Built with [Babashka](https://babashka.org/) (Clojure) and [ImageMagick](https://imagemagick.org/).

Unlike general panoramic stitching, this tool is optimized for screenshots: same width, vertical scrolling only, pixel-perfect captures. It uses template matching with Sum of Absolute Differences (SAD) to find overlapping regions between consecutive images, then assembles them into one seamless result.

## Requirements

- [Babashka](https://babashka.org/) (v1.0+)
- [ImageMagick](https://imagemagick.org/) 7.x (`brew install imagemagick`)

## Usage

```bash
# Stitch specific files
bb stitch -- shot1.png shot2.png shot3.png -o result.png

# Stitch all images in a directory (natural sort order)
bb stitch -- -d screenshots/ -o result.png

# Verbose output
bb stitch -- -v -d screenshots/ -o result.png

# Direct invocation
bb -m screensticher.core shot1.png shot2.png -o result.png
```

## Options

```
-o, --output FILE      Output file path            [default: stitched.png]
-d, --dir DIR          Read all images from directory
-p, --max-overlap PCT  Max overlap to search (%)    [default: 70]
-s, --scale FACTOR     Downscale factor for matching [default: 4]
-t, --threshold NUM    SAD threshold per pixel       [default: 10]
-v, --verbose          Print progress info
-h, --help             Show help
```

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
bin/screenstitch               # Shell wrapper for PATH install
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
