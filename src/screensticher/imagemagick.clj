(ns screensticher.imagemagick
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn check-magick!
  "Verify ImageMagick is installed. Throws on failure."
  []
  (try
    (p/shell {:out :string :err :string} "magick" "--version")
    (catch Exception _
      (binding [*out* *err*]
        (println "Error: ImageMagick (magick) not found. Install with: brew install imagemagick"))
      (System/exit 1))))

(defn identify
  "Get image dimensions and format. Returns {:width :height :format}."
  [path]
  (let [result (p/shell {:out :string :err :string}
                         "magick" "identify" "-format" "%w %h %m" (str path))
        parts  (str/split (str/trim (:out result)) #"\s+")]
    {:width  (parse-long (nth parts 0))
     :height (parse-long (nth parts 1))
     :format (nth parts 2)}))

(defn extract-grayscale-pixels
  "Extract downscaled grayscale pixels as a byte array.
   Returns {:bytes byte[] :width int :height int}."
  [path scale-factor]
  (let [info     (identify path)
        w        (quot (:width info) scale-factor)
        h        (quot (:height info) scale-factor)
        resize   (str w "x" h "!")
        result   (p/shell {:out :bytes :err :string}
                          "magick" (str path)
                          "-resize" resize
                          "-colorspace" "Gray"
                          "-depth" "8"
                          "gray:-")]
    {:bytes  (:out result)
     :width  w
     :height h}))

(defn- blend-pair!
  "Cut two images at the best seam with a thin gradient blend.
   seam is the row in the bottom image where we cut.
   Returns path to the blended temporary file."
  [top-path bottom-path overlap seam tmp-dir idx]
  (let [top-info (identify top-path)
        w        (:width top-info)
        top-h    (:height top-info)
        ;; Thin blend zone: 4px above and below the seam
        blend-r  (min 4 (quot seam 2))
        ;; Top image: keep up to (top-h - overlap + seam - blend-r)
        top-cut  (- (+ top-h seam) overlap blend-r)
        ;; Bottom image: start from (seam + blend-r)
        bot-cut  (+ seam blend-r)
        ;; Blend strip height
        blend-h  (* blend-r 2)
        ;; Top blend strip starts at top-cut in top image
        ;; Bottom blend strip starts at (seam - blend-r) in bottom image
        top-blend-y (max 0 top-cut)
        bot-blend-y (max 0 (- seam blend-r))
        out-path (str tmp-dir "/blend_" idx ".png")]
    (apply p/shell {:out :string :err :string}
           "magick"
           ;; Top section (above blend zone)
           "(" (str top-path) "-crop" (str w "x" top-blend-y "+0+0") "+repage" ")"
           ;; Blend zone
           "("
             "(" (str top-path) "-crop" (str w "x" blend-h "+0+" top-blend-y) "+repage" ")"
             "(" (str bottom-path) "-crop" (str w "x" blend-h "+0+" bot-blend-y) "+repage" ")"
             "(" "-size" (str w "x" blend-h) "gradient:black-white" ")"
             "-compose" "Over" "-composite"
           ")"
           ;; Bottom section (below blend zone)
           "(" (str bottom-path) "-crop" (str "0x0+0+" bot-cut) "+repage" ")"
           ["-append" out-path])
    out-path))

(defn stitch-images!
  "Stitch images vertically, cutting at optimal seam with thin blend.
   overlap-results is a vector of {:overlap-pixels :seam} maps.
   Writes result to output-path."
  [image-paths overlap-results output-path]
  (let [tmp-dir (str (doto (java.io.File/createTempFile "screensticher" "")
                       (.delete)
                       (.mkdirs)))]
    (try
      (let [result (reduce
                    (fn [{:keys [current-path]} [i [_ next-path]]]
                      (let [{:keys [overlap-pixels seam]} (nth overlap-results i)
                            blended (blend-pair! current-path next-path
                                                 overlap-pixels seam tmp-dir i)]
                        {:current-path blended}))
                    {:current-path (str (first image-paths))}
                    (map-indexed vector (partition 2 1 image-paths)))]
        ;; Copy final result to output
        (p/shell {:out :string :err :string}
                 "magick" (:current-path result) (str output-path)))
      (finally
        ;; Clean up temp files
        (doseq [f (.listFiles (java.io.File. tmp-dir))]
          (.delete f))
        (.delete (java.io.File. tmp-dir))))))
