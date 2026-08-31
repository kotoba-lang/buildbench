(ns buildbench.lanes
  "The toolchain lanes, and what each one has to prove before it is timed.

  A lane is not just a command. It is a command plus the check that the thing
  the command produced is the program we asked for. Without that second half
  a build-time benchmark measures how fast a compiler can be wrong: the
  fastest way to emit an artifact is to emit a broken one.

  Every lane therefore reports one of three things, and they are never the
  same value:

    :measured      — samples, and the artifact answered correctly
    :unavailable   — the tool is not on this host (never 0, never omitted)
    :invalid       — the tool ran and produced something that is not the
                     program; the error text is kept, not discarded"
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn which [cmd]
  (let [r (cp/spawnSync "which" #js [cmd] #js {:encoding "utf8"})]
    (when (zero? (.-status r)) (str/trim (.-stdout r)))))

(defn version-of [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8"})]
    (when r (first (str/split-lines (str/trim (str (.-stdout r) (.-stderr r))))))))

;; ── artifact validation ──────────────────────────────────────────────────

(defn validate-wasm
  "Instantiate the module and call its entry. Returns nil on success, or a
  string saying what went wrong — which is how the LEB128 function-index
  overflow in the released 0.7.3 emitter was found rather than timed."
  [file entry expected]
  (try
    (let [bytes (fs/readFileSync file)
          module (js/WebAssembly.Module. bytes)
          instance (js/WebAssembly.Instance. module #js {})
          f (aget (.-exports instance) entry)]
      (cond
        (nil? f) (str "module exports no " entry)
        :else (let [v (f)
                    n (js/Number v)]
                (when-not (= n expected)
                  (str entry "() returned " n ", expected " expected)))))
    (catch :default e (str "instantiate failed: " (.-message e)))))

(defn validate-kexe
  "A sealed Kotoba executable is an EDN header followed by machine code, not
  a Mach-O or ELF image, so it is checked structurally: the format marker has
  to be there and there has to be a payload behind it.

  This is a weaker check than the Wasm lanes get, which are instantiated and
  called. Saying so is the point — an unexecuted artifact must not be
  reported as if it had answered."
  [file _expected]
  (try
    (let [b (fs/readFileSync file)
          head (.toString (.subarray b 0 64) "utf8")]
      (cond
        (not (str/starts-with? head "{:format :kotoba.kexe/v1"))
        (str "not a sealed kexe: begins " (pr-str (subs head 0 (min 32 (count head)))))
        (< (.-length b) 512)
        (str "kexe is " (.-length b) " bytes: header with no meaningful payload")
        :else nil))
    (catch :default e (str "read failed: " (.-message e)))))

(defn validate-magic [file magic what]
  (try
    (let [b (fs/readFileSync file)]
      (if (< (.-length b) (count magic))
        (str what " is " (.-length b) " bytes, too short to be a " what)
        (let [head (map #(aget b %) (range (count magic)))]
          (when-not (= (vec head) (vec magic))
            (str what " has magic "
                 (str/join " " (map #(.toString % 16) head))
                 ", expected " (str/join " " (map #(.toString % 16) magic)))))))
    (catch :default e (str "read failed: " (.-message e)))))

(defn validate-jvm [dir expected]
  (or (validate-magic (path/join dir "Main.class") [0xCA 0xFE 0xBA 0xBE] "JVM class")
      (let [r (cp/spawnSync "java" #js ["-cp" dir "Main"] #js {:encoding "utf8"})]
        (when-not (zero? (.-status r))
          (str "java -cp " dir " Main exited " (.-status r) ": "
               (str/trim (str (.-stderr r))))))))

;; Mach-O 64-bit little-endian: cf fa ed fe
(def macho-magic [0xCF 0xFA 0xED 0xFE])
;; ELF
(def elf-magic [0x7F 0x45 0x4C 0x46])

(defn- native-magic []
  (if (= "darwin" (.-platform js/process)) macho-magic elf-magic))

(defn- native-target []
  (let [arch (.-arch js/process)
        os (.-platform js/process)
        os-suffix (case os "darwin" "macos" "linux" "linux" "win32" "windows" nil)]
    (when os-suffix
      (str (case arch "arm64" "aarch64" "x64" "x86_64" nil) "-" os-suffix))))

;; ── lane definitions ─────────────────────────────────────────────────────
;;
;; `amu` is a repository path, not a global binary, so it is passed in.

(defn lanes
  [{:keys [amu clang]}]
  (let [amu-bin (when (and amu (fs/existsSync (path/join amu "bin" "amu")))
                  (path/join amu "bin" "amu"))
        clang-bin (or clang (which "clang"))
        wasm-clang (or (when (fs/existsSync "/opt/homebrew/opt/llvm/bin/clang")
                         "/opt/homebrew/opt/llvm/bin/clang")
                       clang-bin)
        nt (native-target)]
    (->>
     [{:id :kotoba-wasm-cli
       :label "Kotoba CLI"
       :target "WebAssembly"
       :note "the released kotoba binary, the artifact a user installs today"
       :language :kotoba
       :tool (which "kotoba")
       :version-cmd ["kotoba" ["compile" "--help"]]
       :argv (fn [dir out] ["kotoba" ["compile" (path/join dir "main.kotoba")
                                      "--target" "wasm" "--output" out "--json"]])
       :output "cli.wasm"
       :validate (fn [out _dir expected] (validate-wasm out "main" expected))}

      {:id :amu-wasm
       :label "Amu"
       :target "WebAssembly"
       :note "the current compiler, process-cold, hosted on nbb"
       :language :kotoba
       :tool amu-bin
       :version-cmd (when amu-bin ["git" ["-C" amu "rev-parse" "--short" "HEAD"]])
       :argv (fn [dir out] [amu-bin ["compile" (path/join dir "main.kotoba")
                                     "--target" "wasm32" "--output" out]])
       :output "amu.wasm"
       :validate (fn [out _dir expected] (validate-wasm out "main" expected))}

      {:id :amu-native
       :label "Amu"
       :target (str "Native " (or nt "unsupported"))
       :note "AOT machine code, process-cold, hosted on nbb"
       :language :kotoba
       :tool (when nt amu-bin)
       :version-cmd (when amu-bin ["git" ["-C" amu "rev-parse" "--short" "HEAD"]])
       :argv (fn [dir out] [amu-bin ["compile" (path/join dir "main.kotoba")
                                     "--target" nt "--output" out]])
       :output "amu.kexe"
       :validate (fn [out _dir expected] (validate-kexe out expected))}

      {:id :rustc-wasm
       :label "Rust / rustc"
       :target "WebAssembly"
       :language :rust
       :tool (which "rustc")
       :version-cmd ["rustc" ["--version"]]
       :argv (fn [dir out] ["rustc" ["--edition=2024" "--crate-type=cdylib"
                                     "--target=wasm32-unknown-unknown"
                                     "-C" "opt-level=0" "-C" "debuginfo=0"
                                     "-C" "panic=abort"
                                     "-o" out (path/join dir "main.rs")]])
       :output "rust.wasm"
       :validate (fn [out _dir expected] (validate-wasm out "main" expected))}

      {:id :rustc-native
       :label "Rust / rustc"
       :target "Native host"
       :language :rust
       :tool (which "rustc")
       :version-cmd ["rustc" ["--version"]]
       :argv (fn [dir out] ["rustc" ["--edition=2024" "--crate-type=cdylib"
                                     "-C" "opt-level=0" "-C" "debuginfo=0"
                                     "-o" out (path/join dir "main.rs")]])
       :output (if (= "darwin" (.-platform js/process)) "librust.dylib" "librust.so")
       :validate (fn [out _dir _expected]
                   (validate-magic out (native-magic) "native image"))}

      {:id :clang-wasm
       :label "C / Clang"
       :target "WebAssembly"
       :language :c
       :tool wasm-clang
       :version-cmd (when wasm-clang [wasm-clang ["--version"]])
       :argv (fn [dir out] [wasm-clang ["--target=wasm32" "-O0" "-nostdlib"
                                        "-Wno-main-return-type"
                                        "-Wl,--no-entry" "-Wl,--export=main"
                                        "-o" out (path/join dir "main.c")]])
       :output "c.wasm"
       :validate (fn [out _dir expected] (validate-wasm out "main" expected))}

      {:id :clang-native
       :label "C / Clang"
       :target "Native host"
       :language :c
       :tool clang-bin
       :version-cmd (when clang-bin [clang-bin ["--version"]])
       :argv (fn [dir out] [clang-bin ["-O0" "-shared" "-Wno-main-return-type"
                                       "-o" out (path/join dir "main.c")]])
       :output (if (= "darwin" (.-platform js/process)) "libc.dylib" "libc.so")
       :validate (fn [out _dir _expected]
                   (validate-magic out (native-magic) "native image"))}

      {:id :javac
       :label "JVM / javac"
       :target "JVM class"
       :language :java
       :tool (which "javac")
       :version-cmd ["javac" ["-version"]]
       :argv (fn [dir out] ["javac" ["-g:none" "-d" out (path/join dir "Main.java")]])
       :output "jout"
       :output-dir? true
       :validate (fn [out _dir expected] (validate-jvm out expected))}]
     (map (fn [lane] (assoc lane :available? (some? (:tool lane)))))
     vec)))
