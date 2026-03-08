# Image Stitching Algorithm Reference

## Sources
- **ScreenStitch App**: https://github.com/Krocified/ScreenStitch_Image-Stitcher
- **Medium Article**: https://medium.com/@Krocified/screenstitch-an-application-to-stitch-screenshots-a26d67ee5f45 (paywall)
- **Academic Paper**: "Automatic Panoramic Image Stitching using Invariant Features" by Matthew Brown and David G. Lowe (IJCV 2007) - http://matthewalunbrown.com/papers/ijcv2007.pdf (unavailable at time of writing)

## Core Pipeline (from OpenCV Stitcher & Brown/Lowe paper)

### 1. Feature Detection
- **SIFT** (Scale-Invariant Feature Transform): Detects keypoints invariant to scale, rotation, and illumination changes
- **ORB** (Oriented FAST and Rotated BRIEF): Faster alternative, patent-free
- For screenshots: features are typically UI elements, text edges, icons

### 2. Feature Description
- Each keypoint gets a descriptor vector (128-dim for SIFT, 32-dim for ORB)
- Descriptors encode local gradient information around the keypoint

### 3. Feature Matching
- **Brute-Force Matcher** or **FLANN** (Fast Library for Approximate Nearest Neighbors)
- **Lowe's Ratio Test**: For each feature, compare distance to best match vs second-best match. Keep only if ratio < 0.75 (reduces false matches)

### 4. Homography Estimation
- Given matched feature pairs, estimate 3x3 homography matrix H
- **RANSAC** (Random Sample Consensus): Robust estimation that handles outliers
  1. Randomly select 4 point pairs
  2. Compute homography from these 4 pairs
  3. Count inliers (matches consistent with this homography)
  4. Repeat N times, keep best homography

### 5. Image Warping
- Apply homography to warp one image to align with the other
- For screenshots (vertical scrolling), the transform is primarily a vertical translation

### 6. Seam Finding & Blending
- **Multi-band blending**: Blend images at different frequency bands for smooth transitions
- **Feather blending**: Simple alpha blending in the overlap region
- For screenshots: the overlap region should be nearly identical, so simple overlay works

## Screenshot-Specific Simplifications

Unlike general panoramic stitching, screenshot stitching has key constraints:
1. **Same width**: All screenshots share the same horizontal dimension
2. **Vertical-only scrolling**: The translation is purely vertical (no rotation, scaling, or perspective)
3. **No lens distortion**: Screenshots are pixel-perfect captures
4. **Overlapping regions are identical**: Pixel-for-pixel match (unlike photos with exposure differences)

### Simplified Algorithm for Screenshots
1. Convert images to grayscale
2. For consecutive image pairs (img_i, img_i+1):
   a. Take bottom strip of img_i (e.g., bottom 30-50% rows)
   b. Slide this strip along img_i+1 vertically (template matching)
   c. Find position with best match (normalized cross-correlation or sum of absolute differences)
   d. The offset = position of best match
3. Stitch: place img_i, then place img_i+1 starting from the offset
4. Repeat for all pairs
5. Crop any artifacts

### Template Matching (cv2.matchTemplate)
- `cv2.TM_CCOEFF_NORMED`: Normalized cross-correlation coefficient
- Returns a similarity map; find the peak location
- For screenshots, expect near-perfect correlation (>0.99) at the correct offset

## ScreenStitch Source Code Analysis

The reference implementation uses OpenCV's high-level `Stitcher` class:
```python
stitcher = cv2.Stitcher_create()
(status, stitched) = stitcher.stitch(images)
```

Status codes:
- 0: OK (success)
- 1: ERR_NEED_MORE_IMGS
- 2: ERR_HOMOGRAPHY_EST_FAIL (not enough keypoints)
- 3: ERR_CAMERA_PARAMS_ADJUST_FAIL

The implementation rotates images 90° before stitching (to convert vertical screenshots to horizontal panoramas, which OpenCV's stitcher handles better), then rotates back 270° after.
