# Deferred ideas

These are ideas deferred from the Daniel-OS01 fork's improvement plan. They are not committed work.

## Live partial text for imported streaming models

Parked. It is low value on clips (the zipformer runs at RTF ~0.02) and is folded into the dictation work's streaming slice.

## Two-pass gate: a stale built-in saved path counts as installed

If a built-in model's folder is deleted but its saved path remains, the two-pass gate still treats it as installed. With Parakeet stale and one Whisper import, Settings can offer "Choose a preview model" and unlock two-pass, and the refine pass then fails to load. The gate predates R2. Fix: probe the disk, with the saved path as fallback, for catalog backends in `SettingsViewModel`'s accurate-side check. Deferred from R2 review: it changes behaviour for users with only built-in models.

## Automatic built-in preview choice ignores saved paths

`SherpaModelManager.installedFastEntryId` probes only the standard catalog folders. A built-in model installed only at a saved path works when pinned as the preview model, but the automatic choice does not find it. Deferred from R2.

## Per-source model choice

Shipped in R5. WhatsApp and WhatsApp Business each get an optional model pin, resolved through `TranscriptionProfileResolver`; a pin means a single pass. Per-folder/subfolder/sender rules and a model-per-language map follow in R6.

## Phrase-by-phrase dictation from com-master/anti-vocale-rt

Parked by the user. If revived: 4 slices; notification id 1007 collides with `MemoryKillStartupCheck`; the remote backend is barred; a Play foreground-service (microphone) declaration is needed.

## Tasker `file_path` input and scoped-storage automation

Dropped. It widens an exported receiver to any shared-storage path, and its "newest signal-*.aac" fallback can transcribe the wrong note. Folder watch covers the need.

## Report the effective NNAPI/CPU provider (plan item 5.5)

Only the requested-provider label shipped (R5): logs and failure context say `requested_provider`, and serialized keys are unchanged. Detecting the effective provider needs native work.

## Upstream fixes to RisorseArtificiali/anti-vocale

- The orphan-sweep fix.
- The Whisper token-cap fix (`max_tokens_per_second`).

Deferred: not posted yet, by decision.

## ja/zh translations

Skipped.

## Dum4G wake lock

Skipped.
