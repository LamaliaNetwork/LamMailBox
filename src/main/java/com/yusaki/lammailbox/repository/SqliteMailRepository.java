package com.yusaki.lammailbox.repository;

import com.yusaki.lammailbox.config.StorageSettings;
import com.yusaki.lammailbox.util.ItemSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite-backed mail repository for high-volume servers.
 */
public class SqliteMailRepository implements MailRepository {
    private static final Map<String, String> COLUMN_MAPPING = createColumnMapping();
    private final JavaPlugin plugin;
    private final StorageSettings.SqliteSettings settings;
    private final SQLiteDataSource dataSource;

    public SqliteMailRepository(JavaPlugin plugin, StorageSettings.SqliteSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.dataSource = createDataSource(settings);
        initialize();
    }

    private SQLiteDataSource createDataSource(StorageSettings.SqliteSettings settings) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + settings.databasePath().toAbsolutePath());
        return dataSource;
    }

    private void initialize() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mail (" +
                    "mail_id TEXT PRIMARY KEY," +
                    "sender TEXT," +
                    "receiver TEXT," +
                    "message TEXT," +
                    "sent_date INTEGER," +
                    "schedule_date INTEGER," +
                    "expire_date INTEGER," +
                    "active INTEGER NOT NULL DEFAULT 1," +
                    "admin_mail INTEGER NOT NULL DEFAULT 0," +
                    "command_block TEXT" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mail_claimed (" +
                    "mail_id TEXT NOT NULL," +
                    "player TEXT NOT NULL," +
                    "PRIMARY KEY (mail_id, player)," +
                    "FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mail_commands (" +
                    "mail_id TEXT NOT NULL," +
                    "ordinal INTEGER NOT NULL," +
                    "command TEXT NOT NULL," +
                    "PRIMARY KEY (mail_id, ordinal)," +
                    "FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mail_items (" +
                    "mail_id TEXT NOT NULL," +
                    "ordinal INTEGER NOT NULL," +
                    "item TEXT NOT NULL," +
                    "PRIMARY KEY (mail_id, ordinal)," +
                    "FOREIGN KEY (mail_id) REFERENCES mail(mail_id) ON DELETE CASCADE" +
                    ")");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
        }

        if (settings.importFromYaml()) {
            migrateFromYamlIfNeeded();
        }
    }

    private Connection getConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private void migrateFromYamlIfNeeded() {
        if (!isDatabaseEmpty()) {
            return;
        }

        YamlMailRepository yamlRepository = new YamlMailRepository(plugin);
        List<String> legacyIds = yamlRepository.listMailIds();
        if (legacyIds.isEmpty()) {
            return;
        }

        plugin.getLogger().info("Migrating " + legacyIds.size() + " mails from YAML to SQLite storage...");
        for (String mailId : legacyIds) {
            Map<String, Object> data = yamlRepository.loadMail(mailId);
            saveMail(mailId, data);
            List<ItemStack> items = yamlRepository.loadMailItems(mailId);
            saveMailItems(mailId, items);
        }
        plugin.getLogger().info("SQLite migration complete.");
    }

    private boolean isDatabaseEmpty() {
        String sql = "SELECT COUNT(*) FROM mail";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getLong(1) == 0L;
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not determine if SQLite database is empty: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> loadMail(String mailId) {
        try (Connection connection = getConnection()) {
            return loadMail(connection, mailId);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load mail " + mailId + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> loadMail(Connection connection, String mailId) throws SQLException {
        String sql = "SELECT sender, receiver, message, sent_date, schedule_date, expire_date, active, admin_mail, command_block " +
                "FROM mail WHERE mail_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mailId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Collections.emptyMap();
                }
                Map<String, Object> data = new HashMap<>();
                data.put("sender", rs.getString("sender"));
                data.put("receiver", rs.getString("receiver"));
                data.put("message", rs.getString("message"));
                data.put("sent-date", rs.getLong("sent_date"));
                Long scheduleDate = getNullableLong(rs, "schedule_date");
                Long expireDate = getNullableLong(rs, "expire_date");
                data.put("schedule-date", scheduleDate);
                data.put("expire-date", expireDate);
                data.put("active", rs.getInt("active") != 0);
                data.put("is-admin-mail", rs.getInt("admin_mail") != 0);
                data.put("command-block", rs.getString("command_block"));
                data.put("claimed-players", loadClaimedPlayers(connection, mailId));
                data.put("commands", loadCommands(connection, mailId));
                return data;
            }
        }
    }

    @Override
    public void saveMail(String mailId, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }

        try (Connection connection = getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                ensureMailRow(connection, mailId);

                List<String> columns = new ArrayList<>();
                List<Object> values = new ArrayList<>();

                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    switch (key) {
                        case "claimed-players":
                            replaceClaimedPlayers(connection, mailId, asStringList(value));
                            break;
                        case "commands":
                            replaceCommands(connection, mailId, asStringList(value));
                            break;
                        default:
                            String column = toColumnName(key);
                            if (column != null) {
                                columns.add(column);
                                values.add(value);
                            }
                            break;
                    }
                }

                if (!columns.isEmpty()) {
                    updateColumns(connection, mailId, columns, values);
                }

                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    plugin.getLogger().warning("Failed to rollback SQLite transaction: " + rollback.getMessage());
                }
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save mail " + mailId + ": " + e.getMessage());
        }
    }

    private void updateColumns(Connection connection,
                               String mailId,
                               List<String> columns,
                               List<Object> values) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE mail SET ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columns.get(i)).append(" = ?");
        }
        sql.append(" WHERE mail_id = ?");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < columns.size(); i++) {
                applyValue(statement, i + 1, columns.get(i), values.get(i));
            }
            statement.setString(columns.size() + 1, mailId);
            statement.executeUpdate();
        }
    }

    private void applyValue(PreparedStatement statement, int index, String column, Object value) throws SQLException {
        switch (column) {
            case "sender":
            case "receiver":
            case "message":
            case "command_block":
                if (value == null) {
                    statement.setNull(index, Types.VARCHAR);
                } else {
                    statement.setString(index, value.toString());
                }
                break;
            case "sent_date":
            case "schedule_date":
            case "expire_date":
                if (value == null) {
                    statement.setNull(index, Types.BIGINT);
                } else {
                    statement.setLong(index, toLong(value));
                }
                break;
            case "active":
            case "admin_mail":
                statement.setInt(index, toBoolean(value) ? 1 : 0);
                break;
            default:
                throw new SQLException("Unknown column " + column);
        }
    }

    private void ensureMailRow(Connection connection, String mailId) throws SQLException {
        String sql = "INSERT INTO mail (mail_id, sent_date) VALUES (?, ?) " +
                "ON CONFLICT(mail_id) DO UPDATE SET sent_date = sent_date";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mailId);
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void replaceClaimedPlayers(Connection connection, String mailId, List<String> players) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM mail_claimed WHERE mail_id = ?")) {
            delete.setString(1, mailId);
            delete.executeUpdate();
        }

        if (players.isEmpty()) {
            return;
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO mail_claimed (mail_id, player) VALUES (?, ?)")) {
            for (String player : players) {
                insert.setString(1, mailId);
                insert.setString(2, player);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void replaceCommands(Connection connection, String mailId, List<String> commands) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM mail_commands WHERE mail_id = ?")) {
            delete.setString(1, mailId);
            delete.executeUpdate();
        }

        if (commands.isEmpty()) {
            return;
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO mail_commands (mail_id, ordinal, command) VALUES (?, ?, ?)")) {
            for (int i = 0; i < commands.size(); i++) {
                insert.setString(1, mailId);
                insert.setInt(2, i);
                insert.setString(3, commands.get(i));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<String> loadClaimedPlayers(Connection connection, String mailId) throws SQLException {
        List<String> players = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player FROM mail_claimed WHERE mail_id = ? ORDER BY player")) {
            statement.setString(1, mailId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    players.add(rs.getString("player"));
                }
            }
        }
        return players;
    }

    private List<String> loadCommands(Connection connection, String mailId) throws SQLException {
        List<String> commands = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT command FROM mail_commands WHERE mail_id = ? ORDER BY ordinal")) {
            statement.setString(1, mailId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    commands.add(rs.getString("command"));
                }
            }
        }
        return commands;
    }

    @Override
    public void deleteMail(String mailId) {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM mail WHERE mail_id = ?")) {
            statement.setString(1, mailId);
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete mail " + mailId + ": " + e.getMessage());
        }
    }

    @Override
    public List<String> listMailIds() {
        String sql = "SELECT mail_id FROM mail ORDER BY sent_date DESC";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString("mail_id"));
            }
            return ids;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list mail ids: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> listMailIdsBySender(String sender) {
        String sql = "SELECT mail_id FROM mail WHERE sender = ? ORDER BY sent_date DESC";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sender);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("mail_id"));
                }
                return ids;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list mail ids for sender " + sender + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> listActiveMailIdsFor(String playerName) {
        String sql = "SELECT mail_id FROM mail WHERE active = 1";
        List<String> ids = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String mailId = rs.getString("mail_id");
                Map<String, Object> data = loadMail(connection, mailId);
                MailRecord.from(mailId, data).ifPresent(record -> {
                    if (record.canBeClaimedBy(playerName)) {
                        ids.add(mailId);
                    }
                });
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list active mails for " + playerName + ": " + e.getMessage());
        }
        return ids;
    }

    @Override
    public Optional<Map<String, Object>> findMail(String mailId) {
        Map<String, Object> data = loadMail(mailId);
        return data.isEmpty() ? Optional.empty() : Optional.of(data);
    }

    @Override
    public void save() {
        // SQLite operations are auto-committed; nothing to flush.
    }

    @Override
    public void saveMailItems(String mailId, List<ItemStack> items) {
        List<String> serialized = ItemSerialization.serializeItems(items);
        try (Connection connection = getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM mail_items WHERE mail_id = ?")) {
                    delete.setString(1, mailId);
                    delete.executeUpdate();
                }

                if (!serialized.isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO mail_items (mail_id, ordinal, item) VALUES (?, ?, ?)")) {
                        for (int i = 0; i < serialized.size(); i++) {
                            insert.setString(1, mailId);
                            insert.setInt(2, i);
                            insert.setString(3, serialized.get(i));
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }

                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    plugin.getLogger().warning("Failed to rollback SQLite transaction: " + rollback.getMessage());
                }
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save items for mail " + mailId + ": " + e.getMessage());
        }
    }

    @Override
    public List<ItemStack> loadMailItems(String mailId) {
        String sql = "SELECT item FROM mail_items WHERE mail_id = ? ORDER BY ordinal";
        List<ItemStack> items = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mailId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String serialized = rs.getString("item");
                    ItemStack item = ItemSerialization.deserializeItem(serialized);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load items for mail " + mailId + ": " + e.getMessage());
        }
        return items;
    }

    @Override
    public Optional<MailRecord> findRecord(String mailId) {
        Map<String, Object> data = loadMail(mailId);
        return MailRecord.from(mailId, data);
    }

    @Override
    public int countActiveMailFor(String playerName) {
        List<String> ids = listActiveMailIdsFor(playerName);
        return ids.size();
    }

    @Override
    public void shutdown() {
        // nothing to do for SQLite when shutting down
    }

    private static Map<String, String> createColumnMapping() {
        Map<String, String> map = new HashMap<>();
        map.put("sender", "sender");
        map.put("receiver", "receiver");
        map.put("message", "message");
        map.put("sent-date", "sent_date");
        map.put("schedule-date", "schedule_date");
        map.put("expire-date", "expire_date");
        map.put("active", "active");
        map.put("is-admin-mail", "admin_mail");
        map.put("command-block", "command_block");
        return Collections.unmodifiableMap(map);
    }

    private static String toColumnName(String key) {
        return COLUMN_MAPPING.get(key);
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException("Cannot convert " + value + " to long");
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return false;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?>) {
            List<String> result = new ArrayList<>();
            for (Object element : (List<?>) value) {
                if (element != null) {
                    result.add(element.toString());
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
