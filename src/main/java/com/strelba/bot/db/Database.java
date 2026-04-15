package com.strelba.bot.db;

import com.strelba.bot.model.DiaryEntry;
import com.strelba.bot.model.SurveyStep;
import com.strelba.bot.model.UserRow;
import org.telegram.telegrambots.meta.api.objects.User;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Database {
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String jdbcUrl;

    public Database(String dbPath, String initialAdminsCsv) throws Exception {
        Path dbFile = Path.of(dbPath);
        if (dbFile.getParent() != null) {
            Files.createDirectories(dbFile.getParent());
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        initSchema();
        seedInitialAdmins(initialAdminsCsv);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() throws SQLException {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY,
                        username TEXT,
                        first_name TEXT,
                        last_name TEXT,
                        is_admin INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        date_time TEXT,
                        venue TEXT,
                        sleep TEXT,
                        wellbeing TEXT,
                        food TEXT,
                        weather TEXT,
                        wind TEXT,
                        lighting TEXT,
                        exercise TEXT,
                        result TEXT,
                        target_max TEXT,
                        comment TEXT,
                        city TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_created_at ON entries(created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_user_id ON entries(user_id)");
        }
    }

    private void seedInitialAdmins(String csv) throws SQLException {
        if (csv == null || csv.isBlank()) {
            return;
        }
        String[] chunks = csv.split(",");
        for (String raw : chunks) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                promoteAdmin(id);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public void upsertUser(User user) {
        String sql = """
                INSERT INTO users(id, username, first_name, last_name, is_admin, created_at)
                VALUES(?, ?, ?, ?, COALESCE((SELECT is_admin FROM users WHERE id = ?), 0), ?)
                ON CONFLICT(id) DO UPDATE SET
                  username=excluded.username,
                  first_name=excluded.first_name,
                  last_name=excluded.last_name
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, user.getId());
            ps.setString(2, user.getUserName());
            ps.setString(3, user.getFirstName());
            ps.setString(4, user.getLastName());
            ps.setLong(5, user.getId());
            ps.setString(6, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert user", e);
        }
    }

    public boolean isAdmin(long userId) {
        String sql = "SELECT is_admin FROM users WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("is_admin") == 1;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check admin", e);
        }
    }

    public boolean promoteAdmin(long userId) throws SQLException {
        return setAdminFlag(userId, true);
    }

    public boolean demoteAdmin(long userId) throws SQLException {
        return setAdminFlag(userId, false);
    }

    private boolean setAdminFlag(long userId, boolean admin) throws SQLException {
        try (Connection conn = connect()) {
            String ensureUserSql = """
                    INSERT INTO users(id, username, first_name, last_name, is_admin, created_at)
                    VALUES(?, NULL, NULL, NULL, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET is_admin=excluded.is_admin
                    """;
            try (PreparedStatement ps = conn.prepareStatement(ensureUserSql)) {
                ps.setLong(1, userId);
                ps.setInt(2, admin ? 1 : 0);
                ps.setString(3, now());
                return ps.executeUpdate() > 0;
            }
        }
    }

    public List<Long> getAdminIds() {
        String sql = "SELECT id FROM users WHERE is_admin = 1 ORDER BY id";
        List<Long> ids = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get admins", e);
        }
    }

    public void saveEntry(long userId, Map<SurveyStep, String> answers) {
        String sql = """
                INSERT INTO entries(user_id, date_time, venue, sleep, wellbeing, food, weather, wind, lighting, exercise, result, target_max, comment, city, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, answers.get(SurveyStep.A1_DATE_TIME));
            ps.setString(3, answers.get(SurveyStep.B1_VENUE));
            ps.setString(4, answers.get(SurveyStep.C1_SLEEP));
            ps.setString(5, answers.get(SurveyStep.D1_WELLBEING));
            ps.setString(6, answers.get(SurveyStep.E1_FOOD));
            ps.setString(7, answers.get(SurveyStep.F1_WEATHER));
            ps.setString(8, answers.get(SurveyStep.G1_WIND));
            ps.setString(9, answers.get(SurveyStep.H1_LIGHTING));
            ps.setString(10, answers.get(SurveyStep.I1_EXERCISE));
            ps.setString(11, answers.get(SurveyStep.J1_RESULT));
            ps.setString(12, answers.get(SurveyStep.K1_TARGET_MAX));
            ps.setString(13, answers.get(SurveyStep.L1_COMMENT));
            ps.setString(14, answers.get(SurveyStep.M1_CITY));
            ps.setString(15, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save entry", e);
        }
    }

    public int countDaysWithEntries() {
        String sql = "SELECT COUNT(*) AS cnt FROM (SELECT SUBSTR(created_at, 1, 10) AS day FROM entries GROUP BY day)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt("cnt") : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count days", e);
        }
    }

    public String getDayByPage(int page) {
        String sql = """
                SELECT SUBSTR(created_at, 1, 10) AS day
                FROM entries
                GROUP BY day
                ORDER BY day DESC
                LIMIT 1 OFFSET ?
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(page, 0));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("day") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get day page", e);
        }
    }

    public List<DiaryEntry> getEntriesByDay(String day) {
        String sql = """
                SELECT *
                FROM entries
                WHERE SUBSTR(created_at, 1, 10) = ?
                ORDER BY created_at DESC, id DESC
                """;
        List<DiaryEntry> list = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DiaryEntry(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("date_time"),
                            rs.getString("venue"),
                            rs.getString("sleep"),
                            rs.getString("wellbeing"),
                            rs.getString("food"),
                            rs.getString("weather"),
                            rs.getString("wind"),
                            rs.getString("lighting"),
                            rs.getString("exercise"),
                            rs.getString("result"),
                            rs.getString("target_max"),
                            rs.getString("comment"),
                            rs.getString("city"),
                            rs.getString("created_at")
                    ));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get entries by day", e);
        }
    }

    public int countUsers() {
        String sql = "SELECT COUNT(*) AS cnt FROM users";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt("cnt") : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count users", e);
        }
    }

    public List<UserRow> getUsersPage(int page, int pageSize) {
        String sql = """
                SELECT id, username, first_name, last_name, is_admin, created_at
                FROM users
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """;
        List<UserRow> users = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, Math.max(page, 0) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(new UserRow(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getInt("is_admin") == 1,
                            rs.getString("created_at")
                    ));
                }
            }
            return users;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get users page", e);
        }
    }

    private String now() {
        return LocalDateTime.now().format(TS_FORMAT);
    }
}
