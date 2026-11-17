# shop-samples

The MVP's own evaluation corpus (copied from `capstone-mvp-1@2905635:eval/samples/`): 20 sample classes
plus the `Db.java` helper in `src/main/java/com/shop/app/`. Each sample is a vulnerable or safe
variant of one CWE. Ground truth is `secpipe-eval/src/main/resources/mvp/answers.csv`.
Do not build it on the host.

| CWE | Vulnerable (must be flagged) | Safe (must not be flagged) |
|---|---|---|
| 89 | `OrderSearch`, `ReportSort` | `InvoiceLookup`, `TagFilter` |
| 78 | `HostCheck`, `ScriptRunner` | `DiskUsage`, `ArchiveJob` |
| 22 | `AttachmentDownload`, `LogViewer` | `AvatarServe`, `ThemeLoader` |
| 328 | `PasswordHasher` | `FileChecksum` |
| 327 | `ConfigEncryptor` | `BackupEncryptor` |
| 798 | `MailClient` | `NewsletterClient` |
| 502 | `CartRestore` | `PrefsRestore` |

The MVP's no-AI result on this corpus (builtin + PMD, `eval/results/2026-11-03-noai.md`) is TPR 100%,
FPR 20% (false positives: `TagFilter`, `ArchiveJob`). WO-P.2 must reproduce it with the ported builtin adapter.
