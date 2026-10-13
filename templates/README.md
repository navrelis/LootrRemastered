# Templates

`branch/` holds the infra files that every version branch (`mc/<minecraft version>`) shares:

| File | Purpose |
|---|---|
| `branch/build-all.ps1` | builds every loader folder of the branch with its own Gradle wrapper |
| `branch/release.ps1` | builds, checks the version inside each jar, copies the jars to `<loader>/release/` |
| `branch/.gitattributes` | line endings: LF for `gradlew`, CRLF for `*.bat` and `*.ps1` |
| `branch/.github/workflows/build.yml` | CI: finds the loader folders and builds each one |

These are the master copies. A branch must not change them; change the copy here, then run

```powershell
pwsh scripts/sync-branch-infra.ps1 -DryRun     # list what differs in each versions/<minecraft version> worktree
pwsh scripts/sync-branch-infra.ps1             # copy the files onto every worktree
```

`sync-branch-infra.ps1` only copies files. It never commits; review the diff in each worktree and commit it there.
The files are generic on purpose: loader folders are found at run time (any top-level folder with a `gradlew.bat`),
and the values come from the branch's `gradle/shared.properties`.

The `.github` folder in here is only a master copy and is not a workflow of this repository.
