LamMailBox lets admins deliver items and rich-text messages to players who don’t need to be online. It’s a lightweight mailbox system for Paper/Folia 1.20+ that works from commands or plugin triggers.

## Key Features

* **Offline delivery**: Send mail and item attachments to any player, anytime. Items stay server-side until claimed.
* **Command-friendly**: Use `/lmb send` directly or call it from other plugins to automate rewards, gifts, or event drops.
* **Simple GUI**: Players browse, read, and claim mail through an intuitive interface with inventory checks on pickup.
* **Rich messages**: Supports color codes and `\n` line breaks.
* **Admin tools**: Optional console commands execute when mail is claimed. Schedule or expire mail using `YYYY:MM:DD:HH:mm`.
* **Bulk targets**: `player1;player2`, `allonline` (current online players), or `all` (remains until everyone claims).
* **Flexible storage**: Choose between YAML or SQLite backends. SQLite recommended for high-volume servers (1000+ mails).
* **Notifications**: Chat, title, and sound alerts for new mail and join reminders.
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
| `/lmb mailings`                | `lammailbox.admin`       | Show scheduled mailings with status |
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
2. Edit `plugins/LamMailBox/config.yml` to customize GUI text, slots, notification settings, and the mailings output templates under `messages.mailings`. Set `enabled: false` on any button entry to remove it from the interface. Decoration fillers can also run console commands via the `commands` list, with `%player%` and `%uuid%` placeholders.
3. Grant the permissions that fit your ranks.
4. Compose mail through the GUI or use `/lmb send` (or another plugin trigger) for automated deliveries.

## Default `mailings.yml`

<details>
<summary>Show default <code>mailings.yml</code></summary>

```yaml
mailings:
  # === COMMON FIELDS ===
  # enabled: true/false toggle for the mailing.
  # type: FIRST_JOIN | REPEATING
  #   FIRST_JOIN  -> fires when a player joins for the first time (per UUID).
  #   REPEATING   -> follows the cron schedule defined under schedule.cron.
  # message: The text stored in the mail body (supports color codes like &a).
  # sender: Name shown as the mail sender (defaults to "Console" if omitted).
  # receiver: Target spec (player name, semicolon list, "all", etc.). Defaults to "all".
  # required-permission: Optional permission node players must have to receive the mail.
  # expire-days: Optional integer; how many days until the mail auto-expires (null = config default).
  # items: List of item directives converted directly into ItemStacks (supports namespace ids).
  # commands: Console commands executed when the mail is claimed (supports %player% placeholder).
  # schedule:
  #   For FIRST_JOIN:
  #     delay-minutes / delay-seconds (optional) -> wait before delivering after first join.
  #   For CRON:
  #     cron: UNIX cron expression "m h dom mon dow" (required).
  #     max-runs: Optional positive integer to limit total executions (1 = run once).

  # --- Example 1: New player welcome mail ---
  welcome-new-players:
    enabled: false
    type: FIRST_JOIN
    message: "&aWelcome to the server!"
    sender: "Server"
    expire-days: 7
    items:
      - "minecraft:cookie 16"  # Give 16 cookies when claiming the mail.
    commands:
      - "title %player% subtitle {\"text\":\"Check /mailbox for a welcome gift!\",\"color\":\"gold\"}"
    schedule:
      delay-seconds: 10        # Wait 10 seconds after first join before sending.

  # --- Example 2: Daily reward for everyone at 18:00 server time ---
  daily-reward:
    enabled: false
    type: REPEATING
    receiver: "all"
    message: "&eYour daily reward is waiting!"
    sender: "Server"
    expire-days: 3
    items:
      - "minecraft:diamond 1"
    commands: []
    schedule:
      cron: "0 18 * * *"       # Minute Hour DOM Month DOW -> 18:00 every day.
      # max-runs omitted -> repeats forever.

  # --- Example 3: Single launch announcement, runs once on Jan 1 at 12:00 ---
  launch-announcement:
    enabled: false
    type: REPEATING
    receiver: "all"
    message: "&6Server launch celebration!"
    sender: "Server"
    expire-days: 14
    items:
      - "minecraft:emerald 3"
    commands:
      - "broadcast Server is live!"
    schedule:
      cron: "0 12 1 1 *"       # Noon on January 1.
      max-runs: 1              # Limit to a single execution.
```

</details>

## Requirements

* Paper or Folia 1.20+
* Java 21 runtime
* [YskLib](https://github.com/YusakiDev/YskLib/releases) 1.6.0 or above

## Support

* Issues: [GitHub](https://github.com/LamaliaNetwork/LamMailBox/issues)
* Discord: [YusakiDev](https://discord.gg/AjEh3dMPfq)

Deliver mail, gifts, and rewards while players are offline—no clunky chest exchanges needed.
