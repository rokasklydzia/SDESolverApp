# SDE Solver

An Android app for simulating stochastic differential equations, with live animated charts of
Brownian motion, random walks and other stochastic processes. An iPhone version will follow.

## Layout

| Folder | What it is |
| --- | --- |
| `shared/` | The maths engine in pure Kotlin (Kotlin Multiplatform), shared later with iOS. No Android code. |
| `androidApp/` | The Android app (Jetpack Compose): live animated charts of every model, demos, and an on-device speed test. |
| `python/` | A numpy notebook that cross-checks the engine's results independently. |

## The engine

- **Random numbers:** xoshiro256++ seeded through SplitMix64, normals by Marsaglia's polar method.
  A seed reproduces a run exactly, on any number of CPU cores and on any platform.
- **Schemes:** Euler–Maruyama (strong order ½), Milstein (strong order 1) and exact transitions where
  a model has one.
- **Preset models:** Brownian motion, scaled random walk, geometric Brownian motion,
  Ornstein–Uhlenbeck / Vasicek, Cox–Ingersoll–Ross (full truncation), Brownian bridge, Heston and
  Merton jump diffusion. `sde.catalog.ModelCatalog` lists them with their parameters and LaTeX.
- **Your own SDE:** `CustomSde("kappa*(theta - x)", "sigma*sqrt(x)", parameters)` parses the
  formulas, differentiates σ symbolically for Milstein, and compiles both into fast closures.
  Implicit multiplication (`2x`, `0.3 sigma x`), `^` or `**`, and the usual functions all work.
- **Monte Carlo:** parallel over all cores with coroutines, per-time mean and variance, terminal
  quantiles, histograms, confidence intervals and antithetic variates.
- **Analytics and Itô sums:** Φ(x), Black–Scholes prices, and running left point (Itô), midpoint
  (Stratonovich) and right point (backward) sums, p-variation, Σ Y(ΔX)ⁿ and covariation along a path.
- **Itô's formula:** `sde.ito.ItoFormula` derives the dt and dB terms of f(t, X) symbolically, and
  `sde.ito.MartingaleCheck` decides whether f(t, X) + ∫g ds is a martingale, finds the g that makes
  it one, and estimates E[M(t)] over many paths. `sde.ito.CovariationSetup` gives ⟨f(t, X), g(t, Y)⟩
  for two SDEs driven by one Brownian path, and `sde.montecarlo.RunningEstimate` follows a Monte Carlo
  estimate as the paths come in.
- **Symbolic solver:** `sde.expr.Antiderivative` integrates the usual forms (powers, exp, sin, cos,
  polynomials times them by parts) and checks every answer by differentiating it back;
  `sde.ito.StochasticIntegral` and `sde.ito.LinearSde` build the closed forms on top of it.

## The app

- **Simulate:** pick a model, move its parameter sliders, and watch paths draw themselves with the
  mean, 25–75% and 5–95% bands and, beside the chart, the histogram of X(t) against the exact
  density where one exists. Pinch to zoom, drag to pan, double tap to reset. Play, pause, scrub,
  change the speed, or loop with fresh paths. Schemes can be switched for scalar models.
  *Your own SDE* takes typed drift and diffusion formulas, with a slider for every parameter name.
- **Labs:** the Itô calculus from the course, hands on. *Integrals* compares the left, mid and
  right point sums on one path as the mesh shrinks; *Variation* shows Σ|ΔX|ᵖ blowing up, settling
  on ⟨X⟩ or vanishing for p = 1, 2, 3; *Martingales* applies Itô's formula to a typed f(t, x) and
  checks E[M(t)] over 10,000 paths; *Covariation* compares Σ ΔU·ΔV with ∫σ_U σ_V ds and E⟨U, V⟩
  with a closed form; *Monte Carlo* shows plain and antithetic estimates of E[g(X(T))] converging
  to the exact value from the model's density. The course exercises are built in as examples.
- **Solver:** ∫h(t, X) dX in closed form for Itô, Stratonovich and backward integrals, found by
  running Itô's formula backwards with a symbolic antiderivative (∫B³dB = B⁴/4 − ∫1.5B² ds), with
  the Itô-isometry mean and variance and a check on simulated paths; and explicit solutions of
  linear SDEs dX = (aX + b) dt + (cX + d) dB, with mean and variance when the noise is additive.
- **Demos:** a scrolling white noise oscilloscope with its integral, and random walks turning into
  Brownian motion as the number of steps grows.
- **Engine:** the speed test and the list of models.

Charts are drawn by hand on a Compose `Canvas`; the engine side (`sde.live.Ensemble`,
`sde.chart.Viewport`, `sde.chart.Ticks`) is plain Kotlin with unit tests.

## Building

Open the folder in Android Studio and run `androidApp` on a phone or emulator.

From the command line:

```
./gradlew :shared:jvmTest                     # engine tests
./gradlew :shared:jvmTest -Psde.showOutput=true  # also print convergence orders and benchmark timings
./gradlew :androidApp:assembleDebug            # debug APK in androidApp/build/outputs/apk/debug
./gradlew -Psde.android=false :shared:jvmTest  # engine only, no Android SDK needed
```

Every pull request runs the tests and builds a debug APK on GitHub Actions; the APK is attached
to the run as an artifact you can install on a phone.

## What the tests check

- Simulated means and variances of every preset against their closed forms, within a few
  standard errors (fixed seeds, so the tests are deterministic).
- Strong convergence orders: about 0.5 for Euler–Maruyama and 1.0 for Milstein, measured on GBM
  against its exact solution driven by the same Brownian path.
- European call prices against Black–Scholes, with and without antithetic variates.
- ∫W dW: the Itô sum against ½W² − ½t, the Stratonovich sum against ½W², and Σ(ΔW)² → t.
- The formula parser, printer and symbolic derivatives (checked against finite differences).
- The labs against course exercises: tB − ∫B ds and e^{t/2}cos B are martingales, X³ with
  dX = X dt + X dB needs g = −6x³, Σ(ΔX)² → ∫4X² dt and Σ X(ΔX)³ → 0 for X = e^{2B−3t},
  ⟨B⁴, B²⟩ = ∫8B⁴ dt with E⟨B⁴, B²⟩ = 8t³, E⟨B³, Y⟩ = 3t²/2 for dY = cos²Y dt + dB, and
  ⟨sin X⟩ = ∫cos²X d⟨X⟩.
- The solver: antiderivatives, ∫B dB in all three senses, ∫B³ dB and ∫t dB with their leftover
  integrals, closed forms against Riemann sums on a fine path, Itô-isometry variances, and the
  OU mean and variance from the linear SDE solution.
