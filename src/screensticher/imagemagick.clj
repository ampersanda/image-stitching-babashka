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

(defn stitch-images!
  "Stitch images vertically, cropping overlap from each subsequent image.
   overlaps is a vector of pixel counts to crop from top of each image (starting from 2nd).
   Writes result to output-path."
  [image-paths overlaps output-path]
  (let [args (into ["magick" (str (first image-paths))]
                   (concat
                    (mapcat (fn [path overlap]
                              ["(" (str path)
                               "-crop" (str "0x0+0+" overlap)
                               "+repage" ")"])
                            (rest image-paths)
                            overlaps)
                    ["-append" (str output-path)]))]
    (apply p/shell {:out :string :err :string} args)))
