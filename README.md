# SDE Solver

An Android app for simulating stochastic differential equations, with live animated charts of
Brownian motion, random walks and other stochastic processes. An iPhone version will follow.

## Layout

| Folder | What it is |
| --- | --- |
| `shared/` | The maths engine in pure Kotlin (Kotlin Multiplatform), shared later with iOS. No Android code. |
| `androidApp/` | The Android app (Jetpack Compose). For now a preview screen with an on-device speed test. |
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
- **Analytics and Itô sums:** Φ(x), Black–Scholes prices, and running Itô and Stratonovich sums
  and quadratic variation along a Brownian path.

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
