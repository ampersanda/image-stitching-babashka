# Screensticher - Babashka CLI Implementation Plan

## Architecture

**Hybrid approach:**
- **Babashka (Clojure)** for CLI parsing, orchestration, and template matching algorithm
- **ImageMagick** (`magick` CLI) for image I/O: reading, pixel extraction, cropping, final assembly

Babashka's GraalVM native image strips `javax.imageio` / `java.awt`, so ImageMagick handles all image operations. Babashka pipes raw grayscale bytes from ImageMagick for the matching algorithm.

## Project Structure

```
screensticher/
  bb.edn                         # Babashka config: paths, tasks
  src/
    screensticher/
      core.clj                   # Entry point, CLI parsing, orchestration
      imagemagick.clj            # ImageMagick interop: identify, pixel extract, stitch
      matcher.clj                # Template matching algorithm (pure Clojure)
  bin/
    screenstitch                 # Executable shell wrapper
  references/
    screenstitch-source.py       # Reference Python implementation
    image-stitching-algorithm.md # Algorithm documentation
    implementation-plan.md       # This file
```

## CLI Interface

```
Usage: bb stitch [options] [image1 image2 ...]

Options:
  -o, --output FILE        Output file path           [default: stitched.png]
  -d, --dir DIR            Read all images from directory
  -p, --max-overlap PCT    Max overlap to search (%)   [default: 70]
  -s, --scale FACTOR       Downscale factor for matching [default: 4]
  -t, --threshold NUM      SAD threshold per pixel      [default: 10]
  -v, --verbose            Print progress info
  -h, --help               Show help
```

Invocation:
- `bb stitch -- img1.png img2.png img3.png`
- `bb stitch -- -d screenshots/ -o result.png`
- `bb -m screensticher.core img1.png img2.png`

## bb.edn

```clojure
{:paths ["src"]
 :tasks
 {stitch {:doc "Stitch screenshots vertically"
          :task (exec 'screensticher.core/-main)}}}
```

No external deps needed - uses only Babashka built-ins:
- `clojure.tools.cli` - CLI parsing
- `babashka.process` - shell out to ImageMagick
- `babashka.fs` - file operations

## Algorithm: Screenshot Template Matching

### Why simpler than general panoramic stitching:
1. Same width across all screenshots
2. Vertical-only scrolling (no rotation, scaling, perspective)
3. Pixel-perfect captures (no lens distortion)
4. Overlap regions are near-identical

### Steps for each consecutive pair (img_i, img_i+1):

**Step 1: Extract pixels**
- Pipe both images through ImageMagick to get downscaled grayscale bytes
- Command: `magick <path> -resize 25% -depth 8 gray:-`
- At 4x downscale: 1080x1920 -> 270x480 = 129,600 bytes

**Step 2: Template strip**
- Take the bottom K rows (K=16 at downscaled resolution) of the top image
- This is the "template" to search for in the bottom image

**Step 3: Sliding window SAD**
- Slide the template over the top portion of the bottom image (0 to max_overlap%)
- At each position y, compute Sum of Absolute Differences (SAD):
  ```
  For y in 0..max_overlap_rows:
    sad = 0
    For row in 0..K:
      For col in 0..W (step 2 for speed):
        sad += |template[row][col] - bottom[y + row][col]|
      If sad > best_sad_so_far: break  // early termination
    Track best_y with lowest sad
  ```

**Optimizations:**
1. 4x downscale = 16x fewer pixels
2. Sample every 2nd pixel = additional 2x speedup
3. Early termination when SAD exceeds current best
4. Thin template strip (16 rows vs full overlap)

**Step 4: Validate match**
- Compute mean absolute difference per pixel: `mean_diff = best_sad / (K * W_sampled)`
- If `mean_diff > threshold`: warning or error
- For pixel-perfect screenshots: expect mean_diff near 0

**Step 5: Compute overlap**
- `overlap_original = (best_y + K) * scale_factor`

### Final Stitching (ImageMagick)

Single command to assemble all images:
```
magick img1.png \
  ( img2.png -crop WxH+0+overlap1 +repage ) \
  ( img3.png -crop WxH+0+overlap2 +repage ) \
  -append output.png
```

## Module Details

### `src/screensticher/imagemagick.clj`
- `check-magick!` - verify ImageMagick installed
- `identify` - get `{:width :height :format}` via `magick identify`
- `extract-grayscale-pixels` - pipe image to raw grayscale byte array
- `stitch-images!` - final assembly via `-append`

### `src/screensticher/matcher.clj`
- `sad-block` - compute SAD between template and target region (inner loop)
- `find-best-match` - slide template, return best y-offset and SAD score
- `find-overlap` - high-level: two image paths -> `{:overlap-pixels :confidence}`
- `find-all-overlaps` - map over consecutive pairs

### `src/screensticher/core.clj`
- `cli-opts` - option spec vector
- `resolve-inputs` - resolve and naturally sort image paths
- `validate-inputs!` - check file existence, format, width consistency
- `-main` - orchestration entry point

## Execution Flow

```
1. Parse CLI args
2. check-magick!
3. Resolve inputs: glob dir or use positional args, natural sort
4. Validate: >= 2 images, all exist, all same width
5. For each pair (i, i+1):
   a. Extract downscaled grayscale pixels (ImageMagick pipe)
   b. Template matching to find overlap
   c. Record overlap in pixels
   d. Print: "Matched 2/5: overlap=847px (confidence: 99.8%)"
6. Execute single ImageMagick stitch command
7. Print: "Stitched 5 images -> result.png (1080x8234)"
```

## Error Handling

| Error | Message |
|-------|---------|
| No ImageMagick | "Error: ImageMagick not found. Install: brew install imagemagick" |
| < 2 images | "Error: Need at least 2 images. Got N." |
| File not found | "Error: File not found: <path>" |
| Bad image | "Error: Cannot read image: <path>" |
| Width mismatch | "Error: Width mismatch. <path1>=Wpx, <path2>=W2px" |
| No overlap | "Error: No overlap found between <i> and <i+1>" |
| Low confidence | "Warning: Low confidence match (mean diff: N)" |

## Edge Cases

1. **Status bar changes**: Clock/battery changes between captures. Skip top ~60px of bottom image if `--status-bar-height` option set.
2. **Floating elements**: Sticky headers/footers. Thin template from bottom mitigates this.
3. **JPEG artifacts**: SAD threshold (default 10) accommodates compression noise.
4. **Small overlap**: Works down to ~50px overlap; reduce scale factor if needed.
5. **Retina/HiDPI**: Downscale handles naturally.

## Performance Estimates

| Operation | Time |
|-----------|------|
| Pixel extraction (2 images, downscaled) | ~180ms |
| Template matching (270x480) | ~300ms |
| Per pair total | ~500ms |
| Final stitch (5 images) | ~1-2s |
| Typical 3-5 screenshot job | < 5 seconds |
