# Deferred ideas

These are ideas deferred from the Daniel-OS01 fork's improvement plan. They are not committed work.

## Live partial text for imported streaming models

The built-in Nemotron model streams live text. Imported streaming zipformers do not show partials yet. Deferred past the current plan.

## Per-source model choice

Pick a model by input source, for example WhatsApp voice notes to a zipformer and files to Whisper. Deferred past the current plan.

## Phrase-by-phrase dictation from com-master/anti-vocale-rt

Candidate commits: `6e77b076`, `037a6ca0`, `18169024`, `b41139d9`. Deferred past the current plan.

## Tasker `file_path` input and scoped-storage automation

An idea from pikatchu2k3. Deferred over permission concerns.

## Report the effective NNAPI/CPU provider (plan item 5.5)

The app has no runtime fallback signal. `InferenceProvider.resolve` only resolves the preference. Reporting the real provider needs native work. Deferred: low value.

## Upstream fixes to RisorseArtificiali/anti-vocale

- The orphan-sweep fix.
- The Whisper token-cap fix (`max_tokens_per_second`).

Deferred: not posted yet, by decision.

## ja/zh translations

Skipped.

## Dum4G wake lock

Skipped.
