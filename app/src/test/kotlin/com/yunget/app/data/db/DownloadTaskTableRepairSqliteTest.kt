package com.yunget.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class DownloadTaskTableRepairSqliteTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    private fun Connection.columnNames(table: String): Set<String> = prepareStatement("PRAGMA table_info(`$table`)").use { st ->
        st.executeQuery().use { rows ->
            buildSet { while (rows.next()) add(rows.getString("name")) }
        }
    }

    private fun Connection.repair(existing: Set<String>) {
        createStatement().use { it.execute(DownloadTaskTableRepair.createSql("download_task_repaired")) }
        createStatement().use { it.execute(DownloadTaskTableRepair.copySql(existing)) }
        createStatement().use { st ->
            st.execute("UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name='download_task'), 0)) WHERE name='download_task_repaired'")
            st.execute("DROP TABLE `download_task`")
            st.execute("ALTER TABLE `download_task_repaired` RENAME TO `download_task`")
        }
    }

    @Test
    fun `repair keeps every task while filling legacy missing columns`() {
        db().use { db ->
            db.createStatement().use { st ->
                st.execute("CREATE TABLE download_task (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, url TEXT NOT NULL, fileName TEXT NOT NULL, totalSize INTEGER NOT NULL, downloadedSize INTEGER NOT NULL, status INTEGER NOT NULL, errorMsg TEXT NOT NULL DEFAULT '', savePath TEXT NOT NULL DEFAULT '', requestHeadersJson TEXT NOT NULL DEFAULT '{}', chunkCount INTEGER NOT NULL DEFAULT 0, plannedTotalSize INTEGER NOT NULL DEFAULT 0, cleanupId TEXT NOT NULL DEFAULT '', engineTaskId TEXT NOT NULL DEFAULT '', avgSpeed INTEGER NOT NULL DEFAULT 0, platform TEXT NOT NULL DEFAULT '', fallbackUrl TEXT NOT NULL DEFAULT '', altSourcesJson TEXT NOT NULL DEFAULT '[]', mergeAudioUrl TEXT NOT NULL DEFAULT '', mergeAudioHeadersJson TEXT NOT NULL DEFAULT '{}', motionUrl TEXT NOT NULL DEFAULT '', audioOnly INTEGER NOT NULL DEFAULT 0, createTime INTEGER NOT NULL)")
                st.execute("INSERT INTO download_task (url,fileName,totalSize,downloadedSize,status,createTime) VALUES ('https://example.invalid/a','a.bin',99,42,2,1234)")
                st.execute("INSERT INTO download_task (url,fileName,totalSize,downloadedSize,status,createTime) VALUES ('https://example.invalid/b','b.bin',88,77,4,5678)")
            }
            val oldColumns = db.columnNames("download_task")
            db.repair(oldColumns)
            assertEquals(setOf("id", "url", "fileName", "totalSize", "downloadedSize", "status", "errorMsg",
                "savePath", "requestHeadersJson", "chunkCount", "plannedTotalSize", "cleanupId", "engineTaskId",
                "avgSpeed", "platform", "fallbackUrl", "altSourcesJson", "mergeAudioUrl", "mergeAudioHeadersJson",
                "motionUrl", "audioOnly", "createTime", "sourceAccountId", "refreshReferenceJson", "transferPolicyJson",
                "pipelineStage", "completedCachePath", "completedCacheSize", "attemptGeneration"), db.columnNames("download_task"))
            db.prepareStatement("SELECT id,fileName,downloadedSize,status,sourceAccountId,pipelineStage FROM download_task ORDER BY id").use { st ->
                st.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("a.bin", rows.getString("fileName"))
                    assertEquals(42L, rows.getLong("downloadedSize"))
                    assertEquals("", rows.getString("sourceAccountId"))
                    assertEquals("NETWORK", rows.getString("pipelineStage"))
                    assertTrue(rows.next())
                    assertEquals("b.bin", rows.getString("fileName"))
                    assertEquals(77L, rows.getLong("downloadedSize"))
                    assertEquals(4, rows.getInt("status"))
                }
            }
            db.createStatement().use { st ->
                st.execute("INSERT INTO download_task (url,fileName,totalSize,downloadedSize,status,errorMsg,savePath,createTime) VALUES ('https://example.invalid/c','c.bin',7,0,0,'','',9999)")
                st.executeQuery("SELECT MAX(id) FROM download_task").use { rows -> rows.next(); assertEquals(3L, rows.getLong(1)) }
            }
        }
    }

    @Test
    fun `repair also accepts a fully shaped version 25 table`() {
        db().use { db ->
            db.createStatement().use { it.execute(DownloadTaskTableRepair.createSql("download_task")) }
            db.createStatement().use { it.execute("INSERT INTO download_task (url,fileName,totalSize,downloadedSize,status,errorMsg,savePath,createTime,sourceAccountId,completedCachePath) VALUES ('u','f',10,4,1,'','',10,'acct','/cache/f')") }
            val oldColumns = db.columnNames("download_task")
            db.repair(oldColumns)
            db.createStatement().executeQuery("SELECT sourceAccountId,completedCachePath FROM download_task").use { rows ->
                assertTrue(rows.next())
                assertEquals("acct", rows.getString(1))
                assertEquals("/cache/f", rows.getString(2))
            }
        }
    }
}
