LamMailBox lets admins deliver items and rich-text messages to players who don’t need to be online. It’s a lightweight mailbox system for Paper/Folia 1.20+ that works from commands or plugin triggers.

**Latest release:** v1.5.3

## Key Features

* **Offline delivery**: Send mail and item attachments to any player, anytime. Items stay server-side until claimed.
* **Command-friendly**: Use `/lmb send` or trigger it from other plugins for instant rewards, gifts, or event drops.
* **Simple GUI**: Players browse, read, and claim mail through an intuitive interface with inventory checks on pickup.
* **Rich messages**: Supports colour codes, `\n` line breaks, and unicode icons.
* **Admin tools**: Attach console commands, schedule future deliveries, set expirations, or limit repeats via `max-runs`.
* **Bulk targets**: `player1;player2`, `allonline` (snapshot of current players), or `all` (persistent for everyone).
* **Automated mailings**: Cron-style repeating jobs and first-join bundles with catch-up when the server restarts.
* **Flexible storage**: Choose between YAML or SQLite backends (SQLite recommended beyond ~1000 mails).
* **Notifications**: Chat, title, and sound alerts for new mail plus join reminders.
* **Folia/Paper ready**: Uses bundled FoliaLib scheduler for smooth cross-platform timing.
* **Mailings dashboard**: `/lmb mailings` lists recurring jobs with status colors, run history, and human-readable schedules.

## Mailings Command

`/lmb mailings` shows every configured mailing job so you can audit schedules without opening configuration files.

### Output at a glance

- **Header** – configurable banner pulled from `messages.mailings.header`.
- **Status** – green when enabled, red when disabled; formatting comes from `messages.mailings.status-enabled` / `status-disabled`.
- **Schedule** – repeating mailings translate their cron expression to plain language (for example, "Every day at 02:00").
- **Next / Last run** – timestamps generated from stored run data; missing values fall back to `messages.mailings.value-missing`.
- **Runs** – whenever `max-runs` is set, the command displays the current count and highlights when the mailing has completed.
- **First-join mailings** – flagged with a simplified template that keeps output tidy.

### Customizing the layout

All strings live under `messages.mailings` in `config.yml`, so you can localize the header, list templates, and fallback text. Reload the plugin (`/lmbreload`) after editing to pick up changes instantly.

![main page](https://cdn.modrinth.com/data/cached_images/27a045c3d426870f8941d9d3ca1e7b0282d3a900_0.webp)
![mail creation page](https://cdn.modrinth.com/data/cached_images/8f6c3a33f10f14d70cdd1221b8c5c716a071d9fb_0.webp)

## Limitations & Roadmap

* No cross-server syncing or Bungee/Velocity support yet.

## Commands

| Command                        | Permission               | Description                      |
| ------------------------------ | ------------------------ | -------------------------------- |
| `/lmb`                         | `lammailbox.open`        | Open your mailbox                |
| `/lmb <player>`                | `lammailbox.open.others` | View another player's mailbox    |
| `/lmb view <id>`               | *(no permission)*        | View mail by ID (if you can access it) |
| `/lmb as <player>`             | `lammailbox.view.as`     | View mail UI as another player   |
| `/lmb send <player> <message>` | `lammailbox.admin`       | Send mail via command or console |
| `/lmb mailings`                | `lammailbox.admin`       | View cron/first-join automation dashboard |
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
2. Edit `plugins/LamMailBox/config.yml` to customize GUI text, slots, notification settings, and default expiry days. Set `enabled: false` on any button entry to remove it from the interface. Decoration fillers can run console commands via the `commands` list, with `%player%` and `%uuid%` placeholders.
3. Grant the permissions that fit your ranks.
4. Compose mail through the GUI or use `/lmb send`; set up recurring deliveries in `mailings.yml` for cron or first-join workflows (`/lmb mailings` shows status).

## Documentation

Need examples or deeper guidance? Check the wiki:

* **[Home](https://github.com/LamaliaNetwork/LamMailBox/wiki/Home)** – overview and quick start.
* **[Sending Mail](https://github.com/LamaliaNetwork/LamMailBox/wiki/Sending-Mail)** – GUI walkthrough, command syntax, bulk targets, attachments.
* **[Automated Mailings](https://github.com/LamaliaNetwork/LamMailBox/wiki/Automated-Mailings)** – cron tips, scenario library, catch-up behaviour, `max-runs`.
* **[Configuration](https://github.com/LamaliaNetwork/LamMailBox/wiki/Configuration)** – notifications, storage backends, permissions, GUI theming links.

## Requirements

* Paper or Folia 1.20+
* Java 21 runtime
* [YskLib](https://github.com/YusakiDev/YskLib/releases) 1.6.7 or above

## Support

* Issues: [GitHub](https://github.com/LamaliaNetwork/LamMailBox/issues)
* Discord: [YusakiDev](https://discord.gg/AjEh3dMPfq)

Deliver mail, gifts, and rewards while players are offline—no clunky chest exchanges needed.
