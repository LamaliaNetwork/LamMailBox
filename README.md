# 📬 LamMailBox

[![Version](https://img.shields.io/badge/version-1.2.1-blue.svg)](https://github.com/YusukiDev/SecureMailBox)
[![Java](https://img.shields.io/badge/java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spigot](https://img.shields.io/badge/spigot-1.20+-green.svg)](https://www.spigotmc.org/)
[![Folia](https://img.shields.io/badge/folia-supported-brightgreen.svg)](https://papermc.io/software/folia)

A secure mailbox system for Minecraft servers that allows players to send items, messages, and commands between each other. Built with modern Bukkit/Spigot API and Folia support.

## ✨ Features

- **Item Transfer**: Send items safely between players through GUI-based mailboxes
- **Rich Messaging**: Support for formatted messages with color codes and newlines
- **Command Attachments**: Attach commands that execute when mail is claimed (admin feature)
- **Scheduled Delivery**: Schedule mail for delivery at specific times
- **Mail Expiration**: Automatic cleanup of expired mail
- **Admin Tools**: View and manage other players' mailboxes
- **Notifications**: Chat, title, and sound notifications for new mail
- **Folia Compatible**: Full support for Paper's multi-threaded Folia server software
- **Config Management**: Automatic configuration updates with YskLib integration

## 📋 Requirements

- **Minecraft Server**: Spigot/Paper 1.20+
- **Java Version**: Java 21 or higher
- **Dependencies**:
  - FoliaLib (automatically shaded)
  - YskLib (for config management)

## 🚀 Installation

1. Download the latest release
2. Place the JAR file in your server's `plugins` folder
3. Restart your server
4. Configure the plugin by editing `plugins/LamMailBox/config.yml`

## 🎛️ Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/smb` | `lammailbox.open` | Open your mailbox |
| `/smb <player>` | `lammailbox.open.others` | Open another player's mailbox |
| `/smb view <mailId>` | `lammailbox.open` | View specific mail by ID |
| `/smb as <player>` | `lammailbox.view.as` | View mailbox as another player |
| `/smb send <player> <message>` | `lammailbox.admin` | Send mail via command (admin only) |
| `/smbreload` | `lammailbox.reload` | Reload plugin configuration |

**Aliases**: `mailbox`, `mail`

## 🔑 Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `lammailbox.admin` | `op` | Access to all admin features |
| `lammailbox.open` | `true` | Open own mailbox |
| `lammailbox.open.others` | `op` | Open other players' mailboxes |
| `lammailbox.compose` | `true` | Compose and send new mail |
| `lammailbox.items` | `true` | Add items to mail |
| `lammailbox.reload` | `op` | Reload plugin configuration |
| `lammailbox.delete` | `op` | Delete sent mail |
| `lammailbox.view.as` | `op` | View mailbox as another player |

## ⚙️ Configuration

The plugin uses a YAML configuration system with automatic updates:

### Basic Settings
```yaml
version: 1.0
settings:
  max-mails-per-player: 54
  admin-mail-expire-days: 7
  join-notification: true
  admin-permission: 'lammailbox.admin'
```

### GUI Configuration
The GUI is fully customizable through the config file, including:
- Custom item materials, names, and lore
- Configurable inventory sizes and layouts
- Decoration and border items
- Message formatting and colors

### Notification Settings
```yaml
settings:
  notification:
    title-enabled: true
    chat-enabled: true
    sound: ENTITY_EXPERIENCE_ORB_PICKUP
    volume: 1.0
    pitch: 1.0
```

## 📖 Usage

### Basic Mail System
1. Use `/smb` to open your mailbox
2. Click "Create New Mail" to compose a message
3. Select recipient, write message, and optionally add items
4. Send the mail - recipient will be notified when they join

### Admin Features
- Use `/smb as <player>` to view any player's mailbox for support
- Send mail via commands using `/smb send <player> <message>`
- Delete sent mail through the GUI interface
- Schedule mail delivery and set expiration dates

### Date Format
For scheduling and expiry dates, use: `YYYY:MM:DD:HH:mm`
Example: `2024:12:25:09:30`

## 🔧 Technical Details

### Architecture
```
src/main/java/com/yusaki/lammailbox/
├── command/          # Command handlers and tab completion
├── config/           # Configuration management with YskLib
├── gui/              # GUI factories and inventory handlers
├── repository/       # Data storage (YAML-based)
├── service/          # Mail business logic and operations
├── session/          # Mail creation session management
└── util/             # Utility classes
```

### Storage
- Uses YAML files for data persistence
- Automatic mail cleanup for expired items
- Backup creation during config updates
- Configuration migration support

### Performance
- Efficient scheduled task system for mail delivery
- Folia-compatible threading for multi-threaded servers
- Optimized inventory operations

## 🔧 Building

```bash
git clone <repository-url>
cd SecureMailBox
mvn clean package
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License.

## 🙏 Acknowledgments

- **YskLib** for configuration management and updates
- **FoliaLib** for Folia server compatibility
- **Spigot/Paper** community for continuous support

---

*Developed by Yusaki*