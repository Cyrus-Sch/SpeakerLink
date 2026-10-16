`io.github.cyrus_sch.speakerlink.yml` is the F-Droid build recipe for SpeakerLink. It goes into
`metadata/` of a fork of [fdroiddata](https://gitlab.com/fdroid/fdroiddata); see
[../PUBLISHING.md](../PUBLISHING.md). If SpeakerLink moves to its own repository, change
`SourceCode`, `IssueTracker`, `Changelog`, `Repo` and `Binaries`, set `subdir: app`, and use the
tag pattern `Tags ^v\d+\.\d+\.\d+$` (with matching tags).
