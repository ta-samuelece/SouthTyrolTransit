# Release notes

One Markdown file per published version, named after its `versionName`: `0.2.0-preview.1.md`, `0.2.0.md`. Write it in the same pull request as the version bump, for users rather than developers. The app shows it in its "What's new" dialog (headings, bullets, **bold** and links are rendered); previews get a "Preview build" banner added automatically.

The Release workflow refuses to run when the file for the current `versionName` is missing or empty. See "Publishing a release" in the main `README.md`.
