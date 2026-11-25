# v862 - Measure-first instrumentation (no behaviour change)

- MessageBubbleCanvasView.onMeasure: wrapped in Trace section "Bubble#measure".
- MediaBubbleScrollBenchmark: added TraceSectionMetric Sum for "Bubble.record" and "Bubble#measure".

How to use (same device, same chat with 60+ messages incl. photos):
1. ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.callx.benchmark.MediaBubbleScrollBenchmark
2. Read frameDurationCpuMs P50/P90/P99 + Msg#bind / Bubble.record / Bubble#measure sums.
3. Open the .perfetto-trace in ui.perfetto.dev; slice count:
   select count(*) from slice where name='Bubble.record'
4. Fix only the section that is largest relative to the frame budget; compare before/after with scripts/compare_results.js.
