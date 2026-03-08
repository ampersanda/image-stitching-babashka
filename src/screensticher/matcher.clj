(ns screensticher.matcher
  (:require [screensticher.imagemagick :as im]))

(defn- byte->unsigned
  "Convert a signed Java byte to unsigned int (0-255)."
  [b]
  (Byte/toUnsignedInt b))

(defn- sad-block
  "Compute SAD between template strip and a region of target starting at target-y.
   Both are flat byte arrays in row-major order with given width.
   Uses column step for speed. Returns early if SAD exceeds best-so-far."
  [^bytes template ^bytes target
   template-w template-h
   target-w target-y
   col-step best-so-far]
  (let [template-w (int template-w)
        template-h (int template-h)
        target-w   (int target-w)
        target-y   (int target-y)
        col-step   (int col-step)
        best       (long best-so-far)]
    (loop [row (int 0)
           sad (long 0)]
      (if (>= row template-h)
        sad
        (let [t-offset (int (* row template-w))
              b-offset (int (* (+ target-y row) target-w))
              row-sad  (loop [col (int 0)
                              rs  (long 0)]
                         (if (>= col template-w)
                           rs
                           (let [tv (byte->unsigned (aget template (+ t-offset col)))
                                 bv (byte->unsigned (aget target (+ b-offset col)))
                                 diff (Math/abs (- tv bv))]
                             (recur (+ col col-step) (+ rs diff)))))]
          (let [new-sad (+ sad row-sad)]
            (if (> new-sad best)
              new-sad ; early termination
              (recur (inc row) new-sad))))))))

(defn- refine-overlap
  "Refine coarse overlap at full resolution by searching a small window.
   Uses a strip from 20% above the bottom to avoid UI elements.
   Returns pixel-accurate overlap value."
  [top-path bottom-path coarse-overlap scale-factor]
  (let [search-range (* scale-factor 2)
        top-full     (im/extract-grayscale-pixels top-path 1)
        bot-full     (im/extract-grayscale-pixels bottom-path 1)
        top-h        (:height top-full)
        w            (:width top-full)
        bot-h        (:height bot-full)
        ;; Use a strip from ~20% above bottom, avoid UI elements
        k            (min 16 (quot top-h 8))
        strip-start  (- top-h (quot top-h 5) k)
        strip-start  (max 0 (min strip-start (- top-h k)))
        from-bottom  (- top-h strip-start)
        template     (let [arr (byte-array (* k w))]
                       (System/arraycopy (:bytes top-full) (* strip-start w) arr 0 (* k w))
                       arr)
        ;; In bottom image, the strip should be at y where: strip-start in top = y in bottom
        ;; overlap = from-bottom + y, so y = overlap - from-bottom
        center-y     (- coarse-overlap from-bottom)
        min-y        (max 0 (- center-y search-range))
        max-y        (min (- bot-h k) (+ center-y search-range))
        result       (loop [y    (int min-y)
                            best-y   (int min-y)
                            best-sad Long/MAX_VALUE]
                       (if (> y max-y)
                         {:best-y best-y :best-sad best-sad}
                         (let [sad (sad-block template (:bytes bot-full)
                                             w k w y 1 best-sad)]
                           (if (< sad best-sad)
                             (recur (inc y) y sad)
                             (recur (inc y) best-y best-sad)))))]
    (+ (:best-y result) from-bottom)))

(defn find-best-seam
  "Within the overlap region, find the row where the two images match best.
   Returns the seam position as pixels from top of the bottom image."
  [top-path bottom-path overlap]
  (let [top-full  (im/extract-grayscale-pixels top-path 1)
        bot-full  (im/extract-grayscale-pixels bottom-path 1)
        w         (:width top-full)
        top-h     (:height top-full)
        ;; Skip top/bottom 10% of overlap to avoid status bars and edges
        margin    (max 4 (quot overlap 10))
        start-y   margin
        end-y     (- overlap margin)
        ;; For each row in the overlap, compute row SAD
        result    (loop [y      (int start-y)
                         best-y (int start-y)
                         best-sad Long/MAX_VALUE]
                    (if (>= y end-y)
                      {:best-y best-y :best-sad best-sad}
                      (let [;; Row in top image: (top-h - overlap + y)
                            top-row (+ (- top-h overlap) y)
                            t-off   (* top-row w)
                            b-off   (* y w)
                            sad     (loop [col (int 0)
                                           s   (long 0)]
                                      (if (>= col w)
                                        s
                                        (let [tv (byte->unsigned (aget ^bytes (:bytes top-full) (+ t-off col)))
                                              bv (byte->unsigned (aget ^bytes (:bytes bot-full) (+ b-off col)))
                                              d  (Math/abs (- tv bv))]
                                          (recur (inc col) (+ s d)))))]
                        (if (< sad best-sad)
                          (recur (inc y) y sad)
                          (recur (inc y) best-y best-sad)))))]
    (:best-y result)))

