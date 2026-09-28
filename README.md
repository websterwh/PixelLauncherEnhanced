![HEAD](https://socialify.git.ci/Mahmud0808/PixelLauncherEnhanced/image?description=1&font=Jost&forks=1&issues=1&logo=https%3A%2F%2Fi.postimg.cc%2FmgfNTCbc%2Fpixel-launcher-enhanced.png&name=1&owner=1&pattern=Formal+Invitation&pulls=1&stargazers=1&theme=Auto)

<p align="center">
Pixel Launcher Enhanced is an Xposed module designed to unlock a variety of exciting features for your launcher. Even though the name suggests it's for Pixel Launcher, it also fully supports Launcher3. From customizing the look to adding more functionality, this module enhances your launcher experience with minimal effort.
</p>

> [!NOTE]
> **This is a fork** with folder-preview rework, working Import/Export, and
> fixes for the **Android 17 (SDK 37) / Pixel Launcher 907** refactor.
> Upstream: [Mahmud0808/PixelLauncherEnhanced](https://github.com/Mahmud0808/PixelLauncherEnhanced).
> The feature list below is upstream's and still broadly accurate.
>
> Hooked classes and fields move between launcher builds, and a hook that
> misses a renamed field fails **silently** — no crash, no log, the setting
> just quietly stops working. If something looks broken after a system update,
> check the old→new field map in [`CONTEXT.md` §4a](CONTEXT.md) before
> debugging the feature itself; [`AGENTS.md`](AGENTS.md) has build/test workflow.

<br>
<p align="center">
  <a href="https://github.com/Mahmud0808/PixelLauncherEnhanced/releases"><img src="https://img.shields.io/github/downloads/Mahmud0808/PixelLauncherEnhanced/total?color=%233DDC84&logo=android&logoColor=%23fff&style=for-the-badge" alt="Downloads"></a>
  <a href="https://github.com/Mahmud0808/PixelLauncherEnhanced"><img alt="Repo Size" src="https://img.shields.io/github/repo-size/Mahmud0808/PixelLauncherEnhanced?style=for-the-badge"></a>
  <a href="https://t.me/DrDsProjects"><img src="https://img.shields.io/badge/Telegram-5K+-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram"></a>
  <br><br>
  <a href="https://www.buymeacoffee.com/DrDisagree"><img src="https://github.com/Mahmud0808/Iconify/blob/beta/.github/resources/bmc-button.png" width="30%" alt="Buy me a coffee" /></a>
</p>

# 🌟 Feature List

> ✅ Fully supported
> ⚠️ Partially supported
> 🚫 Not supported

<details>
<summary>Icons</summary>

| Feature                    | Pixel Launcher | Launcher3 Launcher |
|----------------------------|:--------------:|:------------------:|
| Force themed icons         |       ✅        |         ✅          |
| Remove shortcut icon badge |       ✅        |         ✅          |
| Icon size                  |       ✅        |         ✅          |
| Text size                  |       ✅        |         ✅          |
| Custom themed icon color   |       ✅        |         ⚠️         |

</details>

<details>
<summary>Home screen</summary>

| Feature                 | Pixel Launcher | Launcher3 Launcher |
|-------------------------|:--------------:|:------------------:|
| Lock layout             |       ✅        |         ✅          |
| Double tap to sleep     |       ✅        |         ✅          |
| Wallpaper zooming       |       ✅        |         ✅          |
| Hide statusbar          |       ✅        |         ✅          |
| Hide top shadow         |       ✅        |         ✅          |
| Dark statusbar icon     |       ✅        |         ✅          |
| Dark page indicator     |       ✅        |         ✅          |
| Icon labels on desktop  |       ✅        |         ✅          |
| Homescreen columns      |       ✅        |         ✅          |
| Homescreen rows         |       ✅        |         ✅          |
| Hide At A Glance        |       ✅        |         ✅          |
| Hide desktop search bar |       ✅        |         ✅          |
| Search bar opacity      |       ✅        |         🚫         |
| Dock spacing            |       ✅        |         ✅          |

</details>

<details>
<summary>App drawer</summary>

| Feature                       | Pixel Launcher | Launcher3 Launcher |
|-------------------------------|:--------------:|:------------------:|
| Themed icons                  |       ✅        |         ✅          |
| Toggle app search bar         |       ✅        |         ✅          |
| Icon labels in app drawer     |       ✅        |         ✅          |
| Hide apps from app drawer     |       ✅        |         ✅          |
| Search hidden apps            |       ✅        |         ✅          |
| App drawer background opacity |       ✅        |         ✅          |
| App drawer columns            |       ✅        |         ✅          |
| Row height multiplier         |       ✅        |         ✅          |

</details>

<details>
<summary>Recents</summary>

| Feature                    | Pixel Launcher | Launcher3 Launcher |
|----------------------------|:--------------:|:------------------:|
| Clear all button           |       ✅        |         ✅          |
| Remove screenshot button   |       ✅        |         ✅          |
| Disable recents live tile  |       ✅        |         ✅          |
| Recents background opacity |       ✅        |         ✅          |
| Freeform mode gesture      |       ✅        |         ✅          |

</details>

<details>
<summary>Miscellaneous</summary>

| Feature                                  | Pixel Launcher | Launcher3 Launcher |
|------------------------------------------|:--------------:|:------------------:|
| Hide gesture indicator                   |       ✅        |         ✅          |
| Hide navigation bar space                |       ✅        |         ✅          |
| Show entry in launcher settings          |       ✅        |         ✅          |
| Prevent wallpaper dimming caused restart |       ✅        |         ✅          |
| Developer options                        |       ✅        |         🚫         |
| Restart                                  |       ✅        |         ✅          |

</details>

# 🛠 Requirements

- **Root access:** A few features require root to function properly.

- **Xposed Framework:** Make sure you have the Xposed framework installed.

# 🔧 Installation

1. Download and install [Pixel Launcher Enhanced APK](https://github.com/Mahmud0808/PixelLauncherEnhanced/releases).

2. Grant root access for Pixel Launcher Enhanced app.

3. Enable the module from Xposed manager application.

4. Force close your launcher.

5. Enjoy your new launcher features! 🎉

# 📝 Notes

This module is only compatible with devices using the following launcher packages:

- Pixel Launcher `(com.google.android.apps.nexuslauncher)`

- Launcher3 Launcher `(com.android.launcher3)`

Root access is a requirement for the following features:

- Double tap to sleep

- Restart launcher

# 🌍 Translations

Want to help translate Pixel Launcher Enhanced into your language? You can contribute translations on
our [Crowdin platform](https://crowdin.com/project/pixellauncher-enhanced).