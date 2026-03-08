(ns screensticher.core
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [screensticher.imagemagick :as im]
            [screensticher.matcher :as matcher]))

(def cli-opts
  [["-o" "--output FILE" "Output file path"
    :default "stitched.png"]
   ["-d" "--dir DIR" "Read all images from directory"]
   ["-p" "--max-overlap PCT" "Max overlap to search (%)"
    :default 70
    :parse-fn parse-long]
   ["-s" "--scale FACTOR" "Downscale factor for matching"
    :default 4
    :parse-fn parse-long]
   ["-t" "--threshold NUM" "SAD threshold per pixel (0-255)"
    :default 10
    :parse-fn parse-long]
   ["-v" "--verbose" "Print progress info"
    :default false]
   ["-h" "--help" "Show help"]])

(def image-extensions
  #{"png" "jpg" "jpeg" "bmp" "tiff" "tif" "webp"})

(defn- image-file?
  [path]
  (let [ext (some-> (fs/extension path) str/lower-case)]
    (contains? image-extensions ext)))

(defn- natural-sort-key
  "Extract sort key for natural ordering (numeric parts sorted numerically)."
  [path]
  (let [name (str (fs/file-name path))]
    (mapv (fn [part]
            (if (re-matches #"\d+" part)
              (parse-long part)
              part))
          (re-seq #"\d+|[^\d]+" name))))

(defn- natural-sort
  [paths]
  (sort-by natural-sort-key
           (fn [a b]
             (let [pairs (map vector a b)]
               (or (first (keep (fn [[x y]]
                                  (cond
                                    (and (number? x) (number? y)) (when (not= x y) (compare x y))
                                    (and (string? x) (string? y)) (when (not= x y) (compare x y))
                                    (number? x) -1
                                    :else 1))
                                pairs))
                   (compare (count a) (count b)))))
           paths))

(defn- resolve-inputs
  "Resolve input image paths from CLI options and arguments."
  [{:keys [options arguments]}]
  (let [paths (if-let [dir (:dir options)]
                (let [dir-path (fs/path dir)]
                  (when-not (fs/directory? dir-path)
                    (binding [*out* *err*]
                      (println (str "Error: Not a directory: " dir)))
                    (System/exit 1))
                  (->> (fs/list-dir dir-path)
                       (filter image-file?)
                       (map str)))
                (map str arguments))]
    (natural-sort paths)))

(defn- validate-inputs!
  [image-paths]
  (when (< (count image-paths) 2)
    (binding [*out* *err*]
      (println (str "Error: Need at least 2 images to stitch. Got " (count image-paths) ".")))
    (System/exit 1))
  ;; Check all files exist
  (doseq [path image-paths]
    (when-not (fs/exists? path)
      (binding [*out* *err*]
        (println (str "Error: File not found: " path)))
      (System/exit 1)))
  ;; Check all are images and have same width
  (let [infos (mapv (fn [path]
                      (try
                        (assoc (im/identify path) :path path)
                        (catch Exception _
                          (binding [*out* *err*]
                            (println (str "Error: Cannot read image: " path)))
                          (System/exit 1))))
                    image-paths)
        widths (set (map :width infos))]
    (when (> (count widths) 1)
      (let [first-info (first infos)
            diff-info  (first (filter #(not= (:width %) (:width first-info)) infos))]
        (binding [*out* *err*]
          (println (str "Error: Width mismatch. "
                        (:path first-info) "=" (:width first-info) "px, "
                        (:path diff-info) "=" (:width diff-info) "px")))
        (System/exit 1)))
    infos))

(defn- print-usage
  [summary]
  (println "screensticher - Stitch screenshots vertically")
  (println)
  (println "Usage: bb stitch [options] image1 image2 [image3 ...]")
  (println "       bb stitch [options] -d <directory>")
  (println)
  (println "Options:")
  (println summary))

(defn -main
  [& args]
  (let [parsed  (cli/parse-opts args cli-opts)
        options (:options parsed)
        errors  (:errors parsed)]
    (when errors
      (binding [*out* *err*]
        (doseq [e errors] (println e)))
      (System/exit 1))
    (when (:help options)
      (print-usage (:summary parsed))
      (System/exit 0))

    (im/check-magick!)

    (let [image-paths (resolve-inputs parsed)
          verbose?    (:verbose options)
          _           (when verbose?
                        (println (str "Found " (count image-paths) " images")))
          infos       (validate-inputs! image-paths)
          match-opts  {:scale-factor    (:scale options)
                       :max-overlap-pct (:max-overlap options)
                       :threshold       (:threshold options)}
          n           (count image-paths)
          overlaps    (mapv (fn [i [top bottom]]
                             (when verbose?
                               (println (str "Matching " (inc i) "/" (dec n) ": "
                                             (fs/file-name top) " <-> " (fs/file-name bottom) "...")))
                             (let [result (matcher/find-overlap top bottom match-opts)]
                               (when verbose?
                                 (println (str "  overlap=" (:overlap-pixels result) "px"
                                               " (mean diff: " (format "%.1f" (:mean-diff result)) ")")))
                               result))
                           (range)
                           (partition 2 1 image-paths))
          output      (:output options)]
      ;; Check for failed matches
      (doseq [[i ov] (map-indexed vector overlaps)]
        (when (> (:mean-diff ov) 30)
          (binding [*out* *err*]
            (println (str "Error: No reliable overlap found between "
                          (fs/file-name (nth image-paths i)) " and "
                          (fs/file-name (nth image-paths (inc i)))
                          ". Images may not be sequential screenshots.")))
          (System/exit 1)))

      (im/stitch-images! image-paths overlaps output)

      (let [out-info (im/identify output)]
        (println (str "Stitched " n " images -> " output
                      " (" (:width out-info) "x" (:height out-info) ")")))
      (System/exit 0))))
