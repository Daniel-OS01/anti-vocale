package com.antivocale.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The bulk redo's reads: getByIds across the bind-limit chunking, and ids by folder key. */
@RunWith(AndroidJUnit4::class)
class LogDaoBulkReadTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.logDao()
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Exception) {}
    }

    private fun entity(i: Int, folderKey: String? = null) = LogEntity(
        id = "id-$i",
        timestamp = 1_000_000L + i,
        taskId = "task-$i",
        type = "AUDIO",
        status = "SUCCESS",
        prompt = "",
        sourceFolderKey = folderKey,
    )

    @Test
    fun `getByIds returns every requested row past the bind limit`() = runBlocking {
        val rows = (0 until 1_200).map { entity(it) }
        dao.insertAll(rows)
        val wanted = (0 until 1_100).map { "id-$it" } + "id-missing"
        val got = dao.getByIds(wanted)
        assertEquals((0 until 1_100).map { "id-$it" }.toSet(), got.map { it.id }.toSet())
        assertEquals(1_100, got.size)
    }

    @Test
    fun `getByIds of no ids is empty`() = runBlocking {
        dao.insert(entity(1))
        assertEquals(emptyList<LogEntity>(), dao.getByIds(emptyList()))
    }

    @Test
    fun `getIdsByFolderKey returns only that folder's rows`() = runBlocking {
        dao.insertAll(listOf(entity(1, "tree-a"), entity(2, "tree-b"), entity(3, "tree-a"), entity(4)))
        assertEquals(setOf("id-1", "id-3"), dao.getIdsByFolderKey("tree-a").toSet())
        assertEquals(emptyList<String>(), dao.getIdsByFolderKey("tree-none"))
    }
}