(defn find-overlap
  "Find vertical overlap between two consecutive screenshots.
   Returns {:overlap-pixels int :mean-diff float :seam int} or nil if no match."
  [top-path bottom-path {:keys [scale-factor max-overlap-pct threshold template-rows]
                          :or   {scale-factor     4
                                 max-overlap-pct  70
                                 threshold        10
                                 template-rows    16}}]
  (let [top    (im/extract-grayscale-pixels top-path scale-factor)
        bottom (im/extract-grayscale-pixels bottom-path scale-factor)
        top-h  (:height top)
        top-w  (:width top)
        bot-h  (:height bottom)
        bot-w  (:width bottom)
        _      (when (not= top-w bot-w)
                 (throw (ex-info (str "Width mismatch: " top-path "=" (* top-w scale-factor)
                                      "px, " bottom-path "=" (* bot-w scale-factor) "px")
                                 {:top-w top-w :bot-w bot-w})))
        w             top-w
        ;; Template: strip from 60-80% height of top image
        ;; Avoids bottom UI (input bar, nav bar) and top UI (status bar)
        k             (min (max template-rows 32) (quot top-h 4))
        template-start (- top-h (quot top-h 5) k) ;; 20% from bottom, skip UI
        template-start (max 0 (min template-start (- top-h k)))
        template       (let [arr (byte-array (* k w))]
                         (System/arraycopy (:bytes top) (* template-start w) arr 0 (* k w))
                         arr)
        ;; Offset: template is not at the very bottom, so overlap = top-h - template-start - best_y_adjustment
        template-from-bottom (- top-h template-start)
        ;; Search range in bottom image
        max-search    (min (- bot-h k)
                           (quot (* bot-h max-overlap-pct) 100))
        col-step      2
        ;; Slide template over bottom image
        result        (loop [y    (int 0)
                             best-y   (int 0)
                             best-sad Long/MAX_VALUE]
                        (if (>= y max-search)
                          {:best-y best-y :best-sad best-sad}
                          (let [sad (sad-block template (:bytes bottom)
                                              w k w y col-step best-sad)]
                            (if (< sad best-sad)
                              (recur (inc y) y sad)
                              (recur (inc y) best-y best-sad)))))
        sampled-pixels (* k (quot w col-step))
        mean-diff      (if (pos? sampled-pixels)
                         (double (/ (:best-sad result) sampled-pixels))
                         255.0)
        ;; The template starts at template-start in the top image.
        ;; If found at best-y in bottom image, then row template-start in top = row best-y in bottom.
        ;; Overlap = top-h - template-start + best-y  (how many rows from top of bottom overlap with top image)
        overlap-ds     (+ (:best-y result) template-from-bottom)
        coarse-px      (* overlap-ds scale-factor)
        ;; Refine at full resolution if downscaled
        overlap-px     (if (> scale-factor 1)
                         (refine-overlap top-path bottom-path coarse-px scale-factor)
                         coarse-px)
        ;; Find best seam line within the overlap
        seam           (find-best-seam top-path bottom-path overlap-px)]
    (when (> mean-diff threshold)
      (binding [*out* *err*]
        (println (str "Warning: Low confidence match between "
                      top-path " and " bottom-path
                      " (mean diff: " (format "%.1f" mean-diff) ")"))))
    {:overlap-pixels overlap-px
     :seam           seam
     :mean-diff      mean-diff}))

(defn find-all-overlaps
  "Find overlaps for all consecutive pairs. Returns vector of overlap maps."
  [image-paths opts]
  (mapv (fn [[top bottom]]
          (find-overlap top bottom opts))
        (partition 2 1 image-paths)))
