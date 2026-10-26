# VCXProj Filters Explorer for Rider

Rider has no concept of Visual Studio filters, which makes large C++ solutions hard to navigate. This plugin adds a
**Filters Explorer** tool window that shows solutions, projects and the virtual folders defined in
`*.vcxproj.filters`, and lets you edit them.

- Solutions are found at any depth and nested under the solution in a parent folder.
- Drag files and filters onto other filters; the `.filters` file is updated.
- New File, New Filter, Rename Filter and Remove Filter in the context menu.
- Select Opened File, with an option to follow the editor tab.
- Built to handle projects with thousands of files.

Requires Rider 2024.3 or newer.

## Install

Download `rider-vcxproj-filters-<version>.zip` from the
[latest release](../../releases/latest) (or from the artifacts of a run on the Actions tab), then in Rider open
**Settings → Plugins → ⚙ → Install Plugin from Disk...**, pick the zip (do not unzip it) and restart Rider.
See [wiki/Installation.md](wiki/Installation.md) for details.

## Build

```
./gradlew buildPlugin
```

The zip is written to `build/distributions`. Use `./gradlew runIde` to start a sandbox Rider with the plugin.
Requires JDK 21 or newer; the first build downloads the Rider SDK.
