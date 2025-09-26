LamMailBox lets admins deliver items and rich-text messages to players who don’t need to be online. It’s a lightweight mailbox system for Paper/Folia 1.20+ that works from commands or plugin triggers.

## Key Features

* **Offline delivery**: Send mail and item attachments to any player, anytime. Items stay server-side until claimed.
* **Command-friendly**: Use `/lmb send` directly or call it from other plugins to automate rewards, gifts, or event drops.
* **Simple GUI**: Players browse, read, and claim mail through an intuitive interface with inventory checks on pickup.
* **Rich messages**: Supports colour codes and `\n` line breaks.
* **Admin tools**: Optional console commands execute when mail is claimed. Schedule or expire mail using `YYYY:MM:DD:HH:mm`.
* **Bulk targets**: `player1;player2`, `allonline` (current online players), or `all` (remains until everyone claims).
* **Flexible storage**: Choose between YAML or SQLite backends. SQLite recommended for high-volume servers (1000+ mails).
* **Notifications**: Chat, title, and sound alerts for new mail and join reminders.
* **Folia/Paper ready**: Uses bundled FoliaLib scheduler for smooth cross-platform timing.

![main page](https://cdn.modrinth.com/data/cached_images/27a045c3d426870f8941d9d3ca1e7b0282d3a900_0.webp)
![mail creation page](https://cdn.modrinth.com/data/cached_images/8f6c3a33f10f14d70cdd1221b8c5c716a071d9fb_0.webp)

## Limitations & Roadmap


* No cross-server syncing or Bungee/Velocity support yet.
* `allonline` sends only to players online at send time.
* Mail files are plain text; staff can read or edit them.
* Command attachments always run as console commands—review for security.

## Commands

| Command                        | Permission               | Description                      |
| ------------------------------ | ------------------------ | -------------------------------- |
| `/lmb`                         | `lammailbox.open`        | Open your mailbox                |
| `/lmb <player>`                | `lammailbox.open.others` | View another player's mailbox    |
| `/lmb view <id>`               | *(no permission)*        | View mail by ID (if you can access it) |
| `/lmb as <player>`             | `lammailbox.view.as`     | View mail UI as another player   |
| `/lmb send <player> <message>` | `lammailbox.admin`       | Send mail via command or console |
| `/lmbreload`                   | `lammailbox.reload`      | Reload configuration files       |
| `/lmbmigrate <from> <to>`      | `lammailbox.migrate`     | Migrate mail between storage backends (yaml/sqlite) |

**Aliases:** `/mailbox`, `/mail`

## Additional Permissions

| Permission             | Description                                    |
| ---------------------- | ---------------------------------------------- |
| `lammailbox.compose`   | Create and send new mail through GUI          |
| `lammailbox.items`     | Add items to mail when composing              |
| `lammailbox.delete`    | Delete sent mail from sent mail view          |

## Setup

1. Drop the jar in `plugins/` and start the server to generate config/database files.
2. Edit `plugins/LamMailBox/config.yml` to customize GUI text, slots, and notification settings. Set `enabled: false` on any button entry to remove it from the interface. Decoration fillers (new in v1.3.0) can also run console commands via the `commands` list, with `%player%` and `%uuid%` placeholders.
3. Grant the permissions that fit your ranks.
4. Compose mail through the GUI or use `/lmb send` (or another plugin trigger) for automated deliveries.

## Requirements

* Paper or Folia 1.20+
* Java 21 runtime
* [YskLib](https://github.com/YusakiDev/YskLib/releases) 1.6.0 or above

## Support

* Issues: [GitHub](https://github.com/LamaliaNetwork/LamMailBox/issues)
* Discord: [YusakiDev](https://discord.gg/AjEh3dMPfq)

Deliver mail, gifts, and rewards while players are offline—no clunky chest exchanges needed.
