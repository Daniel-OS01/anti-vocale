# Deferred ideas

These are ideas deferred from the Daniel-OS01 fork's improvement plan. They are not committed work.

## Live partial text for imported streaming models

The built-in Nemotron model streams live text. Imported streaming zipformers do not show partials yet. Deferred past the current plan.

## Two-pass gate: a stale built-in saved path counts as installed

If a built-in model's folder is deleted but its saved path remains, the two-pass gate still treats it as installed. With Parakeet stale and one Whisper import, Settings can offer "Choose a preview model" and unlock two-pass, and the refine pass then fails to load. The gate predates R2. Fix: probe the disk, with the saved path as fallback, for catalog backends in `SettingsViewModel`'s accurate-side check. Deferred from R2 review: it changes behaviour for users with only built-in models.

## Automatic built-in preview choice ignores saved paths

`SherpaModelManager.installedFastEntryId` probes only the standard catalog folders. A built-in model installed only at a saved path works when pinned as the preview model, but the automatic choice does not find it. Deferred from R2.

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
