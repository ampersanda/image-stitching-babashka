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

(defn find-overlap
  "Find vertical overlap between two consecutive screenshots.
   Returns {:overlap-pixels int :mean-diff float} or nil if no match."
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
        ;; Template: bottom K rows of top image
        k             (min template-rows (quot top-h 4))
        template-start (- top-h k)
        template       (let [arr (byte-array (* k w))]
                         (System/arraycopy (:bytes top) (* template-start w) arr 0 (* k w))
                         arr)
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
        overlap-ds     (+ (:best-y result) k)
        overlap-px     (* overlap-ds scale-factor)]
    (when (> mean-diff threshold)
      (binding [*out* *err*]
        (println (str "Warning: Low confidence match between "
                      top-path " and " bottom-path
                      " (mean diff: " (format "%.1f" mean-diff) ")"))))
    {:overlap-pixels overlap-px
     :mean-diff      mean-diff}))

(defn find-all-overlaps
  "Find overlaps for all consecutive pairs. Returns vector of overlap maps."
  [image-paths opts]
  (mapv (fn [[top bottom]]
          (find-overlap top bottom opts))
        (partition 2 1 image-paths)))
