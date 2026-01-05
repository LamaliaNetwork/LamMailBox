package com.yusaki.lammailbox.mailing.status;

import com.yusaki.lammailbox.LamMailBox;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SqliteMailingStatusRepository implements MailingStatusRepository {
    private static final String GLOBAL_KEY = "__GLOBAL__";

    private final LamMailBox plugin;
    private final SQLiteDataSource dataSource;

    public SqliteMailingStatusRepository(LamMailBox plugin, Path databasePath) {
        this.plugin = plugin;
        this.dataSource = new SQLiteDataSource();
        this.dataSource.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath());
        initialize();
    }

    private void initialize() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS mailing_status ("
                    + "mailing_id TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "last_sent INTEGER NOT NULL,"
                    + "run_count INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (mailing_id, player_uuid))");
            ensureRunCountColumn(connection);
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize mailing status table: " + ex.getMessage());
        }
    }

    private void ensureRunCountColumn(Connection connection) {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, "mailing_status", "run_count")) {
            if (rs.next()) {
                return;
            }
        } catch (SQLException ignored) {
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE mailing_status ADD COLUMN run_count INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to add run_count column to mailing_status: " + ex.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    @Override
    public long getLastRun(String mailingId) {
        String sql = "SELECT last_sent FROM mailing_status WHERE mailing_id = ? AND player_uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mailingId);
            ps.setString(2, GLOBAL_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("last_sent");
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to read last run for mailing " + mailingId + ": " + ex.getMessage());
        }
        return 0L;
    }

    @Override
    public void setLastRun(String mailingId, long timestamp) {
        try (Connection connection = getConnection()) {
            ensureRow(connection, mailingId, null);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE mailing_status SET last_sent = ? WHERE mailing_id = ? AND player_uuid = ?")) {
                ps.setLong(1, timestamp);
                ps.setString(2, mailingId);
                ps.setString(3, GLOBAL_KEY);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to set last run for mailing " + mailingId + ": " + ex.getMessage());
        }
    }

    @Override
    public int getRunCount(String mailingId) {
        String sql = "SELECT run_count FROM mailing_status WHERE mailing_id = ? AND player_uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mailingId);
            ps.setString(2, GLOBAL_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("run_count");
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to read run count for mailing " + mailingId + ": " + ex.getMessage());
        }
        return 0;
    }

    @Override
    public void incrementRunCount(String mailingId) {
        try (Connection connection = getConnection()) {
            ensureRow(connection, mailingId, null);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE mailing_status SET run_count = run_count + 1 WHERE mailing_id = ? AND player_uuid = ?")) {
                ps.setString(1, mailingId);
                ps.setString(2, GLOBAL_KEY);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to increment run count for mailing " + mailingId + ": " + ex.getMessage());
        }
    }

    @Override
    public boolean incrementRunCountIfBelow(String mailingId, int maxRuns) {
        try (Connection connection = getConnection()) {
            ensureRow(connection, mailingId, null);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE mailing_status SET run_count = run_count + 1 "
                    + "WHERE mailing_id = ? AND player_uuid = ? AND run_count < ?")) {
                ps.setString(1, mailingId);
                ps.setString(2, GLOBAL_KEY);
                ps.setInt(3, maxRuns);
                int affected = ps.executeUpdate();
                return affected > 0;
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to conditionally increment run count for mailing " + mailingId + ": " + ex.getMessage());
        }
        return false;
    }

    @Override
    public Optional<Long> getLastRunForPlayer(String mailingId, UUID playerId) {
        String sql = "SELECT last_sent FROM mailing_status WHERE mailing_id = ? AND player_uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mailingId);
            ps.setString(2, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong("last_sent"));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to read player run for mailing " + mailingId + ": " + ex.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void setLastRunForPlayer(String mailingId, UUID playerId, long timestamp) {
        try (Connection connection = getConnection()) {
            ensureRow(connection, mailingId, playerId);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE mailing_status SET last_sent = ? WHERE mailing_id = ? AND player_uuid = ?")) {
                ps.setLong(1, timestamp);
                ps.setString(2, mailingId);
                ps.setString(3, playerId.toString());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to set player run for mailing " + mailingId + ": " + ex.getMessage());
        }
    }

    @Override
    public boolean hasReceived(String mailingId, UUID playerId) {
        String sql = "SELECT 1 FROM mailing_status WHERE mailing_id = ? AND player_uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mailingId);
            ps.setString(2, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to check player receipt for mailing " + mailingId + ": " + ex.getMessage());
        }
        return false;
    }

    @Override
    public void markReceived(String mailingId, UUID playerId, long timestamp) {
        setLastRunForPlayer(mailingId, playerId, timestamp);
    }

    @Override
    public boolean markReceivedIfNew(String mailingId, UUID playerId, long timestamp) {
        String sql = "INSERT INTO mailing_status (mailing_id, player_uuid, last_sent, run_count) VALUES (?, ?, ?, 0) "
                + "ON CONFLICT(mailing_id, player_uuid) DO NOTHING";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mailingId);
            ps.setString(2, playerId.toString());
            ps.setLong(3, timestamp);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to conditionally mark received for mailing " + mailingId + ": " + ex.getMessage());
        }
        return false;
    }

    @Override
    public void flush() {
        // no-op
    }

    private void ensureRow(Connection connection, String mailingId, UUID playerId) throws SQLException {
        String sql = "INSERT INTO mailing_status (mailing_id, player_uuid, last_sent, run_count) VALUES (?, ?, 0, 0) "
                + "ON CONFLICT(mailing_id, player_uuid) DO NOTHING";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, mailingId);
            ps.setString(2, playerId == null ? GLOBAL_KEY : playerId.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public void purgeMissingMailings(Set<String> activeIds) {
        String sql = activeIds.isEmpty()
                ? "DELETE FROM mailing_status"
                : "DELETE FROM mailing_status WHERE mailing_id NOT IN (" + placeholders(activeIds.size()) + ")";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            if (!activeIds.isEmpty()) {
                int index = 1;
                for (String id : activeIds) {
                    ps.setString(index++, id);
                }
            }
            ps.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to purge mailing status entries: " + ex.getMessage());
        }
    }

    private String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.toString();
    }
}