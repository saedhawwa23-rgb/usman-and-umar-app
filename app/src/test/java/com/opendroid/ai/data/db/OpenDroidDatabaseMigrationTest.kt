package com.opendroid.ai.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.opendroid.ai.data.db.entities.CloudServerEntity
import com.opendroid.ai.data.repository.normalizeCloudServerUrl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Avoids [com.opendroid.ai.OpenDroidApp] so Robolectric does not need the
 * Android Keystore used by SecurePrefs during Application.onCreate.
 */
class MigrationTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class OpenDroidDatabaseMigrationTest {

    // Consumes the exported schemas in app/schemas (wired into test assets in
    // app/build.gradle) and validates migrated databases against them.
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenDroidDatabase::class.java
    )

    // Room 2.7+ compares the open helper's configured name against the fully
    // resolved database path, so the helper must be addressed by absolute path.
    private val databasePath: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DATABASE).absolutePath

    @Test
    fun `migration 6 to 7 preserves existing data and creates the exact crash log schema`() {
        // Creates a real version-6 database from the exported 6.json schema.
        helper.createDatabase(databasePath, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`)
                VALUES ('preferred-model', 'claude-sonnet', 'PREFERENCE', 1234, -1, 'FACT')
                """.trimIndent()
            )
        }

        // Validates the migrated schema (crash_logs columns and index included)
        // against the exported 7.json, table by table.
        val migrated = helper.runMigrationsAndValidate(
            databasePath, 7, true, OpenDroidDatabase.MIGRATION_6_7
        )

        migrated.query(
            "SELECT `value`, `timestamp` FROM memories WHERE `key` = 'preferred-model'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("claude-sonnet", cursor.getString(0))
            assertEquals(1234L, cursor.getLong(1))
        }
    }

    @Test
    fun `migration 6 to 7 is safe to run against an existing crash_logs table`() {
        // Room wraps each migration in a transaction, so crash recovery does
        // not need the IF NOT EXISTS guards. They promise something narrower:
        // running the migration when crash_logs already exists and holds data
        // must neither throw nor clobber the table.
        val db = helper.createDatabase(databasePath, 7)
        db.execSQL(
            """
            INSERT INTO crash_logs (`timestamp`, `exceptionClass`, `message`, `threadName`, `stackTrace`,
                `appVersionName`, `appVersionCode`, `androidRelease`, `androidSdkInt`,
                `deviceManufacturer`, `deviceModel`)
            VALUES (1, 'java.lang.RuntimeException', 'boom', 'main', 'trace', '1.0.2', 3, '15', 35, 'Robolectric', 'JVM')
            """.trimIndent()
        )

        OpenDroidDatabase.MIGRATION_6_7.migrate(db)

        db.query("SELECT `message` FROM crash_logs").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("boom", cursor.getString(0))
        }
    }

    @Test
    fun `full migration chain from 1 to 7 produces the current schema and keeps data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)

        // Version 1 predates schema export (app/schemas only contains 6.json and
        // 7.json), so the v1 database is created by hand: every table that no
        // MIGRATION_* creates must already have existed at version 1.
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databasePath)
                .callback(V1SchemaCallback())
                .build()
        ).writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (`id`, `text`, `sender`, `timestamp`, `modelBadge`)
                VALUES ('msg-1', 'hello', 'USER', 42, NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`)
                VALUES ('preferred-model', 'claude-sonnet', 'PREFERENCE', 1234, -1, 'FACT')
                """.trimIndent()
            )
        }

        // Runs every migration in sequence and validates the final schema
        // against the exported 7.json.
        val migrated = helper.runMigrationsAndValidate(
            databasePath, 7, true,
            OpenDroidDatabase.MIGRATION_1_2,
            OpenDroidDatabase.MIGRATION_2_3,
            OpenDroidDatabase.MIGRATION_3_4,
            OpenDroidDatabase.MIGRATION_4_5,
            OpenDroidDatabase.MIGRATION_5_6,
            OpenDroidDatabase.MIGRATION_6_7
        )

        // Pre-existing chat history survives and is attached to the session
        // backfilled by MIGRATION_5_6.
        migrated.query(
            "SELECT `text`, `sessionId` FROM conversations WHERE `id` = 'msg-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertEquals("default_session", cursor.getString(1))
        }
        migrated.query(
            "SELECT `value` FROM memories WHERE `key` = 'preferred-model'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("claude-sonnet", cursor.getString(0))
        }
    }

    @Test
    fun `migration 8 to 9 preserves existing data and creates all 8 social tables`() {
        val db = helper.createDatabase(databasePath, 8)
        db.execSQL(
            """
            INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`)
            VALUES ('social-test-key', 'social-test-val', 'SEMANTIC', 9999, -1, 'FACT')
            """.trimIndent()
        )

        OpenDroidDatabase.MIGRATION_8_9.migrate(db)

        // Verify pre-existing data survives
        db.query("SELECT `value` FROM memories WHERE `key` = 'social-test-key'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("social-test-val", cursor.getString(0))
        }

        // Verify all 8 social tables are queryable and functional
        db.execSQL(
            """
            INSERT INTO social_accounts (`id`, `platform`, `username`, `displayName`, `status`, `permissionsJson`, `connectedAt`)
            VALUES ('acc-1', 'x', 'testuser', 'Test User', 'CONNECTED', '["READ_POSTS"]', 1000)
            """.trimIndent()
        )

        db.query("SELECT `username`, `platform` FROM social_accounts WHERE `id` = 'acc-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("testuser", cursor.getString(0))
            assertEquals("x", cursor.getString(1))
        }

        db.execSQL(
            """
            INSERT INTO social_posts (`id`, `accountId`, `platform`, `content`, `mediaUrlsJson`, `status`, `contentType`, `requiresApproval`, `likesCount`, `commentsCount`, `sharesCount`, `savesCount`, `viewsCount`, `reach`, `impressions`, `engagementRate`, `createdAt`, `updatedAt`)
            VALUES ('post-1', 'acc-1', 'x', 'Hello world', '[]', 'DRAFT', 'ANNOUNCEMENT', 1, 0, 0, 0, 0, 0, 0, 0, 0.0, 1000, 1000)
            """.trimIndent()
        )

        db.query("SELECT `content`, `requiresApproval` FROM social_posts WHERE `id` = 'post-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Hello world", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    @Test
    fun `migration 9 to 10 drops retired model catalog and preserves user data`() {
        val db = helper.createDatabase(databasePath, 9)
        db.execSQL(
            "INSERT INTO models (id, name, version, size, downloadUrl, localPath, status, downloadProgress, lastUsed, installedAt, downloadedSize, downloadSpeed, etaString) " +
                "VALUES ('retired-model', 'Retired', '1', 1, '', '', 'READY', 100, 0, 0, 1, '', '')"
        )
        db.execSQL(
            "INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`) " +
                "VALUES ('keep-me', 'user-data', 'PREFERENCE', 1234, -1, 'FACT')"
        )

        val migrated = helper.runMigrationsAndValidate(
            databasePath, 10, true, OpenDroidDatabase.MIGRATION_9_10
        )

        migrated.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='models'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT `value` FROM memories WHERE `key`='keep-me'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("user-data", cursor.getString(0))
        }
    }

    @Test
    fun `migration to version 12 adds Room-backed cluster servers and preserves existing data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)
        helper.createDatabase(databasePath, 10).use { db ->
            db.execSQL(
                "INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`) " +
                    "VALUES ('before-cluster', 'preserved', 'PREFERENCE', 55, -1, 'FACT')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databasePath,
            12,
            true,
            OpenDroidDatabase.MIGRATION_10_11,
            OpenDroidDatabase.MIGRATION_11_12,
        )
        migrated.execSQL(
            "INSERT INTO cloud_servers (id, name, url, enabled, position) " +
                "VALUES ('server-10', 'Colab 10', 'https://cluster.example', 1, 9)"
        )
        migrated.query("SELECT `value` FROM memories WHERE `key`='before-cluster'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved", cursor.getString(0))
        }
        migrated.query("SELECT `name`, `position` FROM cloud_servers WHERE `id`='server-10'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Colab 10", cursor.getString(0))
            assertEquals(9, cursor.getInt(1))
        }
    }

    @Test
    fun `migration 12 to 13 adds independent Drive account rows and preserves cluster servers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)
        helper.createDatabase(databasePath, 12).use { db ->
            db.execSQL(
                "INSERT INTO cloud_servers (id, name, url, enabled, position) " +
                    "VALUES ('server-1', 'Colab 1', 'https://cluster.example', 1, 0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databasePath, 13, true, OpenDroidDatabase.MIGRATION_12_13
        )
        migrated.execSQL(
            "INSERT INTO google_drive_accounts (id, email, displayName, connectedAtMillis, quotaLimitBytes, quotaUsageBytes, quotaUpdatedAtMillis, quotaError) " +
                "VALUES ('one@example.com', 'one@example.com', 'one', 10, 16106127360, 2147483648, 11, NULL)"
        )
        migrated.execSQL(
            "INSERT INTO google_drive_accounts (id, email, displayName, connectedAtMillis, quotaLimitBytes, quotaUsageBytes, quotaUpdatedAtMillis, quotaError) " +
                "VALUES ('two@example.com', 'two@example.com', 'two', 12, 16106127360, 3221225472, 13, NULL)"
        )

        migrated.query("SELECT COUNT(*) FROM google_drive_accounts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        migrated.query("SELECT quotaUsageBytes FROM google_drive_accounts WHERE id='two@example.com'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3221225472L, cursor.getLong(0))
        }
        migrated.query("SELECT name FROM cloud_servers WHERE id='server-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Colab 1", cursor.getString(0))
        }
    }

    @Test
    fun `migration 13 to 14 preserves chat rows and adds nullable delivery state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)
        helper.createDatabase(databasePath, 13).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions (id, title, createdAt, updatedAt, isCurrent) VALUES ('chat-one', 'Chat', 1, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO conversations (id, text, sender, timestamp, modelBadge, contactPickerData, sessionId) " +
                    "VALUES ('message-one', 'survives migration', 'USER', 2, NULL, NULL, 'chat-one')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databasePath, 14, true, OpenDroidDatabase.MIGRATION_13_14
        )
        migrated.query(
            "SELECT text, deliveryState, deliveryTarget, deliveryTaskId, deliveryMetadata, deliveryError, deliveryRetryable, imageBase64 FROM conversations WHERE id='message-one'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("survives migration", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
        }
    }

    @Test
    fun `Room preserves ten Colab server rows and long tunnel URLs without truncation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val providedTunnelUrls = listOf(
            "https://colab-node-1.trycloudflare.com",
            "https://colab-node-2.trycloudflare.com",
            "https://colab-node-3.trycloudflare.com",
        )
        val sourceUrls = (1..10).map { serverNumber ->
            when (serverNumber) {
                1, 2, 3 -> providedTunnelUrls[serverNumber - 1]
                10 -> "https://colab-node-10.trycloudflare.com/${"runtime-segment/".repeat(24)}health-base/"
                else -> "https://colab-node-$serverNumber.trycloudflare.com"
            }
        }
        val normalizedUrls = sourceUrls.map(::normalizeCloudServerUrl)
        assertEquals(10, normalizedUrls.size)
        assertTrue(normalizedUrls.last().length > 300)

        val database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.cloudServerDao().upsertAll(
                normalizedUrls.mapIndexed { index, url ->
                    CloudServerEntity(
                        id = "colab-server-${index + 1}",
                        name = "Colab Server ${index + 1}",
                        url = url,
                        enabled = true,
                        position = index,
                    )
                }
            )

            val persisted = database.cloudServerDao().observeAll().first()
            assertEquals(10, persisted.size)
            assertEquals(normalizedUrls, persisted.map { it.url })
            assertTrue(persisted.last().url.length > 300)
        } finally {
            database.close()
        }
    }

    private class V1SchemaCallback : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            V1_CREATE_STATEMENTS.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TEST_DATABASE = "migration-test"

        // The version-1 schema: the current schema minus everything the
        // migrations add (contactPickerData 1->2, notifications 2->3 with
        // senderEmail 3->4, models 4->5, chat_sessions + sessionId 5->6,
        // crash_logs 6->7).
        val V1_CREATE_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `sender` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `modelBadge` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `plans` (`planId` TEXT NOT NULL, `goal` TEXT NOT NULL, `estimatedDuration` TEXT NOT NULL, `estimatedSteps` INTEGER NOT NULL, `stepsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`planId`))",
            "CREATE TABLE IF NOT EXISTS `memories` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `ttlHours` INTEGER NOT NULL, `category` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `task_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stepId` TEXT NOT NULL, `planId` TEXT NOT NULL, `description` TEXT NOT NULL, `actionType` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `success` INTEGER NOT NULL, `resultData` TEXT, `errorMessage` TEXT, `timestamp` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `macros` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `trigger` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isSystem` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `unknown_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attemptedAction` TEXT NOT NULL, `goal` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `fixStatus` TEXT NOT NULL, `wasAutoFixed` INTEGER NOT NULL, `fixedWith` TEXT)"
        )
    }
}
