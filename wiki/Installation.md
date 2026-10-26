# Installing the plugin locally

The plugin is installed from a zip file; it is not on the JetBrains Marketplace.

## 1. Get the zip

- **Release:** open the repository's **Releases** page and download `rider-vcxproj-filters-<version>.zip`.
- **Latest build:** open the **Actions** tab, select the newest successful *Build* run on `main`, and download the
  `rider-vcxproj-filters` artifact. GitHub wraps it in another zip, so unpack once to get the plugin zip inside.

Do not unzip the plugin zip itself.

## 2. Install it in Rider

1. Open **Settings → Plugins**.
2. Click the **⚙** icon next to the *Installed* tab and choose **Install Plugin from Disk...**.
3. Select `rider-vcxproj-filters-<version>.zip`.
4. Restart Rider when asked.

## 3. Use it

Open your solution (or the top-level folder that contains your solutions). The **Filters Explorer** tool window
appears on the left edge. It scans the opened folder, so open a folder that contains all the solutions you need.

## Updating or removing

Install the new zip the same way to update. To remove the plugin, disable or uninstall **VCXProj Filters Explorer**
under **Settings → Plugins → Installed**.

## Notes

- Requires Rider 2024.3 or newer.
- Moving files and editing filters rewrites `.vcxproj.filters` (and `.vcxproj` when creating files). Use a
  version-controlled checkout and review the diff.
