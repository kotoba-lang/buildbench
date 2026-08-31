# kotoba-lang/buildbench

**How long does a build take when the code stops being a toy — and does the
artifact still answer?**

Every published build-time comparison this repository could find shares one
shape: a tiny source, a stopwatch, a table. That shape has two holes in it,
and this harness exists because both of them turned out to be load-bearing.

The first hole is **size**. A twenty-line program measures process startup.
It says nothing about the thing a developer actually waits for, which is a
compiler working through a real file. The interesting number is not the
intercept — it is the slope.

The second hole is **whether the artifact is the program**. The fastest way
to emit a build artifact is to emit a broken one. A benchmark that times a
compiler without executing what it produced is measuring how quickly a tool
can be wrong.

This harness closes both. It generates the same program at several sizes in
four languages, builds it through every toolchain on the host, and then —
after the clock has stopped — runs the artifact and checks the answer.

It found something on the first serious run. See
[What it found](#what-it-found-on-the-first-run).

## Running it

```sh
npm install -g nbb          # or: npx nbb
git clone https://github.com/kotoba-lang/perfgate ../perfgate
git clone https://github.com/kotoba-lang/machine  ../machine

nbb --classpath "src:../perfgate/src:../machine/src" bin/buildbench.cljs \
  --scales 1,32,128,129,512,2048 \
  --runs 7 \
  --budget-ms 180000 \
  --amu /path/to/kotoba-lang/amu \
  --output results/latest.json
```

Every lane whose tool is missing is reported `unavailable`. It is never
reported as zero, and never silently dropped — a toolchain that did not run
must not look like a toolchain that ran and was fast.

`--amu` is optional and points at a checkout of
[`kotoba-lang/amu`](https://github.com/kotoba-lang/amu), because the current
Kotoba compiler is a repository rather than a released binary. Without it the
two Amu lanes report `unavailable` and the rest still run.

### Exit codes are three-valued

| code | meaning |
|---:|---|
| `0` | every available lane built and its artifact answered correctly |
| `1` | a lane produced something that is not the program — details on stdout and in the JSON |
| `2` | the harness could not measure at all (no toolchain found) |

The third one matters. A run that could not answer must not exit like a run
that answered and found nothing wrong.

## The workload

At size `K` the generator emits `K` independent four-operation leaf
functions and one entry point that accumulates all `K` of them through a flat
sequence of bindings.

```clojure
(defn f7 [x :i64] :i64
  (let [a (+ x 7)
        b (- a 7)
        c (+ b 1)]
    (if (< c 0) (- 0 c) c)))
```

Three properties are deliberate:

- **Flat, not nested.** A deeply nested expression measures a parser's
  recursion limit, which is not what anyone means by build time.
- **Every leaf live.** The entry point calls all `K`, so no toolchain gets to
  skip codegen for dead code and post a faster number for less work.
- **Small integers everywhere.** Each leaf is `x + 1` by construction, so the
  values stay tiny and no language needs overflow semantics another language
  does not have. `main` returns exactly `K`, which is what each artifact is
  checked against.

Generated rather than hand-written, because hand-written "equivalent"
programs drift, and the drift lands in the timing as though it were a
property of the compiler.

## The lanes

| lane | language | target | how the artifact is checked |
|---|---|---|---|
| `kotoba-wasm-cli` | Kotoba | WebAssembly | instantiated, `main()` called, answer compared |
| `amu-wasm` | Kotoba | WebAssembly | instantiated, `main()` called, answer compared |
| `amu-native` | Kotoba | native `kexe` | **structural only** — sealed-container header and payload |
| `rustc-wasm` | Rust | WebAssembly | instantiated, `main()` called, answer compared |
| `rustc-native` | Rust | host dylib | Mach-O / ELF header |
| `clang-wasm` | C | WebAssembly | instantiated, `main()` called, answer compared |
| `clang-native` | C | host dylib | Mach-O / ELF header |
| `javac` | Java | JVM class | `CAFEBABE`, then `java Main` run and its exit status checked |

The native lanes get a weaker check than the Wasm lanes, and this table says
so rather than letting a reader assume otherwise. A structurally valid native
image that computes the wrong answer would pass `amu-native` and fail
`amu-wasm`; the two lanes are not equally strong evidence.

**The lanes are not equivalent.** Different targets, ABIs, optimisation
levels, and runtime contracts. `rustc-native` builds a dynamic library;
`javac` emits a class the JVM will later JIT; `amu-native` emits a sealed
container. Comparing their wall times is a legitimate question about
developer feedback latency. It is not a claim that they did the same work.

## Two qualifications, kept apart

Absolute milliseconds and the ordering between lanes are different claims
with different failure modes, so the report carries two separate verdicts.

**Absolute times** are gated on a quiet host (`load1 ≤ 1.0`). On a busy
machine the milliseconds are still true observations of that run, but they
are not portable to anyone else's hardware, and `absoluteTimes.qualified`
says which case you are reading.

**The ordering** is gated by
[`perfgate`](https://github.com/kotoba-lang/perfgate), which refuses a
ranking whose gap falls inside the arms' own spread — however large the ratio
looks. Because the lanes are interleaved on one host, a gap that survives
that test survives the host being busy: the load fell on both arms.

The policy is perfgate's own default, unrelaxed:

```clojure
{:policy/min-samples 5
 :policy/max-relative-stdev 0.10
 :policy/min-improvement 0.05
 :policy/require-provenance :measured
 :policy/require-baseline? true}
```

A threshold loosened to let this run's result through would be a benchmark
measuring its own thresholds. If a lane is too noisy to rank, the report says
`:too-noisy` and publishes the samples anyway.

`:require-provenance :measured` is why `src/buildbench/host.cljs` reads cache
line size, page size and core count off the machine with `sysctl` instead of
declaring a plausible Apple M4. A claim pinned to numbers nobody read is a
claim pinned to nothing, and perfgate refuses it.

## What it found on the first run

The released `kotoba` CLI (0.7.3) **emits an invalid WebAssembly module once a
module has more than 128 functions.**

```
K=128 → ok
K=129 → CompileError: function index #15872 is out of bounds
```

128 is where a LEB128 index stops fitting in one byte. The current compiler
in [`amu`](https://github.com/kotoba-lang/amu) does not have the bug; the
released binary does.

This is the entire argument for validating inside a benchmark harness. Timed
without the check, that lane would have produced a *faster* number at every
size above 128 — less work, no valid module — and the fastest column in the
table would have been the broken one.

## Layout

```
bin/buildbench.cljs        entry point
src/buildbench/workload.cljs   the generator, one program in four languages
src/buildbench/lanes.cljs      toolchains and what each artifact must prove
src/buildbench/measure.cljs    interleaving, budgets, validation-after-clock
src/buildbench/host.cljs       sysctl probe → a :measured machine descriptor
src/buildbench/report.cljs     the two qualifications, kept apart
results/                       published runs, one JSON per host
```

nbb (ClojureScript on Node) throughout, per this workspace's script-host rule.

## Reproducing, and disagreeing

`results/*.json` carries every sample, the machine descriptor and its
fingerprint, tool versions, the exact commands, and both qualification
verdicts. It is a record of one host on one day, not a portable ceiling.

Run it on your own machine. If your ordering differs from the published one,
that is a result — and the JSON contains enough to find out which of us was
measuring something else.

## Licence

MIT. See [LICENSE](LICENSE).
